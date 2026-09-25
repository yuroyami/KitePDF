package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The normalization of [Normalizer] before GSUB, after HarfBuzz's default mode (#316). */
class NormalizerTest {

    private fun normalize(vararg cps: Int, hasGlyph: (Int) -> Boolean = { true }): Normalizer.Result =
        Normalizer.normalize(cps, IntArray(cps.size) { it }, hasGlyph)

    @Test
    fun the_tables_hold_the_decompositions_and_compositions_of_unicode() {
        assertContentEquals(intArrayOf(0x0065, 0x0301), Normalizer.decomposition(0x00E9))
        assertEquals(0x00E9, Normalizer.composition(0x0065, 0x0301))
        // Qa is a composition exclusion, and the Angstrom sign is a singleton.
        assertNull(Normalizer.composition(0x0915, 0x093C))
        assertContentEquals(intArrayOf(0x00C5), Normalizer.decomposition(0x212B))
        assertEquals(230, CombiningClass.canonical(0x0301))
        assertEquals(8, CombiningClass.canonical(0x3099))
        assertEquals(0, CombiningClass.canonical(0x0041))
    }

    @Test
    fun a_letter_and_its_mark_compose_when_the_font_has_the_result() {
        val composed = normalize(0x0065, 0x0301)
        assertContentEquals(intArrayOf(0x00E9), composed.codePoints)
        assertContentEquals(intArrayOf(0), composed.sources)
        // Without a glyph for é, the two stay apart.
        assertContentEquals(intArrayOf(0x0065, 0x0301), normalize(0x0065, 0x0301) { it != 0x00E9 }.codePoints)
    }

    @Test
    fun a_letter_the_font_lacks_decomposes() {
        assertContentEquals(intArrayOf(0x00E9), normalize(0x00E9).codePoints)
        assertContentEquals(intArrayOf(0x0065, 0x0301), normalize(0x00E9) { it != 0x00E9 }.codePoints)
    }

    @Test
    fun marks_sort_by_class_before_they_compose() {
        // a, circumflex, dot below: the dot below comes first and composes into ạ, then ậ.
        assertContentEquals(intArrayOf(0x1EAD), normalize(0x0061, 0x0302, 0x0323).codePoints)
        // Without ậ in the font, ạ keeps the circumflex as a mark, and both share one source.
        val partial = normalize(0x0061, 0x0302, 0x0323) { it != 0x1EAD && it != 0x1EA5 }
        assertContentEquals(intArrayOf(0x1EA1, 0x0302), partial.codePoints)
        assertContentEquals(intArrayOf(0, 0), partial.sources)
    }
}
