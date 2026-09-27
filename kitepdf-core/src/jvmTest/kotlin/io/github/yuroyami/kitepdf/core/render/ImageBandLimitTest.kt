package io.github.yuroyami.kitepdf.core.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The pixel limit applies to the output of a shrink, so a scan above 40 megapixels draws when it
 * is drawn small. It drew as a grey box however small it was drawn (#381).
 */
class ImageBandLimitTest {

    @Test
    fun a_scan_above_the_pixel_limit_converts_when_it_shrinks_below_it() {
        // 48 megapixels at 1 bit each: 6 MB packed, 192 MB as one RGBA array.
        val width = 8_000
        val height = 6_000
        val scan = KiteImageData(
            width = width, height = height, bitsPerComponent = 1, colorSpace = "test", kind = KiteImageData.Kind.RAW,
            encodedBytes = ByteArray(0), pixelBytes = ByteArray(width / 8 * height) { (it * 31).toByte() },
            resolvedColorSpace = KiteColorSpace.DeviceGray,
        )
        assertNull(scan.toRgbaBytes(), "the whole scan is above the limit")
        val small = assertNotNull(scan.toShrunkRgbaBytes(8, 8), "the shrunk scan is below the limit")
        assertEquals(1_000 * 750 * 4, small.size)
    }
}
