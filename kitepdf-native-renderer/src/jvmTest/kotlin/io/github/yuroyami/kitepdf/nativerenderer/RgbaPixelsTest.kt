package io.github.yuroyami.kitepdf.nativerenderer

import kotlin.test.Test
import kotlin.test.assertContentEquals

class RgbaPixelsTest {

    @Test
    fun packs_rgba_bytes_into_argb_ints() {
        val rgba = byteArrayOf(
            0xFF.toByte(), 0x00, 0x00, 0xFF.toByte(),  // opaque red
            0x00, 0x00, 0xFF.toByte(), 0x80.toByte(),  // half-alpha blue
        )
        assertContentEquals(
            intArrayOf(0xFFFF0000.toInt(), 0x800000FF.toInt()),
            RgbaPixels.toArgbInts(rgba),
        )
    }

    @Test
    fun truncated_tail_bytes_are_dropped() {
        val rgba = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        assertContentEquals(intArrayOf(0x04010203), RgbaPixels.toArgbInts(rgba))
    }
}
