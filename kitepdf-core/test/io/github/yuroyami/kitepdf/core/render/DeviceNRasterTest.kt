package io.github.yuroyami.kitepdf.core.render

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * A duotone or tritone image converts each distinct sample tuple once and gives the pixels that
 * converting each pixel on its own gives, also with more colours than its cache holds (#798).
 */
class DeviceNRasterTest {

    /** A Type 0 tint transform from [inputs] colorants to RGB, on a 9-point grid of each. */
    private fun tint(inputs: Int): KiteFunction.Type0 {
        val points = List(inputs) { 9 }
        val count = points.fold(1) { a, b -> a * b }
        return KiteFunction.Type0(
            DoubleArray(inputs * 2) { if (it % 2 == 0) 0.0 else 1.0 }, doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.0, 1.0),
            points.toIntArray(), 8, DoubleArray(inputs * 2) { if (it % 2 == 0) 0.0 else 8.0 },
            doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.0, 1.0), ByteArray(count * 3) { ((it * 97) xor (it ushr 2)).toByte() },
        )
    }

    private fun perPixel(image: KiteImageData, comps: Int): ByteArray {
        val space = image.resolvedColorSpace!!
        val src = image.pixelBytes!!
        val out = ByteArray(image.width * image.height * 4)
        for (p in 0 until image.width * image.height) {
            val rgb = space.toRgb(DoubleArray(comps) { (src[p * comps + it].toInt() and 0xFF) / 255.0 })
            out[p * 4] = (rgb.r * 255.0).roundToInt().toByte()
            out[p * 4 + 1] = (rgb.g * 255.0).roundToInt().toByte()
            out[p * 4 + 2] = (rgb.b * 255.0).roundToInt().toByte()
            out[p * 4 + 3] = 0xFF.toByte()
        }
        return out
    }

    @Test
    fun a_devicen_image_gives_the_colours_of_each_pixel_on_its_own() {
        for (comps in 2..3) {
            // 320 by 300 random tuples: far more distinct colours than the cache's 49,152 before it empties.
            val w = 320
            val h = 300
            val space = KiteColorSpace.DeviceN(comps, KiteColorSpace.DeviceRGB, tint(comps), List(comps) { "Ink$it" })
            var seed = 12345L
            val image = KiteImageData(
                width = w, height = h, bitsPerComponent = 8, colorSpace = "test", kind = KiteImageData.Kind.RAW,
                encodedBytes = ByteArray(0),
                pixelBytes = ByteArray(w * h * comps) {
                    // Half the pixels repeat a few colours, as a photograph does; the rest are noise.
                    seed = seed * 6364136223846793005L + 1442695040888963407L
                    if ((it / comps) % 2 == 0) ((it % comps) * 40).toByte() else (seed ushr 56).toByte()
                },
                resolvedColorSpace = space,
            )
            assertContentEquals(perPixel(image, comps), image.toRgbaBytes(), "$comps colorants")
        }
    }
}
