package io.github.yuroyami.kitepdf.nativerenderer

import android.graphics.BitmapFactory
import android.graphics.BlendMode as AndroidBlendMode
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteBitmapCache
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteImageSampling
import io.github.yuroyami.kitepdf.core.render.KiteMaskTransfer
import io.github.yuroyami.kitepdf.core.render.gridFitImage
import io.github.yuroyami.kitepdf.core.render.hostTextParts
import io.github.yuroyami.kitepdf.core.render.imageSampling
import io.github.yuroyami.kitepdf.core.render.shrinkArgb
import io.github.yuroyami.kitepdf.core.render.strokePen
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.KiteRasterScope
import io.github.yuroyami.kitepdf.core.render.KiteRasterStep
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
import io.github.yuroyami.kitepdf.core.render.paintComplexShading
import io.github.yuroyami.kitepdf.core.render.sampleStops
import io.github.yuroyami.kitepdf.core.render.toShrunkRgbaBytes

/**
 * [KiteCanvas] backed by [android.graphics.Canvas].
 *
 * This is the right choice on Android when you don't want Compose: pass a
 * `Canvas` from your custom View's `onDraw(Canvas)` override straight into
 * the constructor and the renderer paints into it.
 *
 * Pair with [AndroidPdfBitmapRenderer] for the "render a PDF page into a
 * Bitmap" use case.
 *
 * Blend modes require API 29+ (`Paint.setBlendMode`). The module's minSdk
 * is 29 to match. See :kitepdf-native build.gradle.kts.
 */
public class AndroidNativeCanvas(canvas: AndroidCanvas) : KiteCanvas {

    /** The canvas that paints go to: the host's, or the bitmap of a raster step's render. */
    private var canvas: AndroidCanvas = canvas

    /** Open layers from clip pushes + transparency groups. */
    private var openLayers = 0

    /** The open layers of groups and soft masks alone, whose pixels Android keeps to itself. */
    private var layers = 0

    /**
     * The bitmap the host canvas draws into, as [AndroidPdfBitmapRenderer]'s does, so a raster
     * step can read the backdrop from it. Null for a canvas on screen.
     */
    internal var target: android.graphics.Bitmap? = null

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: KiteMatrix) {
        openLayers = 0
        layers = 0
        groups.clear()
    }

    override fun endPage() {
        // Defensive: well-formed PDFs always pair pushes with pops.
        while (openLayers > 0) {
            canvas.restore()
            openLayers--
        }
        layers = 0
        groups.clear()
    }

    override fun fillPath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, evenOdd: Boolean,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        val p = toAndroidPath(path, ctm, scratchPath).apply {
            fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        }
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            this.color = color.toArgb(alpha)
            applyPaintBlend(blendMode)
        }
        canvas.drawPath(p, paint)
    }

    override fun strokePath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, lineWidth: Double,
        alpha: Double, blendMode: KiteBlendMode,
        dashArray: List<Double>?, dashPhase: Double,
        lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) {
        val pen = strokePen(ctm, lineWidth)
        val p = toAndroidPath(path, pen.pathMatrix, scratchPath)
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            this.color = color.toArgb(alpha)
            strokeWidth = pen.width.toFloat()
            strokeCap = when (lineCap) { 1 -> Paint.Cap.ROUND; 2 -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT }
            strokeJoin = when (lineJoin) { 1 -> Paint.Join.ROUND; 2 -> Paint.Join.BEVEL; else -> Paint.Join.MITER }
            strokeMiter = miterLimit.toFloat().coerceAtLeast(1f)
            if (!dashArray.isNullOrEmpty()) {
                // Dash lengths are user-space units; device px = unit × scale.
                val intervals = scaledDashIntervals(dashArray, pen.dashScale)
                pathEffect = DashPathEffect(intervals, (dashPhase * pen.dashScale).toFloat())
            }
            applyPaintBlend(blendMode)
        }
        val m = pen.strokeMatrix
        if (m == null) {
            canvas.drawPath(p, paint)
        } else {
            // The canvas strokes in local space under the matrix, which draws the elliptical pen.
            canvas.save()
            try {
                canvas.concat(pdfMatrixToAndroid(m))
                canvas.drawPath(p, paint)
            } finally {
                canvas.restore()
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

        val unitScale = fontSize / unitsPerEm  // glyph outlines: font units → text space
        val advanceScale = fontSize / 1000.0   // advances are 1/1000 em, not font units
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            this.color = color.toArgb(alpha)
            applyPaintBlend(blendMode)
        }
        var drewAny = false
        var penX = 0.0
        for (glyph in glyphs) {
            val outline = glyph.outline
            if (outline != null && !outline.isEmpty()) {
                val glyphMatrix = textToDevice
                    .concat(KiteMatrix.translation(penX + glyph.xOffset * unitScale, glyph.yOffset * unitScale))
                    .concat(KiteMatrix(unitScale, 0.0, 0.0, unitScale, 0.0, 0.0))
                val p = toAndroidPath(outline, glyphMatrix, scratchPath).apply { fillType = Path.FillType.WINDING }
                canvas.drawPath(p, paint)
                drewAny = true
            }
            penX += glyph.advanceWidth * advanceScale + glyph.advanceAdjust
        }
        // Embedded font present but produced no glyphs (e.g. a subset we can't
        // decode). Fall back to a system font rather than rendering blank.
        if (!drewAny && glyphs.any { it.text.isNotBlank() }) {
            drawTextViaSystemFont(glyphs, fontSize, fontSpec, textToDevice, color, alpha, blendMode)
        }
    }

    /**
     * Fallback for non-embedded fonts (e.g. the Standard-14). Renders with a
     * platform logical font: zero bundled bytes, since Android already ships
     * Serif / SansSerif / Monospace faces that are metric-compatible stand-ins
     * for Times / Helvetica / Courier. Mirrors AwtCanvas / ComposeCanvas.
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

        canvas.save()
        openLayers++
        try {
            canvas.translate(textMatrix.e.toFloat(), textMatrix.f.toFloat())
            if (rotation != 0.0) canvas.rotate(Math.toDegrees(rotation).toFloat())
            if (sx != sy) canvas.scale((sx / sy).toFloat(), 1f)
            val paint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
                this.color = color.toArgb(alpha)
                typeface = systemFontFor(fontSpec)
                textSize = renderedSize.toFloat()
                fontSpec.language?.let { textLocale = java.util.Locale.forLanguageTag(it) }
                applyPaintBlend(blendMode)
            }
            // Position each glyph by the PDF's OWN advance widths (1/1000 em),
            // not the substitute font's natural metrics, otherwise spacing
            // drifts and glyphs crowd together / overlap. advScale already carries
            // sy (renderedSize), so the text-space spacing adjust needs it too.
            val advScale = renderedSize / 1000.0
            for (part in hostTextParts(glyphs, advScale, sy)) {
                if (part.shaped) {
                    // One string, which Android shapes and orders, so Arabic letters join (#588),
                    // fitted to the width the document gives its glyphs.
                    val natural = paint.measureText(part.text)
                    canvas.save()
                    canvas.translate(part.x.toFloat(), 0f)
                    if (natural > 0f && part.width > 0.0) canvas.scale((part.width / natural).toFloat(), 1f)
                    canvas.drawText(part.text, 0f, 0f, paint)
                    canvas.restore()
                    continue
                }
                var x = part.x
                for (glyph in part.glyphs) {
                    val t = glyph.text
                    if (t.isNotEmpty() && t != " ") canvas.drawText(t, x.toFloat(), 0f, paint)
                    x += glyph.advanceWidth * advScale
                }
            }
        } finally {
            canvas.restore()
            openLayers--
        }
    }

    /**
     * The outline of [text] in the logical font [drawGlyphs] draws a font without
     * embedded outlines in, at 1000 units per em with y up (#85). Null before API 34,
     * which has no way to walk a path.
     */
    override fun hostGlyphOutline(text: String, fontSpec: FontSpec): KitePath? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val path = Path()
        Paint().apply {
            typeface = systemFontFor(fontSpec)
            textSize = 1000f
            fontSpec.language?.let { textLocale = java.util.Locale.forLanguageTag(it) }
        }.getTextPath(text, 0, text.length, 0f, 0f, path)
        val b = KitePath.Builder()
        val it = path.pathIterator
        val p = FloatArray(8)
        while (true) {
            when (it.next(p, 0)) {
                android.graphics.PathIterator.VERB_MOVE -> b.moveTo(p[0].toDouble(), -p[1].toDouble())
                android.graphics.PathIterator.VERB_LINE -> b.lineTo(p[2].toDouble(), -p[3].toDouble())
                // Glyph paths hold no conics; one would draw as its quadratic hull.
                android.graphics.PathIterator.VERB_QUAD, android.graphics.PathIterator.VERB_CONIC ->
                    b.quadTo(p[2].toDouble(), -p[3].toDouble(), p[4].toDouble(), -p[5].toDouble())
                android.graphics.PathIterator.VERB_CUBIC -> b.curveTo(
                    p[2].toDouble(), -p[3].toDouble(), p[4].toDouble(), -p[5].toDouble(), p[6].toDouble(), -p[7].toDouble(),
                )
                android.graphics.PathIterator.VERB_CLOSE -> b.close()
                else -> break
            }
        }
        return b.build()
    }

    /**
     * Map a non-embedded PDF font to an Android logical font (mirrors AwtCanvas's family/style
     * choice). The paint's text locale then picks the CJK fallback face of the font's language,
     * since the logical serif face draws Han characters in the fallback of the device locale (#472).
     */
    private fun systemFontFor(spec: FontSpec): Typeface {
        val base = when (spec.family) {
            KiteFontFamily.Serif -> Typeface.SERIF
            KiteFontFamily.Monospace -> Typeface.MONOSPACE
            KiteFontFamily.SansSerif -> Typeface.SANS_SERIF
        }
        val style = when {
            spec.bold && spec.italic -> Typeface.BOLD_ITALIC
            spec.bold -> Typeface.BOLD
            spec.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(base, style)
    }

    override fun fillShading(
        shading: KiteShading, ctm: KiteMatrix, clipPath: KitePath?,
        alpha: Double, blendMode: KiteBlendMode,
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
        val start = if (extendStart) 0 else 1
        val count = stops.colors.size + start + (if (extendEnd) 0 else 1)
        val colors = IntArray(count) { i ->
            val k = i - start
            if (k in stops.colors.indices) stops.colors[k].toArgb(alpha) else Color.TRANSPARENT
        }
        val positions = FloatArray(count) { i ->
            val k = i - start
            if (k in stops.offsets.indices) stops.offsets[k].toFloat() else if (k < 0) 0f else 1f
        }

        // The gradient is built in shading space and the CTM maps it as a whole, so a
        // non-uniform or skewed CTM turns circles into ellipses and tilts the bands
        // (ISO 32000-1, 8.7.4.5.3 and 8.7.4.5.4). A CTM without an inverse paints nothing.
        val det = ctm.a * ctm.d - ctm.b * ctm.c
        if (det == 0.0 || !det.isFinite()) return
        val c = when (shading) {
            is KiteShading.Axial -> shading.coords
            is KiteShading.Radial -> shading.coords
            else -> return // complex shading types already handled by paintComplexShading
        }
        val shader: Shader = if (shading is KiteShading.Axial) {
            LinearGradient(
                c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat(),
                colors, positions, Shader.TileMode.CLAMP,
            )
        } else {
            // A radial shading runs between two circles (ISO 32000-1, 8.7.4.5.4), which
            // Android draws from API 31 on. The end radius is at least a tenth of a device pixel.
            val r0 = c[2].coerceAtLeast(0.0)
            val r1 = c[5].coerceAtLeast(0.1 / kotlin.math.sqrt(kotlin.math.abs(det)))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RadialGradient(
                    c[0].toFloat(), c[1].toFloat(), r0.toFloat(), c[3].toFloat(), c[4].toFloat(), r1.toFloat(),
                    LongArray(colors.size) { Color.pack(colors[it]) }, positions, Shader.TileMode.CLAMP,
                )
            } else {
                oneCircleGradient(c, r0, r1, colors, positions)
            }
        }
        shader.setLocalMatrix(pdfMatrixToAndroid(ctm))
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            this.shader = shader
            applyPaintBlend(blendMode)
        }
        if (clipPath != null) {
            val cp = toAndroidPath(clipPath, ctm, scratchPath).apply { fillType = Path.FillType.WINDING }
            canvas.drawPath(cp, paint)
        } else {
            canvas.drawPaint(paint)
        }
    }

    /**
     * One circle standing in for a radial shading between two circles, below API 31.
     * It is exact when the circles share a centre. Otherwise it keeps only the end circle.
     */
    private fun oneCircleGradient(c: DoubleArray, r0: Double, r1: Double, colors: IntArray, positions: FloatArray): Shader {
        if (c[0] != c[3] || c[1] != c[4]) {
            return RadialGradient(c[3].toFloat(), c[4].toFloat(), r1.toFloat(), colors, positions, Shader.TileMode.CLAMP)
        }
        // Offset s lies on the circle of radius r0 + s (r1 - r0), as a fraction of the larger radius.
        val outer = maxOf(r0, r1)
        val mapped = FloatArray(positions.size) { ((r0 + positions[it] * (r1 - r0)) / outer).toFloat() }
        val ordered = colors.copyOf()
        if (r1 < r0) { mapped.reverse(); ordered.reverse() }
        return RadialGradient(c[3].toFloat(), c[4].toFloat(), outer.toFloat(), ordered, mapped, Shader.TileMode.CLAMP)
    }

    override fun pushClip(path: KitePath, ctm: KiteMatrix, evenOdd: Boolean) {
        canvas.save()
        openLayers++
        val p = toAndroidPath(path, ctm).apply {
            fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        }
        canvas.clipPath(p)
    }

    override fun popClip() {
        if (openLayers > 0) {
            canvas.restore()
            openLayers--
        }
    }

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double) {
        drawImage(image, ctm, alpha, KiteBlendMode.Normal)
    }

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double, blendMode: KiteBlendMode) {
        // One sampling policy on every canvas (#122, #123). The ctm maps to this canvas's pixels,
        // and the edges of an unrotated image move outwards onto whole pixels, as in MuPDF (#300).
        val device = gridFitImage(ctm)
        val sampling = imageSampling(image.width, image.height, device, image.interpolate)
        val bm = bitmaps.getOrPut(image, sampling, { it.width.toLong() * it.height * 4 }) { decodeImage(image, sampling) }
        if (bm == null) {
            drawPlaceholder(ctm)
            return
        }
        canvas.save()
        openLayers++
        canvas.concat(pdfMatrixToAndroid(device))
        canvas.save()
        // Unit square (0,0)-(1,1), bitmap row 0 on the top edge (v = 1): the
        // Skia mapping. The old (0,-1) square with a positive Y scale drew
        // outside the CTM's image of the unit square, upside down.
        canvas.translate(0f, 1f)
        canvas.scale(1f / bm.width, -1f / bm.height)
        val paint = Paint().apply {
            this.alpha = (alpha.coerceIn(0.0, 1.0) * 255).toInt()
            // Set both ways: the default of this flag differs between Android versions.
            isFilterBitmap = sampling.smooth
            applyPaintBlend(blendMode)
        }
        canvas.drawBitmap(bm, 0f, 0f, paint)
        canvas.restore()
        canvas.restore()
        openLayers--
    }

    /** Bitmaps built from images, so that an image drawn many times converts once (#117). */
    private val bitmaps = KiteBitmapCache<android.graphics.Bitmap>()

    /** The image as a bitmap, averaged down when [sampling] shrinks it, so fine detail fades instead of dropping out (#122). */
    private fun decodeImage(image: KiteImageData, sampling: KiteImageSampling): android.graphics.Bitmap? = try {
        when (image.kind) {
            KiteImageData.Kind.JPEG, KiteImageData.Kind.JPEG2000, KiteImageData.Kind.JBIG2 -> decodeEncoded(image.encodedBytes, sampling)
            // Decoded samples: what every successful JPEG / JPX / JBIG2 decode
            // produces, plus plain Flate images. Straight-alpha RGBA from core.
            // An image drawn smaller converts and shrinks a band of rows at a time (#381).
            // A JPEG whose data KiteImageCodec could not decode goes to BitmapFactory, as above (#475).
            KiteImageData.Kind.RAW -> image.toShrunkRgbaBytes(sampling.shrinkX, sampling.shrinkY)?.let { rgba ->
                rgbaBitmap(rgba, sampling.shrunkWidth(image.width), sampling.shrunkHeight(image.height))
            } ?: image.encodedBytes.takeIf { it.isNotEmpty() }?.let { decodeEncoded(it, sampling) }
            else -> null
        }
    } catch (t: Throwable) {
        null
    }

    /** An encoded image through BitmapFactory, averaged down as [sampling] asks. */
    private fun decodeEncoded(bytes: ByteArray, sampling: KiteImageSampling): android.graphics.Bitmap? {
        // The decoder averages a JPEG down while it decodes, by one power of two for both directions.
        val sample = minOf(sampling.shrinkX, sampling.shrinkY)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { bm ->
            val fx = sampling.shrinkX / sample
            val fy = sampling.shrinkY / sample
            if (fx == 1 && fy == 1) return@let bm
            // getPixels gives straight ARGB.
            val small = shrinkArgb(bm.width, bm.height, fx, fy) { pixels, y, rows ->
                bm.getPixels(pixels, 0, bm.width, 0, y, bm.width, rows)
            }
            rgbaBitmap(small, (bm.width + fx - 1) / fx, (bm.height + fy - 1) / fy).also { bm.recycle() }
        }
    }

    /** Straight RGBA as a bitmap. createBitmap premultiplies on the way in, which is what Canvas wants. */
    private fun rgbaBitmap(rgba: ByteArray, width: Int, height: Int): android.graphics.Bitmap =
        android.graphics.Bitmap.createBitmap(RgbaPixels.toArgbInts(rgba), width, height, android.graphics.Bitmap.Config.ARGB_8888)

    private fun drawPlaceholder(ctm: KiteMatrix) {
        canvas.save()
        canvas.concat(pdfMatrixToAndroid(ctm))
        val fill = Paint().apply { color = 0xFFE0E0E0.toInt(); style = Paint.Style.FILL }
        val stroke = Paint().apply {
            color = 0xFF888888.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.01f
        }
        val rect = Path().apply {
            moveTo(0f, 0f); lineTo(1f, 0f); lineTo(1f, 1f); lineTo(0f, 1f); close()
        }
        canvas.drawPath(rect, fill)
        canvas.drawPath(rect, stroke)
        canvas.restore()
    }

    /**
     * A non-isolated group at full alpha in Normal paints straight onto its backdrop, so its
     * blend modes see what lies under it (ISO 32000-1, 11.4.5, #125). Any other group paints
     * into a layer. An Android layer always starts transparent, which is exact for a
     * non-isolated group only when no paint inside blends. In a knockout group (11.4.6)
     * each paint replaces what lies under it inside its shape.
     */
    override fun beginTransparencyGroup(
        bbox: KiteRectangle, ctm: KiteMatrix,
        isolated: Boolean, knockout: Boolean,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        // A group nested in a knockout group gets a layer, so its own paints do not knock each other out.
        val layered = isolated || knockout || knockingOut || alpha < 1.0 || blendMode != KiteBlendMode.Normal
        groups.addLast(Group(layered, knockout))
        if (!layered) return
        val paint = Paint().apply {
            this.alpha = (alpha.coerceIn(0.0, 1.0) * 255).toInt()
            applyBlendMode(blendMode)
        }
        canvas.saveLayer(null, paint)
        openLayers++
        layers++
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

    private fun Paint.applyPaintBlend(mode: KiteBlendMode) {
        if (knockingOut) blendMode = AndroidBlendMode.SRC else applyBlendMode(mode)
    }

    override fun endTransparencyGroup() {
        if (groups.removeLastOrNull()?.layered != true) return
        if (openLayers > 0) {
            canvas.restore()
            openLayers--
            layers--
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

    override fun applySoftMask(
        kind: SoftMask.Kind,
        maskBBox: KiteRectangle, maskCtm: KiteMatrix,
        transfer: KiteMaskTransfer?,
        render: () -> Unit,
        renderMask: (KiteCanvas) -> Unit,
    ) {
        canvas.saveLayer(null, Paint())
        openLayers++
        layers++
        // The content and the mask composite as usual, also inside a knockout group.
        groups.addLast(Group(layered = false, knockout = false))
        try {
            render()
            val luminosity = kind == SoftMask.Kind.Luminosity
            if (transfer != null && !transfer.isNearlyLinear) {
                // Android has no table colour filter, and the closest line would bend the mask,
                // so gate by the mask group's pixels (#445).
                gateByMaskPixels(luminosity, transfer, renderMask)
                return
            }
            val maskPaint = Paint().apply {
                blendMode = AndroidBlendMode.DST_IN
                // ISO 32000-1, 11.5.3: a luminosity mask gates by the group's brightness,
                // so the layer's luminosity becomes its alpha when it composites (#79).
                if (luminosity || transfer != null) colorFilter = ColorMatrixColorFilter(maskToAlpha(luminosity, transfer))
            }
            canvas.saveLayer(null, maskPaint)
            openLayers++
            layers++
            try {
                // Unpainted parts of the group show the black backdrop, whose luminosity is zero.
                if (luminosity) canvas.drawColor(Color.BLACK)
                renderMask(this)
            } finally {
                canvas.restore()
                openLayers--
                layers--
            }
        } finally {
            groups.removeLastOrNull()
            canvas.restore()
            openLayers--
            layers--
        }
    }

    /**
     * The raster step (#209, #308): its box is [region] under [ctm], which maps to this canvas's
     * pixels, cut to the clip. A render paints into a bitmap of the box. Only a canvas over a
     * known [target] bitmap reads a backdrop, and only outside every layer. Past
     * [STEP_MAX_PIXELS] the step works at a lower resolution, and then reads no backdrop.
     */
    override fun rasterStep(region: KiteRectangle, ctm: KiteMatrix, step: KiteRasterStep): Boolean {
        val b = region.normalized()
        val xs = doubleArrayOf(ctm.transformX(b.left, b.bottom), ctm.transformX(b.right, b.bottom), ctm.transformX(b.left, b.top), ctm.transformX(b.right, b.top))
        val ys = doubleArrayOf(ctm.transformY(b.left, b.bottom), ctm.transformY(b.right, b.bottom), ctm.transformY(b.left, b.top), ctm.transformY(b.right, b.top))
        if (!xs.all { it.isFinite() } || !ys.all { it.isFinite() }) return false
        val clip = android.graphics.Rect()
        val clipped = canvas.getClipBounds(clip)
        if (!clipped) return true
        val left = maxOf(kotlin.math.floor(xs.min()), clip.left.toDouble())
        val top = maxOf(kotlin.math.floor(ys.min()), clip.top.toDouble())
        val right = minOf(kotlin.math.ceil(xs.max()), clip.right.toDouble())
        val bottom = minOf(kotlin.math.ceil(ys.max()), clip.bottom.toDouble())
        if (right <= left || bottom <= top) return true
        val pixels = (right - left) * (bottom - top)
        if (pixels > STEP_MAX_PIXELS * 16) return false
        val scale = if (pixels > STEP_MAX_PIXELS) kotlin.math.sqrt(STEP_MAX_PIXELS / pixels) else 1.0
        return step.run(AndroidRasterScope(left.toInt(), top.toInt(), (right - left).toInt(), (bottom - top).toInt(), scale, ctm))
    }

    /** One raster step over the box of [boxWidth] by [boxHeight] pixels from ([x], [y]), at [scale]. */
    private inner class AndroidRasterScope(
        private val x: Int, private val y: Int, private val boxWidth: Int, private val boxHeight: Int,
        private val scale: Double, ctm: KiteMatrix,
    ) : KiteRasterScope {
        private val host = canvas
        private val layered = layers > 0
        override val width = maxOf(1, kotlin.math.ceil(boxWidth * scale).toInt())
        override val height = maxOf(1, kotlin.math.ceil(boxHeight * scale).toInt())
        override val toPixels: KiteMatrix = KiteMatrix.scaling(scale, scale)
            .concat(KiteMatrix.translation(-x.toDouble(), -y.toDouble())).concat(ctm)

        override fun backdrop(): KiteRaster? {
            val bitmap = target ?: return null
            if (layered || scale != 1.0 || host !== hostCanvas || x < 0 || y < 0 || x + width > bitmap.width || y + height > bitmap.height) return null
            val argb = IntArray(width * height)
            bitmap.getPixels(argb, 0, width, x, y, width, height)
            return KiteRaster(width, height, argb)
        }

        override fun render(initial: KiteRaster?, content: () -> Unit): KiteRaster {
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                if (initial != null) {
                    require(initial.width == width && initial.height == height) { "the initial raster has another size" }
                    bitmap.setPixels(initial.pixels, 0, width, 0, 0, width, height)
                }
                val offscreen = AndroidCanvas(bitmap)
                offscreen.scale(scale.toFloat(), scale.toFloat())
                offscreen.translate(-x.toFloat(), -y.toFloat())
                val savedCanvas = canvas
                val savedOpen = openLayers
                val savedLayers = layers
                val savedGroups = groups.toList()
                try {
                    canvas = offscreen
                    openLayers = 0
                    layers = 0
                    groups.clear()
                    content()
                } finally {
                    canvas = savedCanvas
                    openLayers = savedOpen
                    layers = savedLayers
                    groups.clear()
                    groups.addAll(savedGroups)
                }
                val argb = IntArray(width * height)
                bitmap.getPixels(argb, 0, width, 0, 0, width, height)
                return KiteRaster(width, height, argb)
            } finally {
                bitmap.recycle()
            }
        }

        override fun draw(raster: KiteRaster, alpha: Double, blendMode: KiteBlendMode) {
            require(raster.width == width && raster.height == height) { "the raster has another size" }
            // createBitmap premultiplies the straight pixels on the way in, as Canvas wants.
            val bitmap = android.graphics.Bitmap.createBitmap(raster.pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val paint = Paint().apply {
                    this.alpha = (alpha.coerceIn(0.0, 1.0) * 255).toInt()
                    isFilterBitmap = scale != 1.0
                    applyBlendMode(blendMode)
                }
                host.drawBitmap(bitmap, null, android.graphics.Rect(x, y, x + boxWidth, y + boxHeight), paint)
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** The canvas this one was made with, which draws into [target] when there is one. */
    private val hostCanvas: AndroidCanvas = canvas

    /**
     * Gates the open layer by a soft mask whose [transfer] only a table gives: draws the mask
     * group into a bitmap of the whole canvas, turns its pixels into mask values through the
     * table (see [KiteMaskTransfer.toMaskAlpha]), and keeps the layer only where that bitmap is.
     * The whole canvas, because the mask outside its group is the value of the backdrop. A large
     * canvas draws the mask at a lower resolution and scales it up.
     */
    private fun gateByMaskPixels(luminosity: Boolean, transfer: KiteMaskTransfer, renderMask: (KiteCanvas) -> Unit) {
        val width = canvas.width
        val height = canvas.height
        if (width <= 0 || height <= 0) return
        val pixels = width.toDouble() * height
        val scale = if (pixels <= MASK_MAX_PIXELS) 1f else kotlin.math.sqrt(MASK_MAX_PIXELS / pixels).toFloat()
        val w = kotlin.math.ceil(width * scale).toInt()
        val h = kotlin.math.ceil(height * scale).toInt()
        val group = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val offscreen = AndroidCanvas(group)
            // Unpainted parts of a luminosity group show the black backdrop, whose luminosity is zero.
            if (luminosity) offscreen.drawColor(Color.BLACK)
            offscreen.scale(scale, scale)
            renderMask(AndroidNativeCanvas(offscreen))
            val argb = IntArray(w * h)
            group.getPixels(argb, 0, w, 0, 0, w, h)
            transfer.toMaskAlpha(argb, luminosity)
            group.setPixels(argb, 0, w, 0, 0, w, h)
            canvas.save()
            canvas.scale(1f / scale, 1f / scale)
            canvas.drawBitmap(group, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = AndroidBlendMode.DST_IN })
            canvas.restore()
        } finally {
            group.recycle()
        }
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    /**
     * One path that fills, strokes, glyphs and shadings reuse, since a draw copies the path it
     * paints. A clip builds its own (#130).
     */
    private val scratchPath = Path()

    /** [src] under [ctm], rewound into [into] or in a new path. */
    private fun toAndroidPath(src: KitePath, ctm: KiteMatrix, into: Path? = null): Path {
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
                    out.quadTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
                }
                KitePath.Segment.Close -> out.close()
            }
        }
        return out
    }

    private fun pdfMatrixToAndroid(m: KiteMatrix): Matrix = Matrix().apply {
        setValues(
            floatArrayOf(
                m.a.toFloat(), m.c.toFloat(), m.e.toFloat(),
                m.b.toFloat(), m.d.toFloat(), m.f.toFloat(),
                0f, 0f, 1f,
            ),
        )
    }

    private fun RgbColor.toArgb(alpha: Double): Int = Color.argb(
        (alpha.coerceIn(0.0, 1.0) * 255).toInt(),
        (r.coerceIn(0.0, 1.0) * 255).toInt(),
        (g.coerceIn(0.0, 1.0) * 255).toInt(),
        (b.coerceIn(0.0, 1.0) * 255).toInt(),
    )

    private fun Paint.applyBlendMode(mode: KiteBlendMode) {
        // API 29+: minSdk for :kitepdf-native is 29, so this is unconditional.
        blendMode = when (mode) {
            KiteBlendMode.Normal -> AndroidBlendMode.SRC_OVER
            KiteBlendMode.Multiply -> AndroidBlendMode.MULTIPLY
            KiteBlendMode.Screen -> AndroidBlendMode.SCREEN
            KiteBlendMode.Overlay -> AndroidBlendMode.OVERLAY
            KiteBlendMode.Darken -> AndroidBlendMode.DARKEN
            KiteBlendMode.Lighten -> AndroidBlendMode.LIGHTEN
            KiteBlendMode.ColorDodge -> AndroidBlendMode.COLOR_DODGE
            KiteBlendMode.ColorBurn -> AndroidBlendMode.COLOR_BURN
            KiteBlendMode.HardLight -> AndroidBlendMode.HARD_LIGHT
            KiteBlendMode.SoftLight -> AndroidBlendMode.SOFT_LIGHT
            KiteBlendMode.Difference -> AndroidBlendMode.DIFFERENCE
            KiteBlendMode.Exclusion -> AndroidBlendMode.EXCLUSION
            KiteBlendMode.Hue -> AndroidBlendMode.HUE
            KiteBlendMode.Saturation -> AndroidBlendMode.SATURATION
            KiteBlendMode.Color -> AndroidBlendMode.COLOR
            KiteBlendMode.Luminosity -> AndroidBlendMode.LUMINOSITY
        }
    }
}

/** The most pixels the bitmap of a mask group has when the canvas gates by pixels: 4 MB of them. */
private const val MASK_MAX_PIXELS = 1_048_576.0

/** The most pixels a raster step works on before it drops to a lower resolution: 16 MB of them. */
private const val STEP_MAX_PIXELS = 4_194_304.0

/**
 * A colour matrix that turns the mask layer into alpha: the luminosity 0.30 R + 0.59 G + 0.11 B
 * when [luminosity] is true, or else the alpha itself. A matrix can only scale and offset, so
 * [transfer] applies as the straight line closest to its table, which the canvas uses only when
 * that line is within a level of the table. The offset is in levels from 0 to 255.
 */
private fun maskToAlpha(luminosity: Boolean, transfer: KiteMaskTransfer?): FloatArray {
    val slope = (transfer?.slope ?: 1.0).toFloat()
    val offset = ((transfer?.offset ?: 0.0) * 255).toFloat()
    return if (luminosity) {
        floatArrayOf(
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0.30f * slope, 0.59f * slope, 0.11f * slope, 0f, offset,
        )
    } else {
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, slope, offset,
        )
    }
}
