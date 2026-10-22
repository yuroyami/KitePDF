package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A [KiteCanvas] that paints into [width] by [height] pixels in plain Kotlin, the same on every
 * target. Device space is the pixels: x runs right, y runs down, and (0, 0) is the top left
 * corner of the top left pixel. Edges are anti-aliased.
 *
 * It draws fills, strokes, gradients, images, clips, transparency groups, soft masks and every
 * blend mode, and runs raster steps, so SVG filters work. A text run with outlines paints them.
 * A run without outlines paints the outlines that [hostOutlines] gives, and nothing when it
 * gives none, since this canvas has no fonts of its own.
 *
 * ```kotlin
 * val canvas = KiteRasterCanvas(300, 150)
 * svg.render(canvas, KiteMatrix.IDENTITY)
 * val pixels: KiteRaster = canvas.toRaster()
 * ```
 */
public class KiteRasterCanvas(
    public val width: Int,
    public val height: Int,
    private val hostOutlines: ((text: String, fontSpec: FontSpec) -> KitePath?)? = null,
) : KiteCanvas {

    init {
        require(width >= 0 && height >= 0 && width.toLong() * height <= KITE_DEFAULT_MAX_RASTER_PIXELS) {
            "a raster canvas of $width by $height pixels is too large"
        }
    }

    /** Pixels being drawn, premultiplied ARGB, with the clip depth they started at. */
    private class Layer(val pixels: IntArray, val clipBase: Int)

    /** One clip: the coverage of each pixel, combined with the clips under it in the same layer. */
    private class Clip(val mask: FloatArray, val left: Int, val top: Int, val right: Int, val bottom: Int)

    private val layers = ArrayList<Layer>().apply { add(Layer(IntArray(width * height), 0)) }
    private val clips = ArrayList<Clip?>()
    private val scan = RasterScan(width, height)
    private val images = KiteBitmapCache<IntArray>()

    private val target: Layer get() = layers.last()

    /** The clip that drawing into the current layer goes through, or null for none. */
    private val clip: Clip? get() = if (clips.size > target.clipBase) clips.last() else null

    /** The pixels as straight ARGB. */
    public fun toRaster(): KiteRaster {
        val src = layers.first().pixels
        return KiteRaster(width, height, IntArray(src.size) { RasterBlend.unpremultiply(src[it]) })
    }

    /**
     * The pixels as ARGB with each colour channel premultiplied by the alpha, as they are kept.
     * A browser canvas keeps the same 8-bit values, so its `getImageData` reads from these.
     */
    public fun toPremultipliedRaster(): KiteRaster = KiteRaster(width, height, layers.first().pixels.copyOf())

    /** Fills every pixel with [color], as a page background. */
    public fun clear(color: RgbColor? = null, alpha: Double = 1.0) {
        val p = color?.let { RasterBlend.pack((it.r * alpha).toFloat(), (it.g * alpha).toFloat(), (it.b * alpha).toFloat(), alpha.toFloat()) } ?: 0
        layers.first().pixels.fill(p)
    }

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: KiteMatrix) {}
    override fun endPage() {}

    override fun hostGlyphOutline(text: String, fontSpec: FontSpec): KitePath? = hostOutlines?.invoke(text, fontSpec)

    override fun fillPath(path: KitePath, ctm: KiteMatrix, color: RgbColor, evenOdd: Boolean, alpha: Double, blendMode: KiteBlendMode) {
        scan.add(path, ctm)
        paint(evenOdd, solid(color, alpha), blendMode)
    }

    override fun strokePath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, lineWidth: Double, alpha: Double, blendMode: KiteBlendMode,
        dashArray: List<Double>?, dashPhase: Double, lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) {
        addStroke(path, ctm, lineWidth, dashArray, dashPhase, lineCap, lineJoin, miterLimit)
        paint(false, solid(color, alpha), blendMode)
    }

    /** Adds the outline of the stroke of [path] to the scan, in device pixels. */
    private fun addStroke(
        path: KitePath, ctm: KiteMatrix, lineWidth: Double, dashArray: List<Double>?, dashPhase: Double,
        lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) {
        val pen = strokePen(ctm, lineWidth)
        val dashes = dashArray?.map { it * pen.dashScale }
        val strokeMatrix = pen.strokeMatrix
        if (strokeMatrix == null) {
            val device = transform(path, pen.pathMatrix)
            scan.add(device.strokeOutline(pen.width, lineCap, lineJoin, miterLimit, dashes, dashPhase * pen.dashScale, tolerance = 0.05), KiteMatrix.IDENTITY)
        } else {
            val scale = sqrt(abs(strokeMatrix.a * strokeMatrix.d - strokeMatrix.b * strokeMatrix.c))
            val outline = path.strokeOutline(pen.width, lineCap, lineJoin, miterLimit, dashes, dashPhase * pen.dashScale, tolerance = 0.05 / max(scale, 1e-9))
            scan.add(outline, strokeMatrix)
        }
    }

    override fun fillShading(shading: KiteShading, ctm: KiteMatrix, clipPath: KitePath?, alpha: Double, blendMode: KiteBlendMode) {
        if (paintComplexShading(shading, ctm, clipPath, alpha, blendMode)) return
        val source = gradient(shading, ctm, alpha) ?: return
        val box = shading.bbox
        if (box != null) pushClip(KitePath.Builder().apply { rectangle(box.left, box.bottom, box.right - box.left, box.top - box.bottom) }.build(), ctm, false)
        try {
            if (clipPath != null) scan.add(clipPath, ctm) else scan.add(everything(), KiteMatrix.IDENTITY)
            paint(false, source, blendMode)
        } finally {
            if (box != null) popClip()
        }
    }

    override fun drawGlyphs(
        glyphs: List<TextGlyph>, fontSize: Double, unitsPerEm: Int, hasOutlines: Boolean, fontSpec: FontSpec,
        textToDevice: KiteMatrix, color: RgbColor, alpha: Double, blendMode: KiteBlendMode,
    ) {
        val unitScale = if (hasOutlines) fontSize / unitsPerEm else fontSize / 1000.0
        var pen = 0.0
        var any = false
        for (glyph in glyphs) {
            val outline = if (hasOutlines) glyph.outline else if (glyph.text.isBlank()) null else hostGlyphOutline(glyph.text, fontSpec)
            if (outline != null && !outline.isEmpty()) {
                val m = textToDevice
                    .concat(KiteMatrix.translation(pen + glyph.xOffset * unitScale, glyph.yOffset * unitScale))
                    .concat(KiteMatrix(unitScale, 0.0, 0.0, unitScale, 0.0, 0.0))
                scan.add(outline, m)
                any = true
            }
            pen += glyph.advanceWidth * fontSize / 1000.0 + glyph.advanceAdjust
        }
        if (any) paint(false, solid(color, alpha), blendMode)
    }

    override fun pushClip(path: KitePath, ctm: KiteMatrix, evenOdd: Boolean) {
        val under = clip
        val mask = FloatArray(width * height)
        var left = width; var top = height; var right = 0; var bottom = 0
        scan.add(path, ctm)
        scan.fill(evenOdd, under?.left ?: 0, under?.top ?: 0, under?.right ?: width, under?.bottom ?: height) { y, from, to, coverage ->
            val row = y * width
            for (x in from..to) {
                val c = coverage[x] * (under?.mask?.get(row + x) ?: 1f)
                if (c <= 0f) continue
                mask[row + x] = c
                left = min(left, x); right = max(right, x + 1); top = min(top, y); bottom = max(bottom, y + 1)
            }
        }
        clips.add(if (left < right) Clip(mask, left, top, right, bottom) else Clip(mask, 0, 0, 0, 0))
    }

    override fun popClip() {
        if (clips.size > target.clipBase) clips.removeAt(clips.size - 1)
    }

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double): Unit = drawImage(image, ctm, alpha, KiteBlendMode.Normal)

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double, blendMode: KiteBlendMode) {
        val inverse = ctm.invert() ?: return
        val sampling = imageSampling(image.width, image.height, ctm, image.interpolate)
        val pixels = images.getOrPut(image, sampling, { it.size.toLong() * 4 }) {
            image.toSampledRgbaBytes(sampling)?.let { bytes ->
                IntArray(bytes.size / 4) { i ->
                    RasterBlend.premultiply(
                        (bytes[i * 4 + 3].toInt() and 255 shl 24) or (bytes[i * 4].toInt() and 255 shl 16) or
                            (bytes[i * 4 + 1].toInt() and 255 shl 8) or (bytes[i * 4 + 2].toInt() and 255),
                    )
                }
            }
        }
        pixels ?: return
        val w = sampling.rasterWidth(image.width)
        val h = sampling.rasterHeight(image.height)
        scan.add(KitePath.Builder().apply { rectangle(0.0, 0.0, 1.0, 1.0) }.build(), ctm)
        paint(false, ImageSource(pixels, w, h, inverse, sampling.smooth, alpha.toFloat()), blendMode)
    }

    override fun beginTransparencyGroup(bbox: KiteRectangle, ctm: KiteMatrix, isolated: Boolean, knockout: Boolean, alpha: Double, blendMode: KiteBlendMode) {
        groups.add(GroupState(alpha.toFloat(), blendMode))
        layers.add(Layer(IntArray(width * height), clips.size))
    }

    private class GroupState(val alpha: Float, val blendMode: KiteBlendMode)
    private val groups = ArrayList<GroupState>()

    override fun endTransparencyGroup() {
        val group = groups.removeLastOrNull() ?: return
        val layer = popLayer() ?: return
        composite(layer.pixels, null, group.alpha, group.blendMode)
    }

    override fun applySoftMask(kind: SoftMask.Kind, maskBBox: KiteRectangle, maskCtm: KiteMatrix, render: () -> Unit, renderMask: (KiteCanvas) -> Unit): Unit =
        applySoftMask(kind, maskBBox, maskCtm, null, render, renderMask)

    override fun applySoftMask(
        kind: SoftMask.Kind, maskBBox: KiteRectangle, maskCtm: KiteMatrix, transfer: KiteMaskTransfer?,
        render: () -> Unit, renderMask: (KiteCanvas) -> Unit,
    ) {
        layers.add(Layer(IntArray(width * height), clips.size))
        render()
        val content = popLayer() ?: return
        val luminosity = kind == SoftMask.Kind.Luminosity
        // A luminosity mask composites its group over opaque black (ISO 32000-1, 11.6.5.2).
        layers.add(Layer(IntArray(width * height) { if (luminosity) BLACK else 0 }, clips.size))
        renderMask(this)
        val maskLayer = popLayer() ?: return
        val mask = FloatArray(width * height)
        for (i in mask.indices) {
            val p = maskLayer.pixels[i]
            val level = if (luminosity) {
                ((p ushr 16 and 0xFF) * 0.30 + (p ushr 8 and 0xFF) * 0.59 + (p and 0xFF) * 0.11 + 0.5).toInt()
            } else {
                p ushr 24
            }
            mask[i] = (transfer?.get(level) ?: level) / 255f
        }
        composite(content.pixels, mask, 1f, KiteBlendMode.Normal)
    }

    override fun rasterStep(region: KiteRectangle, ctm: KiteMatrix, step: KiteRasterStep): Boolean {
        val corners = listOf(region.left to region.bottom, region.right to region.bottom, region.left to region.top, region.right to region.top)
        val xs = corners.map { (x, y) -> ctm.transformX(x, y) }
        val ys = corners.map { (x, y) -> ctm.transformY(x, y) }
        val c = clip
        val left = max(floor(xs.min()).toInt(), c?.left ?: 0).coerceAtLeast(0)
        val top = max(floor(ys.min()).toInt(), c?.top ?: 0).coerceAtLeast(0)
        val right = min(ceil(xs.max()).toInt(), c?.right ?: width).coerceAtMost(width)
        val bottom = min(ceil(ys.max()).toInt(), c?.bottom ?: height).coerceAtMost(height)
        if (left >= right || top >= bottom) return true
        val w = right - left
        val h = bottom - top
        val canvas = this
        return step.run(object : KiteRasterScope {
            override val width: Int = w
            override val height: Int = h
            override val toPixels: KiteMatrix = KiteMatrix.translation(-left.toDouble(), -top.toDouble()).concat(ctm)

            override fun backdrop(): KiteRaster = crop(target.pixels, left, top, w, h)

            override fun render(initial: KiteRaster?, content: () -> Unit): KiteRaster {
                val pixels = IntArray(canvas.width * canvas.height)
                if (initial != null) {
                    for (y in 0 until h) for (x in 0 until w) {
                        pixels[(top + y) * canvas.width + left + x] = RasterBlend.premultiply(initial.pixels[y * w + x])
                    }
                }
                layers.add(Layer(pixels, clips.size))
                content()
                val layer = popLayer() ?: return KiteRaster(w, h)
                return crop(layer.pixels, left, top, w, h)
            }

            override fun draw(raster: KiteRaster, alpha: Double, blendMode: KiteBlendMode) {
                val pixels = IntArray(canvas.width * canvas.height)
                for (y in 0 until h) for (x in 0 until w) {
                    pixels[(top + y) * canvas.width + left + x] = RasterBlend.premultiply(raster.pixels[y * w + x])
                }
                composite(pixels, null, alpha.toFloat(), blendMode)
            }
        })
    }

    /** Takes the top layer off, with the clips pushed inside it, or answers null for the page itself. */
    private fun popLayer(): Layer? {
        if (layers.size < 2) return null
        val layer = layers.removeAt(layers.size - 1)
        while (clips.size > layer.clipBase) clips.removeAt(clips.size - 1)
        return layer
    }

    /** Draws premultiplied [pixels], gated by [mask], onto the current layer through its clip. */
    private fun composite(pixels: IntArray, mask: FloatArray?, alpha: Float, mode: KiteBlendMode) {
        val c = clip
        val dst = target.pixels
        val x0 = c?.left ?: 0
        val x1 = c?.right ?: width
        val y0 = c?.top ?: 0
        val y1 = c?.bottom ?: height
        for (y in y0 until y1) {
            val row = y * width
            for (x in x0 until x1) {
                val i = row + x
                val p = pixels[i]
                if (p == 0) continue
                val k = alpha * (mask?.get(i) ?: 1f) * (c?.mask?.get(i) ?: 1f)
                if (k <= 0f) continue
                dst[i] = RasterBlend.over(
                    dst[i], (p ushr 16 and 255) / 255f * k, (p ushr 8 and 255) / 255f * k, (p and 255) / 255f * k,
                    (p ushr 24) / 255f * k, mode,
                )
            }
        }
    }

    private fun crop(pixels: IntArray, left: Int, top: Int, w: Int, h: Int): KiteRaster {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = RasterBlend.unpremultiply(pixels[(top + y) * width + left + x])
        return KiteRaster(w, h, out)
    }

    /** Fills the edges added to the scan with [source], through the clip. */
    private fun paint(evenOdd: Boolean, source: Source, mode: KiteBlendMode) {
        val c = clip
        val dst = target.pixels
        val color = FloatArray(4)
        scan.fill(evenOdd, c?.left ?: 0, c?.top ?: 0, c?.right ?: width, c?.bottom ?: height) { y, from, to, coverage ->
            val row = y * width
            for (x in from..to) {
                val k = coverage[x] * (c?.mask?.get(row + x) ?: 1f)
                if (k <= 0f) continue
                if (!source.at(x + 0.5, y + 0.5, color)) continue
                val i = row + x
                dst[i] = RasterBlend.over(dst[i], color[0] * k, color[1] * k, color[2] * k, color[3] * k, mode)
            }
        }
    }

    /** What a paint lays down at a point: premultiplied red, green, blue and alpha, or nothing. */
    private interface Source {
        fun at(x: Double, y: Double, out: FloatArray): Boolean
    }

    private fun solid(color: RgbColor, alpha: Double): Source {
        val a = alpha.coerceIn(0.0, 1.0).toFloat()
        val r = color.r.coerceIn(0.0, 1.0).toFloat() * a
        val g = color.g.coerceIn(0.0, 1.0).toFloat() * a
        val b = color.b.coerceIn(0.0, 1.0).toFloat() * a
        return object : Source {
            override fun at(x: Double, y: Double, out: FloatArray): Boolean {
                out[0] = r; out[1] = g; out[2] = b; out[3] = a
                return true
            }
        }
    }

    /** An axial or radial gradient under [ctm] (ISO 32000-1, 8.7.4.5.3 and 8.7.4.5.4), or null for another kind. */
    private fun gradient(shading: KiteShading, ctm: KiteMatrix, alpha: Double): Source? {
        val inverse = ctm.invert() ?: return null
        val stops = shading.sampleStops(GRADIENT_STEPS) ?: return null
        val a = alpha.coerceIn(0.0, 1.0).toFloat()
        val table = Array(stops.colors.size) { i ->
            val col = stops.colors[i]
            floatArrayOf(col.r.toFloat() * a, col.g.toFloat() * a, col.b.toFloat() * a, a)
        }
        fun put(t: Double, out: FloatArray) {
            val e = table[(t.coerceIn(0.0, 1.0) * (table.size - 1) + 0.5).toInt()]
            out[0] = e[0]; out[1] = e[1]; out[2] = e[2]; out[3] = e[3]
        }
        return when (shading) {
            is KiteShading.Axial -> {
                val (ax, ay, bx, by) = shading.coords.let { listOf(it[0], it[1], it[2], it[3]) }
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                object : Source {
                    override fun at(x: Double, y: Double, out: FloatArray): Boolean {
                        val u = inverse.transformX(x, y)
                        val v = inverse.transformY(x, y)
                        val t = if (len2 == 0.0) 0.0 else ((u - ax) * dx + (v - ay) * dy) / len2
                        if (t < 0.0 && !shading.extendStart || t > 1.0 && !shading.extendEnd) return false
                        put(t, out)
                        return true
                    }
                }
            }
            is KiteShading.Radial -> {
                val c = shading.coords
                object : Source {
                    override fun at(x: Double, y: Double, out: FloatArray): Boolean {
                        val t = radialT(c, inverse.transformX(x, y), inverse.transformY(x, y), shading.extendStart, shading.extendEnd)
                            ?: return false
                        put(t, out)
                        return true
                    }
                }
            }
            else -> null
        }
    }

    /** The image's pixels mapped by [inverse] from device space to its unit square, row 0 at the top. */
    private class ImageSource(
        private val pixels: IntArray, private val w: Int, private val h: Int,
        private val inverse: KiteMatrix, private val smooth: Boolean, private val alpha: Float,
    ) : Source {
        override fun at(x: Double, y: Double, out: FloatArray): Boolean {
            val u = inverse.transformX(x, y) * w
            val v = (1 - inverse.transformY(x, y)) * h
            val p = if (smooth) bilinear(u - 0.5, v - 0.5) else pixels[v.toInt().coerceIn(0, h - 1) * w + u.toInt().coerceIn(0, w - 1)]
            val k = alpha / 255f
            out[0] = (p ushr 16 and 255) * k
            out[1] = (p ushr 8 and 255) * k
            out[2] = (p and 255) * k
            out[3] = (p ushr 24) * k
            return true
        }

        private fun bilinear(u: Double, v: Double): Int {
            val x0 = floor(u).toInt()
            val y0 = floor(v).toInt()
            val fx = (u - x0).toFloat()
            val fy = (v - y0).toFloat()
            val a = px(x0, y0); val b = px(x0 + 1, y0); val c = px(x0, y0 + 1); val d = px(x0 + 1, y0 + 1)
            var out = 0
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val top = (a ushr shift and 255) * (1 - fx) + (b ushr shift and 255) * fx
                val bottom = (c ushr shift and 255) * (1 - fx) + (d ushr shift and 255) * fx
                out = out or (((top * (1 - fy) + bottom * fy) + 0.5f).toInt().coerceIn(0, 255) shl shift)
            }
            return out
        }

        private fun px(x: Int, y: Int): Int = pixels[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
    }

    private fun everything(): KitePath = KitePath.Builder().apply { rectangle(0.0, 0.0, width.toDouble(), height.toDouble()) }.build()

    private fun transform(path: KitePath, m: KiteMatrix): KitePath {
        val b = KitePath.Builder()
        for (s in path.segments) when (s) {
            is KitePath.Segment.MoveTo -> b.moveTo(m.transformX(s.x, s.y), m.transformY(s.x, s.y))
            is KitePath.Segment.LineTo -> b.lineTo(m.transformX(s.x, s.y), m.transformY(s.x, s.y))
            is KitePath.Segment.QuadTo -> b.quadTo(m.transformX(s.x1, s.y1), m.transformY(s.x1, s.y1), m.transformX(s.x2, s.y2), m.transformY(s.x2, s.y2))
            is KitePath.Segment.CurveTo -> b.curveTo(
                m.transformX(s.x1, s.y1), m.transformY(s.x1, s.y1), m.transformX(s.x2, s.y2), m.transformY(s.x2, s.y2),
                m.transformX(s.x3, s.y3), m.transformY(s.x3, s.y3),
            )
            KitePath.Segment.Close -> b.close()
        }
        return b.build()
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()

        /** Colours a gradient is sampled at, enough that a step is under one level of a byte. */
        const val GRADIENT_STEPS = 1024

        /**
         * The t of the circle that holds ([x], [y]) in the radial shading of [c] (x0, y0, r0,
         * x1, y1, r1): the largest t whose radius is not negative, between 0 and 1 or past an
         * end that extends. Null where no such circle paints.
         */
        fun radialT(c: DoubleArray, x: Double, y: Double, extendStart: Boolean, extendEnd: Boolean): Double? {
            val cdx = c[3] - c[0]
            val cdy = c[4] - c[1]
            val dr = c[5] - c[2]
            val px = x - c[0]
            val py = y - c[1]
            // |p - (c0 + t·cd)| = r0 + t·dr, solved for t.
            val a = cdx * cdx + cdy * cdy - dr * dr
            val b = px * cdx + py * cdy + c[2] * dr
            val cc = px * px + py * py - c[2] * c[2]
            fun ok(t: Double): Boolean = c[2] + t * dr >= 0 && (t in 0.0..1.0 || (t < 0 && extendStart) || (t > 1 && extendEnd))
            if (abs(a) < 1e-12) {
                if (b == 0.0) return null
                val t = cc / (2 * b)
                return if (ok(t)) t else null
            }
            val disc = b * b - a * cc
            if (disc < 0) return null
            val root = sqrt(disc)
            val t1 = (b + root) / a
            val t2 = (b - root) / a
            val hi = max(t1, t2)
            val lo = min(t1, t2)
            return if (ok(hi)) hi else if (ok(lo)) lo else null
        }
    }
}
