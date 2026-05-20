package com.yuroyami.kitepdf.skia

import com.yuroyami.kitepdf.Rectangle
import com.yuroyami.kitepdf.font.PdfFont
import com.yuroyami.kitepdf.render.BlendMode as PdfBlendMode
import com.yuroyami.kitepdf.render.ImageXObject
import com.yuroyami.kitepdf.render.Matrix as PdfMatrix
import com.yuroyami.kitepdf.render.PdfCanvas
import com.yuroyami.kitepdf.render.PdfPath
import com.yuroyami.kitepdf.render.PdfShading
import com.yuroyami.kitepdf.render.RgbColor
import com.yuroyami.kitepdf.render.SoftMask
import com.yuroyami.kitepdf.render.sampleStops
import org.jetbrains.skia.BlendMode as SkiaBlendMode
import org.jetbrains.skia.Canvas as SkCanvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Path as SkPath
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.Point
import org.jetbrains.skia.Shader
import org.jetbrains.skia.GradientStyle

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
    ) {
        val sk = toSkPath(path, ctm)
        val avgScale = (ctm.scaleX() + ctm.scaleY()) * 0.5
        val paint = Paint().apply {
            this.color = color.toArgb(alpha)
            this.mode = PaintMode.STROKE
            this.strokeWidth = (lineWidth * avgScale).toFloat().coerceAtLeast(0.1f)
            this.isAntiAlias = true
            this.blendMode = blendMode.toSkia()
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
        if (!font.hasEmbeddedOutlines) return  // system-font fallback isn't wired in v1.

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

    override fun fillShading(
        shading: PdfShading,
        ctm: PdfMatrix,
        clipPath: PdfPath?,
        alpha: Double,
        blendMode: PdfBlendMode,
    ) {
        val stops = shading.sampleStops(32) ?: return
        val colors = IntArray(stops.colors.size) { stops.colors[it].toArgb(alpha) }
        val positions = FloatArray(stops.offsets.size) { stops.offsets[it].toFloat() }

        val shader: Shader = when (shading) {
            is PdfShading.Axial -> {
                val (x0, y0) = ctm.transformPoint(shading.coords[0], shading.coords[1])
                val (x1, y1) = ctm.transformPoint(shading.coords[2], shading.coords[3])
                Shader.makeLinearGradient(
                    Point(x0.toFloat(), y0.toFloat()),
                    Point(x1.toFloat(), y1.toFloat()),
                    colors,
                    positions,
                    GradientStyle.DEFAULT,
                )
            }
            is PdfShading.Radial -> {
                val (cx, cy) = ctm.transformPoint(shading.coords[3], shading.coords[4])
                val r = (shading.coords[5] * kotlin.math.sqrt(ctm.a * ctm.a + ctm.b * ctm.b))
                    .toFloat().coerceAtLeast(0.1f)
                Shader.makeRadialGradient(
                    Point(cx.toFloat(), cy.toFloat()),
                    r,
                    colors,
                    positions,
                    GradientStyle.DEFAULT,
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
        val sk = SkPath().apply {
            moveTo(0f, 0f); lineTo(1f, 0f); lineTo(1f, 1f); lineTo(0f, 1f); closePath()
        }
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
        val out = SkPath()
        for (seg in src.segments) {
            when (seg) {
                is PdfPath.Segment.MoveTo -> {
                    val (x, y) = ctm.transformPoint(seg.x, seg.y)
                    out.moveTo(x.toFloat(), y.toFloat())
                }
                is PdfPath.Segment.LineTo -> {
                    val (x, y) = ctm.transformPoint(seg.x, seg.y)
                    out.lineTo(x.toFloat(), y.toFloat())
                }
                is PdfPath.Segment.CurveTo -> {
                    val (x1, y1) = ctm.transformPoint(seg.x1, seg.y1)
                    val (x2, y2) = ctm.transformPoint(seg.x2, seg.y2)
                    val (x3, y3) = ctm.transformPoint(seg.x3, seg.y3)
                    out.cubicTo(
                        x1.toFloat(), y1.toFloat(),
                        x2.toFloat(), y2.toFloat(),
                        x3.toFloat(), y3.toFloat(),
                    )
                }
                is PdfPath.Segment.QuadTo -> {
                    val (x1, y1) = ctm.transformPoint(seg.x1, seg.y1)
                    val (x2, y2) = ctm.transformPoint(seg.x2, seg.y2)
                    out.quadTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
                }
                PdfPath.Segment.Close -> out.closePath()
            }
        }
        return out
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
