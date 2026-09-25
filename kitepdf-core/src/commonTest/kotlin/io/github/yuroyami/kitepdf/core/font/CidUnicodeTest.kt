package io.github.yuroyami.kitepdf.core.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [CidUnicode] against values read from the Adobe-*-UCS2 CMap files (#309). */
class CidUnicodeTest {

    @Test
    fun cids_of_the_four_collections_find_their_text() {
        assertEquals("あ", CidUnicode.text("Japan1", 843))
        assertEquals("あ", CidUnicode.text("Japan1", 526))
        assertEquals("A", CidUnicode.text("Japan1", 34))
        assertEquals("中", CidUnicode.text("GB1", 4559))
        assertEquals(" ", CidUnicode.text("CNS1", 1))
        assertEquals("가", CidUnicode.text("Korea1", 1086))
    }

    @Test
    fun a_cid_outside_the_basic_plane_keeps_its_surrogate_pair() {
        assertEquals("𨳝", CidUnicode.text("Japan1", 0x1DD9))
    }

    @Test
    fun an_unknown_collection_or_cid_has_no_text() {
        assertNull(CidUnicode.text("Identity", 843))
        assertNull(CidUnicode.text("Japan1", 1_000_000))
        assertNull(CidUnicode.text("Japan1", -1))
    }
}
