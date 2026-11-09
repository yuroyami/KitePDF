package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * In a font with no GSUB for Thai, a mark takes the Private Use Area variant that the font maps,
 * as HarfBuzz's `do_thai_pua_shaping` does (#637). `fonts/thai-nogsub.ttf` maps a few consonants,
 * marks and variants; `make-thai-nogsub.py` writes it.
 */
class ThaiPuaFallbackTest {

    private val face: EmbeddedFace = FontRegistry.face(
        "t", bold = false, italic = false,
        checkNotNull(ThaiPuaFallbackTest::class.java.getResourceAsStream("/fonts/thai-nogsub.ttf")).readBytes(),
    ) ?: error("the font does not parse")

    private fun glyphs(word: String) = HarfBuzzOracle.kitepdf(face, word)

    @Test
    fun a_tone_mark_after_a_tall_consonant_and_an_upper_vowel_moves_left() {
        // U+0E1B U+0E34 U+0E48: the vowel and the tone mark both shift left, to U+F701 and U+F713.
        assertEquals(listOf(0x0E1B, 0xF701, 0xF713).map(face::gidFor), glyphs("ปิ่"))
    }

    @Test
    fun a_tone_mark_with_no_upper_vowel_moves_down() {
        // U+0E01 U+0E48: the tone mark shifts down, to U+F70A.
        assertEquals(listOf(0x0E01, 0xF70A).map(face::gidFor), glyphs("ก่"))
    }

    @Test
    fun a_consonant_drops_its_descender_before_a_lower_vowel() {
        // U+0E0D U+0E38: yo ying loses its descender, U+F70F, and the vowel keeps its place.
        assertEquals(listOf(0xF70F, 0x0E38).map(face::gidFor), glyphs("ญุ"))
    }
}
