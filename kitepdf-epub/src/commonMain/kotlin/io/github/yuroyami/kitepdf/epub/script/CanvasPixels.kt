package io.github.yuroyami.kitepdf.epub.script

import io.github.yuroyami.kitepdf.core.render.KiteRaster
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The pixel arithmetic of `getImageData` and `putImageData` (#610). A canvas keeps 8-bit RGBA
 * with each colour premultiplied by its alpha, as Chromium does, so a value that goes in comes
 * back out rounded the same way. Pixels in Display P3 go through linear light to sRGB and back.
 */
internal object CanvasPixels {

    /** The RGBA bytes of the rectangle of [raster] at ([x], [y]), straight colour. */
    fun read(raster: KiteRaster, x: Int, y: Int, w: Int, h: Int, p3: Boolean): ByteArray {
        val out = ByteArray(w * h * 4)
        val rgb = DoubleArray(3)
        var k = 0
        for (j in 0 until h) for (i in 0 until w) {
            val p = at(raster, x + i, y + j)
            val a = p ushr 24
            if (!p3 || a == 0) {
                val s = unpremultiply(p)
                out[k++] = (s ushr 16).toByte()
                out[k++] = (s ushr 8).toByte()
                out[k++] = s.toByte()
            } else {
                straight(p, rgb)
                convert(rgb, SRGB_TO_P3)
                for (c in rgb) out[k++] = byte(c).toByte()
            }
            out[k++] = a.toByte()
        }
        return out
    }

    /** The rectangle as straight RGBA from 0 to 1, for an `rgba-float16` ImageData. */
    fun readFloats(raster: KiteRaster, x: Int, y: Int, w: Int, h: Int, p3: Boolean): List<Double> {
        val out = ArrayList<Double>(w * h * 4)
        val rgb = DoubleArray(3)
        for (j in 0 until h) for (i in 0 until w) {
            val p = at(raster, x + i, y + j)
            straight(p, rgb)
            if (p3 && p ushr 24 != 0) convert(rgb, SRGB_TO_P3)
            for (c in rgb) out += c
            out += (p ushr 24) / 255.0
        }
        return out
    }

    /**
     * Writes [w] by [h] pixels of straight RGBA into [raster] at ([x], [y]): [data] is a byte array
     * or a list of numbers from 0 to 1. Pixels outside the raster are skipped. Answers
     * whether any pixel landed.
     */
    fun write(raster: KiteRaster, data: Any?, x: Int, y: Int, w: Int, h: Int, p3: Boolean): Boolean {
        val bytes = data as? ByteArray
        val floats = data as? List<*>
        if (bytes == null && floats == null) return false
        val rgb = DoubleArray(3)
        var wrote = false
        for (j in 0 until h) {
            val ty = y + j
            if (ty < 0 || ty >= raster.height) continue
            for (i in 0 until w) {
                val tx = x + i
                if (tx < 0 || tx >= raster.width) continue
                val k = (j * w + i) * 4
                val a: Int
                if (bytes != null) {
                    if (k + 3 >= bytes.size) continue
                    a = bytes[k + 3].toInt() and 255
                    if (p3) for (c in 0..2) rgb[c] = (bytes[k + c].toInt() and 255) / 255.0
                    else {
                        raster.pixels[ty * raster.width + tx] = premultiply(
                            (a shl 24) or (bytes[k].toInt() and 255 shl 16) or (bytes[k + 1].toInt() and 255 shl 8) or (bytes[k + 2].toInt() and 255),
                        )
                        wrote = true
                        continue
                    }
                } else {
                    if (k + 3 >= floats!!.size) continue
                    for (c in 0..2) rgb[c] = unit(floats[k + c])
                    a = byte(unit(floats[k + 3]))
                }
                if (p3) convert(rgb, P3_TO_SRGB)
                raster.pixels[ty * raster.width + tx] = premultiply((a shl 24) or (byte(rgb[0]) shl 16) or (byte(rgb[1]) shl 8) or byte(rgb[2]))
                wrote = true
            }
        }
        return wrote
    }

    /** [premultiplied] with straight colour, each channel rounded, for an image the page paints. */
    fun straightened(premultiplied: KiteRaster): KiteRaster =
        KiteRaster(premultiplied.width, premultiplied.height, IntArray(premultiplied.pixels.size) { unpremultiply(premultiplied.pixels[it]) })

    private fun at(raster: KiteRaster, x: Int, y: Int): Int =
        if (x >= 0 && y >= 0 && x < raster.width && y < raster.height) raster.pixels[y * raster.width + x] else 0

    /** The colour of a premultiplied pixel from 0 to 1, divided by its alpha without rounding first. */
    private fun straight(p: Int, out: DoubleArray) {
        val a = p ushr 24
        if (a == 0) { out.fill(0.0); return }
        out[0] = (p ushr 16 and 255).toDouble() / a
        out[1] = (p ushr 8 and 255).toDouble() / a
        out[2] = (p and 255).toDouble() / a
    }

    private fun unit(v: Any?): Double {
        val d = (v as? Number)?.toDouble() ?: 0.0
        return if (d.isNaN()) 0.0 else d.coerceIn(0.0, 1.0)
    }

    private fun byte(c: Double): Int = (c.coerceIn(0.0, 1.0) * 255).roundToInt()

    fun premultiply(argb: Int): Int {
        val a = argb ushr 24
        if (a == 255) return argb
        if (a == 0) return 0
        val r = ((argb ushr 16 and 255) * a + 127) / 255
        val g = ((argb ushr 8 and 255) * a + 127) / 255
        val b = ((argb and 255) * a + 127) / 255
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun unpremultiply(p: Int): Int {
        val a = p ushr 24
        if (a == 255) return p
        if (a == 0) return 0
        val r = minOf(255, ((p ushr 16 and 255) * 255 + a / 2) / a)
        val g = minOf(255, ((p ushr 8 and 255) * 255 + a / 2) / a)
        val b = minOf(255, ((p and 255) * 255 + a / 2) / a)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** [rgb] in the other space by [m], which maps linear light; both spaces share the sRGB curve. */
    private fun convert(rgb: DoubleArray, m: DoubleArray) {
        val r = linear(rgb[0]); val g = linear(rgb[1]); val b = linear(rgb[2])
        rgb[0] = encoded(m[0] * r + m[1] * g + m[2] * b)
        rgb[1] = encoded(m[3] * r + m[4] * g + m[5] * b)
        rgb[2] = encoded(m[6] * r + m[7] * g + m[8] * b)
    }

    private fun linear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun encoded(c: Double): Double {
        val v = c.coerceIn(0.0, 1.0)
        return if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
    }

    /** Linear sRGB to linear Display P3, through CIE XYZ with the D65 white of both (CSS Color 4, 18). */
    private val SRGB_TO_P3 = doubleArrayOf(
        0.8224619687143625, 0.17753803128563772, 0.0,
        0.033194198850961525, 0.9668058011490378, 0.0,
        0.017082630721120026, 0.0723974406639634, 0.9105199286149164,
    )
    private val P3_TO_SRGB = doubleArrayOf(
        1.2249401762805594, -0.22494017628055993, 0.0,
        -0.042056954709688045, 1.0420569547096885, 0.0,
        -0.019637554590334425, -0.07863604555063186, 1.0982736001409663,
    )
}
