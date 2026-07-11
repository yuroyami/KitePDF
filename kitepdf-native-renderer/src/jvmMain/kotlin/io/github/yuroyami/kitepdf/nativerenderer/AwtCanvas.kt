package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.render.paintComplexShading
import io.github.yuroyami.kitepdf.Rectangle
import io.github.yuroyami.kitepdf.font.FontFamily
import io.github.yuroyami.kitepdf.font.FontSpec
import io.github.yuroyami.kitepdf.font.TextGlyph
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
public class AwtCanvas(private val g: Graphics2D) : PdfCanvas {

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
        lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) {
        val awt = toAwtPath(path, ctm)
        val avgScale = (ctm.scaleX() + ctm.scaleY()) * 0.5
        val width = (lineWidth * avgScale).toFloat().coerceAtLeast(0.1f)
        val cap = when (lineCap) { 1 -> BasicStroke.CAP_ROUND; 2 -> BasicStroke.CAP_SQUARE; else -> BasicStroke.CAP_BUTT }
        val join = when (lineJoin) { 1 -> BasicStroke.JOIN_ROUND; 2 -> BasicStroke.JOIN_BEVEL; else -> BasicStroke.JOIN_MITER }
        val miter = miterLimit.toFloat().coerceAtLeast(1f)
        val dash = dashArray
            ?.map { (it * avgScale).toFloat() }
            ?.filter { it > 0f }
            ?.toFloatArray()
            ?.takeIf { it.isNotEmpty() }
        withComposite(blendMode, alpha) {
            g.color = color.toAwt()
            g.stroke = if (dash != null) {
                BasicStroke(width, cap, join, miter, dash, (dashPhase * avgScale).toFloat().coerceAtLeast(0f))
            } else {
                BasicStroke(width, cap, join, miter)
            }
            g.draw(awt)
        }
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

        val unitScale = fontSize / unitsPerEm   // glyph outlines: font units → text space
        val advanceScale = fontSize / 1000.0    // PDF glyph widths are 1/1000 em, NOT font units
        var drewAny = false
        withComposite(blendMode, alpha) {
            g.color = color.toAwt()
            var penX = 0.0
            for (glyph in glyphs) {
                val outline = glyph.outline
                if (outline != null && !outline.isEmpty()) {
                    // outline(font units) → ×unitScale → +penX (text space) → finalMatrix (→ device).
                    // concat(other) applies `other` first, so the scale must be the LAST concat.
                    val glyphMatrix = textToDevice
                        .concat(PdfMatrix.translation(penX + glyph.xOffset * unitScale, glyph.yOffset * unitScale))
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
        if (!drewAny && glyphs.any { it.text.isNotBlank() }) {
            drawTextViaSystemFont(glyphs, fontSize, fontSpec, textToDevice, color, alpha, blendMode)
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
        glyphs: List<TextGlyph>,
        fontSize: Double,
        fontSpec: FontSpec,
        textMatrix: PdfMatrix,
        color: RgbColor,
        alpha: Double,
        blendMode: PdfBlendMode,
    ) {
        val text = glyphs.joinToString("") { it.text }
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
                g.color = color.toAwt()
                g.font = systemFontFor(fontSpec, renderedSize.toFloat())
                // Position each glyph by the PDF's OWN advance widths (1/1000 em),
                // not the substitute font's natural metrics — otherwise spacing
                // drifts and glyphs crowd together / overlap.
                var penX = 0.0
                val advScale = renderedSize / 1000.0
                for (glyph in glyphs) {
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
    private fun systemFontFor(spec: FontSpec, sizePx: Float): Font {
        val family = when (spec.family) {
            FontFamily.Serif -> Font.SERIF
            FontFamily.Monospace -> Font.MONOSPACED
            FontFamily.SansSerif -> Font.SANS_SERIF
        }
        var style = Font.PLAIN
        if (spec.bold) style = style or Font.BOLD
        if (spec.italic) style = style or Font.ITALIC
        return Font(family, style, 1).deriveFont(sizePx)
    }

    override fun fillShading(
        shading: PdfShading, ctm: PdfMatrix, clipPath: PdfPath?,
        alpha: Double, blendMode: PdfBlendMode,
    ) {
        if (paintComplexShading(shading, ctm, clipPath, alpha, blendMode)) return
        val stops = shading.sampleStops(32) ?: return
        val fractions = FloatArray(stops.offsets.size) { stops.offsets[it].toFloat() }
        val colors = Array(stops.colors.size) { stops.colors[it].toAwt() }

        // AWT gradients only offer NO_CYCLE, which *clamps* — i.e. always extends
        // the endpoint colours to infinity. PDF's `Extend [s e]` may forbid that on
        // either side. When a side isn't extended we intersect the fill region with
        // an "extent" shape so no pixels are painted past that end. Null = unbounded.
        var extentClip: java.awt.geom.Area? = null

        val paint: java.awt.Paint = when (shading) {
            is PdfShading.Axial -> {
                val (x0, y0) = ctm.transformPoint(shading.coords[0], shading.coords[1])
                val (x1, y1) = ctm.transformPoint(shading.coords[2], shading.coords[3])
                if (!shading.extendStart || !shading.extendEnd) {
                    extentClip = axialExtent(x0, y0, x1, y1, shading.extendStart, shading.extendEnd)
                }
                LinearGradientPaint(
                    Point2D.Double(x0, y0), Point2D.Double(x1, y1),
                    fractions, colors,
                    MultipleGradientPaint.CycleMethod.NO_CYCLE,
                )
            }
            is PdfShading.Radial -> {
                // PDF two-circle radial (§8.7.4.5.4): circle0 at t0, circle1 at t1.
                // RadialGradientPaint models one bounding circle + a focus point,
                // exact when the inner radius is 0 (point→circle). Use the larger
                // circle as the bounding one and the smaller circle's centre as the
                // focus, reversing the colours when circle0 is the larger.
                val sc = kotlin.math.sqrt(ctm.a * ctm.a + ctm.b * ctm.b)
                val (ax, ay) = ctm.transformPoint(shading.coords[0], shading.coords[1])
                val ar = shading.coords[2] * sc
                val (bx, by) = ctm.transformPoint(shading.coords[3], shading.coords[4])
                val br = shading.coords[5] * sc
                val outerIsB = br >= ar
                val cx = if (outerIsB) bx else ax
                val cy = if (outerIsB) by else ay
                val radius = (if (outerIsB) br else ar).toFloat().coerceAtLeast(0.1f)
                var fx = if (outerIsB) ax else bx
                var fy = if (outerIsB) ay else by
                // Focus must lie inside the bounding circle for AWT.
                val ddx = fx - cx; val ddy = fy - cy
                val dist = kotlin.math.sqrt(ddx * ddx + ddy * ddy)
                if (dist > radius * 0.99) { val k = radius * 0.99 / dist; fx = cx + ddx * k; fy = cy + ddy * k }
                val cols = if (outerIsB) colors else colors.reversedArray()
                // Extend: the outer circle (larger radius) carries the t-end that
                // extends outward; the inner circle the t-end that extends inward.
                // extend=false on the outer end → don't paint outside the outer disk;
                // on the inner end → don't paint inside the inner disk.
                val outerExtend = if (outerIsB) shading.extendEnd else shading.extendStart
                val innerExtend = if (outerIsB) shading.extendStart else shading.extendEnd
                val innerCx = if (outerIsB) ax else bx
                val innerCy = if (outerIsB) ay else by
                val innerR = (if (outerIsB) ar else br).coerceAtLeast(0.0)
                if (!outerExtend || !innerExtend) {
                    extentClip = radialExtent(
                        cx, cy, radius.toDouble(), outerExtend,
                        innerCx, innerCy, innerR, innerExtend,
                    )
                }
                RadialGradientPaint(
                    Point2D.Double(cx, cy), radius, Point2D.Double(fx, fy),
                    fractions, cols,
                    MultipleGradientPaint.CycleMethod.NO_CYCLE,
                )
            }
            is PdfShading.Unsupported -> return
            else -> return // T-40 types already handled by paintComplexShading
        }

        val extent = extentClip
        withComposite(blendMode, alpha) {
            g.paint = paint
            // Base fill region: the caller's shading clip path, else the device clip.
            val region: java.awt.Shape = if (clipPath != null) {
                toAwtPath(clipPath, ctm).apply { windingRule = Path2D.WIND_NON_ZERO }
            } else {
                g.clipBounds ?: java.awt.Rectangle(0, 0, 10_000, 10_000)
            }
            if (extent != null) {
                // Honour Extend=false: paint only inside the gradient's extent.
                val area = java.awt.geom.Area(region)
                area.intersect(extent)
                g.fill(area)
            } else {
                g.fill(region)
            }
        }
    }

    /**
     * Extent region for an axial gradient with [Extend] false on one/both ends.
     * Builds the half-plane band bounded by the perpendiculars through P0 and P1,
     * extended sideways to cover any plausible device area. A side that IS extended
     * is left unbounded (the band runs to +/- a large distance there).
     */
    private fun axialExtent(
        x0: Double, y0: Double, x1: Double, y1: Double,
        extendStart: Boolean, extendEnd: Boolean,
    ): java.awt.geom.Area {
        val dx = x1 - x0; val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        val big = 1.0e6
        if (len < 1e-9) {
            // Degenerate axis — nothing sensible to bound; allow everything.
            return java.awt.geom.Area(java.awt.geom.Rectangle2D.Double(-big, -big, 2 * big, 2 * big))
        }
        val ux = dx / len; val uy = dy / len            // axis unit vector (P0→P1)
        val px = -uy; val py = ux                        // perpendicular unit vector
        // Along-axis start/end offsets from P0: extended sides run out to `big`.
        val s0 = if (extendStart) -big else 0.0
        val s1 = if (extendEnd) len + big else len
        val hw = big                                     // perpendicular half-width
        fun pt(along: Double, side: Double) = Point2D.Double(
            x0 + ux * along + px * side,
            y0 + uy * along + py * side,
        )
        val path = Path2D.Double()
        val p00 = pt(s0, -hw); val p01 = pt(s1, -hw); val p11 = pt(s1, hw); val p10 = pt(s0, hw)
        path.moveTo(p00.x, p00.y); path.lineTo(p01.x, p01.y)
        path.lineTo(p11.x, p11.y); path.lineTo(p10.x, p10.y); path.closePath()
        return java.awt.geom.Area(path)
    }

    /**
     * Extent region for a radial gradient with [Extend] false on one/both ends.
     * Starts from the outer disk (bounded when the outer end isn't extended, else a
     * huge disk) and subtracts the inner disk when the inner end isn't extended.
     */
    private fun radialExtent(
        outerCx: Double, outerCy: Double, outerR: Double, outerExtend: Boolean,
        innerCx: Double, innerCy: Double, innerR: Double, innerExtend: Boolean,
    ): java.awt.geom.Area {
        val big = 1.0e6
        val outer = if (outerExtend) {
            java.awt.geom.Ellipse2D.Double(outerCx - big, outerCy - big, 2 * big, 2 * big)
        } else {
            java.awt.geom.Ellipse2D.Double(outerCx - outerR, outerCy - outerR, 2 * outerR, 2 * outerR)
        }
        val area = java.awt.geom.Area(outer)
        if (!innerExtend && innerR > 0.0) {
            area.subtract(
                java.awt.geom.Area(
                    java.awt.geom.Ellipse2D.Double(innerCx - innerR, innerCy - innerR, 2 * innerR, 2 * innerR),
                ),
            )
        }
        return area
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
            // Every other kind — RAW (Flate/LZW/CCITT/PNG-predictor decoded) and
            // ImageMask stencils — is assembled into a flat RGBA8888 buffer by the
            // shared rasterizer (the same path the Compose/Skia backends use). Wrap
            // it in an ARGB BufferedImage so ImageMask + SMask alpha survive.
            else -> image.toRgbaBytes()?.let { rgbaToBufferedImage(it, image.width, image.height) }
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * Wrap a flat RGBA8888 buffer (R,G,B,A per pixel, row-major, no padding — as
     * produced by [ImageXObject.toRgbaBytes]) in a [BufferedImage]. Uses
     * TYPE_INT_ARGB (not OPAQUE) so per-pixel alpha from `/ImageMask` stencils and
     * `/SMask` soft masks — already baked into the A channel by the rasterizer —
     * is preserved when the bitmap is composited.
     */
    private fun rgbaToBufferedImage(rgba: ByteArray, width: Int, height: Int): BufferedImage? {
        if (width <= 0 || height <= 0) return null
        val expected = width * height * 4
        if (rgba.size < expected) return null
        val argb = IntArray(width * height)
        var s = 0
        for (i in argb.indices) {
            val r = rgba[s].toInt() and 0xFF
            val g2 = rgba[s + 1].toInt() and 0xFF
            val b = rgba[s + 2].toInt() and 0xFF
            val a = rgba[s + 3].toInt() and 0xFF
            argb[i] = (a shl 24) or (r shl 16) or (g2 shl 8) or b
            s += 4
        }
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, width, height, argb, 0, width)
        return img
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
     * Soft mask (ISO 32000-1 §11.6.5). Content is painted on the live surface,
     * then gated by the mask group:
     *   - [SoftMask.Kind.Alpha] — the mask group's alpha, applied via `DST_IN`.
     *   - [SoftMask.Kind.Luminosity] — the mask group is rendered offscreen over
     *     a black backdrop, its per-pixel luminance is converted to alpha, and
     *     that alpha map is `DST_IN`-composited onto the content.
     */
    override fun applySoftMask(
        kind: SoftMask.Kind,
        maskBBox: Rectangle, maskCtm: PdfMatrix,
        render: () -> Unit,
        renderMask: (PdfCanvas) -> Unit,
    ) {
        // Render the masked content normally onto the live surface first.
        render()

        when (kind) {
            SoftMask.Kind.Alpha -> {
                // The mask group's own *alpha* is the mask. DST_IN keeps only the
                // content pixels the mask covers. (ISO 32000-1 §11.6.5.2)
                val savedComposite = g.composite
                try {
                    g.composite = AlphaComposite.DstIn
                    renderMask(this)
                } finally {
                    g.composite = savedComposite
                }
            }
            SoftMask.Kind.Luminosity -> {
                // The mask group's *luminance* (not its alpha) is the mask, over a
                // black backdrop (§11.6.5.2). Render the mask group offscreen onto
                // an opaque black background, convert each pixel's luminance → alpha,
                // then DST_IN that alpha map onto the live surface.
                // Device-space bounds: transform the (user-space) clip through g's
                // transform so the offscreen surface covers the right pixels even
                // when the host installed a non-identity transform on g.
                val userClip = g.clip ?: java.awt.Rectangle(0, 0, 10_000, 10_000)
                val bounds = g.transform.createTransformedShape(userClip).bounds
                if (bounds.width <= 0 || bounds.height <= 0) return
                val maskImg = BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB)
                val mg = maskImg.createGraphics()
                try {
                    // Black backdrop: pixels the mask group never paints stay black
                    // → luminance 0 → alpha 0 → content fully masked out there.
                    mg.color = Color.BLACK
                    mg.fillRect(0, 0, bounds.width, bounds.height)
                    // Match the live surface's rendering hints + coordinate space so
                    // the mask lands exactly over the content it gates. The main g's
                    // transform already includes the device CTM; shift its origin to
                    // the offscreen image's (0,0) by subtracting the clip origin.
                    mg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    mg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                    mg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    mg.translate(-bounds.x, -bounds.y)
                    mg.transform(g.transform)
                    renderMask(AwtCanvas(mg))
                } finally {
                    mg.dispose()
                }
                // Convert luminance → alpha in place: A = 0.3R + 0.59G + 0.11B.
                val w = maskImg.width; val h = maskImg.height
                val px = maskImg.getRGB(0, 0, w, h, null, 0, w)
                for (i in px.indices) {
                    val p = px[i]
                    val r = (p ushr 16) and 0xFF
                    val gg = (p ushr 8) and 0xFF
                    val b = p and 0xFF
                    val lum = (r * 77 + gg * 150 + b * 29) ushr 8 // /256, integer luma
                    px[i] = (lum shl 24) // pure alpha, colour irrelevant for DST_IN
                }
                maskImg.setRGB(0, 0, w, h, px, 0, w)
                // Blit the alpha map with DST_IN using device coordinates (bypass the
                // active CTM) so it aligns pixel-for-pixel with the offscreen render.
                val savedComposite = g.composite
                val savedTransform = g.transform
                try {
                    g.transform = AffineTransform()
                    g.composite = AlphaComposite.DstIn
                    g.drawImage(maskImg, bounds.x, bounds.y, null)
                } finally {
                    g.composite = savedComposite
                    g.transform = savedTransform
                }
            }
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
