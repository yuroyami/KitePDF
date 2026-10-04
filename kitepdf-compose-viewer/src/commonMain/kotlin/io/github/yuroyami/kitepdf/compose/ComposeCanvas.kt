package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.core.render.paintComplexShading
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathSegment
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.isSupported
import io.github.yuroyami.kitepdf.core.kiteWarn
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteBitmapCache
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteImageSampling
import io.github.yuroyami.kitepdf.core.render.KiteMaskTransfer
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.KiteRasterScope
import io.github.yuroyami.kitepdf.core.render.KiteRasterStep
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
import io.github.yuroyami.kitepdf.core.render.gridFitImage
import io.github.yuroyami.kitepdf.core.render.imageSampling
import io.github.yuroyami.kitepdf.core.render.sampleStops
import io.github.yuroyami.kitepdf.core.render.shrinkArgb
import io.github.yuroyami.kitepdf.core.render.strokePen
import io.github.yuroyami.kitepdf.core.render.toShrunkRgbaBytes
import io.github.yuroyami.kitepdf.core.text.Bidi
import kotlin.math.abs
import kotlin.math.ceil
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
 * Clips and transparency groups use the lower-level `Canvas.save`, `clipPath`
 * and `saveLayer`, so they span many `DrawScope` operations, and each clip is
 * applied once however many paints it covers.
 *
 * On the desktop JVM, iOS, macOS and Android, system-font text and host glyph outlines
 * are shaped by the platform's own text engine, not by Compose's text stack, so they draw
 * on any thread (#131, #487). In a browser they go through Compose's text and require the
 * UI thread; a call on another thread throws [IllegalStateException] before accessing the
 * host text stack. For background exports use [KitePageRasterizer.rasterizeOffMain]. A
 * canvas that skips system text can probe on a worker, but that incomplete bitmap is not
 * an export (#428). The canvas of a Vectorized page in `KiteDocView` draws inside its
 * scene's own draw pass, so its host text takes the thread that scene draws on (#464).
 */
public class ComposeCanvas internal constructor(
    private var drawScope: DrawScope,
    private val textMeasurer: TextMeasurer,
    private val hairlineWidthPx: Float,
    private val skipSystemFontText: Boolean,
    /**
     * How much the output is magnified after this draw, as by the zoom layer of a Vectorized
     * page: image sampling and the hairline follow the pixels on screen, not the pixels drawn.
     */
    private val magnification: Float,
    /** The platform's gradient between two circles, or null where it has none. A test can take it away. */
    private val twoCircleShader: (Float, Float, Float, Float, Float, Float, List<Color>, List<Float>) -> Shader? = ::twoCircleGradient,
    /** True where a colour filter can apply a soft mask's whole transfer table. A test can take it away. */
    private val maskTables: Boolean = maskTableFilters,
    /**
     * Bitmaps built from images, so that an image drawn many times converts once (#117). A
     * vector viewer shares its bounded store across draws, and a soft mask shares its page's
     * store. Keys contain only image identities, so eviction of decoded source images still
     * releases their bytes (#371). Eviction never recycles a bitmap held by a recorded layer.
     */
    private val bitmaps: KiteBitmapCache<ImageBitmap> = KiteBitmapCache(),
    /**
     * True for the canvas of a Vectorized page, which the viewer makes and draws on inside the
     * Compose draw pass of its scene. That thread already draws the scene's own text with the
     * same [textMeasurer], so host text there takes the thread the scene draws on, even off the
     * platform UI thread, as an `ImageComposeScene` on a thread of its own does (#464).
     */
    private val inSceneDrawPass: Boolean = false,
    /**
     * The coverage of glyphs drawn before, for a canvas whose pixels are device pixels, as a
     * raster's are. Null for a canvas drawn under a transform of its own, such as a Vectorized
     * page, which keeps every glyph a path so that it stays sharp under a pinch (#382).
     */
    private val glyphMasks: GlyphMaskCache? = null,
    /**
     * True to draw host-font text with [hostTextLine], which needs no UI thread, where the
     * platform has it (#131). A test sets it false to draw through Compose's text as a browser does.
     */
    private val hostLines: Boolean = hostTextAnyThread,
    /**
     * The bitmap this canvas draws into one pixel for one unit, as a rasterizer's is, so a raster
     * step can read the backdrop from it. Null for a canvas on screen, whose pixels Compose keeps.
     */
    private val target: ImageBitmap? = null,
) : KiteCanvas {

    /**
     * A canvas that draws into [drawScope].
     *
     * @param hairlineWidthPx the width in raster pixels of a stroke whose line width is 0.
     *   Defaults to 1, the one device pixel of ISO 32000-1, 8.4.3.2. Other thin strokes widen
     *   to a fifth of it, as [strokePen] describes. When rasterizing supersampled (raster
     *   larger than its on-screen size), pass the supersample factor instead, so thin strokes
     *   keep their weight after the downscale.
     * @param skipSystemFontText when true, system-font text runs are not measured or drawn;
     *   the canvas only records that one was encountered. The off-main raster path uses this:
     *   Compose's skiko text stack shares process-global state with the host UI thread, so
     *   measuring or drawing through it is only safe on the main thread. A pool-thread render
     *   probes with this flag and, when it trips, the page is re-rendered on Main.
     */
    public constructor(
        drawScope: DrawScope,
        textMeasurer: TextMeasurer,
        hairlineWidthPx: Float = 1f,
        skipSystemFontText: Boolean = false,
    ) : this(drawScope, textMeasurer, hairlineWidthPx, skipSystemFontText, magnification = 1f)

    /** True once a system-font run was skipped because of [skipSystemFontText]. */
    internal var usedSystemFontText: Boolean = false
        private set

    /**
     * What each open save on the canvas holds, oldest first: a clip, or the layer of a
     * transparency group or a soft mask. A clip is applied once when it is pushed and restored
     * when it is popped, as `SkiaCanvas` does, so a paint costs the same at any clip depth and
     * never recurses once per clip (#335). Clips and layers share one stack, so a restore always
     * undoes the save it belongs to.
     */
    private val saves = ArrayDeque<Save>()

    private enum class Save { Clip, Layer }

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: KiteMatrix) {
        restoreAll()
        groups.clear()
    }

    override fun endPage() {
        // Close anything still open (defensive: well-formed PDFs always pair them,
        // but malformed ones leak).
        restoreAll()
        groups.clear()
    }

    private fun restoreAll() {
        while (saves.isNotEmpty()) {
            saves.removeLast()
            drawScope.drawContext.canvas.restore()
        }
    }

    /** Restores the newest layer, and first any clip opened inside it. */
    private fun restoreThroughLayer() {
        while (saves.isNotEmpty()) {
            val top = saves.removeLast()
            drawScope.drawContext.canvas.restore()
            if (top == Save.Layer) return
        }
    }

    override fun fillPath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, evenOdd: Boolean,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        val composePath = toComposePath(path, ctm, scratchPath).apply {
            fillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
        }
        drawScope.drawPath(
            path = composePath,
            color = color.toCompose(),
            alpha = alpha.toFloat().coerceIn(0f, 1f),
            blendMode = paintBlend(blendMode),
        )
    }

    override fun strokePath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, lineWidth: Double,
        alpha: Double, blendMode: KiteBlendMode,
        dashArray: List<Double>?, dashPhase: Double,
        lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) {
        // The hairline scales with a supersampled raster, so thin strokes keep their
        // on-screen weight after the downscale, and shrinks with a magnified one (#418).
        val pen = strokePen(ctm, lineWidth, hairlineWidthPx.toDouble() / magnification)
        val composePath = toComposePath(path, pen.pathMatrix, scratchPath)
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
        if (hasOutlines) {
            drawTextViaOutlines(glyphs, fontSize, unitsPerEm, textToDevice, color, alpha, blendMode)
        } else {
            drawTextViaSystemFont(glyphs, fontSize, fontSpec, textToDevice, color, alpha, blendMode)
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
        // Opaque text in the normal blend mode adds the cached coverage of its glyphs into one
        // bitmap and draws that once, since a Compose draw costs as much as a small glyph (#382).
        // Two glyphs of one colour over each other cover a pixel by 1 - (1 - a)(1 - b) either
        // way. Translucent text and another blend mode would paint an overlap twice, and a
        // knockout group paints with Src, which would clear the empty pixels of the run's box,
        // so those fill each glyph's path.
        val masks = if (a == 1f && blendMode == KiteBlendMode.Normal && !knockingOut) glyphMasks else null
        val run = if (masks != null) ArrayList<RunGlyph>(glyphs.size) else null
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
                val placed = masks?.place(outline, glyphMatrix, unitsPerEm)
                if (placed != null) {
                    run?.add(RunGlyph(placed, outline, glyphMatrix))
                } else {
                    fillGlyph(outline, glyphMatrix, color, a, composeBlend)
                }
            }
            penX += glyph.advanceWidth * advanceScale + glyph.advanceAdjust
        }
        if (!run.isNullOrEmpty() && !drawRun(run, color)) {
            for (g in run) fillGlyph(g.outline, g.matrix, color, a, composeBlend)
        }
    }

    /** One glyph of a run that draws from masks: where its mask lands, and its outline should the run draw paths after all. */
    private class RunGlyph(val placed: GlyphMaskCache.Placement, val outline: KitePath, val matrix: KiteMatrix)

    /** [outline] under [glyphMatrix], filled as a path. A glyph drawn again on the page reuses its path (#382). */
    private fun fillGlyph(outline: KitePath, glyphMatrix: KiteMatrix, color: Color, alpha: Float, blend: ComposeBlendMode) {
        val cp = glyphPaths.getOrPut(GlyphOutline(outline)) {
            toComposePath(outline, KiteMatrix.IDENTITY).apply { fillType = PathFillType.NonZero }
        }
        drawScope.withTransform({ transform(glyphMatrix.toComposeMatrix()) }) {
            drawPath(cp, color = color, alpha = alpha, blendMode = blend)
        }
    }

    /**
     * The masks of [run] added into one bitmap of [color] over the part of the canvas they
     * cover, and drawn once. True when drawn, or when none of it lies on the canvas. False, with
     * nothing drawn, for a run spread so thinly that its box would cost more than its paths.
     */
    private fun drawRun(run: List<RunGlyph>, color: Color): Boolean {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        var covered = 0L
        for (g in run) {
            val m = g.placed.mask
            val x = g.placed.x + m.left
            val y = g.placed.y + m.top
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x + m.width)
            bottom = maxOf(bottom, y + m.height)
            covered += m.width.toLong() * m.height
        }
        // Only the part on the canvas needs pixels.
        left = maxOf(left, 0)
        top = maxOf(top, 0)
        right = minOf(right, ceil(drawScope.size.width).toInt())
        bottom = minOf(bottom, ceil(drawScope.size.height).toInt())
        if (right <= left || bottom <= top) return true
        val w = right - left
        val h = bottom - top
        val area = w.toLong() * h
        if (area > MAX_RUN_PIXELS || area > 8 * covered + 4096) return false
        val coverage = ByteArray((area).toInt())
        for (g in run) {
            val m = g.placed.mask
            val ox = g.placed.x + m.left - left
            val oy = g.placed.y + m.top - top
            for (my in maxOf(0, -oy) until minOf(m.height, h - oy)) {
                var src = my * m.width + maxOf(0, -ox)
                var dst = (oy + my) * w + ox + maxOf(0, -ox)
                for (mx in maxOf(0, -ox) until minOf(m.width, w - ox)) {
                    val b = m.coverage[src++].toInt() and 0xFF
                    if (b != 0) {
                        val k = coverage[dst].toInt() and 0xFF
                        coverage[dst] = (k + b - (k * b + 127) / 255).toByte()
                    }
                    dst++
                }
            }
        }
        val r = (color.red * 255f + 0.5f).toInt().toByte()
        val g = (color.green * 255f + 0.5f).toInt().toByte()
        val b = (color.blue * 255f + 0.5f).toInt().toByte()
        val rgba = ByteArray(coverage.size * 4)
        for (i in coverage.indices) {
            val k = coverage[i]
            if (k.toInt() != 0) {
                rgba[4 * i] = r
                rgba[4 * i + 1] = g
                rgba[4 * i + 2] = b
                rgba[4 * i + 3] = k
            }
        }
        val bitmap = ImageDecoder.decodeRaw(rgba, w, h) ?: return false
        drawScope.drawImage(bitmap, Offset(left.toFloat(), top.toFloat()))
        return true
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
        if (hostLines) {
            drawTextViaHostLines(glyphs, fontSize, fontSpec, textMatrix, color, alpha, blendMode)
            return
        }
        if (!inSceneDrawPass) requireHostTextThread()

        // The whole text matrix applies to every glyph, shear and reflection included (ISO
        // 32000-1, 9.4.4, #416). The text is measured at the matrix's own scale and drawn under
        // the rest of it, y flipped, because host text runs y down and text space runs y up.
        val scale = sqrt(abs(textMatrix.a * textMatrix.d - textMatrix.b * textMatrix.c))
        if (!scale.isFinite() || scale <= 0.0) return
        val rest = textMatrix.concat(KiteMatrix(1.0 / scale, 0.0, 0.0, -1.0 / scale, 0.0, 0.0))
        val renderedSize = fontSize * scale

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
            fontFamily = hostFontFamily(fontSpec) ?: fontSpec.toComposeFamily(),
            fontWeight = fontSpec.toComposeWeight(),
            fontStyle = fontSpec.toComposeStyle(),
            // The locale picks the CJK fallback face of the font's language (#472).
            localeList = fontSpec.language?.let { LocaleList(it) },
        )
        drawScope.withTransform({ transform(rest.toComposeMatrix()) }) {
            // Each piece starts where the document's own advances put it (ISO 32000-1, 9.4.4),
            // character and word spacing included (#121). A piece of one glyph keeps the host
            // face's shape, as on AWT and in MuPDF for a base-14 font. A longer piece is fitted
            // to its document width, since its glyphs cannot be placed one by one.
            var penX = 0.0
            for (piece in spacedPieces(glyphs)) {
                for (part in drawOrderParts(piece)) {
                    if (part.text.isBlank()) continue
                    val layout = textMeasurer.measure(text = part.text, style = style)
                    val metricScale = if (part.glyphs.size == 1) 1f else systemFontMetricScale(
                        glyphs = part.glyphs,
                        renderedSize = renderedSize,
                        measuredWidthPx = layout.multiParagraph.intrinsics.maxIntrinsicWidth.toDouble(),
                    )
                    withTransform({
                        translate((penX + part.offset * fontSize / 1_000.0 * scale).toFloat(), -layout.firstBaseline)
                        if (metricScale != 1f) scale(scaleX = metricScale, scaleY = 1f, pivot = Offset.Zero)
                    }) {
                        drawText(textLayoutResult = layout, blendMode = paintBlend(blendMode))
                    }
                }
                // The pen moves in text space, measured at the matrix's scale as the text is.
                penX += (piece.sumOf { it.advanceWidth } * fontSize / 1_000.0 + piece.last().advanceAdjust) * scale
            }
        }
    }

    /**
     * [drawTextViaSystemFont] through [hostTextLine], which needs no UI thread (#131). The run
     * is placed as there: each piece at the pen the document's advances give it, fitted to its
     * document width, under the text matrix with y flipped. The size is in device pixels, as a
     * Skia font takes it, with no density to divide out.
     */
    private fun drawTextViaHostLines(
        glyphs: List<TextGlyph>,
        fontSize: Double,
        fontSpec: FontSpec,
        textMatrix: KiteMatrix,
        color: RgbColor,
        alpha: Double,
        blendMode: KiteBlendMode,
    ) {
        val scale = sqrt(abs(textMatrix.a * textMatrix.d - textMatrix.b * textMatrix.c))
        if (!scale.isFinite() || scale <= 0.0) return
        val rest = textMatrix.concat(KiteMatrix(1.0 / scale, 0.0, 0.0, -1.0 / scale, 0.0, 0.0))
        val renderedSize = fontSize * scale
        if (!(renderedSize > 0.0 && renderedSize < MAX_HOST_TEXT_PX)) return
        val composeColor = color.toCompose().copy(alpha = alpha.toFloat().coerceIn(0f, 1f))
        val blend = paintBlend(blendMode)
        drawScope.withTransform({ transform(rest.toComposeMatrix()) }) {
            var penX = 0.0
            for (piece in spacedPieces(glyphs)) {
                for (part in drawOrderParts(piece)) {
                    if (part.text.isBlank()) continue
                    val line = hostTextLine(part.text, fontSpec, renderedSize.toFloat(), composeColor, blend) ?: continue
                    val metricScale = if (part.glyphs.size == 1) 1f else systemFontMetricScale(
                        glyphs = part.glyphs,
                        renderedSize = renderedSize,
                        measuredWidthPx = line.width.toDouble(),
                    )
                    withTransform({
                        translate((penX + part.offset * fontSize / 1_000.0 * scale).toFloat(), 0f)
                        if (metricScale != 1f) scale(scaleX = metricScale, scaleY = 1f, pivot = Offset.Zero)
                    }) {
                        line.draw(this)
                    }
                }
                penX += (piece.sumOf { it.advanceWidth } * fontSize / 1_000.0 + piece.last().advanceAdjust) * scale
            }
        }
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
        // The outline comes from Skia's own font API where the platform shapes host text itself (#131).
        if (!hostLines && !inSceneDrawPass) requireHostTextThread()
        // Real glyph contours from the host face. The text layout's range path is the selection
        // highlight, rectangles, which stroked an O as a box (ISO 32000-1, 9.3.6, #415).
        val path = hostTextPath(text, fontSpec) ?: return null
        val b = KitePath.Builder()
        for (seg in path) {
            val p = seg.points
            fun x(i: Int) = p[i].toDouble()
            fun y(i: Int) = -p[i].toDouble()
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
        // Magnified output samples for the pixels on screen, and snaps to none: a zoom layer
        // would enlarge both the averaged detail and the snap (#418).
        val device = if (magnification == 1f) gridFitImage(ctm) else ctm
        val m = magnification.toDouble()
        val onScreen = KiteMatrix(device.a * m, device.b * m, device.c * m, device.d * m, device.e, device.f)
        val sampling = imageSampling(image.width, image.height, onScreen, image.interpolate)
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

    /** Conversions performed by this paint pass, excluding hits in the shared bitmap cache. */
    internal var convertedImages: Int = 0
        private set

    /** The image as a bitmap, averaged down when [sampling] shrinks it, so fine detail fades instead of dropping out. */
    private fun bitmapFor(image: KiteImageData, sampling: KiteImageSampling): ImageBitmap? {
        convertedImages++
        val bytes = when (image.kind) {
            // RAW (FlateDecode etc.): samples are already inflated. Assemble RGBA
            // and build a bitmap directly. Covers the common embedded-PNG case.
            // An image drawn smaller converts and shrinks a band of rows at a time (#381).
            KiteImageData.Kind.RAW -> {
                val rgba = image.toShrunkRgbaBytes(sampling.shrinkX, sampling.shrinkY)
                if (rgba != null) {
                    return ImageDecoder.decodeRaw(rgba, sampling.shrunkWidth(image.width), sampling.shrunkHeight(image.height))
                }
                // A JPEG whose data KiteImageCodec could not decode goes to the platform decoder below (#475).
                image.encodedBytes.takeIf { it.isNotEmpty() } ?: return null
            }
            // An encoded image that the core could not decode goes to the platform decoder. Skia and
            // Android read JPEG and shrink it inside the decoder by up to 8 (#381). They have no JPEG
            // 2000 or JBIG2 codec, so those return null and draw as a placeholder.
            KiteImageData.Kind.JPEG, KiteImageData.Kind.JPEG2000, KiteImageData.Kind.JBIG2 -> image.encodedBytes
            else -> return null
        }
        val (decoded, done) = decodeSampled(bytes, minOf(sampling.shrinkX, sampling.shrinkY, 8)) ?: return null
        // The decoder divided each side by done, and both factors are powers of two: average the rest.
        val fx = sampling.shrinkX / done
        val fy = sampling.shrinkY / done
        if (fx == 1 && fy == 1) return decoded
        // readPixels gives straight ARGB on every platform.
        val w = decoded.width
        val h = decoded.height
        val small = shrinkArgb(w, h, fx, fy) { pixels, y, rows ->
            decoded.readPixels(pixels, startX = 0, startY = y, width = w, height = rows)
        }
        return ImageDecoder.decodeRaw(small, (w + fx - 1) / fx, (h + fy - 1) / fy) ?: decoded
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
        val composeBlend = paintBlend(blendMode)
        val a = alpha.toFloat().coerceIn(0f, 1f)

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
                val native = twoCircleShader(
                    c[0].toFloat(), c[1].toFloat(), r0.toFloat(), c[3].toFloat(), c[4].toFloat(), r1.toFloat(),
                    composeStops.map { it.second }, composeStops.map { it.first },
                )
                if (native == null && (c[0] != c[3] || c[1] != c[4])) {
                    // No gradient between two circles here, and one around the end circle would
                    // move an off-centre shading, so draw its pixels (#413).
                    drawTwoCircles(c[0], c[1], r0, c[3], c[4], r1, composeStops, ctm, region, a, composeBlend)
                    return
                }
                native?.let { ShaderBrush(it) } ?: oneCircleGradient(c[0], c[1], r0, c[3], c[4], r1, composeStops)
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

        drawScope.withTransform({ transform(ctm.toComposeMatrix()) }) {
            val cp = toComposePath(region, KiteMatrix.IDENTITY, scratchPath).apply { fillType = PathFillType.NonZero }
            drawPath(cp, brush = brush, alpha = a, blendMode = composeBlend)
        }
    }

    /**
     * Draws a gradient between two circles, in shading space under [ctm], as an image of the
     * device pixels of [region]. See [twoCircleImage]. A large region is drawn at a lower
     * resolution and scaled up, which a smooth gradient hides.
     */
    private fun drawTwoCircles(
        x0: Double, y0: Double, r0: Double, x1: Double, y1: Double, r1: Double,
        stops: Array<Pair<Float, Color>>,
        ctm: KiteMatrix,
        region: KitePath,
        alpha: Float,
        blend: ComposeBlendMode,
    ) {
        val toShading = ctm.invert() ?: return
        val devicePath = toComposePath(region, ctm).apply { fillType = PathFillType.NonZero }
        val bounds = devicePath.getBounds().intersect(Rect(Offset.Zero, drawScope.size))
        if (bounds.width <= 0f || bounds.height <= 0f) return
        val left = kotlin.math.floor(bounds.left.toDouble())
        val top = kotlin.math.floor(bounds.top.toDouble())
        val w = kotlin.math.ceil(bounds.right.toDouble()) - left
        val h = kotlin.math.ceil(bounds.bottom.toDouble()) - top
        val pixelSize = if (w * h <= TWO_CIRCLE_MAX_PIXELS) 1.0 else sqrt(w * h / TWO_CIRCLE_MAX_PIXELS)
        val width = kotlin.math.ceil(w / pixelSize).toInt()
        val height = kotlin.math.ceil(h / pixelSize).toInt()
        val image = twoCircleImage(x0, y0, r0, x1, y1, r1, stops, toShading, left, top, width, height, pixelSize) ?: return
        drawScope.clipPath(devicePath) {
            drawImage(
                image = image,
                dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                dstSize = IntSize(kotlin.math.ceil(width * pixelSize).toInt(), kotlin.math.ceil(height * pixelSize).toInt()),
                alpha = alpha,
                blendMode = blend,
                filterQuality = FilterQuality.Low,
            )
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
     * A colour filter turns the mask layer into alpha through the [transfer]
     * function: its whole table on Skia, the closest straight line on Android.
     * See [softMaskFilter].
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
        // Outside its box the mask is zero, since the renderer passes the page box when the backdrop
        // lets content through. So both layers cover the box, not the whole canvas (#384).
        val bounds = maskBounds(maskBBox, maskCtm)
        // Outer layer: holds the masked content.
        val outerPaint = Paint()
        composeCanvas.saveLayer(bounds, outerPaint)
        saves.addLast(Save.Layer)
        // The content and the mask composite as usual, also inside a knockout group.
        groups.addLast(Group(layered = false, knockout = false))
        try {
            render()
            if (transfer != null && !maskTables && !transfer.isNearlyLinear) {
                // No filter here takes the whole table (Android), and the closest line would
                // bend the mask, so gate by the mask group's pixels (#445).
                gateByMaskPixels(kind, transfer, renderMask)
                return
            }
            // Inner layer with DstIn: subsequent draws will multiply by the
            // existing layer's alpha, so the mask keeps only what it covers.
            // For a Luminosity mask (§11.6.5.2) the mask group composites over
            // an opaque BLACK backdrop and its LUMINANCE becomes the alpha at the
            // layer restore, mirroring the Skia backend's LUMA filter. The /TR
            // function then maps the alpha.
            val maskPaint = Paint().apply {
                blendMode = ComposeBlendMode.DstIn
                (if (maskTables) softMaskFilter(kind, transfer) else softMaskMatrix(kind, transfer))?.let { colorFilter = it }
            }
            composeCanvas.saveLayer(bounds, maskPaint)
            saves.addLast(Save.Layer)
            try {
                if (kind == SoftMask.Kind.Luminosity) {
                    // Unpainted mask pixels stay black -> luminance 0 -> fully
                    // masked out, per the spec's black-backdrop rule.
                    drawScope.drawRect(Color.Black, topLeft = bounds.topLeft, size = bounds.size)
                }
                renderMask(this)
            } finally {
                restoreThroughLayer()
            }
        } finally {
            groups.removeLastOrNull()
            restoreThroughLayer()
        }
    }

    /**
     * Gates the open layer by a soft mask whose [transfer] only a table gives: draws the mask
     * group into an image of the whole canvas, turns its pixels into mask values through the
     * table (see [KiteMaskTransfer.toMaskAlpha]), and keeps the layer only where that image is.
     * The whole canvas, because the mask outside its group is the value of the backdrop. A
     * large canvas draws the mask at a lower resolution and scales it up.
     */
    private fun gateByMaskPixels(kind: SoftMask.Kind, transfer: KiteMaskTransfer, renderMask: (KiteCanvas) -> Unit) {
        val w = drawScope.size.width.toDouble()
        val h = drawScope.size.height.toDouble()
        if (!(w >= 1.0 && h >= 1.0)) return
        val pixelSize = if (w * h <= MASK_MAX_PIXELS) 1.0 else sqrt(w * h / MASK_MAX_PIXELS)
        val width = kotlin.math.ceil(w / pixelSize).toInt()
        val height = kotlin.math.ceil(h / pixelSize).toInt()
        val group = ImageBitmap(width, height)
        val luminosity = kind == SoftMask.Kind.Luminosity
        var nested: ComposeCanvas? = null
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
            drawScope, drawScope.layoutDirection, androidx.compose.ui.graphics.Canvas(group), Size(width.toFloat(), height.toFloat()),
        ) {
            // Unpainted parts of a luminosity group show the black backdrop, whose luminosity is zero.
            if (luminosity) drawRect(Color.Black)
            scale(1f / pixelSize.toFloat(), pivot = Offset.Zero) {
                val canvas = ComposeCanvas(
                    this, textMeasurer, hairlineWidthPx, skipSystemFontText, magnification, twoCircleShader, maskTables, bitmaps,
                    inSceneDrawPass, hostLines = hostLines,
                )
                nested = canvas
                renderMask(canvas)
            }
        }
        if (nested?.usedSystemFontText == true) usedSystemFontText = true
        val argb = IntArray(width * height)
        group.readPixels(argb)
        transfer.toMaskAlpha(argb, luminosity)
        val rgba = ByteArray(argb.size * 4)
        for (i in argb.indices) rgba[4 * i + 3] = (argb[i] ushr 24).toByte()
        val mask = ImageDecoder.decodeRaw(rgba, width, height) ?: return
        drawScope.drawImage(
            image = mask,
            dstSize = IntSize(kotlin.math.ceil(width * pixelSize).toInt(), kotlin.math.ceil(height * pixelSize).toInt()),
            blendMode = ComposeBlendMode.DstIn,
            filterQuality = FilterQuality.Low,
        )
    }

    /** [box] under [ctm] on this canvas, rounded out to whole pixels and cut to the canvas; empty when they do not meet. */
    private fun maskBounds(box: KiteRectangle, ctm: KiteMatrix): Rect {
        val xs = doubleArrayOf(
            ctm.transformX(box.left, box.bottom), ctm.transformX(box.right, box.bottom),
            ctm.transformX(box.right, box.top), ctm.transformX(box.left, box.top),
        )
        val ys = doubleArrayOf(
            ctm.transformY(box.left, box.bottom), ctm.transformY(box.right, box.bottom),
            ctm.transformY(box.right, box.top), ctm.transformY(box.left, box.top),
        )
        if (xs.any { !it.isFinite() } || ys.any { !it.isFinite() }) return infiniteRect()
        val canvas = infiniteRect()
        val left = kotlin.math.floor(xs.min()).toFloat().coerceAtLeast(canvas.left)
        val top = kotlin.math.floor(ys.min()).toFloat().coerceAtLeast(canvas.top)
        val right = kotlin.math.ceil(xs.max()).toFloat().coerceAtMost(canvas.right)
        val bottom = kotlin.math.ceil(ys.max()).toFloat().coerceAtMost(canvas.bottom)
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else Rect.Zero
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
        val canvas = drawScope.drawContext.canvas
        canvas.save()
        canvas.clipPath(composePath)
        saves.addLast(Save.Clip)
        val outer = clipBounds.lastOrNull() ?: wholeBounds()
        val inner = composePath.getBounds()
        clipBounds.addLast(Rect(maxOf(inner.left, outer.left), maxOf(inner.top, outer.top), minOf(inner.right, outer.right), minOf(inner.bottom, outer.bottom)))
    }

    override fun popClip() {
        // The renderer pops a clip before it ends the group the clip was pushed in. A pop that
        // finds a layer on top would restore that layer instead, so it is ignored.
        if (saves.lastOrNull() != Save.Clip) return
        saves.removeLast()
        clipBounds.removeLastOrNull()
        drawScope.drawContext.canvas.restore()
    }

    /**
     * The bounds of each pushed clip, in the units of [drawScope], since a Compose canvas does not
     * tell the bounds of its clip. A raster step cuts its box to the last.
     */
    private val clipBounds = ArrayDeque<Rect>()

    /** What a raster step's box is cut to without a clip: the draw scope, or the box of the render it paints in. */
    private var stepBounds: Rect? = null

    private fun wholeBounds(): Rect = stepBounds ?: Rect(0f, 0f, drawScope.size.width, drawScope.size.height)

    /**
     * The raster step (#209, #308): its box is [region] under [ctm], in the units of the draw
     * scope, cut to the clip and rounded out to whole units. It works at [magnification] pixels a
     * unit, so a step on a zoomed Vectorized page keeps the detail on screen, and past
     * [STEP_MAX_PIXELS] at a lower resolution. Only a rasterizer's canvas, which knows the bitmap
     * it draws into, reads a backdrop, and only outside every layer.
     */
    override fun rasterStep(region: KiteRectangle, ctm: KiteMatrix, step: KiteRasterStep): Boolean {
        val b = region.normalized()
        val xs = doubleArrayOf(ctm.transformX(b.left, b.bottom), ctm.transformX(b.right, b.bottom), ctm.transformX(b.left, b.top), ctm.transformX(b.right, b.top))
        val ys = doubleArrayOf(ctm.transformY(b.left, b.bottom), ctm.transformY(b.right, b.bottom), ctm.transformY(b.left, b.top), ctm.transformY(b.right, b.top))
        if (!xs.all { it.isFinite() } || !ys.all { it.isFinite() }) return false
        val clip = clipBounds.lastOrNull() ?: wholeBounds()
        val left = maxOf(kotlin.math.floor(xs.min()), kotlin.math.floor(clip.left.toDouble()))
        val top = maxOf(kotlin.math.floor(ys.min()), kotlin.math.floor(clip.top.toDouble()))
        val right = minOf(kotlin.math.ceil(xs.max()), kotlin.math.ceil(clip.right.toDouble()))
        val bottom = minOf(kotlin.math.ceil(ys.max()), kotlin.math.ceil(clip.bottom.toDouble()))
        if (right <= left || bottom <= top) return true
        val units = (right - left) * (bottom - top)
        val wanted = magnification.toDouble().coerceAtLeast(1.0)
        if (units * wanted * wanted > STEP_MAX_PIXELS * 16) return false
        val scale = if (units * wanted * wanted > STEP_MAX_PIXELS) sqrt(STEP_MAX_PIXELS / units) else wanted
        return step.run(ComposeRasterScope(left.toInt(), top.toInt(), (right - left).toInt(), (bottom - top).toInt(), scale, ctm))
    }

    /** One raster step over the box of [boxWidth] by [boxHeight] units from ([x], [y]), at [scale] pixels a unit. */
    private inner class ComposeRasterScope(
        private val x: Int, private val y: Int, private val boxWidth: Int, private val boxHeight: Int,
        private val scale: Double, ctm: KiteMatrix,
    ) : KiteRasterScope {
        private val scope = drawScope
        private val layered = Save.Layer in saves
        override val width = maxOf(1, kotlin.math.ceil(boxWidth * scale).toInt())
        override val height = maxOf(1, kotlin.math.ceil(boxHeight * scale).toInt())
        override val toPixels: KiteMatrix = KiteMatrix.scaling(scale, scale)
            .concat(KiteMatrix.translation(-x.toDouble(), -y.toDouble())).concat(ctm)

        override fun backdrop(): KiteRaster? {
            val bitmap = target ?: return null
            if (layered || scale != 1.0 || x < 0 || y < 0 || x + width > bitmap.width || y + height > bitmap.height) return null
            val argb = IntArray(width * height)
            bitmap.readPixels(argb, x, y, width, height)
            return KiteRaster(width, height, argb)
        }

        override fun render(initial: KiteRaster?, content: () -> Unit): KiteRaster {
            val bitmap = ImageBitmap(width, height)
            CanvasDrawScope().draw(scope, scope.layoutDirection, androidx.compose.ui.graphics.Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
                if (initial != null) {
                    require(initial.width == width && initial.height == height) { "the initial raster has another size" }
                    ImageDecoder.decodeRaw(rgbaOf(initial.pixels), width, height)?.let { drawImage(it, blendMode = ComposeBlendMode.Src) }
                }
                val savedScope = drawScope
                val savedSaves = saves.toList()
                val savedGroups = groups.toList()
                val savedClips = clipBounds.toList()
                val savedBounds = stepBounds
                withTransform({
                    scale(scale.toFloat(), scale.toFloat(), pivot = Offset.Zero)
                    translate(-x.toFloat(), -y.toFloat())
                }) {
                    try {
                        drawScope = this
                        saves.clear()
                        groups.clear()
                        clipBounds.clear()
                        stepBounds = Rect(x.toFloat(), y.toFloat(), (x + boxWidth).toFloat(), (y + boxHeight).toFloat())
                        content()
                    } finally {
                        // Clips and layers the content left open end on the bitmap.
                        restoreAll()
                        drawScope = savedScope
                        saves.clear()
                        saves.addAll(savedSaves)
                        groups.clear()
                        groups.addAll(savedGroups)
                        clipBounds.clear()
                        clipBounds.addAll(savedClips)
                        stepBounds = savedBounds
                    }
                }
            }
            val argb = IntArray(width * height)
            bitmap.readPixels(argb)
            return KiteRaster(width, height, argb)
        }

        override fun draw(raster: KiteRaster, alpha: Double, blendMode: KiteBlendMode) {
            require(raster.width == width && raster.height == height) { "the raster has another size" }
            val image = ImageDecoder.decodeRaw(rgbaOf(raster.pixels), width, height) ?: return
            scope.drawImage(
                image = image, dstOffset = androidx.compose.ui.unit.IntOffset(x, y), dstSize = IntSize(boxWidth, boxHeight),
                alpha = alpha.toFloat().coerceIn(0f, 1f), blendMode = blendMode.toCompose(),
                filterQuality = if (scale == 1.0) FilterQuality.None else FilterQuality.Low,
            )
        }
    }

    /** Straight 0xAARRGGBB pixels as straight RGBA bytes. */
    private fun rgbaOf(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 4)
        for (i in argb.indices) {
            val p = argb[i]
            out[4 * i] = (p ushr 16).toByte()
            out[4 * i + 1] = (p ushr 8).toByte()
            out[4 * i + 2] = p.toByte()
            out[4 * i + 3] = (p ushr 24).toByte()
        }
        return out
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
        saves.addLast(Save.Layer)
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
        if (Save.Layer !in saves) return
        restoreThroughLayer()
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

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

    /**
     * One path that fills, strokes, glyphs and shadings reuse, since a draw copies the path it
     * paints. A clip keeps its own, because the clip stack holds it (#130).
     */
    private val scratchPath = Path()

    /** The paths of the glyph outlines this canvas drew, in glyph space. */
    private val glyphPaths = HashMap<GlyphOutline, Path>()

    /** How many glyph outlines this canvas converted to paths. For tests. */
    internal val convertedGlyphs: Int get() = glyphPaths.size

    /**
     * An outline by identity: a font hands out one object per glyph, and comparing the segments
     * of two outlines costs about as much as converting one. The hash reads a few segments only.
     */
    private class GlyphOutline(val path: KitePath) {
        override fun equals(other: Any?): Boolean = other is GlyphOutline && other.path === path
        override fun hashCode(): Int = path.segments.size * 31 + (path.segments.firstOrNull()?.hashCode() ?: 0)
    }


    private fun RgbColor.toCompose(): Color = Color(r.toFloat(), g.toFloat(), b.toFloat(), 1f)

    /** The Compose blend mode that paints this PDF blend mode here. See [composeBlendMode]. */
    private fun KiteBlendMode.toCompose(): ComposeBlendMode = composeBlendMode(this) { it.isSupported() }

    private fun FontSpec.toComposeFamily(): FontFamily = when (family) {
        KiteFontFamily.Serif -> FontFamily.Serif
        KiteFontFamily.Monospace -> FontFamily.Monospace
        KiteFontFamily.SansSerif -> FontFamily.SansSerif
    }

    private fun FontSpec.toComposeWeight(): FontWeight =
        if (bold) FontWeight.Bold else FontWeight.Normal

    private fun FontSpec.toComposeStyle(): FontStyle =
        if (italic) FontStyle.Italic else FontStyle.Normal
}

/**
 * The Compose blend mode that paints [mode] on a platform that paints the modes [supported]
 * accepts. Android before API 29 paints only the Porter-Duff modes and draws the others as
 * Normal (#412). There Multiply becomes Modulate, which gives the same colour for an opaque paint
 * over an opaque page, so a highlight keeps its text visible. The other modes stay, and the first
 * one met is logged.
 */
internal fun composeBlendMode(mode: KiteBlendMode, supported: (ComposeBlendMode) -> Boolean): ComposeBlendMode {
    val wanted = mode.toComposeBlendMode()
    if (supported(wanted)) return wanted
    if (mode == KiteBlendMode.Multiply && supported(ComposeBlendMode.Modulate)) return ComposeBlendMode.Modulate
    if (!unsupportedBlendLogged) {
        unsupportedBlendLogged = true
        kiteWarn { "render: this platform cannot paint the $mode blend mode, so it paints as Normal" }
    }
    return wanted
}

@kotlin.concurrent.Volatile
private var unsupportedBlendLogged = false

/** A PDF blend mode's Compose twin. All 16 PDF blend modes have one. */
private fun KiteBlendMode.toComposeBlendMode(): ComposeBlendMode = when (this) {
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

/** Glyphs of a piece that a text engine draws as one string, [offset] advance units from the piece's start. */
internal class DrawOrderPart(val glyphs: List<TextGlyph>, val text: String, val offset: Double)

/**
 * [piece], which is in the order it draws, left to right, as the strings a text engine draws in
 * that order (#486). A text engine runs the bidi algorithm, so it would reverse right-to-left
 * letters the layout already reversed. A piece without them stays one part, as it was. Otherwise
 * it is cut where the strong direction changes, and a right-to-left part goes back to logical
 * order inside a right-to-left override, so the engine joins Arabic letters with their logical
 * neighbours and lays them out right to left, which is the order they came in. A left-to-right
 * part beside it takes a left-to-right override, so that its digits and punctuation stay put.
 */
internal fun drawOrderParts(piece: List<TextGlyph>): List<DrawOrderPart> {
    val directions = IntArray(piece.size) { strongDirection(piece[it].text) }
    if (directions.none { it == RIGHT_TO_LEFT }) {
        return listOf(DrawOrderPart(piece, piece.joinToString("") { it.text }, 0.0))
    }
    val parts = ArrayList<DrawOrderPart>()
    var start = 0
    var offset = 0.0
    var direction = directions.first { it != NEUTRAL }
    fun close(end: Int) {
        val glyphs = piece.subList(start, end)
        val text = if (direction == RIGHT_TO_LEFT) {
            glyphs.asReversed().joinToString("", prefix = "\u202E", postfix = "\u202C") { it.text }
        } else {
            glyphs.joinToString("", prefix = "\u202D", postfix = "\u202C") { it.text }
        }
        parts += DrawOrderPart(glyphs, text, offset)
        offset += glyphs.sumOf { it.advanceWidth }
        start = end
    }
    for (i in piece.indices) {
        val d = directions[i]
        if (d == NEUTRAL || d == direction) continue
        close(i)
        direction = d
    }
    close(piece.size)
    return parts
}

private const val NEUTRAL = -1
private const val LEFT_TO_RIGHT = 0
private const val RIGHT_TO_LEFT = 1

/** The direction of the first strong character of [text], or [NEUTRAL] when it has none. */
private fun strongDirection(text: String): Int {
    var i = 0
    while (i < text.length) {
        val high = text[i]
        val pair = high.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
        val cp = if (pair) 0x10000 + ((high.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) else high.code
        when (Bidi.classify(cp)) {
            Bidi.L -> return LEFT_TO_RIGHT
            Bidi.R, Bidi.AL -> return RIGHT_TO_LEFT
        }
        i += if (pair) 2 else 1
    }
    return NEUTRAL
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

/** The largest host-text size, in pixels to the em, that a font is made at: a page is never that large. */
private const val MAX_HOST_TEXT_PX = 100_000.0

/** The pixels a run of glyphs may add its coverage into at most: about four megapixels. */
private const val MAX_RUN_PIXELS = 4L * 1024 * 1024

/** [src] under [ctm], rewound into [into] or in a new path. */
internal fun toComposePath(src: KitePath, ctm: KiteMatrix, into: Path? = null): Path {
    val out = into?.apply { rewind() } ?: Path()
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
