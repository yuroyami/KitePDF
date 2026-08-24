package io.github.yuroyami.kitepdf.cbz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageDimsTest {

    @Test
    fun png_ihdr() {
        val b = ByteArray(26)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(b)
        // IHDR width at 16..19, height at 20..23, big-endian
        b[16] = 0; b[17] = 0; b[18] = 0x01; b[19] = 0x40          // 320
        b[20] = 0; b[21] = 0; b[22] = 0x00; b[23] = 0xC8.toByte() // 200
        assertEquals(320 to 200, ImageDims.of(b))
    }

    @Test
    fun gif_logical_screen() {
        val b = "GIF89a".encodeToByteArray() + byteArrayOf(0x40, 0x01, 0xC8.toByte(), 0x00)
        assertEquals(320 to 200, ImageDims.of(b)) // little-endian u16 pair
    }

    @Test
    fun bmp_info_header_with_negative_height() {
        val b = ByteArray(26)
        b[0] = 'B'.code.toByte(); b[1] = 'M'.code.toByte()
        b[18] = 0x40; b[19] = 0x01                                 // width 320 LE
        // height -200 LE (top-down BMP); dimensions must come back positive
        b[22] = 0x38; b[23] = 0xFF.toByte(); b[24] = 0xFF.toByte(); b[25] = 0xFF.toByte()
        assertEquals(320 to 200, ImageDims.of(b))
    }

    @Test
    fun jpeg_sof0() {
        val b = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),                          // SOI
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x04, 0x00, 0x00,  // APP0, len 4
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B,              // SOF0, len 11
            0x08,                                                  // precision
            0x00, 0xC8.toByte(),                                   // height 200
            0x01, 0x40,                                            // width 320
            0x01, 0x01, 0x11, 0x00,                                // 1 component
        )
        assertEquals(320 to 200, ImageDims.of(b))
    }

    @Test
    fun unknown_bytes_are_null() {
        assertNull(ImageDims.of("not an image".encodeToByteArray()))
        assertNull(ImageDims.of(ByteArray(0)))
    }
}
