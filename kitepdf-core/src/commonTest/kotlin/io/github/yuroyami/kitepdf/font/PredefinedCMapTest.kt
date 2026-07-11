package io.github.yuroyami.kitepdf.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-46: the bundled Adobe locale CMap tables. Expected CIDs come from the
 * Adobe cmap-resources files themselves (GBK-EUC-H chains to GBK-X).
 */
class PredefinedCMapTest {

    @Test
    fun gbk_euc_h_maps_real_registry_cids() {
        val reader = PredefinedCMaps.reader("GBK-EUC-H")
        assertFalse(reader.degraded, "bundled tables are not the degraded fallback")

        // 中 (GBK 0xD6D0) -> Adobe-GB1 CID 4559; 文 (0xCEC4) -> 3795; 'A' -> 846.
        val bytes = byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte(), 0x41)
        val units = ArrayList<Pair<Int, Int>>()
        var off = 0
        while (off < bytes.size) {
            val (cid, consumed) = reader.next(bytes, off) ?: break
            units.add(cid to consumed)
            off += consumed
        }
        assertEquals(listOf(4559 to 2, 3795 to 2, 846 to 1), units)
    }

    @Test
    fun shift_jis_cmap_segments_and_maps() {
        val reader = PredefinedCMaps.reader("90ms-RKSJ-H")
        assertFalse(reader.degraded)
        // ASCII 'A' is 1 byte; the 90ms-RKSJ-H resource maps 0x41 to
        // Adobe-Japan1 CID 264 (proportional roman).
        val (cid, consumed) = reader.next(byteArrayOf(0x41), 0)!!
        assertEquals(1, consumed)
        assertEquals(264, cid)
    }

    @Test
    fun unknown_names_keep_the_synthesized_fallback() {
        val reader = PredefinedCMaps.reader("NotARealCMap-H")
        assertTrue(reader.degraded, "unknown names stay on the degraded path")
    }

    @Test
    fun unmapped_codes_resolve_to_notdef() {
        val reader = PredefinedCMaps.reader("GBK-EUC-H")
        // 0x80 is inside the 1-byte codespace but has no CID entry.
        val (cid, consumed) = reader.next(byteArrayOf(0x80.toByte()), 0)!!
        assertEquals(1, consumed)
        assertEquals(0, cid)
    }
}
