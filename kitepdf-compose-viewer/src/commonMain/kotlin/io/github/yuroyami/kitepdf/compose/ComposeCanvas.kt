package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.core.render.paintComplexShading
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathSegment
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteBitmapCache
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteImageSampling
import io.github.yuroyami.kitepdf.core.render.KiteMaskTransfer
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
import io.github.yuroyami.kitepdf.core.render.gridFitImage
import io.github.yuroyami.kitepdf.core.render.imageSampling
import io.github.yuroyami.kitepdf.core.render.sampleStops
import io.github.yuroyami.kitepdf.core.render.shrinkArgb
import io.github.yuroyami.kitepdf.core.render.shrinkRgba
import io.github.yuroyami.kitepdf.core.render.strokePen
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * [KiteCanvas] backed by a Compose Multiplatform [DrawScope].
 *
 * Three rendering paths:
 *
 *   - **Embedded outlines**: when `hasOutlines` is true,
 *     each byte/CID becomes a Compose `Path` filled at the right position.
 *   - **System-font fallback**: when no outlines are available, decode to text
 *     and hand to Compose's `TextMeasurer`.
 *   - **Transparency groups**: open a `saveLayer` on the underlying Canvas
 *     when the renderer requests one; later `restore` composites the layer
 *     back with the requested blend mode + alpha.
 *
 * Clipping uses Compose's [clipPath] inside a recursive scope; transparency
 * groups use the lower-level `Canvas.saveLayer` so they can span multiple
 * `DrawScope` operations.
 */
public class ComposeCanvas(
    private val drawScope: DrawScope,
    private val textMeasurer: TextMeasurer,
    /**
     * The width in raster pixels of a stroke whose line width is 0. Defaults to 1, the one
     * device pixel of ISO 32000-1, 8.4.3.2. Other thin strokes widen to a fifth of it, as
     * [strokePen] describes. When rasterizing supersampled (raster larger than its on-screen
     * size), pass the supersample factor instead, so thin strokes keep their weight after
     * the downscale.
     */
    private val hairlineWidthPx: Float = 1f,
    /**
     * When true, system-font text runs are not measured or drawn; the canvas
     * only records that one was encountered in [usedSystemFontText]. The
     * off-main raster path uses this: Compose's skiko text stack shares
     * process-global state with the host UI thread, so measuring or drawing
     * through it is only safe on the main thread. A pool-thread render probes
     * with this flag and, when it trips, the page is re-rendered on Main.
     */
    private val skipSystemFontText: Boolean = false,
) : KiteCanvas {

    /** True once a system-font run was skipped because of [skipSystemFontText]. */
    internal var usedSystemFontText: Boolean = false
        private set

    private val clipStack = ArrayDeque<ClipFrame>()
    /** Count of open transparency groups, for matching beginGroup/endGroup pairs. */
    private var openGroups = 0

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: KiteMatrix) {
        clipStack.clear()
        openGroups = 0
        groups.clear()
    }

    override fun endPage() {
        // Close any still-open transparency groups (defensive: well-formed
        // PDFs always pair them, but malformed ones leak).
        while (openGroups > 0) {
            drawScope.drawContext.canvas.restore()
            openGroups--
        }
        clipStack.clear()
        groups.clear()
    }

    override fun fillPath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, evenOdd: Boolean,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        withActiveClips {
            val composePath = toComposePath(path, ctm).apply {
                fillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
            }
            drawScope.drawPath(
                path = composePath,
                color = color.toCompose(),
                alpha = alpha.toFloat().coerceIn(0f, 1f),
                blendMode = paintBlend(blendMode),
            )
        }
    }

    override fun strokePath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, lineWidth: Double,
        alpha: Double, blendMode: KiteBlendMode,
        dashArray: List<Double>?, dashPhase: Double,
        lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) {
        withActiveClips {
            // The hairline scales with a supersampled raster, so thin strokes keep their
            // on-screen weight after the downscale.
            val pen = strokePen(ctm, lineWidth, hairlineWidthPx.toDouble())
            val composePath = toComposePath(path, pen.pathMatrix)
            val dash = composeDashIntervals(dashArray, pen.dashScale)
                ?.let { PathEffect.dashPathEffect(it, (dashPhase * pen.dashScale).toFloat()) }
            val cap = when (lineCap) {
                1 -> androidx.compose.ui.graphics.StrokeCap.Round
                2 -> androidx.compose.ui.graphics.StrokeCap.Square
                else -> androidx.compose.ui.graphics.StrokeCap.Butt
            }
            val join = when (lineJoin) {
                1 -> androidx.compose.ui.graphics.StrokeJoin.Round
                2 -> androidx.compose.ui.graphics.StrokeJoin.Bevel
                else -> androidx.compose.ui.graphics.StrokeJoin.Miter
            }
            val stroke: DrawScope.() -> Unit = {
                drawPath(
                    path = composePath,
                    color = color.toCompose(),
                    alpha = alpha.toFloat().coerceIn(0f, 1f),
                    style = Stroke(
                        width = pen.width.toFloat(),
                        cap = cap,
                        join = join,
                        miter = miterLimit.toFloat().coerceAtLeast(1f),
                        pathEffect = dash,
                    ),
                    blendMode = paintBlend(blendMode),
                )
            }
            // An elliptical pen strokes in user space under the matrix.
            val m = pen.strokeMatrix
            if (m == null) drawScope.stroke() else drawScope.withTransform({ transform(m.toComposeMatrix()) }, stroke)
        }
    }

    override fun drawGlyphs(
        glyphs: List<TextGlyph>,
        fontSize: Double,
        unitsPerEm: Int,
        hasOutlines: Boolean,
        fontSpec: FontSpec,
        textToDevice: KiteMatrix,
        color: RgbColor,
        alpha: Double,
        blendMode: KiteBlendMode,
    ) {
        if (glyphs.isEmpty()) return
        withActiveClips {
            if (hasOutlines) {
                drawTextViaOutlines(glyphs, fontSize, unitsPerEm, textToDevice, color, alpha, blendMode)
            } else {
                drawTextViaSystemFont(glyphs, fontSize, fontSpec, textToDevice, color, alpha, blendMode)
            }
        }
    }

    private fun drawTextViaOutlines(
        glyphs: List<TextGlyph>,
        fontSize: Double,
        unitsPerEm: Int,
        textMatrix: KiteMatrix,
        color: RgbColor,
        alpha: Double,
        blendMode: KiteBlendMode,
    ) {
        val unitScale = fontSize / unitsPerEm
        val advanceScale = fontSize / 1000.0 // PDF glyph widths are 1/1000 em, NOT font units
        val color = color.toCompose()
        val composeBlend = paintBlend(blendMode)
        val a = alpha.toFloat().coerceIn(0f, 1f)
        var penX = 0.0
        for (glyph in glyphs) {
            val outline = glyph.outline
            if (outline != null && !outline.isEmpty()) {
                // outline(font units) → ×unitScale → +penX (text space) → textMatrix (→ device).
                // concat(other) applies `other` first, so unitScale must be the LAST concat,
                // else every glyph collapses to a speck at the origin.
                val glyphMatrix = textMatrix
                    .concat(KiteMatrix.translation(penX + glyph.xOffset * unitScale, glyph.yOffset * unitScale))
                    .concat(KiteMatrix(unitScale, 0.0, 0.0, unitScale, 0.0, 0.0))
                val cp = toComposePath(outline, glyphMatrix).apply { fillType = PathFillType.NonZero }
                drawScope.drawPath(cp, color = color, alpha = a, blendMode = composeBlend)
            }
            penX += glyph.advanceWidth * advanceScale + glyph.advanceAdjust
        }
    }

    private fun drawTextViaSystemFont(
        glyphs: List<TextGlyph>,
        fontSize: Double,
        fontSpec: FontSpec,
        textMatrix: KiteMatrix,
        color: RgbColor,
        alpha: Double,
        blendMode: KiteBlendMode,
    ) {
        val text = glyphs.joinToString("") { it.text }
        if (text.isEmpty()) return
        if (skipSystemFontText) {
            usedSystemFontText = true
            return
        }

        val sx = sqrt(textMatrix.a * textMatrix.a + textMatrix.b * textMatrix.b)
        val sy = sqrt(textMatrix.c * textMatrix.c + textMatrix.d * textMatrix.d)
        val rotationRadians = atan2(textMatrix.b, textMatrix.a)
        val rotationDegrees = (rotationRadians * 180.0 / PI).toFloat()
        val renderedSize = fontSize * sy

        // renderedSize is already in DEVICE PIXELS (font size × text-matrix scale, which
        // includes the page raster scale). A Compose `Sp` size is re-multiplied by the device
        // density AND the user's accessibility font scale when measured, so on a real device
        // (density 2–3×) the text rendered that many times too large, and on high-density
        // Android the oversized runs overlapped ("collapsed"). Divide both back out so the
        // glyph lands at exactly renderedSize px on every platform. (JVM test density is 1×,
        // which is why this stayed invisible in the golden tests.)
        val spValue = (renderedSize / (drawScope.density * drawScope.fontScale)).toFloat()

        val composeColor = color.toCompose().copy(alpha = alpha.toFloat().coerceIn(0f, 1f))
        val style = TextStyle(
            color = composeColor,
            fontSize = TextUnit(spValue, TextUnitType.Sp),
            fontFamily = fontSpec.toComposeFamily(),
            fontWeight = fontSpec.toComposeWeight(),
            fontStyle = fontSpec.toComposeStyle(),
        )
        // Compose's skiko text stack keeps a process-global style cache that is
        // not thread-safe. The library's own off-main path never reaches this
        // point (skipSystemFontText probes on the pool, the real render runs on
        // Main), but the public synchronous rasterize() can still be called from
        // an arbitrary app thread while the host UI lays out its own text, and
        // that race surfaces here as a ConcurrentModificationException. Losing
        // one text run to an abort of the whole process is a terrible trade, so
        // retry a few times (the window is a microsecond-scale map purge) and,
        // if the cache is truly hot, skip this run: one missing fallback-font
        // run on one page beats a dead app.
        drawScope.withTransform({
            translate(textMatrix.e.toFloat(), textMatrix.f.toFloat())
            if (rotationDegrees != 0f) rotate(rotationDegrees, pivot = Offset.Zero)
            if (sy != 0.0 && sx != sy) scale(scaleX = (sx / sy).toFloat(), scaleY = 1f, pivot = Offset.Zero)
        }) {
            // Each piece starts where the document's own advances put it (ISO 32000-1, 9.4.4),
            // character and word spacing included (#121). A piece of one glyph keeps the host
            // face's shape, as on AWT and in MuPDF for a base-14 font. A longer piece is fitted
            // to its document width, since its glyphs cannot be placed one by one.
            var penX = 0.0
            for (piece in spacedPieces(glyphs)) {
                val pieceText = piece.joinToString("") { it.text }
                if (pieceText.isNotBlank()) {
                    val layout = measureOrNull(pieceText, style) ?: return@withTransform
                    val metricScale = if (piece.size == 1) 1f else systemFontMetricScale(
                        glyphs = piece,
                        renderedSize = renderedSize,
                        measuredWidthPx = layout.multiParagraph.intrinsics.maxIntrinsicWidth.toDouble(),
                    )
                    withTransform({
                        translate(penX.toFloat(), -layout.firstBaseline)
                        if (metricScale != 1f) scale(scaleX = metricScale, scaleY = 1f, pivot = Offset.Zero)
                    }) {
                        drawText(textLayoutResult = layout, blendMode = paintBlend(blendMode))
                    }
                }
                // renderedSize already carries sy, so the text-space adjustment needs it too.
                penX += piece.sumOf { it.advanceWidth } * renderedSize / 1_000.0 + piece.last().advanceAdjust * sy
            }
        }
    }

    /** Measures [text], retrying the race on skiko's style cache a few times; null when the cache stays hot. */
    private fun measureOrNull(text: String, style: TextStyle): androidx.compose.ui.text.TextLayoutResult? {
        repeat(5) {
            try {
                return textMeasurer.measure(text = text, style = style)
            } catch (race: ConcurrentModificationException) {
                // Try again: the window is a microsecond-scale map purge.
            }
        }
        return null
    }

    /**
     * The outline of [text] in the host face [drawGlyphs] draws a font without
     * embedded outlines in, at 1000 units per em with y up (#85). Off the main
     * thread it only records the run, as the system-font path does, so the page
     * renders again on Main.
     */
    override fun hostGlyphOutline(text: String, fontSpec: FontSpec): KitePath? {
        if (skipSystemFontText) {
            usedSystemFontText = true
            return null
        }
        val style = TextStyle(
            // 1000 px whatever the density and font scale, as in drawTextViaSystemFont.
            fontSize = TextUnit((1000.0 / (drawScope.density * drawScope.fontScale)).toFloat(), TextUnitType.Sp),
            fontFamily = fontSpec.toComposeFamily(),
            fontWeight = fontSpec.toComposeWeight(),
            fontStyle = fontSpec.toComposeStyle(),
        )
        val layout = measureOrNull(text, style) ?: return null
        val baseline = layout.firstBaseline.toDouble()
        val b = KitePath.Builder()
        for (seg in layout.getPathForRange(0, text.length)) {
            val p = seg.points
            fun x(i: Int) = p[i].toDouble()
            fun y(i: Int) = baseline - p[i]
            when (seg.type) {
                PathSegment.Type.Move -> b.moveTo(x(0), y(1))
                PathSegment.Type.Line -> b.lineTo(x(2), y(3))
                // The iterator turns conics into quadratics.
                PathSegment.Type.Quadratic, PathSegment.Type.Conic -> b.quadTo(x(2), y(3), x(4), y(5))
                PathSegment.Type.Cubic -> b.curveTo(x(2), y(3), x(4), y(5), x(6), y(7))
                PathSegment.Type.Close -> b.close()
                PathSegment.Type.Done -> {}
            }
        }
        return b.build()
    }

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double) {
        drawImage(image, ctm, alpha, KiteBlendMode.Normal)
    }

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double, blendMode: KiteBlendMode) {
        // One sampling policy on every canvas (#122, #123). The ctm maps to this scope's pixels,
        // and the edges of an unrotated image move outwards onto whole pixels, as in MuPDF (#300).
        val device = gridFitImage(ctm)
        val sampling = imageSampling(image.width, image.height, device, image.interpolate)
        withActiveClips {
            val bitmap = bitmaps.getOrPut(image, sampling, { it.width.toLong() * it.height * 4 }) { bitmapFor(image, sampling) }
            if (bitmap != null) {
                drawBitmap(
                    bitmap, device, alpha.toFloat().coerceIn(0f, 1f),
                    if (sampling.smooth) FilterQuality.Low else FilterQuality.None, paintBlend(blendMode),
                )
            } else {
                drawPlaceholder(ctm)
            }
        }
    }

    /** Bitmaps built from images, so that an image drawn many times converts once (#117). */
    private val bitmaps = KiteBitmapCache<ImageBitmap>()

    /** The image as a bitmap, averaged down when [sampling] shrinks it, so fine detail fades instead of dropping out. */
    private fun bitmapFor(image: KiteImageData, sampling: KiteImageSampling): ImageBitmap? {
        val decoded = when (image.kind) {
            // Skia decodes JPEG natively; on JVM/iOS it also handles JP2 / JPEG 2000.
            // BitmapFactory on Android decodes JPEG (JP2 returns null → placeholder).
            // JBIG2 is best-effort: most platforms don't support it natively and
            // ImageDecoder will fall back to null + a placeholder.
            KiteImageData.Kind.JPEG, KiteImageData.Kind.JPEG2000, KiteImageData.Kind.JBIG2 ->
                ImageDecoder.decode(image.encodedBytes)
            // RAW (FlateDecode etc.): samples are already inflated. Assemble RGBA
            // and build a bitmap directly. Covers the common embedded-PNG case.
            KiteImageData.Kind.RAW -> return image.toRgbaBytes()?.let { rgba ->
                if (!sampling.shrinks) return@let ImageDecoder.decodeRaw(rgba, image.width, image.height)
                ImageDecoder.decodeRaw(
                    shrinkRgba(rgba, image.width, image.height, sampling.shrinkX, sampling.shrinkY),
                    sampling.shrunkWidth(image.width),
                    sampling.shrunkHeight(image.height),
                )
            }
            else -> null
        } ?: return null
        if (!sampling.shrinks) return decoded
        // readPixels gives straight ARGB on every platform.
        val w = decoded.width
        val small = shrinkArgb(w, decoded.height, sampling.shrinkX, sampling.shrinkY) { pixels, y, rows ->
            decoded.readPixels(pixels, startX = 0, startY = y, width = w, height = rows)
        }
        return ImageDecoder.decodeRaw(small, sampling.shrunkWidth(w), sampling.shrunkHeight(decoded.height)) ?: decoded
    }

    override fun fillShading(
        shading: KiteShading,
        ctm: KiteMatrix,
        clipPath: KitePath?,
        alpha: Double,
        blendMode: KiteBlendMode,
    ) {
        if (paintComplexShading(shading, ctm, clipPath, alpha, blendMode)) return
        val stops = shading.sampleStops() ?: return
        // An end that is not extended paints nothing past it (ISO 32000-1, 8.7.4.5.3 and
        // 8.7.4.5.4). A transparent stop on that end makes the clamp past it transparent.
        val (extendStart, extendEnd) = when (shading) {
            is KiteShading.Axial -> shading.extendStart to shading.extendEnd
            is KiteShading.Radial -> shading.extendStart to shading.extendEnd
            else -> true to true
        }
        val composeStops = buildList {
            if (!extendStart) add(0f to Color.Transparent)
            for (i in stops.offsets.indices) add(stops.offsets[i].toFloat() to stops.colors[i].toCompose())
            if (!extendEnd) add(1f to Color.Transparent)
        }.toTypedArray()
        // The gradient is built and drawn in shading space under the whole CTM, so a
        // non-uniform or skewed CTM turns circles into ellipses and tilts the bands
        // (ISO 32000-1, 8.7.4.5.3 and 8.7.4.5.4). A CTM without an inverse paints nothing.
        val det = ctm.a * ctm.d - ctm.b * ctm.c
        if (det == 0.0 || !det.isFinite()) return

        val brush: Brush = when (shading) {
            is KiteShading.Axial -> {
                val c = shading.coords
                Brush.linearGradient(
                    colorStops = composeStops,
                    start = Offset(c[0].toFloat(), c[1].toFloat()),
                    end = Offset(c[2].toFloat(), c[3].toFloat()),
                )
            }
            is KiteShading.Radial -> {
                // A radial shading runs between two circles (ISO 32000-1, 8.7.4.5.4).
                // The end radius is at least a tenth of a device pixel.
                val c = shading.coords
                val r0 = c[2].coerceAtLeast(0.0)
                val r1 = c[5].coerceAtLeast(0.1 / sqrt(abs(det)))
                twoCircleGradient(
                    c[0].toFloat(), c[1].toFloat(), r0.toFloat(), c[3].toFloat(), c[4].toFloat(), r1.toFloat(),
                    composeStops.map { it.second }, composeStops.map { it.first },
                )?.let { ShaderBrush(it) } ?: oneCircleGradient(c[0], c[1], r0, c[3], c[4], r1, composeStops)
            }
            is KiteShading.Unsupported -> {
                // Background fall-back: solid colour if the spec gave one.
                val bg = shading.background ?: return
                if (clipPath != null) {
                    fillPath(clipPath, ctm, bg, evenOdd = false, alpha = alpha, blendMode = blendMode)
                }
                return
            }
            else -> return // complex shading types already handled by paintComplexShading
        }

        // Without a region the shading covers the whole canvas (the `sh` operator), taken back into shading space.
        val region = clipPath ?: KitePath.Builder().apply {
            val w = drawScope.size.width.toDouble()
            val h = drawScope.size.height.toDouble()
            fun corner(x: Double, y: Double, first: Boolean) {
                val sx = (ctm.d * (x - ctm.e) - ctm.c * (y - ctm.f)) / det
                val sy = (ctm.a * (y - ctm.f) - ctm.b * (x - ctm.e)) / det
                if (first) moveTo(sx, sy) else lineTo(sx, sy)
            }
            corner(0.0, 0.0, true); corner(w, 0.0, false); corner(w, h, false); corner(0.0, h, false)
            close()
        }.build()
        withActiveClips {
            val composeBlend = paintBlend(blendMode)
            val a = alpha.toFloat().coerceIn(0f, 1f)
            drawScope.withTransform({ transform(ctm.toComposeMatrix()) }) {
                val cp = toComposePath(region, KiteMatrix.IDENTITY).apply { fillType = PathFillType.NonZero }
                drawPath(cp, brush = brush, alpha = a, blendMode = composeBlend)
            }
        }
    }

    override fun applySoftMask(
        kind: SoftMask.Kind,
        maskBBox: KiteRectangle,
        maskCtm: KiteMatrix,
        render: () -> Unit,
        renderMask: (KiteCanvas) -> Unit,
    ) {
        applySoftMask(kind, maskBBox, maskCtm, null, render, renderMask)
    }

    /**
     * Soft-mask compositing (ISO 32000-1 §11.6.5). We open a saveLayer for
     * the content, render it, then over-paint the mask group with
     * [ComposeBlendMode.DstIn] so the mask's alpha clips the content.
     *
     * A colour matrix turns the mask layer into alpha. A matrix can only scale
     * and offset, so the [transfer] function applies as the straight line
     * closest to its table: exact for a linear function such as an inverter.
     */
    override fun applySoftMask(
        kind: SoftMask.Kind,
        maskBBox: KiteRectangle,
        maskCtm: KiteMatrix,
        transfer: KiteMaskTransfer?,
        render: () -> Unit,
        renderMask: (KiteCanvas) -> Unit,
    ) {
        val composeCanvas = drawScope.drawContext.canvas
        // Outer layer: holds the masked content.
        val outerPaint = Paint()
        composeCanvas.saveLayer(infiniteRect(), outerPaint)
        // The content and the mask composite as usual, also inside a knockout group.
        groups.addLast(Group(layered = false, knockout = false))
        try {
            render()
            // Inner layer with DstIn: subsequent draws will multiply by the
            // existing layer's alpha, so the mask keeps only what it covers.
            // For a Luminosity mask (§11.6.5.2) the mask group composites over
            // an opaque BLACK backdrop and its LUMINANCE becomes the alpha:
            // a colour-matrix filter moves 0.299R+0.587G+0.114B into A at the
            // layer restore, mirroring the Skia backend's LUMA filter.
            // The /TR line then maps the alpha: A' = slope * A + offset. The offset of a
            // Compose colour matrix is in levels from 0 to 255.
            val slope = (transfer?.slope ?: 1.0).toFloat()
            val offset = ((transfer?.offset ?: 0.0) * 255).toFloat()
            val maskPaint = Paint().apply {
                blendMode = ComposeBlendMode.DstIn
                if (kind == SoftMask.Kind.Luminosity) {
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                        androidx.compose.ui.graphics.ColorMatrix(
                            floatArrayOf(
                                0f, 0f, 0f, 0f, 0f,
                                0f, 0f, 0f, 0f, 0f,
                                0f, 0f, 0f, 0f, 0f,
                                0.299f * slope, 0.587f * slope, 0.114f * slope, 0f, offset,
                            ),
                        ),
                    )
                } else if (transfer != null) {
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                        androidx.compose.ui.graphics.ColorMatrix(
                            floatArrayOf(
                                1f, 0f, 0f, 0f, 0f,
                                0f, 1f, 0f, 0f, 0f,
                                0f, 0f, 1f, 0f, 0f,
                                0f, 0f, 0f, slope, offset,
                            ),
                        ),
                    )
                }
            }
            composeCanvas.saveLayer(infiniteRect(), maskPaint)
            try {
                if (kind == SoftMask.Kind.Luminosity) {
                    // Unpainted mask pixels stay black -> luminance 0 -> fully
                    // masked out, per the spec's black-backdrop rule.
                    drawScope.drawRect(Color.Black, size = drawScope.size)
                }
                renderMask(this)
            } finally {
                composeCanvas.restore()
            }
        } finally {
            groups.removeLastOrNull()
            composeCanvas.restore()
        }
    }

    private fun infiniteRect(): Rect {
        val w = drawScope.size.width
        val h = drawScope.size.height
        return Rect(0f, 0f, w, h)
    }

    private fun drawBitmap(
        bitmap: ImageBitmap, ctm: KiteMatrix, alpha: Float, filterQuality: FilterQuality,
        blendMode: androidx.compose.ui.graphics.BlendMode = androidx.compose.ui.graphics.BlendMode.SrcOver,
    ) {
        // The full CTM, not its scale magnitudes: rotation, reflection and
        // shear survive. The unit-square mapping matches Skia: translate up
        // one unit and flip Y, so bitmap row 0 lands on the square's top
        // edge (v = 1).
        drawScope.withTransform({
            transform(ctm.toComposeMatrix())
            translate(0f, 1f)
            scale(1f / bitmap.width, -1f / bitmap.height, pivot = Offset.Zero)
        }) {
            drawImage(
                image = bitmap, dstSize = IntSize(bitmap.width, bitmap.height), alpha = alpha,
                blendMode = blendMode, filterQuality = filterQuality,
            )
        }
    }

    private fun drawPlaceholder(ctm: KiteMatrix) {
        val rectPath = KitePath.Builder().apply { rectangle(0.0, 0.0, 1.0, 1.0) }.build()
        val composeRect = toComposePath(rectPath, ctm).apply { fillType = PathFillType.NonZero }
        drawScope.drawPath(composeRect, color = Color(0xFFE0E0E0.toInt()))
        drawScope.drawPath(composeRect, color = Color(0xFF888888.toInt()), style = Stroke(width = 1f))
        val diagonal = KitePath.Builder().apply {
            moveTo(0.0, 0.0); lineTo(1.0, 1.0)
            moveTo(0.0, 1.0); lineTo(1.0, 0.0)
        }.build()
        drawScope.drawPath(
            toComposePath(diagonal, ctm),
            color = Color(0xFFAAAAAA.toInt()),
            style = Stroke(width = 0.5f),
        )
    }

    override fun pushClip(path: KitePath, ctm: KiteMatrix, evenOdd: Boolean) {
        val composePath = toComposePath(path, ctm).apply {
            fillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
        }
        clipStack.addLast(ClipFrame(composePath))
    }

    override fun popClip() {
        if (clipStack.isNotEmpty()) clipStack.removeLast()
    }

    /**
     * Transparency groups: open a Compose `saveLayer` on the active Canvas
     * with a Paint that carries the requested alpha + blend mode. Subsequent
     * draws accumulate into the offscreen layer; matching [endTransparencyGroup]
     * calls `restore`, which composites the layer onto the parent.
     *
     * A non-isolated group at full alpha in Normal paints straight onto its
     * backdrop, so its blend modes see what lies under it (ISO 32000-1, 11.4.5,
     * #125). A non-isolated group's layer starts as a copy of the backdrop where
     * the platform can copy it, which is exact over an opaque backdrop. On
     * Android it starts transparent, which is exact when no paint inside blends.
     * In a knockout group (11.4.6) each paint replaces what lies under it.
     */
    override fun beginTransparencyGroup(
        bbox: KiteRectangle, ctm: KiteMatrix,
        isolated: Boolean, knockout: Boolean,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        // A group nested in a knockout group gets a layer, so its own paints do not knock each other out.
        val nested = knockingOut
        val layered = isolated || knockout || nested || alpha < 1.0 || blendMode != KiteBlendMode.Normal
        groups.addLast(Group(layered, knockout))
        if (!layered) return
        // Compute the layer's pixel bounds in device space.
        val x0 = ctm.transformX(bbox.left, bbox.bottom)
        val y0 = ctm.transformY(bbox.left, bbox.bottom)
        val x1 = ctm.transformX(bbox.right, bbox.bottom)
        val y1 = ctm.transformY(bbox.right, bbox.bottom)
        val x2 = ctm.transformX(bbox.right, bbox.top)
        val y2 = ctm.transformY(bbox.right, bbox.top)
        val x3 = ctm.transformX(bbox.left, bbox.top)
        val y3 = ctm.transformY(bbox.left, bbox.top)
        val rect = Rect(
            minOf(minOf(x0, x1), minOf(x2, x3)).toFloat(),
            minOf(minOf(y0, y1), minOf(y2, y3)).toFloat(),
            maxOf(maxOf(x0, x1), maxOf(x2, x3)).toFloat(),
            maxOf(maxOf(y0, y1), maxOf(y2, y3)).toFloat(),
        )

        val paint = Paint().apply {
            this.alpha = alpha.toFloat().coerceIn(0f, 1f)
            this.blendMode = blendMode.toCompose()
        }
        val canvas = drawScope.drawContext.canvas
        val overBackdrop = !isolated && !knockout && !nested && blendMode == KiteBlendMode.Normal
        if (!overBackdrop || !saveLayerOverBackdrop(canvas, rect, paint)) canvas.saveLayer(rect, paint)
        openGroups++
    }

    /** An open group: whether it opened a layer, and whether its paints knock out. */
    private class Group(val layered: Boolean, val knockout: Boolean)

    private val groups = ArrayDeque<Group>()

    /**
     * True while the paints go straight to the layer of a knockout group. Against the
     * group's transparent backdrop a paint in any blend mode is its own colour, so it
     * replaces what lies under it, and the anti-aliased edge mixes by coverage (#125).
     */
    private val knockingOut: Boolean get() = groups.lastOrNull()?.knockout == true

    private fun paintBlend(mode: KiteBlendMode): ComposeBlendMode = if (knockingOut) ComposeBlendMode.Src else mode.toCompose()

    override fun endTransparencyGroup() {
        if (groups.removeLastOrNull()?.layered != true) return
        if (openGroups <= 0) return
        drawScope.drawContext.canvas.restore()
        openGroups--
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private fun withActiveClips(block: () -> Unit) {
        applyClipsThen(0, block)
    }

    private fun applyClipsThen(index: Int, block: () -> Unit) {
        if (index >= clipStack.size) {
            block(); return
        }
        val frame = clipStack[index]
        drawScope.clipPath(frame.path) {
            applyClipsThen(index + 1, block)
        }
    }

    /**
     * One circle standing in for a radial shading between two circles, on a platform
     * that has no gradient between two circles. It is exact when the circles share a
     * centre. Otherwise it keeps only the end circle.
     */
    private fun oneCircleGradient(
        x0: Double, y0: Double, r0: Double, x1: Double, y1: Double, r1: Double,
        stops: Array<Pair<Float, Color>>,
    ): Brush {
        val center = Offset(x1.toFloat(), y1.toFloat())
        if (x0 != x1 || y0 != y1) return Brush.radialGradient(*stops, center = center, radius = r1.toFloat())
        // Offset s lies on the circle of radius r0 + s (r1 - r0), as a fraction of the larger radius.
        val outer = maxOf(r0, r1)
        val mapped = stops.map { (s, color) -> ((r0 + s * (r1 - r0)) / outer).toFloat() to color }
        val ordered = if (r1 < r0) mapped.reversed() else mapped
        return Brush.radialGradient(*ordered.toTypedArray(), center = center, radius = outer.toFloat())
    }

    /** This matrix as a Compose matrix, rotation, reflection and shear included. */
    private fun KiteMatrix.toComposeMatrix(): androidx.compose.ui.graphics.Matrix {
        val m = androidx.compose.ui.graphics.Matrix()
        m.values[androidx.compose.ui.graphics.Matrix.ScaleX] = a.toFloat()
        m.values[androidx.compose.ui.graphics.Matrix.SkewY] = b.toFloat()
        m.values[androidx.compose.ui.graphics.Matrix.SkewX] = c.toFloat()
        m.values[androidx.compose.ui.graphics.Matrix.ScaleY] = d.toFloat()
        m.values[androidx.compose.ui.graphics.Matrix.TranslateX] = e.toFloat()
        m.values[androidx.compose.ui.graphics.Matrix.TranslateY] = f.toFloat()
        return m
    }

    private fun toComposePath(src: KitePath, ctm: KiteMatrix): Path {
        val out = Path()
        for (seg in src.segments) {
            when (seg) {
                is KitePath.Segment.MoveTo -> {
                    val x = ctm.transformX(seg.x, seg.y)
                    val y = ctm.transformY(seg.x, seg.y)
                    out.moveTo(x.toFloat(), y.toFloat())
                }
                is KitePath.Segment.LineTo -> {
                    val x = ctm.transformX(seg.x, seg.y)
                    val y = ctm.transformY(seg.x, seg.y)
                    out.lineTo(x.toFloat(), y.toFloat())
                }
                is KitePath.Segment.CurveTo -> {
                    val x1 = ctm.transformX(seg.x1, seg.y1)
                    val y1 = ctm.transformY(seg.x1, seg.y1)
                    val x2 = ctm.transformX(seg.x2, seg.y2)
                    val y2 = ctm.transformY(seg.x2, seg.y2)
                    val x3 = ctm.transformX(seg.x3, seg.y3)
                    val y3 = ctm.transformY(seg.x3, seg.y3)
                    out.cubicTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), x3.toFloat(), y3.toFloat())
                }
                is KitePath.Segment.QuadTo -> {
                    val x1 = ctm.transformX(seg.x1, seg.y1)
                    val y1 = ctm.transformY(seg.x1, seg.y1)
                    val x2 = ctm.transformX(seg.x2, seg.y2)
                    val y2 = ctm.transformY(seg.x2, seg.y2)
                    out.quadraticTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
                }
                KitePath.Segment.Close -> out.close()
            }
        }
        return out
    }

    private fun RgbColor.toCompose(): Color = Color(r.toFloat(), g.toFloat(), b.toFloat(), 1f)

    /**
     * Map a PDF blend mode to its Compose equivalent. All 16 PDF blend modes
     * have a 1:1 Compose counterpart, so this is a clean enum dispatch.
     */
    private fun KiteBlendMode.toCompose(): ComposeBlendMode = when (this) {
        KiteBlendMode.Normal -> ComposeBlendMode.SrcOver
        KiteBlendMode.Multiply -> ComposeBlendMode.Multiply
        KiteBlendMode.Screen -> ComposeBlendMode.Screen
        KiteBlendMode.Overlay -> ComposeBlendMode.Overlay
        KiteBlendMode.Darken -> ComposeBlendMode.Darken
        KiteBlendMode.Lighten -> ComposeBlendMode.Lighten
        KiteBlendMode.ColorDodge -> ComposeBlendMode.ColorDodge
        KiteBlendMode.ColorBurn -> ComposeBlendMode.ColorBurn
        KiteBlendMode.HardLight -> ComposeBlendMode.Hardlight
        KiteBlendMode.SoftLight -> ComposeBlendMode.Softlight
        KiteBlendMode.Difference -> ComposeBlendMode.Difference
        KiteBlendMode.Exclusion -> ComposeBlendMode.Exclusion
        KiteBlendMode.Hue -> ComposeBlendMode.Hue
        KiteBlendMode.Saturation -> ComposeBlendMode.Saturation
        KiteBlendMode.Color -> ComposeBlendMode.Color
        KiteBlendMode.Luminosity -> ComposeBlendMode.Luminosity
    }

    private fun FontSpec.toComposeFamily(): FontFamily = when (family) {
        KiteFontFamily.Serif -> FontFamily.Serif
        KiteFontFamily.Monospace -> FontFamily.Monospace
        KiteFontFamily.SansSerif -> FontFamily.SansSerif
    }

    private fun FontSpec.toComposeWeight(): FontWeight =
        if (bold) FontWeight.Bold else FontWeight.Normal

    private fun FontSpec.toComposeStyle(): FontStyle =
        if (italic) FontStyle.Italic else FontStyle.Normal

    private val PI = kotlin.math.PI

    private data class ClipFrame(val path: Path)
}

/**
 * Scales one shaped system-font run to the width assigned by the document
 * layout engine. The host substitute font can have different metrics from the
 * requested PDF or EPUB font. Keeping the run shaped as one unit preserves
 * ligatures and combining marks while preventing adjacent runs from colliding.
 */
internal fun systemFontMetricScale(
    glyphs: List<TextGlyph>,
    renderedSize: Double,
    measuredWidthPx: Double,
): Float {
    if (!renderedSize.isFinite() || renderedSize <= 0.0 || measuredWidthPx <= 0) return 1f
    val targetWidthPx = glyphs.sumOf { it.advanceWidth } * renderedSize / 1_000.0
    if (!targetWidthPx.isFinite() || targetWidthPx <= 0.0) return 1f
    val scale = targetWidthPx / measuredWidthPx
    val floatScale = scale.toFloat()
    return if (floatScale.isFinite() && floatScale > 0f) floatScale else 1f
}

/**
 * [glyphs] cut into pieces that each shape as one unit. A glyph that the document
 * spaces after, by character or word spacing, ends its piece, and a spaced blank
 * glyph is a piece of its own. A run without spacing stays one piece, which keeps
 * its ligatures and combining marks.
 */
internal fun spacedPieces(glyphs: List<TextGlyph>): List<List<TextGlyph>> {
    val pieces = ArrayList<List<TextGlyph>>()
    var start = 0
    for (i in glyphs.indices) {
        val glyph = glyphs[i]
        if (glyph.advanceAdjust == 0.0) continue
        if (glyph.text.isBlank() && start < i) {
            pieces += glyphs.subList(start, i)
            start = i
        }
        pieces += glyphs.subList(start, i + 1)
        start = i + 1
    }
    if (start < glyphs.size) pieces += glyphs.subList(start, glyphs.size)
    return pieces
}

/**
 * Dash intervals for Compose: every element kept, zeros included, and an odd
 * array doubled since a dash path effect needs pairs (ISO 32000-1, 8.4.3.6,
 * #106). Null for an empty or all-zero array, which means a solid line.
 */
internal fun composeDashIntervals(dashArray: List<Double>?, scale: Double): FloatArray? {
    val d = dashArray?.map { v -> (v * scale).toFloat().let { if (it.isFinite()) it.coerceAtLeast(0f) else 0f } } ?: return null
    if (d.isEmpty() || d.none { it > 0f }) return null
    return (if (d.size % 2 == 1) d + d else d).toFloatArray()
}
