package io.github.yuroyami.kitepdf.core.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bundled face that draws a generic family where the host has none (#593). */
class StandardFaceOutlineTest {

    private val serif = FontSpec(KiteFontFamily.Serif, bold = false, italic = false)

    @Test
    fun a_letter_has_its_outline_in_glyph_space() {
        val outline = assertNotNull(standardFaceOutline("H", serif))
        val box = assertNotNull(outline.bounds())
        // Nimbus Roman's capital H stands on the baseline and is about two thirds of an em tall.
        assertTrue(box.bottom in -5.0..5.0 && box.top in 600.0..720.0, "H spans $box")
        assertTrue(box.width in 600.0..760.0, "H spans $box")
    }

    @Test
    fun each_family_weight_and_slant_has_its_own_face() {
        fun shapeOf(spec: FontSpec) = assertNotNull(standardFaceOutline("a", spec)).segments
        val faces = listOf(
            serif,
            serif.copy(bold = true),
            serif.copy(italic = true),
            FontSpec(KiteFontFamily.SansSerif, bold = false, italic = false),
            FontSpec(KiteFontFamily.Monospace, bold = false, italic = false),
        ).map(::shapeOf)
        for (i in faces.indices) for (j in i + 1 until faces.size) assertNotEquals(faces[i], faces[j], "faces $i and $j draw one a")
    }

    @Test
    fun letters_beyond_ascii_have_outlines() {
        // An accented Latin letter, a Cyrillic one and a curly quote, which a book uses all the time.
        for (text in listOf("é", "Ж", "’", "'")) assertNotNull(standardFaceOutline(text, serif), "no outline for $text")
    }

    @Test
    fun space_two_characters_and_a_character_the_face_lacks_have_none() {
        assertNull(standardFaceOutline(" ", serif))
        assertNull(standardFaceOutline("ab", serif))
        assertNull(standardFaceOutline("", serif))
        assertNull(standardFaceOutline("漢", serif))
        assertNull(standardFaceOutline("😀", serif))
    }

    private fun glyph(text: String) = TextGlyph(0, 1, -1, text, 500.0, null, text == " ")

    @Test
    fun a_run_takes_the_outlines_and_keeps_its_advances() {
        val run = listOf(glyph("a"), glyph(" "), glyph("\u00AD"), glyph("é"))
        val bundled = assertNotNull(standardFaceGlyphs(run, serif))
        assertEquals(run.map { it.advanceWidth }, bundled.map { it.advanceWidth })
        assertNotNull(bundled[0].outline)
        assertNull(bundled[1].outline, "a space drew")
        assertNull(bundled[2].outline, "a soft hyphen drew")
        assertNotNull(bundled[3].outline)
    }

    @Test
    fun a_run_with_a_character_the_face_lacks_keeps_the_host_text() {
        assertNull(standardFaceGlyphs(listOf(glyph("a"), glyph("漢")), serif))
    }
}
