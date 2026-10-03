package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteRaster
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The pixels a filter primitive reads and writes: [width] by [height] pixels of premultiplied
 * RGBA floats from 0 to 1, row by row from the top. [linear] says whether the colours are in
 * linearRGB or in sRGB, the two spaces of `color-interpolation-filters` (Filter Effects 1, 17.6).
 * Floats keep the precision a chain of primitives and a trip through linearRGB need.
 */
internal class FilterImage(
    val width: Int,
    val height: Int,
    val linear: Boolean,
    val data: FloatArray = FloatArray(width * height * 4),
) {

    fun copy(): FilterImage = FilterImage(width, height, linear, data.copyOf())

    /** This image in linearRGB when [toLinear], else in sRGB: itself when it already is. */
    fun inSpace(toLinear: Boolean): FilterImage {
        if (toLinear == linear) return this
        val out = FilterImage(width, height, toLinear)
        val d = data
        val o = out.data
        var i = 0
        while (i < d.size) {
            val a = d[i + 3]
            if (a > 0f) {
                for (c in 0 until 3) {
                    val v = (d[i + c] / a).coerceIn(0f, 1f)
                    o[i + c] = (if (toLinear) toLinear(v) else toSrgb(v)) * a
                }
                o[i + 3] = a
            }
            i += 4
        }
        return out
    }

    /** Clears every pixel outside [region], a rectangle of pixel edges. */
    fun keepOnly(region: PixelRect) {
        val x0 = region.x0.coerceIn(0, width)
        val x1 = region.x1.coerceIn(x0, width)
        val y0 = region.y0.coerceIn(0, height)
        val y1 = region.y1.coerceIn(y0, height)
        for (y in 0 until height) {
            val row = y * width * 4
            if (y < y0 || y >= y1) {
                data.fill(0f, row, row + width * 4)
                continue
            }
            data.fill(0f, row, row + x0 * 4)
            data.fill(0f, row + x1 * 4, row + width * 4)
        }
    }

    /**
     * This image at [w] by [h] pixels over the same area: each pixel averages what it covers when
     * the image shrinks, and takes the bilinear mix of the four nearest when it grows.
     */
    fun resized(w: Int, h: Int): FilterImage {
        val out = FilterImage(w, h, linear)
        if (width == 0 || height == 0 || w == 0 || h == 0) return out
        val fx = width.toDouble() / w
        val fy = height.toDouble() / h
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 4
            if (fx > 1.0 || fy > 1.0) {
                val x0 = floor(x * fx).toInt(); val x1 = ceil((x + 1) * fx).toInt().coerceAtMost(width)
                val y0 = floor(y * fy).toInt(); val y1 = ceil((y + 1) * fy).toInt().coerceAtMost(height)
                val n = ((x1 - x0) * (y1 - y0)).coerceAtLeast(1)
                for (sy in y0 until y1) for (sx in x0 until x1) {
                    val i = (sy * width + sx) * 4
                    for (c in 0 until 4) out.data[o + c] += data[i + c]
                }
                for (c in 0 until 4) out.data[o + c] /= n
            } else {
                val sx = ((x + 0.5) * fx - 0.5).coerceIn(0.0, width - 1.0)
                val sy = ((y + 0.5) * fy - 0.5).coerceIn(0.0, height - 1.0)
                val ix = sx.toInt(); val iy = sy.toInt()
                val jx = minOf(ix + 1, width - 1); val jy = minOf(iy + 1, height - 1)
                val tx = (sx - ix).toFloat(); val ty = (sy - iy).toFloat()
                for (c in 0 until 4) {
                    val top = data[(iy * width + ix) * 4 + c] * (1 - tx) + data[(iy * width + jx) * 4 + c] * tx
                    val bottom = data[(jy * width + ix) * 4 + c] * (1 - tx) + data[(jy * width + jx) * 4 + c] * tx
                    out.data[o + c] = top * (1 - ty) + bottom * ty
                }
            }
        }
        return out
    }

    /** These pixels as a raster in sRGB with straight alpha. */
    fun toRaster(): KiteRaster {
        val srgb = inSpace(toLinear = false)
        val out = KiteRaster(width, height)
        val d = srgb.data
        for (p in 0 until width * height) {
            val i = p * 4
            val a = d[i + 3].coerceIn(0f, 1f)
            if (a <= 0f) continue
            fun channel(c: Int) = (d[i + c] / a).coerceIn(0f, 1f).times(255f).roundToInt()
            out.pixels[p] = ((a * 255f).roundToInt() shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
        }
        return out
    }

    companion object {
        /** The pixels of [raster], straight sRGB, as an image in sRGB. */
        fun of(raster: KiteRaster): FilterImage {
            val out = FilterImage(raster.width, raster.height, linear = false)
            val o = out.data
            for (p in raster.pixels.indices) {
                val v = raster.pixels[p]
                val a = (v ushr 24) / 255f
                if (a <= 0f) continue
                val i = p * 4
                o[i] = ((v ushr 16) and 0xFF) / 255f * a
                o[i + 1] = ((v ushr 8) and 0xFF) / 255f * a
                o[i + 2] = (v and 0xFF) / 255f * a
                o[i + 3] = a
            }
            return out
        }

        private const val LUT_SIZE = 4096

        /** sRGB to linear light and back (IEC 61966-2-1), as tables with linear interpolation. */
        private val TO_LINEAR = FloatArray(LUT_SIZE + 1) { srgbToLinear(it / LUT_SIZE.toDouble()).toFloat() }
        private val TO_SRGB = FloatArray(LUT_SIZE + 1) { linearToSrgb(it / LUT_SIZE.toDouble()).toFloat() }

        fun toLinear(v: Float): Float = lookup(TO_LINEAR, v)
        fun toSrgb(v: Float): Float = lookup(TO_SRGB, v)

        private fun lookup(table: FloatArray, v: Float): Float {
            val x = v.coerceIn(0f, 1f) * LUT_SIZE
            val i = x.toInt().coerceAtMost(LUT_SIZE - 1)
            val f = x - i
            return table[i] + (table[i + 1] - table[i]) * f
        }

        private fun srgbToLinear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        private fun linearToSrgb(c: Double): Double = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
    }
}

/** A rectangle of pixel edges: columns [x0] to [x1] and rows [y0] to [y1], the ends excluded. */
internal data class PixelRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val isEmpty: Boolean get() = x1 <= x0 || y1 <= y0

    fun union(other: PixelRect): PixelRect = when {
        isEmpty -> other
        other.isEmpty -> this
        else -> PixelRect(minOf(x0, other.x0), minOf(y0, other.y0), maxOf(x1, other.x1), maxOf(y1, other.y1))
    }

    fun intersect(other: PixelRect): PixelRect =
        PixelRect(maxOf(x0, other.x0), maxOf(y0, other.y0), minOf(x1, other.x1), minOf(y1, other.y1))

    companion object {
        /** The pixels whose edges lie nearest the box from [left] to [right] and [top] to [bottom]. */
        fun around(left: Double, top: Double, right: Double, bottom: Double): PixelRect {
            fun edge(v: Double) = if (v.isFinite()) v.coerceIn(-1e8, 1e8).roundToInt() else 0
            return PixelRect(edge(left), edge(top), edge(right), edge(bottom))
        }
    }
}

/**
 * The pixel work of the filter primitives (Filter Effects 1, 15). Each takes and gives
 * premultiplied images of one size; the caller converts colour spaces and clips results to
 * their subregions.
 */
internal object FilterOps {

    /**
     * feGaussianBlur (15.17): a standard deviation of [sx] by [sy] pixels. From 2 pixels up, three
     * box blurs stand in for the Gaussian, as the spec describes; below that the kernel is exact.
     * A deviation of zero leaves its direction as it is.
     */
    fun blur(src: FilterImage, sx: Double, sy: Double): FilterImage {
        var out = src
        if (sx > 0.0) out = blurPass(out, sx, horizontal = true)
        // Columns blur as the rows of the turned image, which reads memory in order.
        if (sy > 0.0) out = blurPass(transposed(out), sy, horizontal = true).let(::transposed)
        return if (out === src) src.copy() else out
    }

    /** [src] with its rows as columns. */
    private fun transposed(src: FilterImage): FilterImage {
        val out = FilterImage(src.height, src.width, src.linear)
        val s = src.data
        val o = out.data
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val i = (y * src.width + x) * 4
            val j = (x * src.height + y) * 4
            o[j] = s[i]; o[j + 1] = s[i + 1]; o[j + 2] = s[i + 2]; o[j + 3] = s[i + 3]
        }
        return out
    }

    private fun blurPass(src: FilterImage, sigma: Double, horizontal: Boolean): FilterImage {
        if (sigma < 2.0) return gaussianPass(src, sigma, horizontal)
        val d = floor(sigma * 3.0 * sqrt(2.0 * kotlin.math.PI) / 4.0 + 0.5).toInt().coerceAtLeast(1)
        val a = FilterImage(src.width, src.height, src.linear)
        val b = FilterImage(src.width, src.height, src.linear)
        if (d % 2 == 1) {
            val r = (d - 1) / 2
            boxPass(src.data, a.data, src.width, src.height, horizontal, r, r)
            boxPass(a.data, b.data, src.width, src.height, horizontal, r, r)
            boxPass(b.data, a.data, src.width, src.height, horizontal, r, r)
        } else {
            val h = d / 2
            // Two boxes of d centred on the pixel's left and right edges, then one of d + 1 centred on it.
            boxPass(src.data, a.data, src.width, src.height, horizontal, h, h - 1)
            boxPass(a.data, b.data, src.width, src.height, horizontal, h - 1, h)
            boxPass(b.data, a.data, src.width, src.height, horizontal, h, h)
        }
        return a
    }

    /** One box blur along rows or columns: pixel i averages [i - before, i + after], with transparent pixels outside. */
    private fun boxPass(src: FloatArray, dst: FloatArray, width: Int, height: Int, horizontal: Boolean, before: Int, after: Int) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val step = if (horizontal) 4 else width * 4
        val scale = 1.0 / (before + after + 1)
        for (line in 0 until lines) {
            val base = if (horizontal) line * width * 4 else line * 4
            var r = 0.0; var g = 0.0; var b = 0.0; var a = 0.0
            for (k in 0..minOf(after, length - 1)) {
                val p = base + k * step
                r += src[p]; g += src[p + 1]; b += src[p + 2]; a += src[p + 3]
            }
            for (i in 0 until length) {
                val o = base + i * step
                dst[o] = (r * scale).toFloat(); dst[o + 1] = (g * scale).toFloat()
                dst[o + 2] = (b * scale).toFloat(); dst[o + 3] = (a * scale).toFloat()
                val add = i + 1 + after
                if (add < length) {
                    val p = base + add * step
                    r += src[p]; g += src[p + 1]; b += src[p + 2]; a += src[p + 3]
                }
                val remove = i - before
                if (remove >= 0) {
                    val p = base + remove * step
                    r -= src[p]; g -= src[p + 1]; b -= src[p + 2]; a -= src[p + 3]
                }
            }
        }
    }

    private fun gaussianPass(src: FilterImage, sigma: Double, horizontal: Boolean): FilterImage {
        val radius = ceil(sigma * 3.0).toInt().coerceAtLeast(1)
        val weights = DoubleArray(2 * radius + 1) { k -> val x = (k - radius).toDouble(); kotlin.math.exp(-x * x / (2 * sigma * sigma)) }
        val total = weights.sum()
        for (k in weights.indices) weights[k] /= total
        val out = FilterImage(src.width, src.height, src.linear)
        val lines = if (horizontal) src.height else src.width
        val length = if (horizontal) src.width else src.height
        val step = if (horizontal) 4 else src.width * 4
        val s = src.data
        val o = out.data
        for (line in 0 until lines) {
            val base = if (horizontal) line * src.width * 4 else line * 4
            for (i in 0 until length) {
                var r = 0.0; var g = 0.0; var b = 0.0; var a = 0.0
                for (k in -radius..radius) {
                    val j = i + k
                    if (j < 0 || j >= length) continue
                    val w = weights[k + radius]
                    val p = base + j * step
                    r += s[p] * w; g += s[p + 1] * w; b += s[p + 2] * w; a += s[p + 3] * w
                }
                val p = base + i * step
                o[p] = r.toFloat(); o[p + 1] = g.toFloat(); o[p + 2] = b.toFloat(); o[p + 3] = a.toFloat()
            }
        }
        return out
    }

    /** feOffset (15.19): the image moved by [dx] and [dy] pixels, sampled between pixels when they are not whole. */
    fun offset(src: FilterImage, dx: Double, dy: Double): FilterImage {
        val out = FilterImage(src.width, src.height, src.linear)
        val w = src.width
        val h = src.height
        val ix = floor(dx).toInt()
        val iy = floor(dy).toInt()
        val fx = (dx - ix).toFloat()
        val fy = (dy - iy).toFloat()
        val s = src.data
        val o = out.data
        fun at(x: Int, y: Int, c: Int): Float = if (x < 0 || y < 0 || x >= w || y >= h) 0f else s[(y * w + x) * 4 + c]
        for (y in 0 until h) for (x in 0 until w) {
            // The output pixel (x, y) reads the input at (x - dx, y - dy).
            val sx = x - ix
            val sy = y - iy
            val p = (y * w + x) * 4
            for (c in 0 until 4) {
                o[p + c] = if (fx == 0f && fy == 0f) {
                    at(sx, sy, c)
                } else {
                    val top = at(sx, sy, c) * (1 - fx) + at(sx - 1, sy, c) * fx
                    val bottom = at(sx, sy - 1, c) * (1 - fx) + at(sx - 1, sy - 1, c) * fx
                    top * (1 - fy) + bottom * fy
                }
            }
        }
        return out
    }

    /** feFlood (15.16): [region] filled with a premultiplied colour. */
    fun flood(width: Int, height: Int, linear: Boolean, r: Float, g: Float, b: Float, a: Float, region: PixelRect): FilterImage {
        val out = FilterImage(width, height, linear)
        val area = region.intersect(PixelRect(0, 0, width, height))
        for (y in area.y0 until area.y1) for (x in area.x0 until area.x1) {
            val p = (y * width + x) * 4
            out.data[p] = r * a; out.data[p + 1] = g * a; out.data[p + 2] = b * a; out.data[p + 3] = a
        }
        return out
    }

    /** [top] composited over [bottom] in place of a new image (Porter-Duff source over). */
    fun over(top: FilterImage, bottom: FilterImage): FilterImage {
        val out = FilterImage(top.width, top.height, top.linear)
        val t = top.data; val b = bottom.data; val o = out.data
        for (i in o.indices step 4) {
            val k = 1f - t[i + 3]
            o[i] = t[i] + b[i] * k; o[i + 1] = t[i + 1] + b[i + 1] * k
            o[i + 2] = t[i + 2] + b[i + 2] * k; o[i + 3] = t[i + 3] + b[i + 3] * k
        }
        return out
    }

    /**
     * feComposite (15.12) of [a], the `in` image, with [b], the `in2` image. The arithmetic
     * operator takes [k]: k1 to k4.
     */
    fun composite(a: FilterImage, b: FilterImage, operator: String, k: DoubleArray): FilterImage {
        val out = FilterImage(a.width, a.height, a.linear)
        val s = a.data; val d = b.data; val o = out.data
        for (i in o.indices step 4) {
            val sa = s[i + 3]
            val da = d[i + 3]
            when (operator) {
                "in" -> for (c in 0 until 4) o[i + c] = s[i + c] * da
                "out" -> for (c in 0 until 4) o[i + c] = s[i + c] * (1 - da)
                "atop" -> for (c in 0 until 4) o[i + c] = s[i + c] * da + d[i + c] * (1 - sa)
                "xor" -> for (c in 0 until 4) o[i + c] = s[i + c] * (1 - da) + d[i + c] * (1 - sa)
                "lighter" -> for (c in 0 until 4) o[i + c] = (s[i + c] + d[i + c]).coerceAtMost(1f)
                "arithmetic" -> {
                    for (c in 0 until 4) {
                        val v = k[0] * s[i + c] * d[i + c] + k[1] * s[i + c] + k[2] * d[i + c] + k[3]
                        o[i + c] = v.toFloat().coerceIn(0f, 1f)
                    }
                    // Premultiplied colour cannot pass its alpha.
                    for (c in 0 until 3) if (o[i + c] > o[i + 3]) o[i + c] = o[i + 3]
                }
                else -> for (c in 0 until 4) o[i + c] = s[i + c] + d[i + c] * (1 - sa)
            }
        }
        return out
    }

    /**
     * feBlend (15.10): [top], the `in` image, blended onto [bottom], the `in2` image, in a mode of
     * Compositing and Blending 1 (5.1 and 10): Cr = (1 - ab) Cs + (1 - as) Cb + as ab B(Cb, Cs).
     */
    fun blend(top: FilterImage, bottom: FilterImage, mode: String): FilterImage {
        val out = FilterImage(top.width, top.height, top.linear)
        val s = top.data; val d = bottom.data; val o = out.data
        val cs = FloatArray(3); val cb = FloatArray(3); val mixed = FloatArray(3)
        for (i in o.indices step 4) {
            val sa = s[i + 3]
            val da = d[i + 3]
            if (sa <= 0f || da <= 0f) {
                for (c in 0 until 4) o[i + c] = s[i + c] + d[i + c] * (1 - sa)
                continue
            }
            for (c in 0 until 3) { cs[c] = (s[i + c] / sa).coerceIn(0f, 1f); cb[c] = (d[i + c] / da).coerceIn(0f, 1f) }
            Blends.apply(mode, cb, cs, mixed)
            for (c in 0 until 3) o[i + c] = (1 - da) * s[i + c] + (1 - sa) * d[i + c] + sa * da * mixed[c]
            o[i + 3] = sa + da - sa * da
        }
        return out
    }

    /**
     * feColorMatrix (15.11) as a 4 by 5 matrix [m], row by row, on straight colours, whose last
     * column adds to the channel in units of 0 to 1.
     */
    fun colorMatrix(src: FilterImage, m: DoubleArray): FilterImage {
        val out = FilterImage(src.width, src.height, src.linear)
        val s = src.data; val o = out.data
        for (i in o.indices step 4) {
            val a = s[i + 3]
            val r = if (a > 0f) s[i] / a else 0f
            val g = if (a > 0f) s[i + 1] / a else 0f
            val b = if (a > 0f) s[i + 2] / a else 0f
            fun row(k: Int) = (m[k] * r + m[k + 1] * g + m[k + 2] * b + m[k + 3] * a + m[k + 4]).toFloat().coerceIn(0f, 1f)
            val na = row(15)
            o[i] = row(0) * na; o[i + 1] = row(5) * na; o[i + 2] = row(10) * na; o[i + 3] = na
        }
        return out
    }

    /** feComponentTransfer (15.13): each straight channel through its function, null for the identity. */
    fun componentTransfer(src: FilterImage, functions: Array<((Float) -> Float)?>): FilterImage {
        val out = FilterImage(src.width, src.height, src.linear)
        val s = src.data; val o = out.data
        for (i in o.indices step 4) {
            val a = s[i + 3]
            val straight = FloatArray(4) { c -> if (c == 3) a else if (a > 0f) s[i + c] / a else 0f }
            for (c in 0 until 4) straight[c] = (functions[c]?.invoke(straight[c]) ?: straight[c]).coerceIn(0f, 1f)
            val na = straight[3]
            o[i] = straight[0] * na; o[i + 1] = straight[1] * na; o[i + 2] = straight[2] * na; o[i + 3] = na
        }
        return out
    }

    /** feMorphology (15.20): each channel's least ([erode]) or greatest value within [rx] by [ry] pixels. */
    fun morphology(src: FilterImage, rx: Int, ry: Int, erode: Boolean): FilterImage {
        val pass = FilterImage(src.width, src.height, src.linear)
        extremePass(src, pass, rx, horizontal = true, erode)
        val turned = transposed(pass)
        val out = FilterImage(turned.width, turned.height, src.linear)
        extremePass(turned, out, ry, horizontal = true, erode)
        return transposed(out)
    }

    /**
     * The least or greatest value of each channel over [radius] pixels on each side, along rows
     * or columns, with transparent black outside. A window of indices that keeps its candidates
     * in order makes it one step a pixel whatever the radius.
     */
    private fun extremePass(src: FilterImage, dst: FilterImage, radius: Int, horizontal: Boolean, erode: Boolean) {
        val width = src.width
        val lines = if (horizontal) src.height else width
        val length = if (horizontal) width else src.height
        val step = if (horizontal) 4 else width * 4
        val s = src.data
        val d = dst.data
        val padded = length + 2 * radius
        val line = FloatArray(padded)
        val window = IntArray(padded)
        for (l in 0 until lines) {
            val base = if (horizontal) l * width * 4 else l * 4
            for (c in 0 until 4) {
                line.fill(0f)
                for (i in 0 until length) line[i + radius] = s[base + i * step + c]
                var head = 0
                var tail = 0
                for (k in 0 until padded) {
                    // Drop the candidates this value beats, then the one that left the window.
                    while (tail > head && (if (erode) line[window[tail - 1]] >= line[k] else line[window[tail - 1]] <= line[k])) tail--
                    window[tail++] = k
                    if (window[head] <= k - 2 * radius - 1) head++
                    val i = k - 2 * radius
                    if (i >= 0) d[base + i * step + c] = line[window[head]]
                }
            }
        }
    }

    /** feTile (15.23): the pixels of [tile] in [src] repeated over the whole image. */
    fun tile(src: FilterImage, tile: PixelRect): FilterImage {
        val out = FilterImage(src.width, src.height, src.linear)
        val t = tile.intersect(PixelRect(0, 0, src.width, src.height))
        if (t.isEmpty) return out
        val tw = t.x1 - t.x0
        val th = t.y1 - t.y0
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val sx = t.x0 + (x - t.x0).mod(tw)
            val sy = t.y0 + (y - t.y0).mod(th)
            val p = (y * src.width + x) * 4
            val q = (sy * src.width + sx) * 4
            for (c in 0 until 4) out.data[p + c] = src.data[q + c]
        }
        return out
    }

    /**
     * feConvolveMatrix (15.14): [kernel] of [orderX] by [orderY], centred on [targetX] and [targetY].
     * Pixels outside the image follow [edgeMode]: duplicate, wrap or none.
     */
    fun convolve(
        src: FilterImage, orderX: Int, orderY: Int, kernel: DoubleArray, divisor: Double, bias: Double,
        targetX: Int, targetY: Int, edgeMode: String, preserveAlpha: Boolean,
    ): FilterImage {
        val out = FilterImage(src.width, src.height, src.linear)
        val w = src.width
        val h = src.height
        val s = src.data
        fun sample(x: Int, y: Int, c: Int): Float {
            var px = x
            var py = y
            when (edgeMode) {
                "wrap" -> { px = px.mod(w); py = py.mod(h) }
                "none" -> if (px < 0 || py < 0 || px >= w || py >= h) return 0f
                else -> { px = px.coerceIn(0, w - 1); py = py.coerceIn(0, h - 1) }
            }
            val p = (py * w + px) * 4
            if (!preserveAlpha || c == 3) return s[p + c]
            val a = s[p + 3]
            return if (a > 0f) s[p + c] / a else 0f
        }
        for (y in 0 until h) for (x in 0 until w) {
            val p = (y * w + x) * 4
            val alpha = if (preserveAlpha) s[p + 3] else 0f
            val channels = if (preserveAlpha) 3 else 4
            val result = FloatArray(4)
            for (c in 0 until channels) {
                var sum = 0.0
                for (i in 0 until orderY) for (j in 0 until orderX) {
                    sum += sample(x - targetX + j, y - targetY + i, c) * kernel[(orderY - i - 1) * orderX + (orderX - j - 1)]
                }
                result[c] = (sum / divisor + bias * (if (preserveAlpha) 1f else s[p + 3])).toFloat().coerceIn(0f, 1f)
            }
            if (preserveAlpha) {
                for (c in 0 until 3) out.data[p + c] = result[c] * alpha
                out.data[p + 3] = alpha
            } else {
                for (c in 0 until 3) out.data[p + c] = minOf(result[c], result[3])
                out.data[p + 3] = result[3]
            }
        }
        return out
    }

    /**
     * feDisplacementMap (15.15): each pixel of [src] taken from where the channels [xChannel] and
     * [yChannel] (0 to 3) of [map], straight, move it: by [scaleX] and [scaleY] pixels times the
     * channel less a half.
     */
    fun displace(src: FilterImage, map: FilterImage, scaleX: Double, scaleY: Double, xChannel: Int, yChannel: Int): FilterImage {
        val out = FilterImage(src.width, src.height, src.linear)
        val w = src.width
        val h = src.height
        val m = map.data
        for (y in 0 until h) for (x in 0 until w) {
            val p = (y * w + x) * 4
            val a = m[p + 3]
            fun channel(c: Int): Float = if (c == 3) a else if (a > 0f) m[p + c] / a else 0f
            val sx = floor(x + 0.5 + scaleX * (channel(xChannel) - 0.5)).toInt()
            val sy = floor(y + 0.5 + scaleY * (channel(yChannel) - 0.5)).toInt()
            if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue
            val q = (sy * w + sx) * 4
            for (c in 0 until 4) out.data[p + c] = src.data[q + c]
        }
        return out
    }
}

/** The blend modes of Compositing and Blending 1 (5.1 and 10), on straight colours from 0 to 1. */
internal object Blends {

    /** B([cb], [cs]) of [mode] into [out]; an unknown mode is normal. */
    fun apply(mode: String, cb: FloatArray, cs: FloatArray, out: FloatArray) {
        when (mode) {
            "multiply" -> for (c in 0 until 3) out[c] = cb[c] * cs[c]
            "screen" -> for (c in 0 until 3) out[c] = cb[c] + cs[c] - cb[c] * cs[c]
            "overlay" -> for (c in 0 until 3) out[c] = hardLight(cs[c], cb[c])
            "darken" -> for (c in 0 until 3) out[c] = minOf(cb[c], cs[c])
            "lighten" -> for (c in 0 until 3) out[c] = maxOf(cb[c], cs[c])
            "color-dodge" -> for (c in 0 until 3) out[c] = when {
                cb[c] == 0f -> 0f
                cs[c] >= 1f -> 1f
                else -> minOf(1f, cb[c] / (1 - cs[c]))
            }
            "color-burn" -> for (c in 0 until 3) out[c] = when {
                cb[c] >= 1f -> 1f
                cs[c] <= 0f -> 0f
                else -> 1 - minOf(1f, (1 - cb[c]) / cs[c])
            }
            "hard-light" -> for (c in 0 until 3) out[c] = hardLight(cb[c], cs[c])
            "soft-light" -> for (c in 0 until 3) out[c] = softLight(cb[c], cs[c])
            "difference" -> for (c in 0 until 3) out[c] = kotlin.math.abs(cb[c] - cs[c])
            "exclusion" -> for (c in 0 until 3) out[c] = cb[c] + cs[c] - 2 * cb[c] * cs[c]
            "hue" -> { cs.copyInto(out); setSat(out, sat(cb)); setLum(out, lum(cb)) }
            "saturation" -> { cb.copyInto(out); setSat(out, sat(cs)); setLum(out, lum(cb)) }
            "color" -> { cs.copyInto(out); setLum(out, lum(cb)) }
            "luminosity" -> { cb.copyInto(out); setLum(out, lum(cs)) }
            else -> cs.copyInto(out)
        }
    }

    private fun hardLight(cb: Float, cs: Float): Float =
        if (cs <= 0.5f) cb * 2 * cs else { val t = 2 * cs - 1; cb + t - cb * t }

    private fun softLight(cb: Float, cs: Float): Float {
        if (cs <= 0.5f) return cb - (1 - 2 * cs) * cb * (1 - cb)
        val d = if (cb <= 0.25f) ((16 * cb - 12) * cb + 4) * cb else sqrt(cb)
        return cb + (2 * cs - 1) * (d - cb)
    }

    private fun lum(c: FloatArray): Float = 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2]
    private fun sat(c: FloatArray): Float = maxOf(c[0], c[1], c[2]) - minOf(c[0], c[1], c[2])

    private fun setLum(c: FloatArray, l: Float) {
        val d = l - lum(c)
        for (k in 0 until 3) c[k] += d
        val lum = lum(c)
        val n = minOf(c[0], c[1], c[2])
        val x = maxOf(c[0], c[1], c[2])
        if (n < 0f) for (k in 0 until 3) c[k] = lum + (c[k] - lum) * lum / (lum - n)
        if (x > 1f) for (k in 0 until 3) c[k] = lum + (c[k] - lum) * (1 - lum) / (x - lum)
    }

    private fun setSat(c: FloatArray, s: Float) {
        var mn = 0; var md = 1; var mx = 2
        if (c[mn] > c[md]) { val t = mn; mn = md; md = t }
        if (c[md] > c[mx]) { val t = md; md = mx; mx = t }
        if (c[mn] > c[md]) { val t = mn; mn = md; md = t }
        if (c[mx] > c[mn]) {
            c[md] = (c[md] - c[mn]) * s / (c[mx] - c[mn])
            c[mx] = s
        } else {
            c[md] = 0f
            c[mx] = 0f
        }
        c[mn] = 0f
    }
}
