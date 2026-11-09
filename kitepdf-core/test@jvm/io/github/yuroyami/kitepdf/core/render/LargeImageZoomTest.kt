package io.github.yuroyami.kitepdf.core.render

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An image above the raster limit still draws when it is drawn at more than half its size: it
 * averages down by one more power of two until it fits, where it drew as the grey placeholder
 * box (ISO 32000-1, 8.9.5; #748). A 600 dpi A3 scan is 70 megapixels.
 */
class LargeImageZoomTest {

    @Test
    fun a_seventy_megapixel_scan_draws_at_every_zoom() {
        val w = 7000
        val h = 10000
        // Rows of 8 black and 8 white, one bit a pixel.
        val stride = (w + 7) / 8
        val bits = ByteArray(stride * h) { i -> if ((i / stride) / 8 % 2 == 0) 0 else -1 }
        val image = KiteImageData(
            width = w, height = h, bitsPerComponent = 1, colorSpace = "DeviceGray", kind = KiteImageData.Kind.RAW,
            encodedBytes = ByteArray(0), pixelBytes = bits, resolvedColorSpace = KiteColorSpace.DeviceGray,
        )
        for (scale in listOf(0.25, 0.4, 0.5, 0.6, 1.0)) {
            val sampling = imageSampling(w, h, KiteMatrix(w * scale, 0.0, 0.0, h * scale, 0.0, 0.0), interpolate = false)
            val out = sampling.shrunkWidth(w).toLong() * sampling.shrunkHeight(h)
            assertTrue(out <= KITE_DEFAULT_MAX_RASTER_PIXELS, "at $scale the samples are $out pixels")
            assertNotNull(image.toSampledRgbaBytes(sampling), "at $scale the image draws as the placeholder")
        }
    }
}
