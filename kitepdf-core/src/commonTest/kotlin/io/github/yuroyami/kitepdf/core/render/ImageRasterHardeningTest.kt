package io.github.yuroyami.kitepdf.core.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageRasterHardeningTest {

    @Test
    fun hostile_dimensions_are_rejected_before_rgba_allocation() {
        assertNull(raw(width = 40_000_001, height = 1).toRgbaBytes())
        assertNull(raw(width = Int.MAX_VALUE, height = Int.MAX_VALUE).toRgbaBytes())
    }

    @Test
    fun a_truncated_large_image_is_rejected_before_rgba_allocation() {
        assertNull(
            raw(
                width = 20_000_000,
                height = 2,
                colorSpace = KiteColorSpace.DeviceRGB,
                bytes = byteArrayOf(1, 2, 3),
            ).toRgbaBytes(),
        )
    }

    @Test
    fun unsupported_component_depth_is_rejected() {
        assertNull(raw(width = 1, height = 1, bits = 32).toRgbaBytes())
    }

    @Test
    fun hostile_soft_mask_dimensions_do_not_overflow_index_math() {
        val rgba = raw(
            width = 1,
            height = 1,
            bytes = byteArrayOf(0x2A),
            softMask = byteArrayOf(0),
            softMaskWidth = Int.MAX_VALUE,
            softMaskHeight = Int.MAX_VALUE,
        ).toRgbaBytes()!!
        assertEquals(0xFF, rgba[3].toInt() and 0xFF, "an invalid mask is ignored")
    }

    private fun raw(
        width: Int,
        height: Int,
        bits: Int = 8,
        colorSpace: KiteColorSpace = KiteColorSpace.DeviceGray,
        bytes: ByteArray = byteArrayOf(0),
        softMask: ByteArray? = null,
        softMaskWidth: Int = 0,
        softMaskHeight: Int = 0,
    ): KiteImageData = KiteImageData(
        width = width,
        height = height,
        bitsPerComponent = bits,
        colorSpace = "test",
        kind = KiteImageData.Kind.RAW,
        encodedBytes = ByteArray(0),
        pixelBytes = bytes,
        softMaskAlpha = softMask,
        softMaskWidth = softMaskWidth,
        softMaskHeight = softMaskHeight,
        resolvedColorSpace = colorSpace,
    )
}
