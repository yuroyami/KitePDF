package io.github.yuroyami.kitepdf.core.render

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

/**
 * An image whose pixels take their colour from their sample alone averages its samples when it
 * is drawn smaller, and gives the pixels of the whole image converted and then shrunk (#462).
 */
class SampleShrinkTest {

    // 25 whole bytes and 3 bits a row at 1 bit, so a block can end inside a byte.
    private val w = 203
    private val h = 61

    private fun image(
        bits: Int,
        space: KiteColorSpace?,
        isImageMask: Boolean = false,
        decode: DoubleArray? = null,
    ) = KiteImageData(
        width = w, height = h, bitsPerComponent = bits, colorSpace = "test", kind = KiteImageData.Kind.RAW,
        encodedBytes = ByteArray(0),
        pixelBytes = ByteArray((w * bits + 7) / 8 * h) { ((it * 37) xor (it ushr 3) xor (it * it ushr 5)).toByte() },
        resolvedColorSpace = space, decode = decode, isImageMask = isImageMask,
        maskFill = if (isImageMask) RgbColor(0.2, 0.5, 0.9) else null,
    )

    private val calGray = KiteColorSpace.CalGray(doubleArrayOf(0.9505, 1.0, 1.089), 2.2)

    private fun cases(): List<Pair<String, KiteImageData>> = listOf(
        "grey, 1 bit" to image(1, KiteColorSpace.DeviceGray),
        "grey, 1 bit, inverted" to image(1, KiteColorSpace.DeviceGray, decode = doubleArrayOf(1.0, 0.0)),
        "grey, 2 bits" to image(2, KiteColorSpace.DeviceGray),
        "grey, 4 bits" to image(4, KiteColorSpace.DeviceGray),
        "grey, 8 bits" to image(8, KiteColorSpace.DeviceGray),
        "grey, 8 bits, with a decode array" to image(8, KiteColorSpace.DeviceGray, decode = doubleArrayOf(0.9, 0.1)),
        "calibrated grey, 1 bit" to image(1, calGray),
        "calibrated grey, 8 bits" to image(8, calGray),
        "indexed, 1 bit" to image(1, KiteColorSpace.Indexed(KiteColorSpace.DeviceRGB, 1, byteArrayOf(10, 20, 30, -10, -20, -30))),
        "indexed, 8 bits, a short palette" to image(8, KiteColorSpace.Indexed(KiteColorSpace.DeviceRGB, 99, ByteArray(300) { (it * 7).toByte() })),
        "stencil" to image(1, null, isImageMask = true),
        "stencil, inverted" to image(1, null, isImageMask = true, decode = doubleArrayOf(1.0, 0.0)),
    )

    @Test
    fun samples_average_as_their_pixels_would() {
        for ((name, image) in cases()) {
            val whole = assertNotNull(image.toRgbaBytes(), name)
            for ((fx, fy) in listOf(1 to 2, 2 to 1, 2 to 2, 4 to 4, 8 to 8, 16 to 2, 32 to 32, 64 to 1, 3 to 5, 6 to 7, 256 to 256)) {
                assertContentEquals(shrinkRgba(whole, w, h, fx, fy), image.toShrunkRgbaBytes(fx, fy), "$name, $fx x $fy")
            }
        }
    }
}
