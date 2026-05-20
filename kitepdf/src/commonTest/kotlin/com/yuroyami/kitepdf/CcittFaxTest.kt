package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.filters.CcittFaxFilter
import com.yuroyami.kitepdf.parser.PdfBoolean
import com.yuroyami.kitepdf.parser.PdfDictionary
import com.yuroyami.kitepdf.parser.PdfInt
import com.yuroyami.kitepdf.parser.PdfObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Tests for the CCITTFaxDecode filter. Each test hand-encodes a tiny
 * known-bitmap and verifies the decoder produces the expected 1bpp output.
 *
 * Bit polarity reminder: PDF default has 0 = drawn (black), 1 = background
 * (white). /BlackIs1 inverts.
 */
class CcittFaxTest {

    @Test
    fun group4_all_white_8col_row() {
        // V0 (`1`) then EOFB. EOFB = 000000000001_000000000001 (24 bits).
        // Layout after the 1-bit V0:
        //   byte0 bit 0 = 1 (V0); bits 1-7 = 0    ⇒ 0x80
        //   byte1: bits 0-3 = 0, bit 4 = 1, rest 0 ⇒ 0x08
        //   byte2 = 0x00
        //   byte3 bit 0 = 1                        ⇒ 0x80
        val input = byteArrayOf(0x80.toByte(), 0x08, 0x00, 0x80.toByte())
        val out = decode(input, K = -1, columns = 8, rows = 1)
        assertContentEquals(byteArrayOf(0xFF.toByte()), out)
    }

    @Test
    fun group4_all_black_8col_row() {
        // Horizontal (`001`) + white run-0 (00110101) + black run-8 (000101)
        // = 3 + 8 + 6 = 17 bits, then EOFB.
        //
        // Bit layout:
        //   bits 0..2  = 001 (Horizontal)
        //   bits 3..10 = 00110101 (white-0)
        //   bits 11..16 = 000101 (black-8)
        //   bits 17+    = EOFB
        //
        // byte0 = 0010 0110           = 0x26
        // byte1 = 1010 0010           = 0xA2
        // byte2 = 1 + EOFB[0..6]      = 1000 0000 = 0x80
        // byte3 = EOFB[7..14]         = 0000 1000 = 0x08
        // byte4 = EOFB[15..22]        = 0000 0000 = 0x00
        // byte5 = EOFB[23] + padding  = 1000 0000 = 0x80
        val input = byteArrayOf(
            0x26, 0xA2.toByte(), 0x80.toByte(),
            0x08, 0x00, 0x80.toByte(),
        )
        val out = decode(input, K = -1, columns = 8, rows = 1)
        // All-black with BlackIs1=false: pixel 1 (black) → bit 0 → byte 0x00.
        assertContentEquals(byteArrayOf(0x00), out)
    }

    @Test
    fun group4_all_white_with_blackIs1_inverts_output() {
        val input = byteArrayOf(0x80.toByte(), 0x08, 0x00, 0x80.toByte())
        val out = decode(input, K = -1, columns = 8, rows = 1, blackIs1 = true)
        // BlackIs1=true: pixel 0 (white) → bit 0 → byte 0x00.
        assertContentEquals(byteArrayOf(0x00), out)
    }

    @Test
    fun group3_1d_all_white_8col_row() {
        // White run-8 = `10011` (5 bits). Padded byte: 1001 1000 = 0x98.
        // /EndOfBlock=false because Group 3 1D doesn't have EOFB; we use /Rows.
        val input = byteArrayOf(0x98.toByte())
        val out = decode(input, K = 0, columns = 8, rows = 1, endOfBlock = false)
        assertContentEquals(byteArrayOf(0xFF.toByte()), out)
    }

    @Test
    fun group3_1d_all_black_8col_row() {
        // Each line starts with a (possibly zero-length) WHITE run.
        // white run-0 = 00110101 (8 bits)
        // black run-8 = 000101  (6 bits)
        // Total 14 bits, padded to 16:
        //   byte0 = 0011 0101 = 0x35
        //   byte1 = 0001 01 + 00 = 0001 0100 = 0x14
        val input = byteArrayOf(0x35, 0x14)
        val out = decode(input, K = 0, columns = 8, rows = 1, endOfBlock = false)
        assertContentEquals(byteArrayOf(0x00), out)
    }

    @Test
    fun group4_two_white_rows_produces_two_bytes() {
        // V0 V0 (`1 1`) then EOFB.
        // bits: 11 + 22 zeros + 1 + ...
        // Actually EOFB = 12 bits + 12 bits = 24 bits.
        // After 2 bits of V0, we need 24 EOFB bits.
        //   byte0 bit 0 = 1, bit 1 = 1, bits 2..7 = 0   ⇒ 1100 0000 = 0xC0
        //   bits of EOFB start at byte0 bit 2:
        //     EOFB bit 0..5 = byte0 bits 2..7 = 0
        //     EOFB bit 6..13 = byte1 bits 0..7
        //     EOFB bit 14..21 = byte2 bits 0..7
        //     EOFB bit 22..23 = byte3 bits 0..1
        //   EOFB pattern: 1 at positions 11 and 23.
        //     position 11 = byte1 bit 5 = 1
        //     position 23 = byte3 bit 1 = 1
        //   ⇒ byte1 = 0000 0100 = 0x04
        //   ⇒ byte2 = 0x00
        //   ⇒ byte3 = 0100 0000 = 0x40
        val input = byteArrayOf(0xC0.toByte(), 0x04, 0x00, 0x40)
        val out = decode(input, K = -1, columns = 8, rows = 2)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), out)
    }

    @Test
    fun supports_columns_not_multiple_of_8() {
        // 5 columns of all white, G4. 5 pixels packed in 1 byte → top 5 bits set.
        // 11111000 = 0xF8.
        val input = byteArrayOf(0x80.toByte(), 0x08, 0x00, 0x80.toByte())
        val out = decode(input, K = -1, columns = 5, rows = 1)
        assertContentEquals(byteArrayOf(0xF8.toByte()), out)
    }

    /* ─── Helper ──────────────────────────────────────────────────────────── */

    private fun decode(
        bytes: ByteArray,
        K: Int,
        columns: Int,
        rows: Int = 0,
        blackIs1: Boolean = false,
        endOfBlock: Boolean = true,
    ): ByteArray {
        val entries: MutableMap<String, PdfObject> = mutableMapOf(
            "K" to PdfInt(K.toLong()),
            "Columns" to PdfInt(columns.toLong()),
            "Rows" to PdfInt(rows.toLong()),
            "BlackIs1" to PdfBoolean(blackIs1),
            "EndOfBlock" to PdfBoolean(endOfBlock),
        )
        return CcittFaxFilter.decode(bytes, PdfDictionary(entries))
    }
}
