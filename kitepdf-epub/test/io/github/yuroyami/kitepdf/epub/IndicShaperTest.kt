package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.GsubGlyph
import io.github.yuroyami.kitepdf.core.font.OpenTypeGsub
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reordering of [IndicShaper] on a made-up Devanagari font whose `rphf` joins Ra and
 * virama into a reph and whose `half` joins Ka and virama into a half form (#211). The
 * glyph ids are the code points less 0x900, and 0x80 and on are the joined forms.
 */
class IndicShaperTest {

    private val ka = 0x15
    private val ssa = 0x37
    private val ra = 0x30
    private val virama = 0x4D
    private val iMatra = 0x3F
    private val reph = 0x80
    private val kaHalf = 0x81

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun cat(parts: List<ByteArray>) = parts.fold(ByteArray(0)) { a, b -> a + b }

    /** A ligature lookup joining [first] and [second] into [lig]. */
    private fun ligatureLookup(first: Int, second: Int, lig: Int): ByteArray {
        val rule = u16(lig) + u16(2) + u16(second)
        val set = u16(1) + u16(4) + rule
        val coverage = u16(1) + u16(1) + u16(first)
        val subtable = u16(1) + u16(8) + u16(1) + u16(8 + coverage.size) + coverage + set
        return u16(4) + u16(0) + u16(1) + u16(8) + subtable
    }

    /** GSUB for script `dev2`: feature 0 `rphf` with lookup 0, feature 1 `half` with lookup 1. */
    private val gsub: OpenTypeGsub by lazy { gsubFor("dev2") }

    /** The same GSUB under the script [tag]. */
    private fun gsubFor(tag: String): OpenTypeGsub {
        val langSys = u16(0) + u16(0xFFFF) + u16(2) + u16(0) + u16(1)
        val script = u16(4) + u16(0) + langSys
        val scriptList = u16(1) + tag.encodeToByteArray() + u16(8) + script
        val features = listOf("rphf" to 0, "half" to 1).map { (_, lookup) -> u16(0) + u16(1) + u16(lookup) }
        var featureOffset = 2 + 2 * 6
        val records = listOf("rphf", "half").mapIndexed { i, tag ->
            (tag.encodeToByteArray() + u16(featureOffset)).also { featureOffset += features[i].size }
        }
        val featureList = u16(2) + cat(records) + cat(features)
        val lookups = listOf(ligatureLookup(ra, virama, reph), ligatureLookup(ka, virama, kaHalf))
        val lookupList = u16(2) + u16(6) + u16(6 + lookups[0].size) + cat(lookups)
        val scriptOff = 10
        val featureOff = scriptOff + scriptList.size
        val lookupOff = featureOff + featureList.size
        return assertNotNull(OpenTypeGsub.from(u16(1) + u16(0) + u16(scriptOff) + u16(featureOff) + u16(lookupOff) + scriptList + featureList + lookupList))
    }

    private fun shape(vararg cps: Int): List<Int> {
        val glyphs = cps.mapIndexed { i, cp -> GsubGlyph(if (cp < 0x2000) cp - 0x900 else 0x7F, i) }.toMutableList()
        IndicShaper.shape(gsub, "dev2", glyphs, cps, { cp -> if (cp == 0x25CC) 0x7E else if (cp < 0x2000) cp - 0x900 else 0x7F }, optionalLigatures = true)
        return glyphs.map { it.gid }
    }

    @Test
    fun a_pre_base_matra_comes_first_and_a_reph_moves_to_the_end() {
        // र्कि: the reph forms, the i matra moves before Ka, and the reph moves after the base.
        assertEquals(listOf(iMatra, ka, reph), shape(0x930, 0x94D, 0x915, 0x93F))
    }

    @Test
    fun a_consonant_before_the_base_takes_its_half_form() {
        // क्षि: Ka takes its half form before Ssa, the base, and the matra leads.
        assertEquals(listOf(iMatra, kaHalf, ssa), shape(0x915, 0x94D, 0x937, 0x93F))
    }

    @Test
    fun a_zwnj_after_the_virama_ends_the_syllable() {
        // क्‌ष: no half form, and the ZWNJ stays as its own glyph.
        assertEquals(listOf(ka, virama, 0x7F, ssa), shape(0x915, 0x94D, 0x200C, 0x937))
    }

    @Test
    fun a_lone_matra_gets_a_dotted_circle() {
        assertEquals(listOf(iMatra, 0x7E), shape(0x93F))
    }

    @Test
    fun a_font_that_falls_back_to_its_default_script_is_not_reordered() {
        assertTrue(IndicShaper.handles("dev2", gsub))
        assertFalse(IndicShaper.handles("dev2", gsubFor("DFLT")))
        assertFalse(IndicShaper.handles("dev2", gsubFor("latn")))
        // A font whose only script is the misspelt dflt shapes by the old specification, as in HarfBuzz.
        assertTrue(IndicShaper.handles("dev2", gsubFor("dflt")))
    }

    @Test
    fun a_vowel_sequence_that_draws_like_another_vowel_gets_a_dotted_circle() {
        // अ and the aa matra would draw like आ, so a dotted circle goes between them.
        val prepared = IndicShaper.prepare("dev2", intArrayOf(0x0905, 0x093E)) { true }
        assertContentEquals(intArrayOf(0x0905, 0x25CC, 0x093E), prepared.codePoints)
        assertContentEquals(intArrayOf(0, 1, 1), prepared.sources)
    }

    @Test
    fun characters_decompose_and_compose_as_harfbuzz_normalizes_them() {
        // Qa stays decomposed, Nnna composes when the font has it, and a split matra stays split.
        assertContentEquals(intArrayOf(0x0915, 0x093C), IndicShaper.prepare("dev2", intArrayOf(0x0958)) { true }.codePoints)
        assertContentEquals(intArrayOf(0x0929), IndicShaper.prepare("dev2", intArrayOf(0x0928, 0x093C)) { true }.codePoints)
        assertContentEquals(intArrayOf(0x0995, 0x09C7, 0x09BE), IndicShaper.prepare("bng2", intArrayOf(0x0995, 0x09CB)) { true }.codePoints)
        // Without a glyph for the nukta, Qa stays whole.
        assertContentEquals(intArrayOf(0x0958), IndicShaper.prepare("dev2", intArrayOf(0x0958)) { it != 0x093C }.codePoints)
    }
}
