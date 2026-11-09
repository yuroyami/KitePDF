package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Conjoining jamo join into the precomposed syllable that the font has, by the arithmetic of the
 * Unicode Standard, 3.12, as HarfBuzz's Hangul shaper does, and the jamo that stay apart take the
 * `ljmo`, `vjmo` and `tjmo` features (#635).
 */
class HangulJamoTest {

    private val everySyllable: (Int) -> Boolean = { it in 0xAC00..0xD7A3 }

    @Test
    fun a_leading_a_vowel_and_a_trailing_jamo_make_one_syllable() {
        // U+1100 U+1161 U+11A8 is U+AC01.
        val joined = TextShaper.composeHangul(intArrayOf(0x1100, 0x1161, 0x11A8), everySyllable)!!
        assertEquals(listOf(0xAC01), joined.codePoints.toList())
        assertEquals(listOf(0), joined.sources.toList())
        // Without a trailing jamo: U+AC00.
        assertEquals(listOf(0xAC00, 0x20), TextShaper.composeHangul(intArrayOf(0x1100, 0x1161, 0x20), everySyllable)!!.codePoints.toList())
        // A precomposed LV syllable and a trailing jamo: U+AC00 U+11A8 is U+AC01.
        assertEquals(listOf(0xAC01), TextShaper.composeHangul(intArrayOf(0xAC00, 0x11A8), everySyllable)!!.codePoints.toList())
    }

    @Test
    fun a_syllable_the_font_lacks_keeps_its_jamo() {
        assertNull(TextShaper.composeHangul(intArrayOf(0x1100, 0x1161, 0x11A8)) { false })
        // The LVT syllable is missing but the LV one is there: the trailing jamo stays apart.
        val partial = TextShaper.composeHangul(intArrayOf(0x1100, 0x1161, 0x11A8)) { it == 0xAC00 }!!
        assertEquals(listOf(0xAC00, 0x11A8), partial.codePoints.toList())
        assertEquals(listOf(0, 2), partial.sources.toList())
    }

    @Test
    fun a_word_of_several_syllables_joins_each() {
        // 한국 written as jamo: U+1112 U+1161 U+11AB U+1100 U+116E U+11A8.
        val joined = TextShaper.composeHangul(intArrayOf(0x1112, 0x1161, 0x11AB, 0x1100, 0x116E, 0x11A8), everySyllable)!!
        assertEquals("한국", joined.codePoints.joinToString("") { it.toChar().toString() })
        assertEquals(listOf(0, 3), joined.sources.toList())
    }

    @Test
    fun jamo_that_stay_apart_take_their_feature() {
        assertEquals(listOf("ljmo", "vjmo", "tjmo", null), listOf(0x1100, 0x1161, 0x11A8, 0xAC00).map(TextShaper::jamoFeature))
    }
}
