package io.github.yuroyami.kitepdf.core.render

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

/**
 * An image of one sample a pixel at 1, 2 or 4 bits converts each sample value once and copies its
 * colour to each pixel, and gives the pixels that converting each pixel on its own gives (#477).
 */
class LowBitRasterTest {

    // 25 whole bytes and 3 bits a row at 1 bit, 50 and 6 at 2 bits, 101 and 4 at 4 bits.
    private val w = 203
    private val h = 37

    private fun image(
        bits: Int,
        space: KiteColorSpace,
        decode: DoubleArray? = null,
        colorKey: IntArray? = null,
    ) = KiteImageData(
        width = w, height = h, bitsPerComponent = bits, colorSpace = "test", kind = KiteImageData.Kind.RAW,
        encodedBytes = ByteArray(0),
        pixelBytes = ByteArray((w * bits + 7) / 8 * h) { ((it * 37) xor (it ushr 3) xor (it * it ushr 5)).toByte() },
        resolvedColorSpace = space, decode = decode, colorKeyMask = colorKey,
    )

    /**
     * Each pixel on its own: its sample through `/Decode` and the space (ISO 32000-1, 8.9.5.2), or an
     * index into the palette (8.6.6.3), and transparent inside the colour key (8.9.6.4).
     */
    private fun perPixel(image: KiteImageData): ByteArray {
        val bits = image.bitsPerComponent
        val space = assertNotNull(image.resolvedColorSpace)
        val src = assertNotNull(image.pixelBytes)
        val d = image.decode
        val key = image.colorKeyMask
        val maxval = (1 shl bits) - 1
        val rowBytes = (w * bits + 7) / 8
        val out = ByteArray(w * h * 4)
        var o = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val bit = x * bits
                val sample = (src[y * rowBytes + bit / 8].toInt() ushr (8 - bits - bit % 8)) and maxval
                val rgb = if (space is KiteColorSpace.Indexed) {
                    space.colorAt(if (d == null) sample else (d[0] + sample.toDouble() * (d[1] - d[0]) / maxval).roundToInt())
                } else {
                    val lo = d?.get(0) ?: space.componentMin(0)
                    val hi = d?.get(1) ?: space.componentMax(0)
                    space.toRgb(doubleArrayOf(lo + sample * (hi - lo) / maxval.toDouble()))
                }
                out[o++] = (rgb.r * 255.0).roundToInt().toByte()
                out[o++] = (rgb.g * 255.0).roundToInt().toByte()
                out[o++] = (rgb.b * 255.0).roundToInt().toByte()
                out[o++] = if (key != null && sample >= key[0] && sample <= key[1]) 0 else 0xFF.toByte()
            }
        }
        return out
    }

    private val calGray = KiteColorSpace.CalGray(doubleArrayOf(0.9505, 1.0, 1.089), 2.2)

    // A spot colour that runs from white at no tint to a dark teal at full tint.
    private val separation = KiteColorSpace.DeviceN(
        1, KiteColorSpace.DeviceRGB,
        KiteFunction.Type2(doubleArrayOf(0.0, 1.0), null, doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(0.0, 0.3, 0.35), 1.0),
        listOf("Teal"),
    )

    private val palette = KiteColorSpace.Indexed(KiteColorSpace.DeviceRGB, 15, ByteArray(48) { (it * 53 + 7).toByte() })

    // Ten entries, so the samples 10 to 15 of a 4-bit image fall past the end and take the last one.
    private val shortPalette = KiteColorSpace.Indexed(KiteColorSpace.DeviceRGB, 9, ByteArray(30) { (it * 29 + 3).toByte() })

    private fun cases(): List<Pair<String, KiteImageData>> = listOf(
        "grey, 1 bit" to image(1, KiteColorSpace.DeviceGray),
        "grey, 1 bit, inverted" to image(1, KiteColorSpace.DeviceGray, decode = doubleArrayOf(1.0, 0.0)),
        "grey, 2 bits" to image(2, KiteColorSpace.DeviceGray),
        "grey, 2 bits, with a colour key" to image(2, KiteColorSpace.DeviceGray, colorKey = intArrayOf(1, 2)),
        "grey, 4 bits" to image(4, KiteColorSpace.DeviceGray),
        "grey, 4 bits, with a decode array" to image(4, KiteColorSpace.DeviceGray, decode = doubleArrayOf(0.9, 0.1)),
        "calibrated grey, 1 bit" to image(1, calGray),
        "calibrated grey, 2 bits" to image(2, calGray),
        "calibrated grey, 4 bits" to image(4, calGray),
        "separation, 2 bits" to image(2, separation),
        "separation, 4 bits" to image(4, separation),
        "indexed, 1 bit" to image(1, palette),
        "indexed, 2 bits" to image(2, palette),
        "indexed, 4 bits" to image(4, palette),
        "indexed, 4 bits, a short palette" to image(4, shortPalette),
        "indexed, 4 bits, with a decode array" to image(4, palette, decode = doubleArrayOf(15.0, 0.0)),
    )

    @Test
    fun each_pixel_takes_the_colour_its_sample_converts_to() {
        for ((name, image) in cases()) {
            assertContentEquals(perPixel(image), image.toRgbaBytes(), name)
        }
    }
}
