package io.github.yuroyami.kitepdf.core.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bundled Adobe locale CMap tables. Expected CIDs come from the
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

    /** The CID and the byte count of the first code in [bytes] under the CMap [name]. */
    private fun cid(name: String, vararg bytes: Int): Pair<Int, Int> =
        PredefinedCMaps.reader(name).next(ByteArray(bytes.size) { bytes[it].toByte() }, 0)!!

    @Test
    fun unicode_keyed_cmaps_map_real_registry_cids() {
        // Expected CIDs come from the Adobe files, resolved through usecmap (#198).
        assertFalse(PredefinedCMaps.reader("UniJIS-UCS2-H").degraded)
        assertEquals(843 to 2, cid("UniJIS-UCS2-H", 0x30, 0x42)) // あ
        assertEquals(34 to 2, cid("UniJIS-UCS2-H", 0x00, 0x41)) // A
        assertEquals(264 to 2, cid("UniJIS-UCS2-HW-H", 0x00, 0x41)) // A, half-width
        assertEquals(634 to 2, cid("UniJIS-UCS2-H", 0x30, 0x01)) // 、
        assertEquals(7887 to 2, cid("UniJIS-UCS2-V", 0x30, 0x01)) // 、 in its vertical form
        assertEquals(4559 to 2, cid("UniGB-UCS2-H", 0x4E, 0x2D)) // 中
        assertEquals(661 to 2, cid("UniCNS-UCS2-H", 0x4E, 0x2D)) // 中
        assertEquals(1086 to 2, cid("UniKS-UCS2-H", 0xAC, 0x00)) // 가
    }

    @Test
    fun a_utf16_cmap_reads_a_surrogate_pair_as_one_code() {
        // D840 DC0B is U+2000B. As a 4-byte code it is above 7FFFFFFF, so codes compare unsigned.
        assertEquals(13839 to 4, cid("UniJIS-UTF16-H", 0xD8, 0x40, 0xDC, 0x0B))
        assertEquals(15861 to 4, cid("UniCNS-UTF16-H", 0xD8, 0x40, 0xDC, 0x21))
        assertEquals(843 to 2, cid("UniJIS-UTF16-H", 0x30, 0x42))
        assertEquals(4559 to 2, cid("UniGB-UTF16-H", 0x4E, 0x2D))
    }

    @Test
    fun every_bundled_cmap_decodes() {
        for (name in PredefinedCMapData.entries.keys) {
            val reader = PredefinedCMaps.reader(name)
            assertFalse(reader.degraded, name)
            assertNotNull(reader.next(byteArrayOf(0x30, 0x42, 0x30, 0x42), 0), name)
        }
        assertEquals("Japan1", PredefinedCMaps.ordering("UniJIS-UCS2-V"))
        assertEquals("GB1", PredefinedCMaps.ordering("GBK-EUC-H"))
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
