package com.yuroyami.kitepdf.nativerenderer

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
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * [PdfCanvas] backed by [java.awt.Graphics2D]. Pure JRE — no Skia, no
 * Compose, no native binaries.
 *
 * The right choice when:
 *
 *   - you're rendering a PDF inside an AWT / Swing / JavaFX (via
 *     `SwingNode`) component
 *   - you want PNG/JPEG output via `javax.imageio.ImageIO` without
 *     pulling in Skia's native libs
 *   - server-side rendering where the JVM is already there
 *
 * Pair with [AwtPdfRasterizer] for the common "give me a `BufferedImage`"
 * use case.
 *
 * Caveats: Java2D supports a subset of PDF's 16 blend modes natively. The
 * common ones (Normal, SrcOver) are mapped to `AlphaComposite`. The rest
 * (Multiply, Screen, …) require a custom `java.awt.Composite` — we ship a
 * pixel-level [PdfBlendComposite] that implements all 16 modes for fidelity.
 */
class AwtCanvas(private val g: Graphics2D) : PdfCanvas {

    /** Save-state stack — mirrors clip + transform + composite per push. */
    private val saveStack = ArrayDeque<SavedState>()
    private var openLayers = 0

    init {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: PdfMatrix) {
        // The host owns the Graphics; we don't clear.
        saveStack.clear()
        openLayers = 0
    }

    override fun endPage() {
        // Roll back any leftover save layers (defensive).
        while (saveStack.isNotEmpty()) saveStack.removeLast().restore(g)
    }

    override fun fillPath(
        path: PdfPath, ctm: PdfMatrix, color: RgbColor, evenOdd: Boolean,
        alpha: Double, blendMode: PdfBlendMode,
    ) {
        val awt = toAwtPath(path, ctm).apply {
            windingRule = if (evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO
        }
        withComposite(blendMode, alpha) {
            g.color = color.toAwt()
            g.fill(awt)
        }
    }

    override fun strokePath(
        path: PdfPath, ctm: PdfMatrix, color: RgbColor, lineWidth: Double,
        alpha: Double, blendMode: PdfBlendMode,
        dashArray: List<Double>?, dashPhase: Double,
    ) {
        val awt = toAwtPath(path, ctm)
        val avgScale = (ctm.scaleX() + ctm.scaleY()) * 0.5
        val width = (lineWidth * avgScale).toFloat().coerceAtLeast(0.1f)
        val dash = dashArray
            ?.map { (it * avgScale).toFloat() }
            ?.filter { it > 0f }
            ?.toFloatArray()
            ?.takeIf { it.isNotEmpty() }
        withComposite(blendMode, alpha) {
            g.color = color.toAwt()
            g.stroke = if (dash != null) {
                BasicStroke(
                    width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    dash, (dashPhase * avgScale).toFloat().coerceAtLeast(0f),
                )
            } else {
                BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
            }
            g.draw(awt)
        }
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
        val unitScale = fontSize / upm          // glyph outlines: font units → text space
        val advanceScale = fontSize / 1000.0    // PDF glyph widths are 1/1000 em, NOT font units
        var drewAny = false
        withComposite(blendMode, alpha) {
            g.color = fillColor.toAwt()
            var penX = 0.0
            for (glyph in font.layoutBytes(bytes)) {
                val outline = glyph.outline
                if (outline != null && !outline.isEmpty()) {
                    // outline(font units) → ×unitScale → +penX (text space) → finalMatrix (→ device).
                    // concat(other) applies `other` first, so the scale must be the LAST concat.
                    val glyphMatrix = textMatrix
                        .concat(PdfMatrix.translation(penX, 0.0))
                        .concat(PdfMatrix(unitScale, 0.0, 0.0, unitScale, 0.0, 0.0))
                    val awt = toAwtPath(outline, glyphMatrix).apply { windingRule = Path2D.WIND_NON_ZERO }
                    g.fill(awt)
                    drewAny = true
                }
                penX += glyph.advanceWidth * advanceScale
            }
        }
        // Embedded font present but produced no glyphs (e.g. a subset we can't
        // decode) — fall back to a system font rather than rendering blank.
        if (!drewAny && font.decode(bytes).isNotBlank()) {
            drawTextViaSystemFont(bytes, font, fontSize, textMatrix, fillColor, alpha, blendMode)
        }
    }

    /**
     * Fallback for non-embedded fonts (e.g. the Standard-14). Renders with a
     * platform logical font — zero bundled bytes, since the JVM already ships
     * Serif / SansSerif / Monospaced faces that are metric-compatible stand-ins
     * for Times / Helvetica / Courier. This is how Apple's PDFKit and KitePDF's
     * own Compose backend handle the case; we mirror ComposeCanvas here.
     */
    private fun drawTextViaSystemFont(
        bytes: ByteArray,
        font: PdfFont,
        fontSize: Double,
        textMatrix: PdfMatrix,
        fillColor: RgbColor,
        alpha: Double,
        blendMode: PdfBlendMode,
    ) {
        val text = font.decode(bytes)
        if (text.isBlank()) return

        // Decompose the (text-space → device) matrix like the Compose path:
        // translation + rotation + *positive* scale magnitudes, so the device
        // Y-flip baked into the matrix doesn't render the glyphs mirrored.
        val sx = kotlin.math.sqrt(textMatrix.a * textMatrix.a + textMatrix.b * textMatrix.b)
        val sy = kotlin.math.sqrt(textMatrix.c * textMatrix.c + textMatrix.d * textMatrix.d)
        if (sy == 0.0) return
        val rotation = kotlin.math.atan2(textMatrix.b, textMatrix.a)
        val renderedSize = (fontSize * sy).coerceAtLeast(0.01)

        val tx = AffineTransform().apply {
            translate(textMatrix.e, textMatrix.f)
            if (rotation != 0.0) rotate(rotation)
            if (sx != sy) scale(sx / sy, 1.0)
        }
        val saved = g.transform
        try {
            g.transform(tx)
            withComposite(blendMode, alpha) {
                g.color = fillColor.toAwt()
                g.font = systemFontFor(font, renderedSize.toFloat())
                // Position each glyph by the PDF's OWN advance widths (1/1000 em),
                // not the substitute font's natural metrics — otherwise spacing
                // drifts and glyphs crowd together / overlap.
                var penX = 0.0
                val advScale = renderedSize / 1000.0
                for (glyph in font.layoutBytes(bytes)) {
                    val t = glyph.text
                    if (t.isNotEmpty() && t != " ") g.drawString(t, penX.toFloat(), 0f)
                    penX += glyph.advanceWidth * advScale
                }
            }
        } finally {
            g.transform = saved
        }
    }

    /** Map a non-embedded PDF font to a JVM logical font (mirrors ComposeCanvas's family/style choice). */
    private fun systemFontFor(font: PdfFont, sizePx: Float): Font {
        val family = when {
            font.baseFont.startsWith("Times") -> Font.SERIF
            font.baseFont.startsWith("Courier") -> Font.MONOSPACED
            else -> Font.SANS_SERIF
        }
        var style = Font.PLAIN
        if ("Bold" in font.baseFont) style = style or Font.BOLD
        if ("Italic" in font.baseFont || "Oblique" in font.baseFont) style = style or Font.ITALIC
        return Font(family, style, 1).deriveFont(sizePx)
    }

    override fun fillShading(
        shading: PdfShading, ctm: PdfMatrix, clipPath: PdfPath?,
        alpha: Double, blendMode: PdfBlendMode,
    ) {
        val stops = shading.sampleStops(32) ?: return
        val fractions = FloatArray(stops.offsets.size) { stops.offsets[it].toFloat() }
        val colors = Array(stops.colors.size) { stops.colors[it].toAwt() }

        val paint: java.awt.Paint = when (shading) {
            is PdfShading.Axial -> {
                val (x0, y0) = ctm.transformPoint(shading.coords[0], shading.coords[1])
                val (x1, y1) = ctm.transformPoint(shading.coords[2], shading.coords[3])
                LinearGradientPaint(
                    Point2D.Double(x0, y0), Point2D.Double(x1, y1),
                    fractions, colors,
                    MultipleGradientPaint.CycleMethod.NO_CYCLE,
                )
            }
            is PdfShading.Radial -> {
                val (cx, cy) = ctm.transformPoint(shading.coords[3], shading.coords[4])
                val r = (shading.coords[5] * kotlin.math.sqrt(ctm.a * ctm.a + ctm.b * ctm.b))
                    .toFloat().coerceAtLeast(0.1f)
                RadialGradientPaint(
                    Point2D.Double(cx, cy), r,
                    fractions, colors,
                    MultipleGradientPaint.CycleMethod.NO_CYCLE,
                )
            }
            is PdfShading.Unsupported -> return
        }

        withComposite(blendMode, alpha) {
            g.paint = paint
            if (clipPath != null) {
                val awt = toAwtPath(clipPath, ctm).apply { windingRule = Path2D.WIND_NON_ZERO }
                g.fill(awt)
            } else {
                val clip = g.clipBounds ?: java.awt.Rectangle(0, 0, 10_000, 10_000)
                g.fill(clip)
            }
        }
    }

    override fun drawImage(image: ImageXObject, ctm: PdfMatrix, alpha: Double) {
        val bitmap = decodeImage(image) ?: return drawPlaceholder(ctm)
        val saved = g.transform
        try {
            val matrix = AffineTransform(ctm.a, ctm.b, ctm.c, ctm.d, ctm.e, ctm.f)
            g.transform = AffineTransform(g.transform).apply { concatenate(matrix) }
            // PDF image is in the unit square (0..1)² with Y flipped already by deviceCtm.
            // We draw at (0, -1) sized (1, 1) — the Y-flip in CTM puts it upright.
            val drawOp = AffineTransform().apply {
                // Map the bitmap onto the PDF image unit square [0,1]²: row 0 (top)
                // → v=1, last row → v=0. So translate up by 1, then flip Y. Mapping
                // into [-1,0] instead places the image one image-height too low —
                // invisible when it sits inside a tight clip (the Maths banner).
                translate(0.0, 1.0)
                scale(1.0 / bitmap.width, -1.0 / bitmap.height)
            }
            withComposite(PdfBlendMode.Normal, alpha) {
                g.drawImage(bitmap, drawOp, null)
            }
        } finally {
            g.transform = saved
        }
    }

    private fun decodeImage(image: ImageXObject): BufferedImage? = try {
        when (image.kind) {
            ImageXObject.Kind.JPEG, ImageXObject.Kind.JPEG2000 -> decodeJpeg(image.encodedBytes)
            else -> null
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * Decode a JPEG. ImageIO handles 3-channel (YCbCr) JPEGs correctly, but
     * 4-channel CMYK / YCCK JPEGs (Adobe, APP14 marker) come back inverted or
     * rejected — so for those we read the raw raster and convert ourselves.
     */
    private fun decodeJpeg(bytes: ByteArray): BufferedImage? {
        val iis = ImageIO.createImageInputStream(java.io.ByteArrayInputStream(bytes))
            ?: return ImageIO.read(java.io.ByteArrayInputStream(bytes))
        val readers = ImageIO.getImageReaders(iis)
        if (!readers.hasNext()) { iis.close(); return ImageIO.read(java.io.ByteArrayInputStream(bytes)) }
        val reader = readers.next()
        try {
            reader.setInput(iis)
            val raster = reader.readRaster(0, null)
            if (raster.numBands < 4) return ImageIO.read(java.io.ByteArrayInputStream(bytes))

            // 4-channel CMYK / YCCK. Adobe stores the channels inverted, so the
            // raster already holds (255-C, 255-M, 255-Y, 255-K) → RGB = inv*invK/255.
            val transform = adobeTransform(bytes) // 2 = YCCK (bands 0-2 are YCbCr)
            val w = raster.width
            val h = raster.height
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val p = IntArray(4)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    raster.getPixel(x, y, p)
                    val invC: Int
                    val invM: Int
                    val invY: Int
                    val kMul: Int // black multiplier: 255 = no black ink, 0 = full black
                    if (transform == 2) {
                        // YCCK: bands 0-2 are YCbCr of the (inverted) CMY → RGB;
                        // band 3 is K stored DIRECTLY, so the multiplier is 255-K.
                        val yy = p[0].toDouble(); val cb = p[1] - 128.0; val cr = p[2] - 128.0
                        invC = (yy + 1.402 * cr).toInt().coerceIn(0, 255)
                        invM = (yy - 0.344136 * cb - 0.714136 * cr).toInt().coerceIn(0, 255)
                        invY = (yy + 1.772 * cb).toInt().coerceIn(0, 255)
                        kMul = 255 - p[3]
                    } else {
                        // Adobe CMYK stored inverted: raster = (255-C,255-M,255-Y,255-K).
                        invC = p[0]; invM = p[1]; invY = p[2]; kMul = p[3]
                    }
                    val r = invC * kMul / 255
                    val g2 = invM * kMul / 255
                    val b = invY * kMul / 255
                    out.setRGB(x, y, (r shl 16) or (g2 shl 8) or b)
                }
            }
            return out
        } catch (t: Throwable) {
            return try { ImageIO.read(java.io.ByteArrayInputStream(bytes)) } catch (e: Throwable) { null }
        } finally {
            reader.dispose()
            try { iis.close() } catch (_: Throwable) {}
        }
    }

    /** APP14 Adobe `transform` byte: -1 none, 0 CMYK, 1 YCbCr, 2 YCCK. */
    private fun adobeTransform(bytes: ByteArray): Int {
        var i = 2
        while (i + 4 < bytes.size) {
            if (bytes[i].toInt() and 0xFF != 0xFF) { i++; continue }
            when (val marker = bytes[i + 1].toInt() and 0xFF) {
                0xEE -> {
                    val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                    return if (len >= 14 && i + 2 + len <= bytes.size) bytes[i + 2 + len - 1].toInt() and 0xFF else -1
                }
                0xDA, 0xD9 -> return -1
                in 0xD0..0xD8 -> i += 2
                else -> {
                    val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                    i += 2 + len
                }
            }
        }
        return -1
    }

    private fun drawPlaceholder(ctm: PdfMatrix) {
        val saved = g.transform
        try {
            g.transform = AffineTransform(g.transform).apply {
                concatenate(AffineTransform(ctm.a, ctm.b, ctm.c, ctm.d, ctm.e, ctm.f))
            }
            g.color = Color(0xE0, 0xE0, 0xE0)
            g.fillRect(0, -1, 1, 1)
            g.color = Color(0x88, 0x88, 0x88)
            g.drawRect(0, -1, 1, 1)
        } finally {
            g.transform = saved
        }
    }

    override fun pushClip(path: PdfPath, ctm: PdfMatrix, evenOdd: Boolean) {
        saveStack.addLast(SavedState.snapshot(g))
        val awt = toAwtPath(path, ctm).apply {
            windingRule = if (evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO
        }
        g.clip(awt)  // intersect with existing clip (Java2D semantics match PDF)
    }

    override fun popClip() {
        if (saveStack.isNotEmpty()) saveStack.removeLast().restore(g)
    }

    /**
     * Open an offscreen layer for transparency-group compositing. Java2D has
     * no built-in saveLayer like Skia, so we manually pixel-render into a
     * BufferedImage and blit it back when the group ends.
     */
    override fun beginTransparencyGroup(
        bbox: Rectangle, ctm: PdfMatrix,
        isolated: Boolean, knockout: Boolean,
        alpha: Double, blendMode: PdfBlendMode,
    ) {
        // For Java2D, we approximate by setting the active composite + alpha
        // and pushing them on the save stack. True saveLayer semantics (so
        // intermediate operations composite *then* blend) would need a
        // separate BufferedImage. That's a follow-up for fidelity-sensitive
        // PDFs; the alpha-only case (the common one) works here.
        saveStack.addLast(SavedState.snapshot(g))
        g.composite = PdfBlendComposite(blendMode, alpha.toFloat().coerceIn(0f, 1f))
    }

    override fun endTransparencyGroup() {
        if (saveStack.isNotEmpty()) saveStack.removeLast().restore(g)
    }

    /**
     * Soft mask via offscreen compositing. We render the content layer onto
     * an offscreen BufferedImage, render the mask group onto a second one,
     * apply DST_IN, then blit the result onto the underlying Graphics2D.
     */
    override fun applySoftMask(
        kind: SoftMask.Kind,
        maskBBox: Rectangle, maskCtm: PdfMatrix,
        render: () -> Unit,
        renderMask: (PdfCanvas) -> Unit,
    ) {
        // Best-effort with Java2D: use AlphaComposite.DST_IN on the active
        // surface. Equivalent to Compose / Skia's "DstIn" wrapping pattern.
        val savedComposite = g.composite
        try {
            // Render content normally.
            render()
            // Apply mask as DST_IN — the mask group's alpha clips the content.
            g.composite = AlphaComposite.DstIn
            renderMask(this)
        } finally {
            g.composite = savedComposite
        }
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private inline fun withComposite(blendMode: PdfBlendMode, alpha: Double, block: () -> Unit) {
        val saved = g.composite
        val a = alpha.toFloat().coerceIn(0f, 1f)
        g.composite = if (blendMode == PdfBlendMode.Normal) AlphaComposite.SrcOver.derive(a)
            else PdfBlendComposite(blendMode, a)
        try { block() } finally { g.composite = saved }
    }

    private fun toAwtPath(src: PdfPath, ctm: PdfMatrix): Path2D.Double {
        val out = Path2D.Double()
        for (seg in src.segments) {
            when (seg) {
                is PdfPath.Segment.MoveTo -> {
                    val (x, y) = ctm.transformPoint(seg.x, seg.y)
                    out.moveTo(x, y)
                }
                is PdfPath.Segment.LineTo -> {
                    val (x, y) = ctm.transformPoint(seg.x, seg.y)
                    out.lineTo(x, y)
                }
                is PdfPath.Segment.CurveTo -> {
                    val (x1, y1) = ctm.transformPoint(seg.x1, seg.y1)
                    val (x2, y2) = ctm.transformPoint(seg.x2, seg.y2)
                    val (x3, y3) = ctm.transformPoint(seg.x3, seg.y3)
                    out.curveTo(x1, y1, x2, y2, x3, y3)
                }
                is PdfPath.Segment.QuadTo -> {
                    val (x1, y1) = ctm.transformPoint(seg.x1, seg.y1)
                    val (x2, y2) = ctm.transformPoint(seg.x2, seg.y2)
                    out.quadTo(x1, y1, x2, y2)
                }
                PdfPath.Segment.Close -> out.closePath()
            }
        }
        return out
    }

    /**
     * Opaque AWT colour. Constant alpha (`/ca`, `/CA`) is applied exactly once
     * by [withComposite] — via `AlphaComposite` for Normal, or
     * [PdfBlendComposite] for the other modes. Baking it into the colour too
     * would apply it twice and wash the paint out.
     */
    private fun RgbColor.toAwt(): Color = Color(
        (r.coerceIn(0.0, 1.0) * 255).toInt(),
        (g.coerceIn(0.0, 1.0) * 255).toInt(),
        (b.coerceIn(0.0, 1.0) * 255).toInt(),
    )

    /** Snapshot of Graphics2D state that needs restoring after a clip / group push. */
    private data class SavedState(
        val clip: java.awt.Shape?,
        val transform: AffineTransform,
        val composite: java.awt.Composite,
        val paint: java.awt.Paint,
    ) {
        fun restore(g: Graphics2D) {
            g.clip = clip
            g.transform = transform
            g.composite = composite
            g.paint = paint
        }
        companion object {
            fun snapshot(g: Graphics2D) = SavedState(g.clip, AffineTransform(g.transform), g.composite, g.paint)
        }
    }
}
