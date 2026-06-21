package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.Rectangle
import io.github.yuroyami.kitepdf.font.PdfFont
import io.github.yuroyami.kitepdf.render.BlendMode as PdfBlendMode
import io.github.yuroyami.kitepdf.render.ImageXObject
import io.github.yuroyami.kitepdf.render.Matrix as PdfMatrix
import io.github.yuroyami.kitepdf.render.PdfCanvas
import io.github.yuroyami.kitepdf.render.PdfPath
import io.github.yuroyami.kitepdf.render.PdfShading
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
 * [PdfCanvas] backed by a raw [org.jetbrains.skia.Canvas] (Skiko).
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
class SkiaCanvas(private val canvas: SkCanvas) : PdfCanvas {

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
        path: PdfPath, ctm: PdfMatrix, color: RgbColor, evenOdd: Boolean,
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
        path: PdfPath, ctm: PdfMatrix, color: RgbColor, lineWidth: Double,
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

    override fun drawText(
        bytes: ByteArray,
        font: PdfFont,
        fontSize: Double,
        textMatrix: PdfMatrix,
        fillColor: RgbColor,
        alpha: Double,
        blendMode: PdfBlendMode,
    ) {
        if (bytes.isEmpty()) return
        if (!font.hasEmbeddedOutlines) {
            drawTextViaSystemFont(bytes, font, fontSize, textMatrix, fillColor, alpha, blendMode)
            return
        }

        val upm = font.unitsPerEm ?: 1000
        val unitScale = fontSize / upm
        val paint = Paint().apply {
            color = fillColor.toArgb(alpha)
            mode = PaintMode.FILL
            isAntiAlias = true
            this.blendMode = blendMode.toSkia()
        }
        var penX = 0.0
        for (glyph in font.layoutBytes(bytes)) {
            val outline = glyph.outline
            if (outline != null && !outline.isEmpty()) {
                val glyphMatrix = textMatrix
                    .let { tm -> PdfMatrix.translation(penX, 0.0).concat(tm) }
                    .let { tm -> PdfMatrix(unitScale, 0.0, 0.0, unitScale, 0.0, 0.0).concat(tm) }
                val sk = toSkPath(outline, glyphMatrix).apply { fillMode = PathFillMode.WINDING }
                canvas.drawPath(sk, paint)
            }
            penX += glyph.advanceWidth * unitScale
        }
    }

    /**
     * System-font fallback for fonts without embedded outlines (Standard-14
     * Helvetica/Times/Courier etc). Decode to text, pick a host typeface by
     * family, and draw the run with the text matrix's scale/rotation applied
     * (baseline at the matrix origin). Mirrors ComposeCanvas.drawTextViaSystemFont.
     */
    private fun drawTextViaSystemFont(
        bytes: ByteArray, font: PdfFont, fontSize: Double,
        textMatrix: PdfMatrix, fillColor: RgbColor, alpha: Double, blendMode: PdfBlendMode,
    ) {
        val text = font.decode(bytes)
        if (text.isEmpty()) return
        val sx = kotlin.math.sqrt(textMatrix.a * textMatrix.a + textMatrix.b * textMatrix.b)
        val sy = kotlin.math.sqrt(textMatrix.c * textMatrix.c + textMatrix.d * textMatrix.d)
        val renderedSize = (fontSize * sy).toFloat()
        if (renderedSize <= 0f) return
        val rotationDeg = (kotlin.math.atan2(textMatrix.b, textMatrix.a) * 180.0 / kotlin.math.PI).toFloat()

        val paint = Paint().apply {
            color = fillColor.toArgb(alpha)
            mode = PaintMode.FILL
            isAntiAlias = true
            this.blendMode = blendMode.toSkia()
        }
        val skFont = Font(systemTypeface(font), renderedSize)
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

    private fun systemTypeface(font: PdfFont): Typeface? {
        val name = font.baseFont
        val bold = "Bold" in name
        val italic = "Italic" in name || "Oblique" in name
        val style = when {
            bold && italic -> FontStyle.BOLD_ITALIC
            bold -> FontStyle.BOLD
            italic -> FontStyle.ITALIC
            else -> FontStyle.NORMAL
        }
        val family = when {
            name.startsWith("Times") -> "Times New Roman"
            name.startsWith("Courier") -> "Courier New"
            else -> "Helvetica"
        }
        return try {
            FontMgr.default.matchFamilyStyle(family, style) ?: FontMgr.default.matchFamilyStyle(null, style)
        } catch (t: Throwable) {
            null
        }
    }

    override fun fillShading(
        shading: PdfShading,
        ctm: PdfMatrix,
        clipPath: PdfPath?,
        alpha: Double,
        blendMode: PdfBlendMode,
    ) {
        val stops = shading.sampleStops(32) ?: return
        // skiko 0.148 moved gradient colours/positions into Gradient(Gradient.Colors(...)),
        // taking Color4f[] (default tile-mode CLAMP + sRGB colour space).
        val gradient = Gradient(
            Gradient.Colors(
                Array(stops.colors.size) { Color4f(stops.colors[it].toArgb(alpha)) },
                FloatArray(stops.offsets.size) { stops.offsets[it].toFloat() },
                FilterTileMode.CLAMP,
            ),
        )

        val shader: Shader = when (shading) {
            is PdfShading.Axial -> {
                val (x0, y0) = ctm.transformPoint(shading.coords[0], shading.coords[1])
                val (x1, y1) = ctm.transformPoint(shading.coords[2], shading.coords[3])
                Shader.makeLinearGradient(
                    x0.toFloat(), y0.toFloat(),
                    x1.toFloat(), y1.toFloat(),
                    gradient,
                )
            }
            is PdfShading.Radial -> {
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
            is PdfShading.Unsupported -> return
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

    override fun pushClip(path: PdfPath, ctm: PdfMatrix, evenOdd: Boolean) {
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
                    Image.makeRaster(ImageInfo(image.width, image.height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE), it, image.width * 4)
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
        // PDF image space is the unit square (0,0)-(1,1) before the CTM.
        // CTM maps that to device coords. We translate to (e,f) - height,
        // then scale by the matrix' magnitudes.
        canvas.save()
        openLayers++
        val matrix = pdfMatrixToSkia(ctm)
        canvas.concat(matrix)
        // The image's destination rect is (0, -1)..(1, 0) in PDF user-space —
        // PDF image origin is top-left of the unit square but PDF Y is up.
        // We flip Y so the bitmap is upright after the matrix.
        val paint = Paint().apply { this.alpha = alpha.toFloat().coerceIn(0f, 1f).let { (it * 255).toInt() } }
        canvas.save()
        canvas.translate(0f, -1f)
        canvas.scale(1f / sk.width, 1f / sk.height)
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
        renderMask: (PdfCanvas) -> Unit,
    ) {
        // Outer layer captures the content. Inner layer paints the mask
        // group on top with DstIn so the mask alpha clips the content.
        canvas.saveLayer(null, Paint())
        openLayers++
        try {
            render()
            val maskPaint = Paint().apply { blendMode = SkiaBlendMode.DST_IN }
            canvas.saveLayer(null, maskPaint)
            openLayers++
            try {
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

    private fun toSkPath(src: PdfPath, ctm: PdfMatrix): SkPath {
        // skiko 0.148: Path is immutable; build via PathBuilder then snapshot().
        val b = PathBuilder()
        for (seg in src.segments) {
            when (seg) {
                is PdfPath.Segment.MoveTo -> {
                    val (x, y) = ctm.transformPoint(seg.x, seg.y)
                    b.moveTo(x.toFloat(), y.toFloat())
                }
                is PdfPath.Segment.LineTo -> {
                    val (x, y) = ctm.transformPoint(seg.x, seg.y)
                    b.lineTo(x.toFloat(), y.toFloat())
                }
                is PdfPath.Segment.CurveTo -> {
                    val (x1, y1) = ctm.transformPoint(seg.x1, seg.y1)
                    val (x2, y2) = ctm.transformPoint(seg.x2, seg.y2)
                    val (x3, y3) = ctm.transformPoint(seg.x3, seg.y3)
                    b.cubicTo(
                        x1.toFloat(), y1.toFloat(),
                        x2.toFloat(), y2.toFloat(),
                        x3.toFloat(), y3.toFloat(),
                    )
                }
                is PdfPath.Segment.QuadTo -> {
                    val (x1, y1) = ctm.transformPoint(seg.x1, seg.y1)
                    val (x2, y2) = ctm.transformPoint(seg.x2, seg.y2)
                    b.quadTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
                }
                PdfPath.Segment.Close -> b.closePath()
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
