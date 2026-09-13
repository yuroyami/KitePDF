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

    @Test
    fun webp_lossless_lossy_and_extended_headers() {
        assertEquals(6 to 4, ImageDims.of(CbzFixtures.webpLossless6x4()))
        assertEquals(6 to 4, ImageDims.of(CbzFixtures.webpLossy6x4()))
        // VP8X: four flag bytes, then 24-bit canvas width-1 (319) and height-1 (199).
        val vp8x = "RIFF".encodeToByteArray() + byteArrayOf(30, 0, 0, 0) + "WEBPVP8X".encodeToByteArray() +
            byteArrayOf(10, 0, 0, 0, 0, 0, 0, 0, 0x3F, 0x01, 0, 0xC7.toByte(), 0, 0)
        assertEquals(320 to 200, ImageDims.of(vp8x))
    }

    @Test
    fun tiff_first_directory_in_either_byte_order() {
        fun tiff(le: Boolean): ByteArray {
            val b = ByteArray(38)
            fun p16(o: Int, v: Int) {
                if (le) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() } else { b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte() }
            }
            fun p32(o: Int, v: Int) = if (le) { p16(o, v and 0xFFFF); p16(o + 2, v ushr 16) } else { p16(o, v ushr 16); p16(o + 2, v and 0xFFFF) }
            b[0] = (if (le) 'I' else 'M').code.toByte(); b[1] = b[0]
            p16(2, 42); p32(4, 8); p16(8, 2)
            p16(10, 256); p16(12, 3); p32(14, 1); p16(18, 320) // width as a SHORT
            p16(22, 257); p16(24, 4); p32(26, 1); p32(30, 200) // height as a LONG
            return b
        }
        assertEquals(320 to 200, ImageDims.of(tiff(le = true)))
        assertEquals(320 to 200, ImageDims.of(tiff(le = false)))
    }
}
