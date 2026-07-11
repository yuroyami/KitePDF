package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.font.IdentityCodeUnitReader
import io.github.yuroyami.kitepdf.font.PredefinedCMaps
import io.github.yuroyami.kitepdf.font.SingleByteCodeUnitReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for the code-unit readers + composite-font helpers. The full Type 0
 * → CompositeFont integration is exercised by [FontPipelineTest].
 */
class CompositeFontTest {

    @Test
    fun identity_h_pairs_bytes_into_two_byte_cids() {
        val reader = IdentityCodeUnitReader
        val bytes = byteArrayOf(0x00, 0x41, 0x00, 0x42, 0x4E, 0x2D)
        val units = generateSequence(0) { it + 2 }
            .takeWhile { it < bytes.size }
            .map { reader.next(bytes, it)!! }
            .toList()
        assertEquals(3, units.size)
        assertEquals(0x0041, units[0].first)
        assertEquals(2, units[0].second)
        assertEquals(0x0042, units[1].first)
        assertEquals(0x4E2D, units[2].first)
    }

    @Test
    fun identity_h_handles_odd_trailing_byte_as_single_cid() {
        val reader = IdentityCodeUnitReader
        val bytes = byteArrayOf(0x00, 0x41, 0x99.toByte())
        val first = reader.next(bytes, 0)!!
        assertEquals(0x0041, first.first); assertEquals(2, first.second)
        val second = reader.next(bytes, 2)!!
        assertEquals(0x99, second.first); assertEquals(1, second.second)
        assertNull(reader.next(bytes, 3))
    }

    @Test
    fun single_byte_reader_emits_one_cid_per_byte() {
        val reader = SingleByteCodeUnitReader
        val bytes = byteArrayOf(0x41, 0x42, 0x43)
        var offset = 0
        val cids = mutableListOf<Int>()
        while (true) {
            val pair = reader.next(bytes, offset) ?: break
            cids.add(pair.first); offset += pair.second
        }
        assertEquals(listOf(0x41, 0x42, 0x43), cids)
    }

    @Test
    fun predefined_cmap_resolution() {
        assertSame(IdentityCodeUnitReader, PredefinedCMaps.reader("Identity-H"))
        assertSame(IdentityCodeUnitReader, PredefinedCMaps.reader("Identity-V"))
        assertSame(SingleByteCodeUnitReader, PredefinedCMaps.reader(null))
        assertSame(SingleByteCodeUnitReader, PredefinedCMaps.reader("WeirdUnknown"))
        // Locale CJK CMaps carry the bundled Adobe tables since T-46: real
        // registry CIDs, not the degraded CID==code fallback.
        val gbk = PredefinedCMaps.reader("GBK-EUC-H")
        assertTrue(!gbk.degraded, "bundled tables are authoritative")
        assertTrue(gbk !== IdentityCodeUnitReader && gbk !== SingleByteCodeUnitReader)
        // The Unicode-keyed CMaps stay synthesized (tables not bundled).
        val utf16 = PredefinedCMaps.reader("UniJIS-UTF16-H")
        assertTrue(utf16.degraded)
    }

    @Test
    fun cjk_reader_segments_mixed_width_ascii_and_kanji() {
        // 90ms-RKSJ-H is Shift-JIS: ASCII 'A' (0x41) is 1 byte, a kanji lead
        // 0x88 0x9F is 2 bytes. Widest-first would have swallowed 0x41 0x88.
        val reader = PredefinedCMaps.reader("90ms-RKSJ-H")
        val bytes = byteArrayOf(0x41, 0x88.toByte(), 0x9F.toByte(), 0x42)
        var offset = 0
        val widths = mutableListOf<Int>()
        while (true) {
            val pair = reader.next(bytes, offset) ?: break
            widths.add(pair.second); offset += pair.second
        }
        assertEquals(listOf(1, 2, 1), widths)
    }
}
