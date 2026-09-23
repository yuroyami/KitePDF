package io.github.yuroyami.kitepdf.core.render

import kotlin.math.abs
import kotlin.math.hypot

/**
 * How a canvas samples an image that it draws. ISO 32000-1, 8.9.5.3 leaves image
 * interpolation to the reader, so every canvas follows [imageSampling] and draws
 * the same pixels (#122, #123).
 */
public class KiteImageSampling internal constructor(
    /** Average this many source columns into one before drawing: a power of two, 1 to keep them. */
    public val shrinkX: Int,
    /** Average this many source rows into one before drawing: a power of two, 1 to keep them. */
    public val shrinkY: Int,
    /** Blend neighbouring pixels. Otherwise take the nearest pixel, so enlarged pixels keep hard edges. */
    public val smooth: Boolean,
) {
    /** True when the canvas must average the image down with [shrinkRgba] or [shrinkArgb] before drawing it. */
    public val shrinks: Boolean get() = shrinkX > 1 || shrinkY > 1

    /** The width of an image [width] pixels wide after it is averaged down. */
    public fun shrunkWidth(width: Int): Int = (width + shrinkX - 1) / shrinkX

    /** The height of an image [height] pixels high after it is averaged down. */
    public fun shrunkHeight(height: Int): Int = (height + shrinkY - 1) / shrinkY
}

/**
 * The sampling for an image of [width] by [height] pixels drawn under [ctm], which
 * maps the image's unit square to device pixels. The rules follow MuPDF:
 *
 * - A direction in which the image has at least twice as many pixels as it covers is
 *   averaged down by a power of two, to no fewer pixels than it covers. Thin strokes
 *   in a scanned page then fade instead of dropping out. MuPDF averages both
 *   directions by one factor. Here each direction has its own factor.
 * - An image drawn smaller than its pixels in both directions is smoothed.
 * - Otherwise, an image enlarged more than twice in either direction keeps hard pixel
 *   edges, unless [interpolate] is true: the /Interpolate entry of ISO 32000-1, Table 89.
 * - Otherwise, an image that is rotated, skewed or enlarged is smoothed, and an image
 *   drawn at its own size takes the nearest pixel.
 */
public fun imageSampling(width: Int, height: Int, ctm: KiteMatrix, interpolate: Boolean): KiteImageSampling {
    // Device pixels per image pixel along each image axis.
    val sx = hypot(ctm.a, ctm.b) / width
    val sy = hypot(ctm.c, ctm.d) / height
    if (!(sx > 0.0) || !(sy > 0.0) || !sx.isFinite() || !sy.isFinite()) return KiteImageSampling(1, 1, false)
    val size = abs(ctm.a) + abs(ctm.b) + abs(ctm.c) + abs(ctm.d)
    val rectilinear = (abs(ctm.b) <= 1e-9 * size && abs(ctm.c) <= 1e-9 * size) ||
        (abs(ctm.a) <= 1e-9 * size && abs(ctm.d) <= 1e-9 * size)
    val smooth = when {
        sx < 1.0 && sy < 1.0 -> true
        !interpolate && (sx > 2.0 || sy > 2.0) -> false
        else -> !rectilinear || sx > 1.0 || sy > 1.0
    }
    return KiteImageSampling(shrinkFactor(sx, width), shrinkFactor(sy, height), smooth)
}

/** The largest power of two that keeps [pixels] at least as many as [scale] times [pixels] device pixels. */
private fun shrinkFactor(scale: Double, pixels: Int): Int {
    var factor = 1
    while (factor < 256 && scale * factor * 2 <= 1.0 && pixels / (factor * 2) >= 1) factor *= 2
    return factor
}

/**
 * [rgba], straight RGBA of [width] by [height] pixels, averaged down by [shrinkX]
 * columns and [shrinkY] rows into each output pixel. A block at the right or bottom
 * edge averages the pixels it has. Colours are weighted by their alpha, so a
 * transparent pixel does not darken its neighbours. The result is
 * `ceil(width / shrinkX)` by `ceil(height / shrinkY)` pixels.
 */
public fun shrinkRgba(rgba: ByteArray, width: Int, height: Int, shrinkX: Int, shrinkY: Int): ByteArray {
    val fx = shrinkX.coerceAtLeast(1)
    val fy = shrinkY.coerceAtLeast(1)
    val w = (width + fx - 1) / fx
    val h = (height + fy - 1) / fy
    val out = ByteArray(w * h * 4)
    for (oy in 0 until h) {
        val y0 = oy * fy
        val y1 = minOf(height, y0 + fy)
        for (ox in 0 until w) {
            val x0 = ox * fx
            val x1 = minOf(width, x0 + fx)
            var r = 0L
            var g = 0L
            var b = 0L
            var a = 0L
            for (y in y0 until y1) {
                var p = (y * width + x0) * 4
                for (x in x0 until x1) {
                    val alpha = rgba[p + 3].toInt() and 0xFF
                    r += (rgba[p].toInt() and 0xFF) * alpha
                    g += (rgba[p + 1].toInt() and 0xFF) * alpha
                    b += (rgba[p + 2].toInt() and 0xFF) * alpha
                    a += alpha
                    p += 4
                }
            }
            val count = (y1 - y0) * (x1 - x0)
            val o = (oy * w + ox) * 4
            if (a > 0) {
                out[o] = ((r + a / 2) / a).toInt().toByte()
                out[o + 1] = ((g + a / 2) / a).toInt().toByte()
                out[o + 2] = ((b + a / 2) / a).toInt().toByte()
            }
            out[o + 3] = ((a + count / 2) / count).toInt().toByte()
        }
    }
    return out
}

/**
 * A bitmap of [width] by [height] straight (not premultiplied) ARGB pixels, averaged
 * down like [shrinkRgba] into straight RGBA bytes. [readRows] fills its array with the
 * `rows` rows that start at row `y`, [shrinkY] rows at a time, so a large decoded
 * bitmap is never copied whole.
 */
public fun shrinkArgb(
    width: Int,
    height: Int,
    shrinkX: Int,
    shrinkY: Int,
    readRows: (pixels: IntArray, y: Int, rows: Int) -> Unit,
): ByteArray {
    val fx = shrinkX.coerceAtLeast(1)
    val fy = shrinkY.coerceAtLeast(1)
    val outWidth = (width + fx - 1) / fx
    val outHeight = (height + fy - 1) / fy
    val out = ByteArray(outWidth * outHeight * 4)
    val argb = IntArray(width * fy)
    val rgba = ByteArray(width * fy * 4)
    for (row in 0 until outHeight) {
        val rows = minOf(fy, height - row * fy)
        readRows(argb, row * fy, rows)
        for (i in 0 until width * rows) {
            val c = argb[i]
            rgba[i * 4] = (c shr 16).toByte()
            rgba[i * 4 + 1] = (c shr 8).toByte()
            rgba[i * 4 + 2] = c.toByte()
            rgba[i * 4 + 3] = (c ushr 24).toByte()
        }
        shrinkRgba(rgba, width, rows, fx, fy).copyInto(out, row * outWidth * 4)
    }
    return out
}
