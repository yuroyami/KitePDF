package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.core.render.paintComplexShading
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteBitmapCache
import io.github.yuroyami.kitepdf.core.render.KiteImageSampling
import io.github.yuroyami.kitepdf.core.render.KiteMaskTransfer
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
import io.github.yuroyami.kitepdf.core.render.gridFitImage
import io.github.yuroyami.kitepdf.core.render.imageSampling
import io.github.yuroyami.kitepdf.core.render.sampleStops
import io.github.yuroyami.kitepdf.core.render.shrinkRgba
import io.github.yuroyami.kitepdf.core.render.strokePen
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import org.jetbrains.skia.BlendMode as SkiaBlendMode
import org.jetbrains.skia.Canvas as SkCanvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Font
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
import org.jetbrains.skia.PathVerb
import org.jetbrains.skia.Shader
import org.jetbrains.skia.TextBlob
import org.jetbrains.skia.Gradient
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/**
 * [KiteCanvas] backed by a raw [org.jetbrains.skia.Canvas] (Skiko).
 *
 * Same rendering engine Compose Multiplatform rides on for JVM Desktop and
 * iOS, minus the Compose runtime. Pure-Skia means this adapter is the
 * right choice for:
 *
 *  - **Server-side rasterization**: PDF → PNG, headless thumbnail
 *    generation, CI pipelines.
 *  - **CLI tools**: anything that needs pixels without adding
 *    androidx.compose.runtime.
 *  - **Smaller dependency footprint**: Skiko alone is significantly less
 *    than Compose Multiplatform.
 *
 * Pair with [PdfPageRasterizer] for the common "give me a `ByteArray` of a
 * page's PNG" use case.
 */
public class SkiaCanvas(private val canvas: SkCanvas) : KiteCanvas {

    /** Count of open transparency groups + soft-mask layers, for endPage cleanup. */
    private var openLayers = 0

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: KiteMatrix) {
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
        path: KitePath, ctm: KiteMatrix, color: RgbColor, evenOdd: Boolean,
        alpha: Double, blendMode: KiteBlendMode,
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
        path: KitePath, ctm: KiteMatrix, color: RgbColor, lineWidth: Double,
        alpha: Double, blendMode: KiteBlendMode,
        dashArray: List<Double>?, dashPhase: Double,
        lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) {
        val pen = strokePen(ctm, lineWidth)
        val sk = toSkPath(path, pen.pathMatrix)
        val paint = Paint().apply {
            this.color = color.toArgb(alpha)
            this.mode = PaintMode.STROKE
            this.strokeWidth = pen.width.toFloat()
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
                val scaled = da.map { (it * pen.dashScale).toFloat().coerceAtLeast(0f) }
                val intervals = if (scaled.size % 2 == 0) scaled else scaled + scaled
                if (intervals.isNotEmpty() && intervals.sum() > 0f) {
                    this.pathEffect = PathEffect.makeDash(intervals.toFloatArray(), (dashPhase * pen.dashScale).toFloat())
                }
            }
        }
        val m = pen.strokeMatrix
        if (m == null) {
            canvas.drawPath(sk, paint)
        } else {
            // Skia strokes in local space under the matrix, which draws the elliptical pen.
            canvas.save()
            try {
                canvas.concat(pdfMatrixToSkia(m))
                canvas.drawPath(sk, paint)
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
                    .concat(KiteMatrix.translation(penX + glyph.xOffset * unitScale, glyph.yOffset * unitScale))
                    .concat(KiteMatrix(unitScale, 0.0, 0.0, unitScale, 0.0, 0.0))
                val sk = toSkPath(outline, glyphMatrix).apply { fillMode = PathFillMode.WINDING }
                canvas.drawPath(sk, paint)
            }
            penX += glyph.advanceWidth * advanceScale + glyph.advanceAdjust
        }
    }

    /**
     * System-font fallback for fonts without embedded outlines (Standard-14
     * Helvetica/Times/Courier etc). Decode to text, pick a host typeface by
     * family, and draw the run with the text matrix's scale/rotation applied
     * (baseline at the matrix origin). Each glyph starts where the document's
     * own advances put it, as on AWT.
     */
    private fun drawTextViaSystemFont(
        glyphs: List<TextGlyph>, fontSize: Double, fontSpec: FontSpec,
        textMatrix: KiteMatrix, color: RgbColor, alpha: Double, blendMode: KiteBlendMode,
    ) {
        if (glyphs.all { it.text.isEmpty() }) return
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
        // A null typeface would draw nothing at all, silently, so skip the run
        // instead and leave the warning trail a blank page never gives.
        val typeface = systemTypeface(fontSpec) ?: return
        val skFont = Font(typeface, renderedSize)
        // renderedSize already carries sy, so the text-space adjustment needs it too.
        val run = placedRun(glyphs, skFont, renderedSize / 1000.0, sy) ?: return
        canvas.save()
        try {
            canvas.translate(textMatrix.e.toFloat(), textMatrix.f.toFloat())
            if (rotationDeg != 0f) canvas.rotate(rotationDeg)
            if (sx != sy && sy != 0.0) canvas.scale((sx / sy).toFloat(), 1f)
            canvas.drawTextBlob(run, 0f, 0f, paint)
        } finally {
            canvas.restore()
        }
    }

    /**
     * The characters of [glyphs] in [font], each glyph placed where the document's own
     * advances put it (ISO 32000-1, 9.4.4), character and word spacing included (#121).
     * The characters of one glyph, such as a ligature, keep the host face's spacing.
     */
    private fun placedRun(glyphs: List<TextGlyph>, font: Font, advanceScale: Double, adjustScale: Double): TextBlob? {
        val capacity = glyphs.sumOf { it.text.length }
        if (capacity == 0) return null
        val codePoints = IntArray(capacity)
        // The pen position of the glyph a character starts, or NaN for a later character of the same glyph.
        val origins = DoubleArray(capacity)
        var n = 0
        var penX = 0.0
        for (glyph in glyphs) {
            val t = glyph.text
            var i = 0
            while (i < t.length) {
                val pair = t[i].isHighSurrogate() && i + 1 < t.length && t[i + 1].isLowSurrogate()
                codePoints[n] = if (pair) 0x10000 + ((t[i].code - 0xD800) shl 10) + (t[i + 1].code - 0xDC00) else t[i].code
                origins[n] = if (i == 0) penX else Double.NaN
                n++
                i += if (pair) 2 else 1
            }
            penX += glyph.advanceWidth * advanceScale + glyph.advanceAdjust * adjustScale
        }
        val ids = font.getUTF32Glyphs(codePoints.copyOf(n))
        val widths = font.getWidths(ids)
        val xs = FloatArray(n)
        var x = 0.0
        for (k in 0 until n) {
            x = if (origins[k].isNaN()) x + widths[k - 1] else origins[k]
            xs[k] = x.toFloat()
        }
        return TextBlob.makeFromPosH(ids, xs, 0f, font)
    }

    /**
     * The outline of [text] in the host typeface [drawGlyphs] draws a font without
     * embedded outlines in, at 1000 units per em with y up (#85).
     */
    override fun hostGlyphOutline(text: String, fontSpec: FontSpec): KitePath? {
        val typeface = systemTypeface(fontSpec) ?: return null
        val font = Font(typeface, 1000f)
        val ids = font.getStringGlyphs(text)
        val advances = font.getWidths(ids)
        val b = KitePath.Builder()
        var penX = 0.0
        for (k in ids.indices) {
            font.getPath(ids[k])?.let { appendFlipped(b, it, penX) }
            penX += advances[k]
        }
        return b.build()
    }

    /** Appends a Skia glyph path, whose y runs down, moved right by [dx] and with y up. */
    private fun appendFlipped(b: KitePath.Builder, path: SkPath, dx: Double) {
        fun x(p: org.jetbrains.skia.Point?) = dx + (p?.x ?: 0f)
        fun y(p: org.jetbrains.skia.Point?) = -(p?.y ?: 0f).toDouble()
        for (seg in path) {
            if (seg == null) continue
            when (seg.verb) {
                PathVerb.MOVE -> b.moveTo(x(seg.p0), y(seg.p0))
                PathVerb.LINE -> b.lineTo(x(seg.p1), y(seg.p1))
                // Glyph paths hold no conics; one would draw as its quadratic hull.
                PathVerb.QUAD, PathVerb.CONIC -> b.quadTo(x(seg.p1), y(seg.p1), x(seg.p2), y(seg.p2))
                PathVerb.CUBIC -> b.curveTo(x(seg.p1), y(seg.p1), x(seg.p2), y(seg.p2), x(seg.p3), y(seg.p3))
                PathVerb.CLOSE -> b.close()
                PathVerb.DONE -> {}
            }
        }
    }

    private fun systemTypeface(spec: FontSpec): Typeface? {
        val style = when {
            spec.bold && spec.italic -> FontStyle.BOLD_ITALIC
            spec.bold -> FontStyle.BOLD
            spec.italic -> FontStyle.ITALIC
            else -> FontStyle.NORMAL
        }
        return SkiaSystemFonts.resolve(spec.family, style)
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

        // PDF /Extend [start end] controls whether the shading keeps painting
        // past t<0 (start) and t>1 (end) with the terminal colours. Skia's
        // gradient tile mode is a single flag covering both ends:
        //   CLAMP → both ends extend with the terminal colour (t outside [0,1]).
        //   DECAL → nothing is painted outside [0,1] (transparent).
        // We can express both-extend and neither-extend exactly. A one-sided
        // extend can't be captured by a single tile mode, so we approximate:
        // give the extending side its colour by adding a transparent stop just
        // past the non-extending end (so that end fades out) and keep CLAMP.
        // CLAMP then holds the extending end's colour and the injected
        // transparent stop suppresses the other end.
        val (extendStart, extendEnd) = when (shading) {
            is KiteShading.Axial -> shading.extendStart to shading.extendEnd
            is KiteShading.Radial -> shading.extendStart to shading.extendEnd
            is KiteShading.Unsupported -> return
            else -> return // complex shading types already handled by paintComplexShading
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

        // The gradient is built in shading space and the CTM maps it as a whole, so a
        // non-uniform or skewed CTM turns circles into ellipses and tilts the bands
        // (ISO 32000-1, 8.7.4.5.3 and 8.7.4.5.4). A CTM without an inverse paints nothing.
        val det = ctm.a * ctm.d - ctm.b * ctm.c
        if (det == 0.0 || !det.isFinite()) return
        val toDevice = pdfMatrixToSkia(ctm)
        val shader: Shader = when (shading) {
            is KiteShading.Axial -> {
                val c = shading.coords
                Shader.makeLinearGradient(
                    c[0].toFloat(), c[1].toFloat(),
                    c[2].toFloat(), c[3].toFloat(),
                    gradient, toDevice,
                )
            }
            is KiteShading.Radial -> {
                // True PDF two-circle radial via a two-point conical gradient.
                // The outer radius is at least a tenth of a device pixel.
                val c = shading.coords
                val minRadius = 0.1 / kotlin.math.sqrt(kotlin.math.abs(det))
                Shader.makeTwoPointConicalGradient(
                    c[0].toFloat(), c[1].toFloat(), c[2].coerceAtLeast(0.0).toFloat(),
                    c[3].toFloat(), c[4].toFloat(), c[5].coerceAtLeast(minRadius).toFloat(),
                    gradient, toDevice,
                )
            }
            is KiteShading.Unsupported -> return
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
            // `sh` operator over the whole device area: paint a huge rect.
            canvas.drawPaint(paint)
        }
    }

    override fun pushClip(path: KitePath, ctm: KiteMatrix, evenOdd: Boolean) {
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

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double) {
        drawImage(image, ctm, alpha, KiteBlendMode.Normal)
    }

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double, blendMode: KiteBlendMode) {
        // One sampling policy on every canvas (#122, #123), read from the whole transform to device pixels.
        val m = canvas.localToDeviceAsMatrix33.makeConcat(pdfMatrixToSkia(ctm)).mat
        // The edges of an unrotated image move outwards onto whole pixels, as in MuPDF (#300).
        val device = gridFitImage(
            KiteMatrix(m[0].toDouble(), m[3].toDouble(), m[1].toDouble(), m[4].toDouble(), m[2].toDouble(), m[5].toDouble()),
        )
        val sampling = imageSampling(image.width, image.height, device, image.interpolate)
        // Skia decodes JPEG natively + JP2/JPEG-2000 where the platform shim
        // supports it. Other kinds fall back to a placeholder rectangle.
        val sk = images.getOrPut(image, sampling, { it.width.toLong() * it.height * 4 }) { imageFor(image, sampling) }
        if (sk == null) {
            drawPlaceholder(ctm)
            return
        }
        // PDF image space is the unit square (0,0)-(1,1) before the CTM; the
        // CTM maps that square to device coords. Below we lay the bitmap into
        // that unit square (upright) and let the concatenated CTM place it.
        canvas.save()
        openLayers++
        canvas.setMatrix(pdfMatrixToSkia(device))
        // Map the bitmap onto the PDF image unit square [0,1]². The bitmap's
        // row 0 is its top edge (v=1), last row is v=0. So translate up by 1
        // then flip Y (negative Y scale) to land the image upright inside the
        // unit square, matching AwtCanvas' documented mapping. The earlier
        // translate(0,-1)+positive-Y scale both mis-placed and flipped it.
        val paint = Paint().apply {
            this.alpha = alpha.toFloat().coerceIn(0f, 1f).let { (it * 255).toInt() }
            this.blendMode = blendMode.toSkia()
        }
        val mode = when {
            // An encoded image is not averaged above, so Skia's mipmaps average it.
            sampling.shrinks && image.kind != KiteImageData.Kind.RAW -> FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)
            sampling.smooth -> SamplingMode.LINEAR
            else -> SamplingMode.DEFAULT
        }
        canvas.save()
        canvas.translate(0f, 1f)
        canvas.scale(1f / sk.width, -1f / sk.height)
        val bounds = Rect.makeWH(sk.width.toFloat(), sk.height.toFloat())
        canvas.drawImageRect(sk, bounds, bounds, mode, paint, false)
        canvas.restore()
        canvas.restore()
        openLayers--
    }

    /** Bitmaps built from images, so that an image drawn many times converts once (#117). */
    private val images = KiteBitmapCache<Image>()

    /** The image as a Skia image, averaged down when [sampling] shrinks a RAW image. */
    private fun imageFor(image: KiteImageData, sampling: KiteImageSampling): Image? = when (image.kind) {
        KiteImageData.Kind.JPEG, KiteImageData.Kind.JPEG2000, KiteImageData.Kind.JBIG2 -> try {
            Image.makeFromEncoded(image.encodedBytes)
        } catch (t: Throwable) {
            null
        }
        KiteImageData.Kind.RAW -> try {
            image.toRgbaBytes()?.let { rgba ->
                // An image drawn smaller than its pixels is averaged down first, so fine detail fades instead of dropping out.
                val w = sampling.shrunkWidth(image.width)
                val h = sampling.shrunkHeight(image.height)
                val pixels = if (sampling.shrinks) shrinkRgba(rgba, image.width, image.height, sampling.shrinkX, sampling.shrinkY) else rgba
                // toRgbaBytes() emits straight (non-premultiplied) R,G,B,A
                // per pixel, matching RGBA_8888. UNPREMUL honours the alpha
                // channel (SMask alpha, ImageMask stencil transparency);
                // OPAQUE would discard it, rendering masks as solid black.
                Image.makeRaster(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), pixels, w * 4)
            }
        } catch (t: Throwable) {
            null
        }
        else -> null
    }

    private fun drawPlaceholder(ctm: KiteMatrix) {
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
        bbox: KiteRectangle, ctm: KiteMatrix,
        isolated: Boolean, knockout: Boolean,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        // A non-isolated group at full alpha in Normal paints straight onto its backdrop, so its blend
        // modes see what lies under it. A layer would isolate it (ISO 32000-1, 11.4.5, #125).
        val layered = isolated || alpha < 1.0 || blendMode != KiteBlendMode.Normal
        groupLayers.addLast(layered)
        if (!layered) return
        val paint = Paint().apply {
            this.alpha = (alpha.coerceIn(0.0, 1.0) * 255).toInt()
            this.blendMode = blendMode.toSkia()
        }
        canvas.saveLayer(null, paint)
        openLayers++
    }

    /** For each open group, whether it opened a layer. */
    private val groupLayers = ArrayDeque<Boolean>()

    override fun endTransparencyGroup() {
        if (groupLayers.removeLastOrNull() != true) return
        if (openLayers > 0) {
            canvas.restore()
            openLayers--
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
            // luminance, so unpainted areas (luminance 0) mask fully out. We
            // realise that with the LUMA colour filter, which maps each pixel's
            // luminance into its alpha, applied as the layer's restore paint.
            // The /TR table then maps the alpha, also where the group paints nothing.
            val table = transfer?.let { org.jetbrains.skia.ColorFilter.makeTableARGB(it.toByteArray(), null, null, null) }
            val maskPaint = Paint().apply {
                blendMode = SkiaBlendMode.DST_IN
                colorFilter = when {
                    kind == SoftMask.Kind.Alpha -> table
                    table != null -> org.jetbrains.skia.ColorFilter.makeComposed(table, org.jetbrains.skia.ColorFilter.luma)
                    else -> org.jetbrains.skia.ColorFilter.luma
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

    private fun toSkPath(src: KitePath, ctm: KiteMatrix): SkPath {
        // skiko 0.148: Path is immutable; build via PathBuilder then snapshot().
        val b = PathBuilder()
        for (seg in src.segments) {
            when (seg) {
                is KitePath.Segment.MoveTo -> {
                    val x = ctm.transformX(seg.x, seg.y)
                    val y = ctm.transformY(seg.x, seg.y)
                    b.moveTo(x.toFloat(), y.toFloat())
                }
                is KitePath.Segment.LineTo -> {
                    val x = ctm.transformX(seg.x, seg.y)
                    val y = ctm.transformY(seg.x, seg.y)
                    b.lineTo(x.toFloat(), y.toFloat())
                }
                is KitePath.Segment.CurveTo -> {
                    val x1 = ctm.transformX(seg.x1, seg.y1)
                    val y1 = ctm.transformY(seg.x1, seg.y1)
                    val x2 = ctm.transformX(seg.x2, seg.y2)
                    val y2 = ctm.transformY(seg.x2, seg.y2)
                    val x3 = ctm.transformX(seg.x3, seg.y3)
                    val y3 = ctm.transformY(seg.x3, seg.y3)
                    b.cubicTo(
                        x1.toFloat(), y1.toFloat(),
                        x2.toFloat(), y2.toFloat(),
                        x3.toFloat(), y3.toFloat(),
                    )
                }
                is KitePath.Segment.QuadTo -> {
                    val x1 = ctm.transformX(seg.x1, seg.y1)
                    val y1 = ctm.transformY(seg.x1, seg.y1)
                    val x2 = ctm.transformX(seg.x2, seg.y2)
                    val y2 = ctm.transformY(seg.x2, seg.y2)
                    b.quadTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
                }
                KitePath.Segment.Close -> b.closePath()
            }
        }
        return b.snapshot()
    }

    private fun pdfMatrixToSkia(m: KiteMatrix): Matrix33 = Matrix33(
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
    private fun KiteBlendMode.toSkia(): SkiaBlendMode = when (this) {
        KiteBlendMode.Normal -> SkiaBlendMode.SRC_OVER
        KiteBlendMode.Multiply -> SkiaBlendMode.MULTIPLY
        KiteBlendMode.Screen -> SkiaBlendMode.SCREEN
        KiteBlendMode.Overlay -> SkiaBlendMode.OVERLAY
        KiteBlendMode.Darken -> SkiaBlendMode.DARKEN
        KiteBlendMode.Lighten -> SkiaBlendMode.LIGHTEN
        KiteBlendMode.ColorDodge -> SkiaBlendMode.COLOR_DODGE
        KiteBlendMode.ColorBurn -> SkiaBlendMode.COLOR_BURN
        KiteBlendMode.HardLight -> SkiaBlendMode.HARD_LIGHT
        KiteBlendMode.SoftLight -> SkiaBlendMode.SOFT_LIGHT
        KiteBlendMode.Difference -> SkiaBlendMode.DIFFERENCE
        KiteBlendMode.Exclusion -> SkiaBlendMode.EXCLUSION
        KiteBlendMode.Hue -> SkiaBlendMode.HUE
        KiteBlendMode.Saturation -> SkiaBlendMode.SATURATION
        KiteBlendMode.Color -> SkiaBlendMode.COLOR
        KiteBlendMode.Luminosity -> SkiaBlendMode.LUMINOSITY
    }
}
