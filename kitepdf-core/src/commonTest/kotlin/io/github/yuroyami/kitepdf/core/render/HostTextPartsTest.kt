package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.font.TextGlyph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How a canvas without a layout of its own cuts host text into parts (#588): a part whose
 * letters join or reorder is shaped as one string, any other glyph keeps the pen the document
 * gives it.
 */
class HostTextPartsTest {

    private fun glyphs(vararg letters: String, adjustAfter: Int = -1) = letters.mapIndexed { i, t ->
        TextGlyph(0, 1, -1, t, 500.0, null, false, advanceAdjust = if (i == adjustAfter) 100.0 else 0.0)
    }

    @Test
    fun letters_that_join_or_reorder_need_shaping() {
        for (text in listOf("ب", "ي", "ש", "क", "ि", "ไ", "ក", "́", "‍", "‮")) {
            assertTrue(needsShaping(text), "U+${text[0].code.toString(16)}")
        }
    }

    @Test
    fun letters_that_look_the_same_alone_do_not() {
        // A supplementary ideograph, U+20000, checks that a surrogate pair reads as one character.
        val ideograph = charArrayOf('\uD840', '\uDC00').concatToString()
        for (text in listOf("a", "é", "ß", "Ω", "Ж", "Ա", "ẞ", "—", "€", "中", "あ", "한", "Ａ", ideograph)) {
            assertFalse(needsShaping(text), text)
        }
    }

    @Test
    fun a_latin_run_is_one_part_that_keeps_its_pens() {
        val parts = hostTextParts(glyphs("a", "b", "c"), advanceScale = 0.01, adjustScale = 2.0)
        assertEquals(1, parts.size)
        assertFalse(parts[0].shaped)
        assertEquals(0.0, parts[0].x)
        assertEquals(15.0, parts[0].width, 1e-9)
    }

    @Test
    fun an_arabic_word_is_one_shaped_part_in_logical_order() {
        // بيت in visual order: teh, yeh, beh.
        val part = hostTextParts(glyphs("ت", "ي", "ب"), advanceScale = 0.01, adjustScale = 1.0).single()
        assertTrue(part.shaped)
        assertTrue(part.rightToLeft)
        assertEquals("‮بيت‬", part.text)
        assertEquals(15.0, part.width, 1e-9)
    }

    @Test
    fun a_lone_arabic_letter_is_not_shaped() {
        assertFalse(hostTextParts(glyphs("ب"), advanceScale = 0.01, adjustScale = 1.0).single().shaped)
    }

    @Test
    fun spacing_after_a_glyph_moves_the_parts_after_it() {
        // Word spacing after the first of two Arabic words: each word is a part, the second
        // starts after the first's advances and the spacing, scaled by adjustScale.
        val parts = hostTextParts(glyphs("ت", "ي", "ب", "ن", "م", adjustAfter = 2), advanceScale = 0.01, adjustScale = 2.0)
        assertEquals(listOf(0.0, 15.0 + 200.0), parts.map { it.x })
        assertEquals(listOf(true, true), parts.map { it.shaped })
    }

    @Test
    fun a_mixed_run_cuts_where_the_direction_changes() {
        val parts = hostTextParts(glyphs("a", "b", " ", "ت", "ي"), advanceScale = 0.01, adjustScale = 1.0)
        assertEquals(listOf(false, true), parts.map { it.rightToLeft })
        assertEquals(listOf(0.0, 15.0), parts.map { it.x })
        assertEquals(listOf(false, true), parts.map { it.shaped })
    }
}
