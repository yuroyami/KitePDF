package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.render.paintComplexShading
import io.github.yuroyami.kitepdf.Rectangle
import io.github.yuroyami.kitepdf.font.FontFamily
import io.github.yuroyami.kitepdf.font.FontSpec
import io.github.yuroyami.kitepdf.font.TextGlyph
import io.github.yuroyami.kitepdf.render.BlendMode as PdfBlendMode
import io.github.yuroyami.kitepdf.render.ImageXObject
import io.github.yuroyami.kitepdf.render.Matrix as PdfMatrix
import io.github.yuroyami.kitepdf.render.KiteCanvas
import io.github.yuroyami.kitepdf.render.KitePath
import io.github.yuroyami.kitepdf.render.KiteShading
import io.github.yuroyami.kitepdf.render.RgbColor
import io.github.yuroyami.kitepdf.render.SoftMask
import io.github.yuroyami.kitepdf.render.sampleStops
import io.github.yuroyami.kitepdf.render.toRgbaBytes
import org.jetbrains.skia.BlendMode as SkiaBlendMode
import org.jetbrains.skia.Canvas as SkCanvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PathEffect
import org.jetbrains.skia.Path as SkPath
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Gradient
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.FilterTileMode

/**
 * [KiteCanvas] backed by a raw [org.jetbrains.skia.Canvas] (Skiko).
 *
 * Same rendering engine Compose Multiplatform rides on for JVM Desktop and
 * iOS — minus the Compose runtime. Pure-Skia means this adapter is the
 * right choice for:
 *
 *  - **Server-side rasterization** — PDF → PNG, headless thumbnail
 *    generation, CI pipelines.
 *  - **CLI tools** — anything that wants pixels without dragging in
 *    androidx.compose.runtime.
 *  - **Smaller dependency footprint** — Skiko alone is significantly less
 *    than Compose Multiplatform.
 *
 * Pair with [PdfPageRasterizer] for the common "give me a `ByteArray` of a
 * page's PNG" use case.
 */
public class SkiaCanvas(private val canvas: SkCanvas) : KiteCanvas {

    /** Count of open transparency groups + soft-mask layers — for endPage cleanup. */
    private var openLayers = 0

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: PdfMatrix) {
        // The caller is responsible for sizing the surface; we don't clear.
        openLayers = 0
    }

    override fun endPage() {
        while (openLayers > 0) {
            canvas.restore()
            openLayers--
        }
    }

    override fun fillPath(
        path: KitePath, ctm: PdfMatrix, color: RgbColor, evenOdd: Boolean,
        alpha: Double, blendMode: PdfBlendMode,
    ) {
        val sk = toSkPath(path, ctm).apply {
            fillMode = if (evenOdd) PathFillMode.EVEN_ODD else PathFillMode.WINDING
        }
        val paint = Paint().apply {
            this.color = color.toArgb(alpha)
            this.mode = PaintMode.FILL
            this.isAntiAlias = true
            this.blendMode = blendMode.toSkia()
        }
        canvas.drawPath(sk, paint)
    }

    override fun strokePath(
        path: KitePath, ctm: PdfMatrix, color: RgbColor, lineWidth: Double,
        alpha: Double, blendMode: PdfBlendMode,
        dashArray: List<Double>?, dashPhase: Double,
        lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) {
        val sk = toSkPath(path, ctm)
        val avgScale = (ctm.scaleX() + ctm.scaleY()) * 0.5
        val paint = Paint().apply {
            this.color = color.toArgb(alpha)
            this.mode = PaintMode.STROKE
            // Hairline minimum: sub-pixel strokes must still render as a visible ~1px
            // line (ISO 32000-1 §8.4.3.2; MuPDF clamps to the anti-alias unit, draw-device.c:800).
            this.strokeWidth = (lineWidth * avgScale).toFloat().coerceAtLeast(1.0f)
            this.isAntiAlias = true
            this.blendMode = blendMode.toSkia()
            this.strokeCap = when (lineCap) {
                1 -> org.jetbrains.skia.PaintStrokeCap.ROUND
                2 -> org.jetbrains.skia.PaintStrokeCap.SQUARE
                else -> org.jetbrains.skia.PaintStrokeCap.BUTT
            }
            this.strokeJoin = when (lineJoin) {
                1 -> org.jetbrains.skia.PaintStrokeJoin.ROUND
                2 -> org.jetbrains.skia.PaintStrokeJoin.BEVEL
                else -> org.jetbrains.skia.PaintStrokeJoin.MITER
            }
            this.strokeMiter = miterLimit.toFloat().coerceAtLeast(1f)
            // Dashed strokes: dash lengths are in user units, so scale them the
            // same way as the line width. Skia needs an even-length, positive
            // interval array.
            dashArray?.let { da ->
                val scaled = da.map { (it * avgScale).toFloat().coerceAtLeast(0f) }
                val intervals = if (scaled.size % 2 == 0) scaled else scaled + scaled
                if (intervals.isNotEmpty() && intervals.sum() > 0f) {
                    this.pathEffect = PathEffect.makeDash(intervals.toFloatArray(), (dashPhase * avgScale).toFloat())
                }
            }
        }
        canvas.drawPath(sk, paint)
    }

    override fun drawGlyphs(
        glyphs: List<TextGlyph>,
        fontSize: Double,
        unitsPerEm: Int,
        hasOutlines: Boolean,
        fontSpec: FontSpec,
        textToDevice: PdfMatrix,
        color: RgbColor,
        alpha: Double,
        blendMode: PdfBlendMode,
    ) {
        if (glyphs.isEmpty()) return
        if (!hasOutlines) {
            drawTextViaSystemFont(glyphs, fontSize, fontSpec, textToDevice, color, alpha, blendMode)
            return
        }

        val unitScale = fontSize / unitsPerEm  // glyph outlines: font units → text space
        val advanceScale = fontSize / 1000.0   // advances are 1/1000 em, not font units
        val argb = color.toArgb(alpha)
        val paint = Paint().apply {
            this.color = argb
            mode = PaintMode.FILL
            isAntiAlias = true
            this.blendMode = blendMode.toSkia()
        }
        var penX = 0.0
        for (glyph in glyphs) {
            val outline = glyph.outline
            if (outline != null && !outline.isEmpty()) {
                val glyphMatrix = textToDevice
                    .let { tm -> PdfMatrix.translation(penX + glyph.xOffset * unitScale, glyph.yOffset * unitScale).concat(tm) }
                    .let { tm -> PdfMatrix(unitScale, 0.0, 0.0, unitScale, 0.0, 0.0).concat(tm) }
                val sk = toSkPath(outline, glyphMatrix).apply { fillMode = PathFillMode.WINDING }
                canvas.drawPath(sk, paint)
            }
            penX += glyph.advanceWidth * advanceScale
        }
    }

    /**
     * System-font fallback for fonts without embedded outlines (Standard-14
     * Helvetica/Times/Courier etc). Decode to text, pick a host typeface by
     * family, and draw the run with the text matrix's scale/rotation applied
     * (baseline at the matrix origin). Mirrors ComposeCanvas.drawTextViaSystemFont.
     */
    private fun drawTextViaSystemFont(
        glyphs: List<TextGlyph>, fontSize: Double, fontSpec: FontSpec,
        textMatrix: PdfMatrix, color: RgbColor, alpha: Double, blendMode: PdfBlendMode,
    ) {
        val text = glyphs.joinToString("") { it.text }
        if (text.isEmpty()) return
        val sx = kotlin.math.sqrt(textMatrix.a * textMatrix.a + textMatrix.b * textMatrix.b)
        val sy = kotlin.math.sqrt(textMatrix.c * textMatrix.c + textMatrix.d * textMatrix.d)
        val renderedSize = (fontSize * sy).toFloat()
        if (renderedSize <= 0f) return
        val rotationDeg = (kotlin.math.atan2(textMatrix.b, textMatrix.a) * 180.0 / kotlin.math.PI).toFloat()

        val argb = color.toArgb(alpha)
        val paint = Paint().apply {
            this.color = argb
            mode = PaintMode.FILL
            isAntiAlias = true
            this.blendMode = blendMode.toSkia()
        }
        val skFont = Font(systemTypeface(fontSpec), renderedSize)
        canvas.save()
        try {
            canvas.translate(textMatrix.e.toFloat(), textMatrix.f.toFloat())
            if (rotationDeg != 0f) canvas.rotate(rotationDeg)
            if (sx != sy && sy != 0.0) canvas.scale((sx / sy).toFloat(), 1f)
            canvas.drawString(text, 0f, 0f, skFont, paint)
        } finally {
            canvas.restore()
        }
    }

    private fun systemTypeface(spec: FontSpec): Typeface? {
        val style = when {
            spec.bold && spec.italic -> FontStyle.BOLD_ITALIC
            spec.bold -> FontStyle.BOLD
            spec.italic -> FontStyle.ITALIC
            else -> FontStyle.NORMAL
        }
        val family = when (spec.family) {
            FontFamily.Serif -> "Times New Roman"
            FontFamily.Monospace -> "Courier New"
            FontFamily.SansSerif -> "Helvetica"
        }
        return try {
            FontMgr.default.matchFamilyStyle(family, style) ?: FontMgr.default.matchFamilyStyle(null, style)
        } catch (t: Throwable) {
            null
        }
    }

    override fun fillShading(
        shading: KiteShading,
        ctm: PdfMatrix,
        clipPath: KitePath?,
        alpha: Double,
        blendMode: PdfBlendMode,
    ) {
        if (paintComplexShading(shading, ctm, clipPath, alpha, blendMode)) return
        val stops = shading.sampleStops(32) ?: return

        // PDF /Extend [start end] controls whether the shading keeps painting
        // past t<0 (start) and t>1 (end) with the terminal colours. Skia's
        // gradient tile mode is a single flag covering both ends:
        //   CLAMP → both ends extend with the terminal colour (t outside [0,1]).
        //   DECAL → nothing is painted outside [0,1] (transparent).
        // We can express both-extend and neither-extend exactly. A one-sided
        // extend can't be captured by a single tile mode, so we approximate:
        // give the extending side its colour by adding a transparent stop just
        // past the non-extending end (so that end fades out) and keep CLAMP —
        // CLAMP then holds the extending end's colour and the injected
        // transparent stop suppresses the other end.
        val (extendStart, extendEnd) = when (shading) {
            is KiteShading.Axial -> shading.extendStart to shading.extendEnd
            is KiteShading.Radial -> shading.extendStart to shading.extendEnd
            is KiteShading.Unsupported -> return
            else -> return // T-40 types already handled by paintComplexShading
        }

        val offsets = ArrayList<Float>(stops.offsets.size + 2)
        val colors = ArrayList<Color4f>(stops.colors.size + 2)
        val tileMode: FilterTileMode = if (!extendStart && !extendEnd) {
            FilterTileMode.DECAL
        } else {
            FilterTileMode.CLAMP
        }
        // For a one-sided extend under CLAMP, inject a fully-transparent stop
        // just outside the non-extending end so CLAMP holds transparency there
        // instead of the terminal colour. Both-extend / both-decal need no pad.
        if (tileMode == FilterTileMode.CLAMP && !extendStart && extendEnd) {
            offsets += -0.0001f
            colors += Color4f(0f, 0f, 0f, 0f)
        }
        for (i in stops.offsets.indices) {
            offsets += stops.offsets[i].toFloat()
            colors += Color4f(stops.colors[i].toArgb(alpha))
        }
        if (tileMode == FilterTileMode.CLAMP && extendStart && !extendEnd) {
            offsets += 1.0001f
            colors += Color4f(0f, 0f, 0f, 0f)
        }

        // skiko 0.148 moved gradient colours/positions into Gradient(Gradient.Colors(...)),
        // taking Color4f[] (+ sRGB colour space by default).
        val gradient = Gradient(
            Gradient.Colors(
                colors.toTypedArray(),
                offsets.toFloatArray(),
                tileMode,
            ),
        )

        val shader: Shader = when (shading) {
            is KiteShading.Axial -> {
                val (x0, y0) = ctm.transformPoint(shading.coords[0], shading.coords[1])
                val (x1, y1) = ctm.transformPoint(shading.coords[2], shading.coords[3])
                Shader.makeLinearGradient(
                    x0.toFloat(), y0.toFloat(),
                    x1.toFloat(), y1.toFloat(),
                    gradient,
                )
            }
            is KiteShading.Radial -> {
                // True PDF two-circle radial via a two-point conical gradient.
                val sc = kotlin.math.sqrt(ctm.a * ctm.a + ctm.b * ctm.b)
                val (x0, y0) = ctm.transformPoint(shading.coords[0], shading.coords[1])
                val r0 = (shading.coords[2] * sc).toFloat().coerceAtLeast(0f)
                val (x1, y1) = ctm.transformPoint(shading.coords[3], shading.coords[4])
                val r1 = (shading.coords[5] * sc).toFloat().coerceAtLeast(0.1f)
                Shader.makeTwoPointConicalGradient(
                    x0.toFloat(), y0.toFloat(), r0,
                    x1.toFloat(), y1.toFloat(), r1,
                    gradient,
                )
            }
            is KiteShading.Unsupported -> return
            else -> return // T-40 types already handled by paintComplexShading
        }

        val paint = Paint().apply {
            this.shader = shader
            this.mode = PaintMode.FILL
            this.isAntiAlias = true
            this.blendMode = blendMode.toSkia()
        }
        if (clipPath != null) {
            val sk = toSkPath(clipPath, ctm).apply { fillMode = PathFillMode.WINDING }
            canvas.drawPath(sk, paint)
        } else {
            // `sh` operator over the whole device area — paint a huge rect.
            canvas.drawPaint(paint)
        }
    }

    override fun pushClip(path: KitePath, ctm: PdfMatrix, evenOdd: Boolean) {
        canvas.save()
        openLayers++
        val sk = toSkPath(path, ctm).apply {
            fillMode = if (evenOdd) PathFillMode.EVEN_ODD else PathFillMode.WINDING
        }
        canvas.clipPath(sk, antiAlias = true)
    }

    override fun popClip() {
        if (openLayers > 0) {
            canvas.restore()
            openLayers--
        }
    }

    override fun drawImage(image: ImageXObject, ctm: PdfMatrix, alpha: Double) {
        // Skia decodes JPEG natively + JP2/JPEG-2000 where the platform shim
        // supports it. Other kinds fall back to a placeholder rectangle.
        val sk = when (image.kind) {
            ImageXObject.Kind.JPEG, ImageXObject.Kind.JPEG2000, ImageXObject.Kind.JBIG2 -> try {
                Image.makeFromEncoded(image.encodedBytes)
            } catch (t: Throwable) {
                null
            }
            ImageXObject.Kind.RAW -> try {
                image.toRgbaBytes()?.let {
                    // toRgbaBytes() emits straight (non-premultiplied) R,G,B,A
                    // per pixel, matching RGBA_8888. UNPREMUL honours the alpha
                    // channel (SMask alpha, ImageMask stencil transparency);
                    // OPAQUE would discard it, rendering masks as solid black.
                    Image.makeRaster(ImageInfo(image.width, image.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), it, image.width * 4)
                }
            } catch (t: Throwable) {
                null
            }
            else -> null
        }
        if (sk == null) {
            drawPlaceholder(ctm)
            return
        }
        // PDF image space is the unit square (0,0)-(1,1) before the CTM; the
        // CTM maps that square to device coords. Below we lay the bitmap into
        // that unit square (upright) and let the concatenated CTM place it.
        canvas.save()
        openLayers++
        val matrix = pdfMatrixToSkia(ctm)
        canvas.concat(matrix)
        // Map the bitmap onto the PDF image unit square [0,1]². The bitmap's
        // row 0 is its top edge (v=1), last row is v=0. So translate up by 1
        // then flip Y (negative Y scale) to land the image upright inside the
        // unit square — matching AwtCanvas' documented mapping. The earlier
        // translate(0,-1)+positive-Y scale both mis-placed and flipped it.
        val paint = Paint().apply { this.alpha = alpha.toFloat().coerceIn(0f, 1f).let { (it * 255).toInt() } }
        canvas.save()
        canvas.translate(0f, 1f)
        canvas.scale(1f / sk.width, -1f / sk.height)
        canvas.drawImage(sk, 0f, 0f, paint)
        canvas.restore()
        canvas.restore()
        openLayers--
    }

    private fun drawPlaceholder(ctm: PdfMatrix) {
        val sk = PathBuilder().apply {
            moveTo(0f, 0f); lineTo(1f, 0f); lineTo(1f, 1f); lineTo(0f, 1f); closePath()
        }.snapshot()
        canvas.save()
        canvas.concat(pdfMatrixToSkia(ctm))
        canvas.drawPath(sk, Paint().apply { color = 0xFFE0E0E0.toInt() })
        canvas.drawPath(sk, Paint().apply {
            color = 0xFF888888.toInt(); mode = PaintMode.STROKE; strokeWidth = 0.01f
        })
        canvas.restore()
    }

    override fun beginTransparencyGroup(
        bbox: Rectangle, ctm: PdfMatrix,
        isolated: Boolean, knockout: Boolean,
        alpha: Double, blendMode: PdfBlendMode,
    ) {
        val paint = Paint().apply {
            this.alpha = (alpha.coerceIn(0.0, 1.0) * 255).toInt()
            this.blendMode = blendMode.toSkia()
        }
        canvas.saveLayer(null, paint)
        openLayers++
    }

    override fun endTransparencyGroup() {
        if (openLayers > 0) {
            canvas.restore()
            openLayers--
        }
    }

    override fun applySoftMask(
        kind: SoftMask.Kind,
        maskBBox: Rectangle, maskCtm: PdfMatrix,
        render: () -> Unit,
        renderMask: (KiteCanvas) -> Unit,
    ) {
        // Outer layer captures the content. Inner layer paints the mask
        // group on top with DstIn so the mask's alpha clips the content.
        canvas.saveLayer(null, Paint())
        openLayers++
        try {
            render()

            // The mask layer resolves to alpha via DstIn. For an Alpha mask we
            // use the mask group's own alpha directly. For a Luminosity mask,
            // the spec (ISO 32000-1 §11.6.5.2) composites the mask group over a
            // fully-opaque BLACK backdrop and derives alpha from the result's
            // luminance — so unpainted areas (luminance 0) mask fully out. We
            // realise that with the LUMA colour filter, which maps each pixel's
            // luminance into its alpha, applied as the layer's restore paint.
            val maskPaint = Paint().apply {
                blendMode = SkiaBlendMode.DST_IN
                if (kind == SoftMask.Kind.Luminosity) {
                    colorFilter = org.jetbrains.skia.ColorFilter.luma
                }
            }
            canvas.saveLayer(null, maskPaint)
            openLayers++
            try {
                // Opaque black backdrop for the luminosity group: unpainted
                // pixels stay luminance 0 → alpha 0. (Harmless for Alpha masks,
                // where LUMA isn't applied; but only paint it for Luminosity to
                // avoid tinting an alpha mask's own colours.)
                if (kind == SoftMask.Kind.Luminosity) {
                    canvas.clear(Color.BLACK)
                }
                renderMask(this)
            } finally {
                canvas.restore()
                openLayers--
            }
        } finally {
            canvas.restore()
            openLayers--
        }
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private fun toSkPath(src: KitePath, ctm: PdfMatrix): SkPath {
        // skiko 0.148: Path is immutable; build via PathBuilder then snapshot().
        val b = PathBuilder()
        for (seg in src.segments) {
            when (seg) {
                is KitePath.Segment.MoveTo -> {
                    val (x, y) = ctm.transformPoint(seg.x, seg.y)
                    b.moveTo(x.toFloat(), y.toFloat())
                }
                is KitePath.Segment.LineTo -> {
                    val (x, y) = ctm.transformPoint(seg.x, seg.y)
                    b.lineTo(x.toFloat(), y.toFloat())
                }
                is KitePath.Segment.CurveTo -> {
                    val (x1, y1) = ctm.transformPoint(seg.x1, seg.y1)
                    val (x2, y2) = ctm.transformPoint(seg.x2, seg.y2)
                    val (x3, y3) = ctm.transformPoint(seg.x3, seg.y3)
                    b.cubicTo(
                        x1.toFloat(), y1.toFloat(),
                        x2.toFloat(), y2.toFloat(),
                        x3.toFloat(), y3.toFloat(),
                    )
                }
                is KitePath.Segment.QuadTo -> {
                    val (x1, y1) = ctm.transformPoint(seg.x1, seg.y1)
                    val (x2, y2) = ctm.transformPoint(seg.x2, seg.y2)
                    b.quadTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
                }
                KitePath.Segment.Close -> b.closePath()
            }
        }
        return b.snapshot()
    }

    private fun pdfMatrixToSkia(m: PdfMatrix): Matrix33 = Matrix33(
        m.a.toFloat(), m.c.toFloat(), m.e.toFloat(),
        m.b.toFloat(), m.d.toFloat(), m.f.toFloat(),
        0f, 0f, 1f,
    )

    private fun RgbColor.toArgb(alpha: Double): Int {
        val a = (alpha.coerceIn(0.0, 1.0) * 255).toInt()
        val rr = (r.coerceIn(0.0, 1.0) * 255).toInt()
        val gg = (g.coerceIn(0.0, 1.0) * 255).toInt()
        val bb = (b.coerceIn(0.0, 1.0) * 255).toInt()
        return Color.makeARGB(a, rr, gg, bb)
    }

    /**
     * Map a PDF blend mode to its Skia equivalent. The PDF spec's 16 blend
     * modes have a 1:1 Skia counterpart, named the same way (with Skia's
     * SCREAMING_SNAKE_CASE convention).
     */
    private fun PdfBlendMode.toSkia(): SkiaBlendMode = when (this) {
        PdfBlendMode.Normal -> SkiaBlendMode.SRC_OVER
        PdfBlendMode.Multiply -> SkiaBlendMode.MULTIPLY
        PdfBlendMode.Screen -> SkiaBlendMode.SCREEN
        PdfBlendMode.Overlay -> SkiaBlendMode.OVERLAY
        PdfBlendMode.Darken -> SkiaBlendMode.DARKEN
        PdfBlendMode.Lighten -> SkiaBlendMode.LIGHTEN
        PdfBlendMode.ColorDodge -> SkiaBlendMode.COLOR_DODGE
        PdfBlendMode.ColorBurn -> SkiaBlendMode.COLOR_BURN
        PdfBlendMode.HardLight -> SkiaBlendMode.HARD_LIGHT
        PdfBlendMode.SoftLight -> SkiaBlendMode.SOFT_LIGHT
        PdfBlendMode.Difference -> SkiaBlendMode.DIFFERENCE
        PdfBlendMode.Exclusion -> SkiaBlendMode.EXCLUSION
        PdfBlendMode.Hue -> SkiaBlendMode.HUE
        PdfBlendMode.Saturation -> SkiaBlendMode.SATURATION
        PdfBlendMode.Color -> SkiaBlendMode.COLOR
        PdfBlendMode.Luminosity -> SkiaBlendMode.LUMINOSITY
    }
}
