package io.github.yuroyami.kitepdf.epub

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [KiteShaper] against HarfBuzz (#945): the glyphs it gives a word, from the left, and where it
 * puts them. Skips without `hb-shape` or the Noto fonts of MuPDF's resources.
 */
class KiteShaperTest {

    private fun font(name: String): File =
        File(HarfBuzzOracle.fontsDir().orSkip("The Noto fonts of mupdf-master/resources"), name).takeIf { it.exists() }.orSkip(name)

    private fun check(fontName: String, words: List<String>) {
        val tool = HarfBuzzOracle.hbShape().orSkip("hb-shape")
        val file = font(fontName)
        val shaper = KiteShaper.load(file.readBytes())
        val expected = HarfBuzzOracle.positioned(tool, file, words)
        val scale = 1000.0 / shaper.unitsPerEm
        for ((i, word) in words.withIndex()) {
            val line = shaper.shape(word)
            assertEquals(expected[i].map { it.gid }, line.glyphs.map { it.gid }, "the glyphs of '$word'")
            var pen = 0
            for ((k, hb) in expected[i].withIndex()) {
                val g = line.glyphs[k]
                assertEquals((pen + hb.dx) * scale, g.x, 1.5 * scale + 0.01, "the x of glyph $k of '$word'")
                assertEquals(hb.dy * scale, g.y, 1.5 * scale + 0.01, "the y of glyph $k of '$word'")
                pen += hb.advance
            }
            assertEquals(pen * scale, line.width, 1.5 * scale * expected[i].size, "the width of '$word'")
        }
    }

    /** A conjunct, a vowel sign that goes before its consonant, and a reph. */
    @Test
    fun devanagari_shapes_as_harfbuzz_shapes_it() =
        check("NotoSerifDevanagari-Regular.otf", listOf("सत्य", "कि", "हिन्दी", "कर्म", "क्षत्रिय"))

    /** Joined letters, a lam-alef ligature and marks, drawn from the right. */
    @Test
    fun arabic_shapes_as_harfbuzz_shapes_it() =
        check("NotoNaskhArabic-Regular.otf", listOf("سلام", "العربية", "مُحَمَّد", "بِسْمِ"))

    @Test
    fun a_right_to_left_line_runs_from_the_right_and_keeps_its_text() {
        val shaper = KiteShaper.load(font("NotoNaskhArabic-Regular.otf").readBytes())
        val line = shaper.shape("سلام")
        assertTrue(line.rightToLeft)
        assertEquals(4, line.glyphs.size)
        // The last letter is drawn first, from the left; and each glyph is joined, so none is the isolated form.
        assertEquals("م", line.glyphs.first().text)
        assertEquals("س", line.glyphs.last().text)
        val isolated = "سلام".map { shaper.glyphId(it.code) }
        assertTrue(line.glyphs.map { it.gid }.reversed().zip(isolated).count { (a, b) -> a != b } >= 3, "the letters take their joining forms")
        assertEquals("سلام", line.glyphs.reversed().joinToString("") { it.text })
    }

    /** Words of both directions in one line keep their own order inside each run. */
    @Test
    fun a_mixed_line_orders_its_runs_by_uax9() {
        val shaper = KiteShaper.load(font("NotoNaskhArabic-Regular.otf").readBytes())
        val line = shaper.shape("abc سلام def", rightToLeft = false)
        val texts = line.glyphs.map { it.text }
        assertEquals("abc ", texts.take(4).joinToString(""))
        assertEquals(" def", texts.takeLast(4).joinToString(""))
        assertEquals("سلام", texts.subList(4, 8).reversed().joinToString(""))
        assertTrue(line.glyphs.zipWithNext().all { (a, b) -> b.x >= a.x - 0.001 || b.advance == 0.0 || a.advance == 0.0 }, "the pen runs to the right")
    }
}
