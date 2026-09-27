package io.github.yuroyami.kitepdf.core.render

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

/**
 * An image drawn smaller converts and shrinks a band of rows at a time, and gives the same pixels
 * as the whole image converted and then shrunk. It became one full-size RGBA array first (#381).
 */
class ImageBandShrinkTest {

    private val w = 37
    private val h = 53

    private fun image(
        bits: Int,
        space: KiteColorSpace?,
        bytes: ByteArray,
        softMask: ByteArray? = null,
        softMaskWidth: Int = 0,
        softMaskHeight: Int = 0,
        isImageMask: Boolean = false,
        colorKey: IntArray? = null,
        decode: DoubleArray? = null,
        matte: RgbColor? = null,
    ) = KiteImageData(
        width = w, height = h, bitsPerComponent = bits, colorSpace = "test", kind = KiteImageData.Kind.RAW,
        encodedBytes = ByteArray(0), pixelBytes = bytes,
        softMaskAlpha = softMask, softMaskWidth = softMaskWidth, softMaskHeight = softMaskHeight,
        resolvedColorSpace = space, decode = decode, isImageMask = isImageMask,
        maskFill = if (isImageMask) RgbColor(1.0, 0.0, 0.0) else null, colorKeyMask = colorKey, softMaskMatte = matte,
    )

    /** Bytes that vary with their position, so a band taken from the wrong rows shows. */
    private fun bytes(count: Int) = ByteArray(count) { ((it * 37) xor (it ushr 5)).toByte() }

    private fun rowBytes(components: Int, bits: Int) = (w * components * bits + 7) / 8

    private fun cases(): List<Pair<String, KiteImageData>> = listOf(
        "rgb, 8 bits" to image(8, KiteColorSpace.DeviceRGB, bytes(rowBytes(3, 8) * h)),
        "grey, 1 bit" to image(1, KiteColorSpace.DeviceGray, bytes(rowBytes(1, 1) * h)),
        "grey with a decode array" to image(8, KiteColorSpace.DeviceGray, bytes(w * h), decode = doubleArrayOf(1.0, 0.0)),
        "cmyk" to image(8, KiteColorSpace.DeviceCMYK, bytes(w * 4 * h)),
        "rgb, 16 bits" to image(16, KiteColorSpace.DeviceRGB, bytes(w * 6 * h)),
        "indexed, 4 bits" to image(4, KiteColorSpace.Indexed(KiteColorSpace.DeviceRGB, 15, bytes(48)), bytes(rowBytes(1, 4) * h)),
        "soft mask of the same size" to image(8, KiteColorSpace.DeviceRGB, bytes(w * 3 * h), bytes(w * h), w, h),
        "soft mask of another size" to image(8, KiteColorSpace.DeviceRGB, bytes(w * 3 * h), bytes(11 * 17), 11, 17),
        "short soft mask" to image(8, KiteColorSpace.DeviceRGB, bytes(w * 3 * h), bytes(w * h / 2), w, h),
        "matte" to image(8, KiteColorSpace.DeviceRGB, bytes(w * 3 * h), bytes(w * h), w, h, matte = RgbColor(0.2, 0.4, 0.6)),
        "colour key" to image(8, KiteColorSpace.DeviceGray, bytes(w * h), colorKey = intArrayOf(0, 100)),
        "stencil" to image(1, null, bytes(rowBytes(1, 1) * h), isImageMask = true),
        "space from the sample count" to image(8, null, bytes(w * 3 * h)),
    )

    @Test
    fun bands_give_the_pixels_of_the_whole_image() {
        for ((name, image) in cases()) {
            val whole = assertNotNull(image.toRgbaBytes(), name)
            for ((fx, fy) in listOf(2 to 2, 4 to 2, 1 to 8, 8 to 1, 64 to 64)) {
                val expected = shrinkRgba(whole, w, h, fx, fy)
                // Bands of 500 bytes hold a few rows here, so the image converts in many bands.
                assertContentEquals(expected, image.toShrunkRgbaBytes(fx, fy, bandBytes = 500), "$name, $fx x $fy, small bands")
                assertContentEquals(expected, image.toShrunkRgbaBytes(fx, fy), "$name, $fx x $fy, one band")
            }
        }
    }
}
