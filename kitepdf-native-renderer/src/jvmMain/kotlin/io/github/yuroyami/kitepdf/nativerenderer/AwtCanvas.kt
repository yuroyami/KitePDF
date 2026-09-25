package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.render.paintComplexShading
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KITE_DEFAULT_MAX_RASTER_PIXELS
import io.github.yuroyami.kitepdf.core.render.KiteBitmapCache
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteImageSampling
import io.github.yuroyami.kitepdf.core.render.KiteMaskTransfer
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
import io.github.yuroyami.kitepdf.core.render.gridFitImage
import io.github.yuroyami.kitepdf.core.render.imageSampling
import io.github.yuroyami.kitepdf.core.render.sampleStops
import io.github.yuroyami.kitepdf.core.render.shrinkArgb
import io.github.yuroyami.kitepdf.core.render.shrinkRgba
import io.github.yuroyami.kitepdf.core.render.strokePen
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Composite
import java.awt.CompositeContext
import java.awt.Font
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.MultipleGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.awt.image.ColorModel
import java.awt.image.Raster
import java.awt.image.WritableRaster
import java.awt.image.DataBufferInt
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor

/**
 * [KiteCanvas] backed by [java.awt.Graphics2D]. Pure JRE: no Skia, no
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
 * (Multiply, Screen, …) require a custom `java.awt.Composite`. We ship a
 * pixel-level [PdfBlendComposite] that implements all 16 modes for fidelity.
 */
public class AwtCanvas(private var g: Graphics2D) : KiteCanvas {

    /** Clips and transparency groups keep separate stacks, so interleaving them cannot restore the wrong state (#138). */
    private val clipStack = ArrayDeque<ClipEntry>()
    private val groupStack = ArrayDeque<GroupFrame>()
    private var openLayers = 0

    init {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: KiteMatrix) {
        // The host owns the Graphics; we don't clear.
        while (groupStack.isNotEmpty()) endTransparencyGroup()
        while (clipStack.isNotEmpty()) popClip()
        openLayers = 0
    }

    override fun endPage() {
        // Close any group left open, then roll back leftover clips (defensive).
        while (groupStack.isNotEmpty()) endTransparencyGroup()
        while (clipStack.isNotEmpty()) popClip()
    }

    override fun fillPath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, evenOdd: Boolean,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        val awt = toAwtPath(path, ctm, scratchPath).apply {
            windingRule = if (evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO
        }
        withComposite(blendMode, alpha) {
            g.color = color.toAwt()
            g.fill(awt)
        }
    }

    override fun strokePath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, lineWidth: Double,
        alpha: Double, blendMode: KiteBlendMode,
        dashArray: List<Double>?, dashPhase: Double,
        lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) {
        val pen = strokePen(ctm, lineWidth)
        val awt = toAwtPath(path, pen.pathMatrix, scratchPath)
        val width = pen.width.toFloat()
        val cap = when (lineCap) { 1 -> BasicStroke.CAP_ROUND; 2 -> BasicStroke.CAP_SQUARE; else -> BasicStroke.CAP_BUTT }
        val join = when (lineJoin) { 1 -> BasicStroke.JOIN_ROUND; 2 -> BasicStroke.JOIN_BEVEL; else -> BasicStroke.JOIN_MITER }
        val miter = miterLimit.toFloat().coerceAtLeast(1f)
        val dash = awtDash(dashArray, pen.dashScale)
        withComposite(blendMode, alpha) {
            g.color = color.toAwt()
            g.stroke = if (dash != null) {
                BasicStroke(width, cap, join, miter, dash, (dashPhase * pen.dashScale).toFloat().coerceAtLeast(0f))
            } else {
                BasicStroke(width, cap, join, miter)
            }
            val m = pen.strokeMatrix
            if (m == null) {
                g.draw(awt)
            } else {
                // Java2D strokes in user space under the transform, which draws the elliptical pen.
                val saved = g.transform
                g.transform(AffineTransform(m.a, m.b, m.c, m.d, m.e, m.f))
                try { g.draw(awt) } finally { g.transform = saved }
            }
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
        if (!hasOutlines) {
            drawTextViaSystemFont(glyphs, fontSize, fontSpec, textToDevice, color, alpha, blendMode)
            return
        }

        val unitScale = fontSize / unitsPerEm   // glyph outlines: font units → text space
        val advanceScale = fontSize / 1000.0    // PDF glyph widths are 1/1000 em, NOT font units
        var drewAny = false
        // Glyph positions snap to MuPDF's subpixel grid, and glyph pixels come from the cache,
        // when this Graphics draws in whole device pixels: no transform, or a shift by whole pixels.
        val base = g.transform
        val onPixels = (base.type and AffineTransform.TYPE_TRANSLATION.inv()) == 0 &&
            base.translateX == floor(base.translateX) && base.translateY == floor(base.translateY)
        // A knockout paint replaces what lies under it, and a cached glyph image would replace its empty corners too.
        val cache = onPixels && blendMode == KiteBlendMode.Normal && !knockingOut
        withComposite(blendMode, alpha) {
            val awtColor = color.toAwt()
            g.color = awtColor
            var penX = 0.0
            for (glyph in glyphs) {
                val outline = glyph.outline
                if (outline != null && !outline.isEmpty()) {
                    // outline(font units) → ×unitScale → +penX (text space) → finalMatrix (→ device).
                    // concat(other) applies `other` first, so the scale must be the LAST concat.
                    val exact = textToDevice
                        .concat(KiteMatrix.translation(penX + glyph.xOffset * unitScale, glyph.yOffset * unitScale))
                        .concat(KiteMatrix(unitScale, 0.0, 0.0, unitScale, 0.0, 0.0))
                    val glyphMatrix = if (onPixels) snapGlyph(exact, unitsPerEm) else exact
                    if (!cache || !drawCachedGlyph(outline, glyphMatrix, unitsPerEm, awtColor)) {
                        g.fill(toAwtPath(outline, glyphMatrix, scratchPath).apply { windingRule = Path2D.WIND_NON_ZERO })
                    }
                    drewAny = true
                }
                penX += glyph.advanceWidth * advanceScale + glyph.advanceAdjust
            }
        }
        // Embedded font present but produced no glyphs (e.g. a subset we can't
        // decode). Fall back to a system font rather than rendering blank.
        if (!drewAny && glyphs.any { it.text.isNotBlank() }) {
            drawTextViaSystemFont(glyphs, fontSize, fontSpec, textToDevice, color, alpha, blendMode)
        }
    }

    /** The pixels of one glyph at one size, subpixel position and colour; null for an outline that covers nothing. */
    private class GlyphRaster(val image: BufferedImage?, val left: Int, val top: Int)

    /** Identifies a [GlyphRaster]. Fonts keep one outline object for each glyph, so the outline compares by identity. */
    private class GlyphKey(val outline: KitePath, val a: Double, val d: Double, val fx: Double, val fy: Double, val rgb: Int) {
        override fun equals(other: Any?): Boolean = other is GlyphKey && other.outline === outline &&
            other.a == a && other.d == d && other.fx == fx && other.fy == fy && other.rgb == rgb

        override fun hashCode(): Int {
            var h = System.identityHashCode(outline)
            h = 31 * h + a.hashCode()
            h = 31 * h + d.hashCode()
            h = 31 * h + fx.hashCode()
            h = 31 * h + fy.hashCode()
            return 31 * h + rgb
        }
    }

    /** Rasterized glyphs, as MuPDF and PDFium keep them: a glyph is filled once and copied after that (#306). */
    private val glyphRasters = object : LinkedHashMap<GlyphKey, GlyphRaster>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GlyphKey, GlyphRaster>?): Boolean = size > GLYPH_CACHE_ENTRIES
    }

    /**
     * Draws [outline] under [m], which maps it to device pixels, from the glyph cache. Returns
     * false for a glyph that the cache does not hold: a turned or skewed one, or one whose em
     * is larger than [GLYPH_CACHE_MAX_EM] pixels.
     */
    private fun drawCachedGlyph(outline: KitePath, m: KiteMatrix, unitsPerEm: Int, color: Color): Boolean {
        if (m.b != 0.0 || m.c != 0.0) return false
        if (kotlin.math.sqrt(abs(m.a * m.d)) * unitsPerEm > GLYPH_CACHE_MAX_EM) return false
        val ix = floor(m.e)
        val iy = floor(m.f)
        val key = GlyphKey(outline, m.a, m.d, m.e - ix, m.f - iy, color.rgb)
        val raster = glyphRasters.getOrPut(key) { rasterizeGlyph(outline, KiteMatrix(m.a, 0.0, 0.0, m.d, m.e - ix, m.f - iy), color) }
        raster.image?.let { g.drawImage(it, ix.toInt() + raster.left, iy.toInt() + raster.top, null) }
        return true
    }

    /** [outline] under [m], filled in [color] into an image with this Graphics' hints, and where its corner goes. */
    private fun rasterizeGlyph(outline: KitePath, m: KiteMatrix, color: Color): GlyphRaster {
        val path = toAwtPath(outline, m).apply { windingRule = Path2D.WIND_NON_ZERO }
        val bounds = path.bounds2D
        if (bounds.isEmpty) return GlyphRaster(null, 0, 0)
        val left = floor(bounds.minX).toInt() - 1
        val top = floor(bounds.minY).toInt() - 1
        val width = kotlin.math.ceil(bounds.maxX).toInt() - left + 1
        val height = kotlin.math.ceil(bounds.maxY).toInt() - top + 1
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
        val gi = image.createGraphics()
        try {
            gi.setRenderingHints(g.renderingHints)
            gi.color = color
            gi.translate(-left, -top)
            gi.fill(path)
        } finally {
            gi.dispose()
        }
        return GlyphRaster(image, left, top)
    }

    /**
     * [m], which maps a glyph to device pixels, with its origin on MuPDF's subpixel grid
     * (`fz_subpixel_adjust`). Along the text, an em under 24 pixels lands on a quarter pixel,
     * one under 48 on half a pixel, and a larger one on a whole pixel. Across horizontal or
     * vertical text, an em of 8 pixels or more lands on a whole pixel.
     */
    private fun snapGlyph(m: KiteMatrix, unitsPerEm: Int): KiteMatrix {
        val size = kotlin.math.sqrt(abs(m.a * m.d - m.b * m.c)) * unitsPerEm
        val (q, r) = when {
            size >= 48 -> 0 to 0.5
            size >= 24 -> 128 to 0.25
            else -> 192 to 0.125
        }
        val (qMin, rMin) = when {
            size >= 8 -> 0 to 0.5
            size >= 4 -> 128 to 0.25
            else -> 192 to 0.125
        }
        var hq = q
        var hr = r
        var vq = q
        var vr = r
        if (m.a == 0.0 && m.d == 0.0) { hq = qMin; hr = rMin }
        if (m.b == 0.0 && m.c == 0.0) { vq = qMin; vr = rMin }
        val e = m.e + hr
        val f = m.f + vr
        val pixE = floor(e)
        val pixF = floor(f)
        val subE = (((e - pixE) * 256).toInt() and hq) / 256.0
        val subF = (((f - pixF) * 256).toInt() and vq) / 256.0
        return KiteMatrix(m.a, m.b, m.c, m.d, pixE + subE, pixF + subF)
    }

    /**
     * Fallback for non-embedded fonts (e.g. the Standard-14). Renders with a
     * platform logical font: zero bundled bytes, since the JVM already ships
     * Serif / SansSerif / Monospaced faces that are metric-compatible stand-ins
     * for Times / Helvetica / Courier. This is how Apple's PDFKit and KitePDF's
     * own Compose backend handle the case; we mirror ComposeCanvas here.
     */
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
                // not the substitute font's natural metrics, otherwise spacing
                // drifts and glyphs crowd together / overlap.
                var penX = 0.0
                val advScale = renderedSize / 1000.0
                for (glyph in glyphs) {
                    val t = glyph.text
                    if (t.isNotEmpty() && t != " ") g.drawString(t, penX.toFloat(), 0f)
                    // advScale already carries sy (renderedSize), so the text-space
                    // spacing adjust needs the same factor to stay in step.
                    penX += glyph.advanceWidth * advScale + glyph.advanceAdjust * sy
                }
            }
        } finally {
            g.transform = saved
        }
    }

    /**
     * The outline of [text] in the logical font [drawGlyphs] draws a font without
     * embedded outlines in, at 1000 units per em with y up (#85).
     */
    override fun hostGlyphOutline(text: String, fontSpec: FontSpec): KitePath {
        val key = fontSpec.copy(name = "") to text
        hostOutlines[key]?.let { return it }
        if (hostOutlines.size >= HOST_OUTLINE_CACHE_SIZE) hostOutlines.clear()
        val shape = systemFontFor(fontSpec, 1000f).createGlyphVector(OUTLINE_CONTEXT, text).outline
        return outlineOf(shape).also { hostOutlines[key] = it }
    }

    /** Map a non-embedded PDF font to a JVM logical font (mirrors ComposeCanvas's family/style choice). */
    private fun systemFontFor(spec: FontSpec, sizePx: Float): Font {
        val family = when (spec.family) {
            KiteFontFamily.Serif -> Font.SERIF
            KiteFontFamily.Monospace -> Font.MONOSPACED
            KiteFontFamily.SansSerif -> Font.SANS_SERIF
        }
        var style = Font.PLAIN
        if (spec.bold) style = style or Font.BOLD
        if (spec.italic) style = style or Font.ITALIC
        return Font(family, style, 1).deriveFont(sizePx)
    }

    override fun fillShading(
        shading: KiteShading, ctm: KiteMatrix, clipPath: KitePath?,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        if (paintComplexShading(shading, ctm, clipPath, alpha, blendMode)) return
        val stops = shading.sampleStops() ?: return
        val fractions = FloatArray(stops.offsets.size) { stops.offsets[it].toFloat() }
        val colors = Array(stops.colors.size) { stops.colors[it].toAwt() }

        // The gradient is built in shading space and the CTM maps it as a whole, so a
        // non-uniform or skewed CTM turns circles into ellipses and tilts the bands
        // (ISO 32000-1, 8.7.4.5.3 and 8.7.4.5.4). A CTM without an inverse paints nothing.
        val toDevice = AffineTransform(ctm.a, ctm.b, ctm.c, ctm.d, ctm.e, ctm.f)
        val det = toDevice.determinant
        if (det == 0.0 || !det.isFinite()) return
        val toShading = toDevice.createInverse()

        // Base fill region: the caller's shading clip path, else the device clip.
        val region: java.awt.Shape = if (clipPath != null) {
            toAwtPath(clipPath, ctm).apply { windingRule = Path2D.WIND_NON_ZERO }
        } else {
            g.clipBounds ?: java.awt.Rectangle(0, 0, 10_000, 10_000)
        }
        val box = region.bounds2D

        // AWT gradients only offer NO_CYCLE, which *clamps*: it always extends
        // the endpoint colours to infinity. PDF's `Extend [s e]` may forbid that on
        // either side. When a side isn't extended we intersect the fill region with
        // an "extent" shape so no pixels are painted past that end. Null = unbounded.
        var extentClip: java.awt.geom.Area? = null

        val paint: java.awt.Paint = when (shading) {
            is KiteShading.Axial -> {
                val (x0, y0, x1, y1) = shading.coords
                if (x0 == x1 && y0 == y1) return
                if (!shading.extendStart || !shading.extendEnd) {
                    val reach = reachOf(box, toShading, x0, y0)
                    extentClip = axialExtent(x0, y0, x1, y1, shading.extendStart, shading.extendEnd, reach)
                        .createTransformedArea(toDevice)
                }
                LinearGradientPaint(
                    Point2D.Double(x0, y0), Point2D.Double(x1, y1),
                    fractions, colors,
                    MultipleGradientPaint.CycleMethod.NO_CYCLE,
                    MultipleGradientPaint.ColorSpaceType.SRGB, toDevice,
                )
            }
            is KiteShading.Radial -> {
                // Both circles and both extend flags, solved per pixel: AWT's own radial
                // gradient has one circle and a focus point (ISO 32000-1, 8.7.4.5.4).
                RadialShadingPaint(shading.coords, shading.extendStart, shading.extendEnd, stops, toDevice)
            }
            is KiteShading.Unsupported -> return
            else -> return // complex shading types already handled by paintComplexShading
        }

        val extent = extentClip
        withComposite(blendMode, alpha) {
            g.paint = paint
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

    /** A distance in shading space from ([x], [y]) that reaches every corner of the device [box]. */
    private fun reachOf(box: Rectangle2D, toShading: AffineTransform, x: Double, y: Double): Double {
        var reach = 0.0
        val corner = Point2D.Double()
        for (cx in doubleArrayOf(box.minX, box.maxX)) for (cy in doubleArrayOf(box.minY, box.maxY)) {
            corner.setLocation(cx, cy)
            toShading.transform(corner, corner)
            reach = maxOf(reach, kotlin.math.hypot(corner.x - x, corner.y - y))
        }
        return reach + 1.0
    }

    /**
     * Extent region for an axial gradient with [Extend] false on one/both ends, in
     * shading space. Builds the band bounded by the perpendiculars through P0 and P1,
     * [reach] wide on each side. A side that IS extended runs [reach] past its end.
     */
    private fun axialExtent(
        x0: Double, y0: Double, x1: Double, y1: Double,
        extendStart: Boolean, extendEnd: Boolean, reach: Double,
    ): java.awt.geom.Area {
        val dx = x1 - x0; val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 1e-9) {
            // Degenerate axis: nothing sensible to bound, so allow everything.
            return java.awt.geom.Area(java.awt.geom.Rectangle2D.Double(x0 - reach, y0 - reach, 2 * reach, 2 * reach))
        }
        val ux = dx / len; val uy = dy / len            // axis unit vector (P0→P1)
        val px = -uy; val py = ux                        // perpendicular unit vector
        // Along-axis start/end offsets from P0: extended sides run out to `reach`.
        val s0 = if (extendStart) -reach else 0.0
        val s1 = if (extendEnd) len + reach else len
        val hw = reach                                   // perpendicular half-width
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

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double) {
        drawImage(image, ctm, alpha, KiteBlendMode.Normal)
    }

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double, blendMode: KiteBlendMode) {
        val matrix = AffineTransform(ctm.a, ctm.b, ctm.c, ctm.d, ctm.e, ctm.f)
        // The policy reads the whole transform to device pixels, including one the host set on the Graphics.
        val whole = AffineTransform(g.transform).apply { concatenate(matrix) }
        // The edges of an unrotated image move outwards onto whole pixels, as in MuPDF (#300).
        val fitted = gridFitImage(KiteMatrix(whole.scaleX, whole.shearY, whole.shearX, whole.scaleY, whole.translateX, whole.translateY))
        val device = AffineTransform(fitted.a, fitted.b, fitted.c, fitted.d, fitted.e, fitted.f)
        val sampling = imageSampling(image.width, image.height, fitted, image.interpolate)
        val bitmap = bitmaps.getOrPut(image, sampling, { it.width.toLong() * it.height * 4 }) { decodeImage(image, sampling) }
            ?: return drawPlaceholder(ctm)
        val saved = g.transform
        val savedHint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        try {
            g.transform = device
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                if (sampling.smooth) RenderingHints.VALUE_INTERPOLATION_BILINEAR else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
            )
            // PDF image is in the unit square (0..1)² with Y flipped already by deviceCtm.
            // We draw at (0, -1) sized (1, 1). The Y-flip in CTM puts it upright.
            val drawOp = AffineTransform().apply {
                // Map the bitmap onto the PDF image unit square [0,1]²: row 0 (top)
                // → v=1, last row → v=0. So translate up by 1, then flip Y. Mapping
                // into [-1,0] instead places the image one image-height too low.
                // The image is then invisible inside a tight clip (the Maths banner).
                translate(0.0, 1.0)
                scale(1.0 / bitmap.width, -1.0 / bitmap.height)
            }
            withComposite(blendMode, alpha) {
                g.drawImage(bitmap, drawOp, null)
            }
        } finally {
            g.transform = saved
            if (savedHint != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, savedHint)
        }
    }

    /** Bitmaps built from images, so that an image drawn many times converts once (#117). */
    private val bitmaps = KiteBitmapCache<BufferedImage>()

    /** The image as a bitmap, averaged down when [sampling] shrinks it, so fine detail fades instead of dropping out (#122). */
    private fun decodeImage(image: KiteImageData, sampling: KiteImageSampling): BufferedImage? = try {
        when (image.kind) {
            KiteImageData.Kind.JPEG, KiteImageData.Kind.JPEG2000 ->
                decodeJpeg(image.encodedBytes)?.let { if (sampling.shrinks) shrink(it, sampling) else it }
            // Every other kind, RAW (Flate/LZW/CCITT/PNG-predictor decoded) and
            // ImageMask stencils, is assembled into a flat RGBA8888 buffer by the
            // shared rasterizer (the same path the Compose/Skia backends use). Wrap
            // it in an ARGB BufferedImage so ImageMask + SMask alpha survive.
            else -> image.toRgbaBytes()?.let { rgba ->
                if (!sampling.shrinks) return@let rgbaToBufferedImage(rgba, image.width, image.height)
                rgbaToBufferedImage(
                    shrinkRgba(rgba, image.width, image.height, sampling.shrinkX, sampling.shrinkY),
                    sampling.shrunkWidth(image.width),
                    sampling.shrunkHeight(image.height),
                )
            }
        }
    } catch (t: Throwable) {
        null
    }

    /** [image] averaged down as [sampling] asks. getRGB gives straight ARGB whatever the image type. */
    private fun shrink(image: BufferedImage, sampling: KiteImageSampling): BufferedImage {
        val w = image.width
        val small = shrinkArgb(w, image.height, sampling.shrinkX, sampling.shrinkY) { pixels, y, rows ->
            image.getRGB(0, y, w, rows, pixels, 0, w)
        }
        return rgbaToBufferedImage(small, sampling.shrunkWidth(w), sampling.shrunkHeight(image.height)) ?: image
    }

    /**
     * Wrap a flat RGBA8888 buffer (R,G,B,A per pixel, row-major, no padding, as
     * produced by [KiteImageData.toRgbaBytes]) in a [BufferedImage]. Uses
     * TYPE_INT_ARGB (not OPAQUE) so per-pixel alpha from `/ImageMask` stencils and
     * `/SMask` soft masks, already baked into the A channel by the rasterizer,
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
     * rejected, so for those we read the raw raster and convert ourselves.
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

    private fun drawPlaceholder(ctm: KiteMatrix) {
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

    /**
     * Java2D clips by pixel centre and never anti-aliases a clip, so a clip is applied
     * as MuPDF applies it (#285). A rectangle clips to the whole pixels it touches. Any
     * other shape opens a [SoftClip]: the paints inside go to a layer, which composites
     * onto the page through the anti-aliased coverage of the shape when the clip pops.
     */
    override fun pushClip(path: KitePath, ctm: KiteMatrix, evenOdd: Boolean) {
        val awt = toAwtPath(path, ctm).apply {
            windingRule = if (evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO
        }
        val saved = g.clip
        val device = g.transform.createTransformedShape(awt)
        val box = wholePixelBox(device)
        if (box != null) {
            clipStack.addLast(ClipEntry(saved))
            val transform = g.transform
            g.transform = AffineTransform()
            g.clip(box)
            g.transform = transform
            return
        }
        // A soft clip would composite each knockout paint over the ones before it, so a knockout group clips hard.
        val soft = if (knockingOut) null else beginSoftClip(device)
        clipStack.addLast(ClipEntry(saved, soft))
        if (soft == null) {
            g.clip(awt) // Too large a layer, or nothing inside: a hard clip.
            return
        }
        g = soft.graphics
        layerBlends = soft.blends
    }

    override fun popClip() {
        clipStack.removeLastOrNull()?.let { entry ->
            val soft = entry.soft
            if (soft == null) g.clip = entry.saved else endSoftClip(soft)
            return
        }
        // A clip pushed before the open group: pop it on the graphics it was pushed on (#138).
        val frame = groupStack.lastOrNull { it.savedClips.size > it.deferredPops } ?: return
        if (frame.deferredPops > 0 || frame.savedClips.last().soft != null) {
            // The group paints onto the layer of a soft clip, so that clip ends after the group.
            frame.deferredPops++
            return
        }
        frame.parent.clip = frame.savedClips.removeLast().saved
    }

    /** One pushed clip: the clip it replaced on the graphics it was pushed on, and its layer when it is soft. */
    private class ClipEntry(val saved: java.awt.Shape?, val soft: SoftClip? = null)

    /**
     * A clip with anti-aliased edges. The paints inside go to [layer] through [graphics].
     * The layer covers [bounds] in device pixels, and [shape] is the clip in device space.
     */
    private class SoftClip(
        val parent: Graphics2D,
        val graphics: Graphics2D,
        val layer: BufferedImage,
        val bounds: java.awt.Rectangle,
        val shape: java.awt.Shape,
        val savedBlends: LayerBlends?,
    ) {
        /** The paints in the layer. A paint in another blend mode first composites what the layer holds. */
        lateinit var blends: LayerBlends
    }

    /**
     * The whole-pixel box that MuPDF clips to when [shape] is one axis-aligned rectangle,
     * or null for any other shape. MuPDF snaps the edges to a grid of 17 by 15 samples a
     * pixel and keeps every pixel the snapped rectangle touches (`fz_bound_rasterizer`).
     */
    private fun wholePixelBox(shape: java.awt.Shape): java.awt.Rectangle? {
        val xs = DoubleArray(5)
        val ys = DoubleArray(5)
        var n = 0
        val c = DoubleArray(6)
        val it = shape.getPathIterator(null)
        while (!it.isDone) {
            when (it.currentSegment(c)) {
                java.awt.geom.PathIterator.SEG_MOVETO -> if (n == 0) { xs[0] = c[0]; ys[0] = c[1]; n = 1 } else return null
                java.awt.geom.PathIterator.SEG_LINETO -> if (n in 1..4) { xs[n] = c[0]; ys[n] = c[1]; n++ } else return null
                java.awt.geom.PathIterator.SEG_CLOSE -> Unit
                else -> return null
            }
            it.next()
        }
        // Four corners, or five with the last back on the first.
        if (n == 5 && (!same(xs[4], xs[0]) || !same(ys[4], ys[0])) || n < 4) return null
        // The sides alternate between horizontal and vertical.
        val flat = same(ys[0], ys[1])
        for (k in 0..3) {
            val next = (k + 1) % 4
            val horizontal = (k % 2 == 0) == flat
            if (horizontal && !same(ys[k], ys[next]) || !horizontal && !same(xs[k], xs[next])) return null
        }
        val x0 = minOf(xs[0], xs[2]); val x1 = maxOf(xs[0], xs[2])
        val y0 = minOf(ys[0], ys[2]); val y1 = maxOf(ys[0], ys[2])
        if (!(x0.isFinite() && x1.isFinite() && y0.isFinite() && y1.isFinite())) return null
        fun pixel(v: Double) = v.coerceIn(-PIXEL_LIMIT, PIXEL_LIMIT).toInt()
        val left = pixel(kotlin.math.floor(x0))
        val top = pixel(kotlin.math.floor(y0))
        val right = pixel(kotlin.math.ceil(kotlin.math.floor(x1 * 17) / 17))
        val bottom = pixel(kotlin.math.ceil(kotlin.math.floor(y1 * 15) / 15))
        return java.awt.Rectangle(left, top, maxOf(0, right - left), maxOf(0, bottom - top))
    }

    private fun same(a: Double, b: Double) = kotlin.math.abs(a - b) <= 1e-6

    /**
     * Opens a layer for a soft clip of [shape], sized to the part of it that the current
     * clip leaves. Returns null when that part is empty or past [maskPixelBudget].
     */
    private fun beginSoftClip(shape: java.awt.Shape): SoftClip? {
        val parent = g
        val area = shape.bounds2D
        if (!listOf(area.minX, area.minY, area.maxX, area.maxY).all { it.isFinite() }) return null
        parent.clip?.let { clip ->
            Rectangle2D.intersect(area, parent.transform.createTransformedShape(clip).bounds2D, area)
        }
        if (area.isEmpty) return null
        val bounds = area.bounds
        if (bounds.width <= 0 || bounds.height <= 0 || bounds.width.toLong() * bounds.height > maskPixelBudget) return null
        val layer = takeMaskBuffer(bounds.width, bounds.height)
        val graphics = layer.createGraphics()
        graphics.setRenderingHints(parent.renderingHints)
        graphics.translate(-bounds.x.toDouble(), -bounds.y.toDouble())
        graphics.transform(parent.transform)
        parent.clip?.let(graphics::clip)
        return SoftClip(parent, graphics, layer, bounds, shape, layerBlends).also { soft ->
            soft.blends = LayerBlends { compositeSoftClip(soft) }
        }
    }

    private fun endSoftClip(soft: SoftClip) {
        soft.graphics.dispose()
        g = soft.parent
        layerBlends = soft.savedBlends
        compositeSoftClip(soft)
        giveBackMaskBuffer(soft.layer, IntRange.EMPTY)
    }

    /**
     * Composites what the layer of [soft] holds onto its parent, through the anti-aliased
     * coverage of its shape, and clears it. The layer composites once, in the blend mode
     * of its paints.
     */
    private fun compositeSoftClip(soft: SoftClip) {
        val bounds = soft.bounds
        val width = bounds.width
        val pixels = (soft.layer.raster.dataBuffer as DataBufferInt).data
        val painted = paintedBounds(pixels, width, bounds.height) ?: return
        val mask = takeMaskBuffer(width, bounds.height)
        val maskGraphics = mask.createGraphics()
        try {
            maskGraphics.clipRect(painted.x, painted.y, painted.width, painted.height)
            maskGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            maskGraphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            maskGraphics.translate(-bounds.x.toDouble(), -bounds.y.toDouble())
            maskGraphics.color = Color.WHITE
            maskGraphics.fill(soft.shape)
        } finally {
            maskGraphics.dispose()
        }
        val coverage = (mask.raster.dataBuffer as DataBufferInt).data
        for (y in painted.y until painted.y + painted.height) {
            for (i in y * width + painted.x until y * width + painted.x + painted.width) {
                val p = pixels[i]
                val cover = coverage[i] ushr 24
                if (cover == 255 || p == 0) continue
                pixels[i] = ((((p ushr 24) * cover + 127) / 255) shl 24) or (p and 0xFFFFFF)
            }
        }
        val dirtyRows = painted.y until painted.y + painted.height
        giveBackMaskBuffer(mask, dirtyRows)
        val mode = soft.blends.single ?: KiteBlendMode.Normal
        // One paint of the layer outside, recorded before it lands there.
        soft.savedBlends?.record(mode)
        val target = soft.parent.create() as Graphics2D
        try {
            target.transform = AffineTransform()
            target.composite = if (mode == KiteBlendMode.Normal) AlphaComposite.SrcOver else PdfBlendComposite(mode, 1f)
            val x = bounds.x + painted.x
            val y = bounds.y + painted.y
            target.drawImage(soft.layer, x, y, x + painted.width, y + painted.height,
                painted.x, painted.y, painted.x + painted.width, painted.y + painted.height, null)
        } finally {
            target.dispose()
        }
        java.util.Arrays.fill(pixels, painted.y * width, (painted.y + painted.height) * width, 0)
    }

    /**
     * An open transparency group. A group that needs its own [layer] paints into it,
     * and the layer composites onto [parent] once when the group ends. Otherwise
     * [layer] is null and the group paints straight onto [parent], which gives the
     * same pixels. In a [knockout] group each paint replaces what lies under it.
     */
    private class GroupFrame(
        val parent: Graphics2D,
        val layer: BufferedImage?,
        val bounds: java.awt.Rectangle,
        val scale: Double,
        val alpha: Float,
        val blendMode: KiteBlendMode,
        val savedClips: ArrayDeque<ClipEntry>,
        val savedBlends: LayerBlends?,
        val knockout: Boolean = false,
    ) {
        /** Clips from [savedClips] popped while the group was open, which end after it. */
        var deferredPops = 0
    }

    /**
     * ISO 32000-1, 11.4.5: a group's constant alpha and blend mode apply once, when the
     * group composites onto its backdrop. So a group that has them paints into a layer
     * first (#77). An isolated group's layer starts transparent. A non-isolated group's
     * layer starts as a copy of the backdrop, so a paint inside blends with the page,
     * and at the end the layer mixes with the page by [alpha]. That is exact over an
     * opaque backdrop. A non-isolated group with a blend mode of its own starts
     * transparent, which is exact when no paint inside blends (#125).
     *
     * In a knockout group (11.4.6) each paint replaces what lies under it inside its
     * shape, so each paint composites against the group's transparent backdrop. That is
     * the result of the spec unless a paint inside blends. A group nested in a knockout
     * group gets a layer, so its own paints do not knock each other out.
     */
    override fun beginTransparencyGroup(
        bbox: KiteRectangle, ctm: KiteMatrix,
        isolated: Boolean, knockout: Boolean,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        val parent = g
        val a = alpha.toFloat().coerceIn(0f, 1f)
        val nested = knockingOut
        fun direct() = groupStack.addLast(GroupFrame(parent, null, java.awt.Rectangle(), 1.0, a, blendMode, ArrayDeque(), layerBlends))
        // An isolated group composites its paints onto a transparent backdrop, so it needs a layer
        // even at full alpha in Normal (ISO 32000-1, 11.4.5, #125).
        if (a >= 1f && blendMode == KiteBlendMode.Normal && !isolated && !knockout && !nested) return direct()
        val box = bbox.normalized()
        val area = AffineTransform(parent.transform).apply {
            concatenate(AffineTransform(ctm.a, ctm.b, ctm.c, ctm.d, ctm.e, ctm.f))
        }.createTransformedShape(Rectangle2D.Double(box.left, box.bottom, box.width, box.height)).bounds2D
        // Malformed geometry: paint the group straight onto the page.
        if (!listOf(area.minX, area.minY, area.maxX, area.maxY).all { it.isFinite() }) return direct()
        parent.clip?.let { clip ->
            Rectangle2D.intersect(area, parent.transform.createTransformedShape(clip).bounds2D, area)
        }
        // Nothing of the group can show: its paints go to a layer that is never composited.
        val bounds = if (area.isEmpty) java.awt.Rectangle(0, 0, 0, 0) else area.bounds
        val pixels = bounds.width.toLong() * bounds.height
        if (pixels > maskPixelBudget * UNBOUNDED_MASK_FACTOR) return direct()
        // Past the raster budget the layer is drawn at a lower resolution, as a soft mask is (#264).
        val scale = if (pixels > maskPixelBudget) kotlin.math.sqrt(maskPixelBudget.toDouble() / pixels) else 1.0
        val layer = BufferedImage(
            maxOf(1, kotlin.math.ceil(bounds.width * scale).toInt()),
            maxOf(1, kotlin.math.ceil(bounds.height * scale).toInt()),
            BufferedImage.TYPE_INT_ARGB,
        )
        // A non-isolated group starts from the page under it, so its paints blend with the page (#125).
        if (!isolated && !knockout && !nested && blendMode == KiteBlendMode.Normal && scale == 1.0 && !bounds.isEmpty) {
            transferMaskBackdrop(parent, bounds, layer, null)
        }
        val layerGraphics = layer.createGraphics()
        layerGraphics.setRenderingHints(parent.renderingHints)
        if (bounds.isEmpty) layerGraphics.clipRect(0, 0, 0, 0)
        if (scale != 1.0) layerGraphics.scale(scale, scale)
        layerGraphics.translate(-bounds.x.toDouble(), -bounds.y.toDouble())
        layerGraphics.transform(parent.transform)
        parent.clip?.let(layerGraphics::clip)
        groupStack.addLast(GroupFrame(parent, layer, bounds, scale, a, blendMode, ArrayDeque(clipStack), layerBlends, knockout))
        g = layerGraphics
        clipStack.clear()
        // The paints inside composite onto the layer, not onto a soft-masked layer outside.
        layerBlends = null
    }

    override fun endTransparencyGroup() {
        val frame = groupStack.lastOrNull() ?: return
        // A clip left open inside the group ends first, onto the group's layer.
        if (frame.layer != null) while (clipStack.isNotEmpty()) popClip()
        groupStack.removeLast()
        val layer = frame.layer ?: return
        try {
            compositeGroup(frame, layer)
        } finally {
            repeat(frame.deferredPops) { popClip() }
        }
    }

    private fun compositeGroup(frame: GroupFrame, layer: BufferedImage) {
        val layerGraphics = g
        g = frame.parent
        clipStack.clear()
        clipStack.addAll(frame.savedClips)
        layerBlends = frame.savedBlends
        layerGraphics.dispose()
        if (frame.bounds.isEmpty) return
        // The group composites as one paint with its own blend mode.
        layerBlends?.record(frame.blendMode)
        val target = g.create() as Graphics2D
        try {
            target.transform = AffineTransform()
            target.composite = if (frame.blendMode == KiteBlendMode.Normal) AlphaComposite.SrcOver.derive(frame.alpha)
                else PdfBlendComposite(frame.blendMode, frame.alpha)
            if (frame.scale == 1.0) {
                target.drawImage(layer, frame.bounds.x, frame.bounds.y, null)
            } else {
                target.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                target.drawImage(layer, frame.bounds.x, frame.bounds.y, frame.bounds.width, frame.bounds.height, null)
            }
        } finally {
            target.dispose()
        }
    }

    override fun applySoftMask(
        kind: SoftMask.Kind,
        maskBBox: KiteRectangle, maskCtm: KiteMatrix,
        render: () -> Unit,
        renderMask: (KiteCanvas) -> Unit,
    ) {
        applySoftMask(kind, maskBBox, maskCtm, null, render, renderMask)
    }

    /**
     * Soft mask (ISO 32000-1, 11.6.5): the content paints into its own transparent
     * layer, the mask scales that layer's alpha, and the layer then composites onto
     * the page once. The backdrop is never read or masked (#78), the layer has alpha
     * whatever the destination type (#80), and the mask group's colours never reach
     * the page (#79). Skia and Compose use the same layer model.
     *
     * One native blit replaces a per-pixel readback of the page, which made masked
     * pages 3 to 44 times slower (#256). When every paint in the content used one
     * blend mode, the layer composites with that mode, so a masked Multiply shadow
     * still multiplies. Content that mixes blend modes renders again on the exact,
     * slower backdrop path. The [transfer] table maps each mask value once, on both paths.
     */
    override fun applySoftMask(
        kind: SoftMask.Kind,
        maskBBox: KiteRectangle, maskCtm: KiteMatrix,
        transfer: KiteMaskTransfer?,
        render: () -> Unit,
        renderMask: (KiteCanvas) -> Unit,
    ) {
        val parent = g
        val box = maskBBox.normalized()
        val maskTransform = AffineTransform(parent.transform).apply {
            concatenate(AffineTransform(maskCtm.a, maskCtm.b, maskCtm.c, maskCtm.d, maskCtm.e, maskCtm.f))
        }
        val area = maskTransform.createTransformedShape(
            Rectangle2D.Double(box.left, box.bottom, box.width, box.height),
        ).bounds2D
        if (!listOf(area.minX, area.minY, area.maxX, area.maxY).all { it.isFinite() }) {
            render() // Malformed geometry: keep the paint without its unusable mask.
            return
        }
        parent.clip?.let { clip ->
            Rectangle2D.intersect(area, parent.transform.createTransformedShape(clip).bounds2D, area)
        }
        if (area.isEmpty) return
        val bounds = area.bounds
        if (bounds.width <= 0 || bounds.height <= 0) return
        val pixels = bounds.width.toLong() * bounds.height
        if (pixels > maskPixelBudget * UNBOUNDED_MASK_FACTOR) {
            render() // An unclipped box far past any page: keep the paint without its mask.
            return
        }
        // Past the raster budget the mask applies at a lower resolution instead of
        // being dropped, so the page never shows unmasked content (#264).
        val scale = if (pixels > maskPixelBudget) kotlin.math.sqrt(maskPixelBudget.toDouble() / pixels) else 1.0
        val width = maxOf(1, kotlin.math.ceil(bounds.width * scale).toInt())
        val height = maxOf(1, kotlin.math.ceil(bounds.height * scale).toInt())
        fun prepare(graphics: Graphics2D) {
            graphics.setRenderingHints(parent.renderingHints)
            if (scale != 1.0) graphics.scale(scale, scale)
            graphics.translate(-bounds.x.toDouble(), -bounds.y.toDouble())
            graphics.transform(parent.transform)
            parent.clip?.let(graphics::clip)
        }

        val layer = takeMaskBuffer(width, height)
        // The content callback closes over this canvas. Redirect it only for
        // this invocation, and restore state even when a malformed paint fails.
        val layerGraphics = layer.createGraphics()
        val savedClips = clipStack.toList()
        val savedGroups = groupStack.toList()
        val savedBlends = layerBlends
        val blends = LayerBlends()
        try {
            prepare(layerGraphics)
            g = layerGraphics
            clipStack.clear()
            groupStack.clear()
            layerBlends = blends
            render()
        } finally {
            // A clip left open by the content ends onto this layer.
            runCatching { while (clipStack.isNotEmpty()) popClip() }
            g = parent
            clipStack.clear()
            clipStack.addAll(savedClips)
            groupStack.clear()
            groupStack.addAll(savedGroups)
            layerBlends = savedBlends
            layerGraphics.dispose()
        }
        // A mask nested in another masked layer is one more paint of that layer.
        savedBlends?.merge(blends)
        val layerPixels = (layer.raster.dataBuffer as DataBufferInt).data
        // Only the pixels the content touched need the mask, the blit and the cleanup.
        val painted = paintedBounds(layerPixels, width, height)
        if (painted == null) {
            giveBackMaskBuffer(layer, IntRange.EMPTY)
            return
        }
        val mask = takeMaskBuffer(width, height)
        if (blends.mixed && scale == 1.0) {
            // Paints with different blend modes each need the real backdrop, which
            // one layer composite cannot give them (#80). This rare case renders again.
            java.util.Arrays.fill(layerPixels, 0)
            maskOverBackdrop(parent, bounds, kind, transfer, layer, mask, ::prepare, render, renderMask)
            return
        }

        val maskGraphics = mask.createGraphics()
        try {
            maskGraphics.clipRect(painted.x, painted.y, painted.width, painted.height)
            if (kind == SoftMask.Kind.Luminosity) {
                // Opaque black backdrop means unpainted pixels have zero luminance.
                maskGraphics.color = Color.BLACK
                maskGraphics.fillRect(painted.x, painted.y, painted.width, painted.height)
            }
            prepare(maskGraphics)
            renderMask(AwtCanvas(maskGraphics))
        } finally {
            maskGraphics.dispose()
        }

        // ISO 32000-1, 11.6.5.2: the mask value scales the content's alpha only.
        val maskPixels = (mask.raster.dataBuffer as DataBufferInt).data
        val luminosity = kind == SoftMask.Kind.Luminosity
        for (y in painted.y until painted.y + painted.height) {
            for (i in y * width + painted.x until y * width + painted.x + painted.width) {
                val p = layerPixels[i]
                val alpha = p ushr 24
                if (alpha == 0) continue
                val m = maskPixels[i]
                val level = if (luminosity) {
                    (((m ushr 16) and 255) * 77 + ((m ushr 8) and 255) * 150 + (m and 255) * 29) ushr 8
                } else m ushr 24
                val coverage = transfer?.get(level) ?: level
                if (coverage == 255) continue
                layerPixels[i] = (((alpha * coverage + 127) / 255) shl 24) or (p and 0xFFFFFF)
            }
        }

        val target = parent.create() as Graphics2D
        try {
            target.transform = AffineTransform()
            val mode = blends.single
            target.composite = if (mode == null || mode == KiteBlendMode.Normal) AlphaComposite.SrcOver
                else PdfBlendComposite(mode, 1f)
            if (scale == 1.0) {
                val x = bounds.x + painted.x
                val y = bounds.y + painted.y
                target.drawImage(layer, x, y, x + painted.width, y + painted.height,
                    painted.x, painted.y, painted.x + painted.width, painted.y + painted.height, null)
            } else {
                target.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                target.drawImage(layer, bounds.x, bounds.y, bounds.width, bounds.height, null)
            }
        } finally {
            target.dispose()
        }
        val dirtyRows = painted.y until painted.y + painted.height
        giveBackMaskBuffer(layer, dirtyRows)
        giveBackMaskBuffer(mask, dirtyRows)
    }

    /** The bounds of the pixels with any alpha, or null when the content painted nothing. */
    private fun paintedBounds(pixels: IntArray, width: Int, height: Int): java.awt.Rectangle? {
        var minY = -1; var maxY = -1; var minX = width; var maxX = -1
        for (y in 0 until height) {
            val row = y * width
            // The alpha of an OR is the OR of the alphas, and the JIT vectorises this loop.
            var any = 0
            for (i in row until row + width) any = any or pixels[i]
            if (any ushr 24 == 0) continue
            var first = 0
            while (pixels[row + first] ushr 24 == 0) first++
            var last = width - 1
            while (pixels[row + last] ushr 24 == 0) last--
            if (minY < 0) minY = y
            maxY = y
            if (first < minX) minX = first
            if (last > maxX) maxX = last
        }
        return if (minY < 0) null else java.awt.Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /**
     * The exact path for content that mixes blend modes: [content] starts with the
     * page backdrop, the content paints over it with its own modes, and the mask
     * then interpolates the original and rendered colours once per pixel.
     */
    private fun maskOverBackdrop(
        parent: Graphics2D, bounds: java.awt.Rectangle, kind: SoftMask.Kind, transfer: KiteMaskTransfer?,
        content: BufferedImage, mask: BufferedImage, prepare: (Graphics2D) -> Unit,
        render: () -> Unit, renderMask: (KiteCanvas) -> Unit,
    ) {
        transferMaskBackdrop(parent, bounds, content, null)
        val contentGraphics = content.createGraphics()
        val savedClips = clipStack.toList()
        val savedGroups = groupStack.toList()
        val savedBlends = layerBlends
        try {
            prepare(contentGraphics)
            g = contentGraphics
            clipStack.clear()
            groupStack.clear()
            layerBlends = null
            render()
        } finally {
            // A clip left open by the content ends onto this layer.
            runCatching { while (clipStack.isNotEmpty()) popClip() }
            g = parent
            clipStack.clear()
            clipStack.addAll(savedClips)
            groupStack.clear()
            groupStack.addAll(savedGroups)
            layerBlends = savedBlends
            contentGraphics.dispose()
        }

        val maskGraphics = mask.createGraphics()
        try {
            if (kind == SoftMask.Kind.Luminosity) {
                maskGraphics.color = Color.BLACK
                maskGraphics.fillRect(0, 0, bounds.width, bounds.height)
            }
            prepare(maskGraphics)
            renderMask(AwtCanvas(maskGraphics))
        } finally {
            maskGraphics.dispose()
        }
        if (kind == SoftMask.Kind.Luminosity || transfer != null) {
            // Opaque black backdrop means unpainted pixels have zero luminance.
            val luminosity = kind == SoftMask.Kind.Luminosity
            val pixels = (mask.raster.dataBuffer as DataBufferInt).data
            for (i in pixels.indices) {
                val p = pixels[i]
                val level = if (luminosity) {
                    (((p ushr 16) and 255) * 77 + ((p ushr 8) and 255) * 150 + (p and 255) * 29) ushr 8
                } else p ushr 24
                pixels[i] = (transfer?.get(level) ?: level) shl 24
            }
        }
        transferMaskBackdrop(parent, bounds, content, mask)
    }

    /**
     * Graphics2D does not expose its backing image. A Composite receives the
     * destination raster, allowing a readback without changing the host surface.
     * Opaque coordinate tiles carry explicit indices through any raster tiling
     * or origin translation performed by Java2D. No destination layout or alpha
     * channel is assumed. A null mask copies the backdrop into [content]; the
     * second pass mixes the rendered content with that unchanged backdrop.
     */
    private fun transferMaskBackdrop(
        parent: Graphics2D, bounds: java.awt.Rectangle,
        content: BufferedImage, mask: BufferedImage?,
    ) {
        val contentPixels = (content.raster.dataBuffer as DataBufferInt).data
        val maskPixels = mask?.let { (it.raster.dataBuffer as DataBufferInt).data }
        val tileWidth = minOf(bounds.width, 1024)
        val tileHeight = minOf(bounds.height, 1024)
        val coordinates = BufferedImage(tileWidth, tileHeight, BufferedImage.TYPE_INT_RGB)
        val indices = (coordinates.raster.dataBuffer as DataBufferInt).data
        for (i in indices.indices) indices[i] = i
        val target = parent.create() as Graphics2D
        try {
            target.transform = AffineTransform()
            for (top in 0 until bounds.height step tileHeight) {
                for (left in 0 until bounds.width step tileWidth) {
                    target.composite = object : Composite {
                        override fun createContext(srcColorModel: ColorModel, dstColorModel: ColorModel, hints: RenderingHints?): CompositeContext =
                            object : CompositeContext {
                                override fun dispose() {}
                                override fun compose(src: Raster, dstIn: Raster, dstOut: WritableRaster) {
                                    var sourceData: Any? = null
                                    var destinationData: Any? = null
                                    var outputData: Any? = null
                                    for (y in 0 until minOf(src.height, dstIn.height, dstOut.height)) {
                                        for (x in 0 until minOf(src.width, dstIn.width, dstOut.width)) {
                                            sourceData = src.getDataElements(x + src.minX, y + src.minY, sourceData)
                                            val index = srcColorModel.getRGB(sourceData) and 0xFFFFFF
                                            val position = (top + index / tileWidth) * bounds.width + left + index % tileWidth
                                            destinationData = dstIn.getDataElements(x + dstIn.minX, y + dstIn.minY, destinationData)
                                            if (maskPixels == null) {
                                                contentPixels[position] = dstColorModel.getRGB(destinationData)
                                                dstOut.setDataElements(x + dstOut.minX, y + dstOut.minY, destinationData)
                                            } else {
                                                val coverage = maskPixels[position] ushr 24
                                                val backdrop = dstColorModel.getRGB(destinationData)
                                                if (coverage == 0 || backdrop == contentPixels[position]) {
                                                    // Preserve untouched destination precision, including >8-bit colour models.
                                                    dstOut.setDataElements(x + dstOut.minX, y + dstOut.minY, destinationData)
                                                } else {
                                                    val result = mixMaskedPixel(backdrop, contentPixels[position], coverage)
                                                    outputData = dstColorModel.getDataElements(result, outputData)
                                                    dstOut.setDataElements(x + dstOut.minX, y + dstOut.minY, outputData)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                    }
                    val width = minOf(tileWidth, bounds.width - left)
                    val height = minOf(tileHeight, bounds.height - top)
                    val x = bounds.x + left
                    val y = bounds.y + top
                    target.drawImage(coordinates, x, y, x + width, y + height, 0, 0, width, height, null)
                }
            }
        } finally {
            target.dispose()
        }
    }

    private fun mixMaskedPixel(backdrop: Int, rendered: Int, coverage: Int): Int {
        if (coverage == 0 || backdrop == rendered) return backdrop
        if (coverage == 255) return rendered
        val oldWeight = (backdrop ushr 24) * (255 - coverage)
        val newWeight = (rendered ushr 24) * coverage
        val weight = oldWeight + newWeight
        if (weight == 0) return 0
        fun channel(shift: Int): Int =
            ((((backdrop ushr shift) and 255) * oldWeight +
                ((rendered ushr shift) and 255) * newWeight + weight / 2) / weight)
        return (((weight + 127) / 255) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /**
     * The blend modes the paints of one layer used; [single] is set only when all agree.
     * The layer of a soft clip passes [flush], which composites what the layer holds, so
     * a paint in another mode starts the layer again and its modes never mix (#285).
     */
    private class LayerBlends(private val flush: (() -> Unit)? = null) {
        var single: KiteBlendMode? = null
            private set
        var mixed = false
            private set

        fun record(mode: KiteBlendMode) {
            if (mixed || mode == single) return
            if (single != null && flush != null) flush.invoke()
            if (single == null || flush != null) single = mode else { single = null; mixed = true }
        }

        fun merge(inner: LayerBlends) {
            when {
                // Content that mixed modes lands as one normal paint.
                inner.mixed -> if (flush != null) record(KiteBlendMode.Normal) else { single = null; mixed = true }
                else -> inner.single?.let(::record)
            }
        }
    }

    /** Pixels one soft mask layer may use before it drops to a lower resolution. */
    internal var maskPixelBudget: Long = KITE_DEFAULT_MAX_RASTER_PIXELS

    private companion object {
        /** A mask area this many budgets wide cannot be a page render; it keeps the paint unmasked. */
        const val UNBOUNDED_MASK_FACTOR = 16L

        /** The largest device coordinate of a clip box, so its width still fits in an Int. */
        const val PIXEL_LIMIT = 268_435_456.0

        /** Glyph rasters that one canvas keeps. A page of text uses a few hundred. */
        const val GLYPH_CACHE_ENTRIES = 4096

        /** The largest em, in pixels, whose glyphs go to the cache. A larger glyph is filled as a path. */
        const val GLYPH_CACHE_MAX_EM = 256.0

        /** Host glyph outlines per font and text, shared by every canvas. Cleared when full. */
        val hostOutlines = java.util.concurrent.ConcurrentHashMap<Pair<FontSpec, String>, KitePath>()
        const val HOST_OUTLINE_CACHE_SIZE = 4096

        /** Unhinted outlines with fractional advances, so a glyph keeps its design shape. */
        val OUTLINE_CONTEXT = java.awt.font.FontRenderContext(null, true, true)

        /** A Java 2D shape, whose y runs down, as a [KitePath] whose y runs up. */
        fun outlineOf(shape: java.awt.Shape): KitePath {
            val b = KitePath.Builder()
            val c = DoubleArray(6)
            val it = shape.getPathIterator(null)
            while (!it.isDone) {
                when (it.currentSegment(c)) {
                    java.awt.geom.PathIterator.SEG_MOVETO -> b.moveTo(c[0], -c[1])
                    java.awt.geom.PathIterator.SEG_LINETO -> b.lineTo(c[0], -c[1])
                    java.awt.geom.PathIterator.SEG_QUADTO -> b.quadTo(c[0], -c[1], c[2], -c[3])
                    java.awt.geom.PathIterator.SEG_CUBICTO -> b.curveTo(c[0], -c[1], c[2], -c[3], c[4], -c[5])
                    java.awt.geom.PathIterator.SEG_CLOSE -> b.close()
                }
                it.next()
            }
            return b.build()
        }
    }

    /** The blends of the layer a soft mask or a soft clip is rendering into, or null outside both. */
    private var layerBlends: LayerBlends? = null

    /** Two same-sized buffers survive between masked paints, so a masked page does not reallocate per paint. */
    private val spareMaskBuffers = ArrayList<BufferedImage>(2)

    /** A fully transparent buffer: pooled buffers are cleared when they come back. */
    private fun takeMaskBuffer(width: Int, height: Int): BufferedImage {
        val index = spareMaskBuffers.indexOfFirst { it.width == width && it.height == height }
        return if (index < 0) BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB) else spareMaskBuffers.removeAt(index)
    }

    /** Clears [dirtyRows], the only rows a masked paint wrote, and keeps the buffer for the next paint. */
    private fun giveBackMaskBuffer(buffer: BufferedImage, dirtyRows: IntRange) {
        if (spareMaskBuffers.size >= 2) return
        if (!dirtyRows.isEmpty()) {
            java.util.Arrays.fill((buffer.raster.dataBuffer as DataBufferInt).data,
                dirtyRows.first * buffer.width, (dirtyRows.last + 1) * buffer.width, 0)
        }
        spareMaskBuffers.add(buffer)
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    /**
     * True while the paints go straight to the layer of a knockout group. Against the
     * group's transparent backdrop a paint in any blend mode is its own colour, so it
     * replaces what lies under it, and the anti-aliased edge mixes by coverage (#125).
     */
    private val knockingOut: Boolean get() = groupStack.lastOrNull()?.knockout == true

    private inline fun withComposite(blendMode: KiteBlendMode, alpha: Double, block: () -> Unit) {
        layerBlends?.record(blendMode)
        val saved = g.composite
        val a = alpha.toFloat().coerceIn(0f, 1f)
        g.composite = when {
            knockingOut -> AlphaComposite.Src.derive(a)
            blendMode == KiteBlendMode.Normal -> AlphaComposite.SrcOver.derive(a)
            else -> PdfBlendComposite(blendMode, a)
        }
        try { block() } finally { g.composite = saved }
    }

    /**
     * One path that fills, strokes and glyph fills reuse, since Java 2D keeps no reference
     * to a path it paints. A clip keeps its own path, because the Graphics may keep it (#130).
     */
    private val scratchPath = Path2D.Double(Path2D.WIND_NON_ZERO, 64)

    /**
     * [src] under [ctm] as a Java 2D path: reset into [into], or a new one sized for [src], so
     * a path of any length grows no array while it is built (#130).
     */
    private fun toAwtPath(src: KitePath, ctm: KiteMatrix, into: Path2D.Double? = null): Path2D.Double {
        val out = into?.apply { reset(); windingRule = Path2D.WIND_NON_ZERO } ?: Path2D.Double(Path2D.WIND_NON_ZERO, pointCount(src))
        for (seg in src.segments) {
            when (seg) {
                is KitePath.Segment.MoveTo -> {
                    val x = ctm.transformX(seg.x, seg.y)
                    val y = ctm.transformY(seg.x, seg.y)
                    out.moveTo(x, y)
                }
                is KitePath.Segment.LineTo -> {
                    val x = ctm.transformX(seg.x, seg.y)
                    val y = ctm.transformY(seg.x, seg.y)
                    out.lineTo(x, y)
                }
                is KitePath.Segment.CurveTo -> {
                    val x1 = ctm.transformX(seg.x1, seg.y1)
                    val y1 = ctm.transformY(seg.x1, seg.y1)
                    val x2 = ctm.transformX(seg.x2, seg.y2)
                    val y2 = ctm.transformY(seg.x2, seg.y2)
                    val x3 = ctm.transformX(seg.x3, seg.y3)
                    val y3 = ctm.transformY(seg.x3, seg.y3)
                    out.curveTo(x1, y1, x2, y2, x3, y3)
                }
                is KitePath.Segment.QuadTo -> {
                    val x1 = ctm.transformX(seg.x1, seg.y1)
                    val y1 = ctm.transformY(seg.x1, seg.y1)
                    val x2 = ctm.transformX(seg.x2, seg.y2)
                    val y2 = ctm.transformY(seg.x2, seg.y2)
                    out.quadTo(x1, y1, x2, y2)
                }
                KitePath.Segment.Close -> out.closePath()
            }
        }
        return out
    }

    /** The points [src] stores in a Java 2D path: one a move, line or close, two a quadratic and three a cubic segment. */
    private fun pointCount(src: KitePath): Int {
        var n = 0
        for (seg in src.segments) n += when (seg) {
            is KitePath.Segment.CurveTo -> 3
            is KitePath.Segment.QuadTo -> 2
            else -> 1
        }
        return maxOf(n, 1)
    }

    /**
     * Opaque AWT colour. Constant alpha (`/ca`, `/CA`) is applied exactly once
     * by [withComposite], via `AlphaComposite` for Normal, or
     * [PdfBlendComposite] for the other modes. Baking it into the colour too
     * would apply it twice and wash the paint out.
     */
    private fun RgbColor.toAwt(): Color = Color(
        (r.coerceIn(0.0, 1.0) * 255).toInt(),
        (g.coerceIn(0.0, 1.0) * 255).toInt(),
        (b.coerceIn(0.0, 1.0) * 255).toInt(),
    )

    /** Snapshot of Graphics2D state that needs restoring after a clip / group push. */
}

/**
 * The dash for a Java2D stroke: every element kept, zeros included, since a
 * zero dash under round caps is a dot and a zero gap is solid (ISO 32000-1,
 * 8.4.3.6, #106). Null for an empty or all-zero array, which means solid and
 * is the one array BasicStroke refuses.
 */
internal fun awtDash(dashArray: List<Double>?, scale: Double): FloatArray? {
    val d = dashArray?.map { v -> (v * scale).toFloat().let { if (it.isFinite()) it.coerceAtLeast(0f) else 0f } } ?: return null
    return d.takeIf { it.isNotEmpty() && it.any { v -> v > 0f } }?.toFloatArray()
}
