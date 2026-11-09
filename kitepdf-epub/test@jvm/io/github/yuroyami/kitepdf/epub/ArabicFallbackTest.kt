package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * In a font with no GSUB, Arabic letters take the presentation forms that the font maps, and lam
 * with alef the lam-alef ligature, as HarfBuzz's Arabic fallback does (#636). The font of
 * `fonts/arabic-nogsub.ttf` maps a few letters and their forms and has no shaping tables;
 * `make-arabic-nogsub.py` writes it.
 */
class ArabicFallbackTest {

    private val face: EmbeddedFace = FontRegistry.face(
        "t", bold = false, italic = false,
        checkNotNull(ArabicFallbackTest::class.java.getResourceAsStream("/fonts/arabic-nogsub.ttf")).readBytes(),
    ) ?: error("the font does not parse")

    private fun glyphs(word: String) = HarfBuzzOracle.kitepdf(face, word)

    @Test
    fun letters_join_through_their_presentation_forms() {
        // U+0628 U+064A U+062A: beh initial, yeh medial, teh final.
        assertEquals(listOf(0xFE91, 0xFEF4, 0xFE96).map(face::gidFor), glyphs("بيت"))
        // A letter alone keeps its isolated form.
        assertEquals(listOf(0xFE8F).map(face::gidFor), glyphs("ب"))
    }

    @Test
    fun lam_and_alef_take_the_ligature() {
        // Lam alef alone is the isolated ligature, and after beh the final one.
        assertEquals(listOf(0xFEFB).map(face::gidFor), glyphs("لا"))
        assertEquals(listOf(0xFE91, 0xFEFC).map(face::gidFor), glyphs("بلا"))
    }
}
