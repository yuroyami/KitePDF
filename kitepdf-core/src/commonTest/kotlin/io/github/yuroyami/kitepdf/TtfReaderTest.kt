package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.font.TtfReader
import kotlin.test.Test
import kotlin.test.assertEquals

class TtfReaderTest {

    @Test
    fun u16_reads_big_endian() {
        val r = TtfReader(byteArrayOf(0x12, 0x34))
        assertEquals(0x1234, r.u16())
    }

    @Test
    fun s16_handles_negative() {
        val r = TtfReader(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        assertEquals(-2, r.s16())
    }

    @Test
    fun u32_reads_big_endian_long() {
        val r = TtfReader(byteArrayOf(0x00, 0x01, 0x00, 0x00))
        assertEquals(0x00010000L, r.u32())
    }

    @Test
    fun tag_reads_four_ascii_chars() {
        val r = TtfReader("head".encodeToByteArray())
        assertEquals("head", r.tag())
    }

    @Test
    fun seek_then_read_back() {
        val r = TtfReader(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        r.seek(2)
        assertEquals(0x03, r.u8())
        r.seek(0)
        assertEquals(0x01, r.u8())
    }

    @Test
    fun skip_advances_position() {
        val r = TtfReader(byteArrayOf(0x00, 0x00, 0xAB.toByte()))
        r.skip(2)
        assertEquals(0xAB, r.u8())
    }
}
