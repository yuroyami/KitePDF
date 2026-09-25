package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.OpenTypeGsub
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The text helpers and Unicode tables for characters outside the Basic Multilingual Plane (#319). */
class SupplementaryPlaneTest {

    @Test
    fun a_surrogate_pair_reads_as_one_code_point() {
        // a, MATHEMATICAL BOLD CAPITAL A, b
        val text = "a\uD835\uDC00b"
        assertEquals(0x1D400, codePointAt(text, 1))
        assertContentEquals(intArrayOf(0x61, 0x1D400, 0x62), codePointsOf(text))
        // A lone surrogate stays a character of its own.
        assertContentEquals(intArrayOf(0xD835, 0x62), codePointsOf("\uD835b"))
        assertEquals("\uD835\uDC00", CharText.of(0x1D400))
    }

    @Test
    fun the_unicode_tables_cover_every_plane() {
        // Brahmi: the script of ka, a spacing mark, the class of the virama.
        assertEquals("Brah", UnicodeScript.of(0x11013))
        assertEquals(CharCategory.COMBINING_SPACING_MARK, GeneralCategory.of(0x11000))
        assertEquals(9, CombiningClass.canonical(0x11046))
        // Kaithi dddha decomposes, and its parts compose back.
        assertContentEquals(intArrayOf(0x11099, 0x110BA), Normalizer.decomposition(0x1109A))
        assertEquals(0x1109A, Normalizer.composition(0x11099, 0x110BA))
        // Adlam joins on both sides, Hanifi Rohingya a joins on the left, and a musical mark is transparent.
        assertEquals(ArabicJoining.Jt.D, ArabicJoining.type(0x1E900))
        assertEquals(ArabicJoining.Jt.L, ArabicJoining.type(0x10D00))
        assertEquals(ArabicJoining.Jt.T, ArabicJoining.type(0x1D167))
        // The danda belongs to no one script, and a noncharacter has no script and no category.
        assertNull(UnicodeScript.of(0x0964))
        assertNull(UnicodeScript.of(0x2FFFE))
        assertEquals(CharCategory.UNASSIGNED, GeneralCategory.of(0x2FFFE))
    }

    @Test
    fun a_word_outside_the_bmp_takes_the_tag_of_its_script() {
        assertEquals("brah", TextShaper.script(intArrayOf(0x11013, 0x11046), OpenTypeGsub.EMPTY))
        assertEquals("adlm", TextShaper.script(intArrayOf(0x0964, 0x1E900), OpenTypeGsub.EMPTY))
        assertTrue(TextShaper.isRightToLeft("adlm"))
    }
}
