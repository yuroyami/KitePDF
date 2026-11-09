package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.epub.HarfBuzzOracle.fontsDir
import io.github.yuroyami.kitepdf.epub.HarfBuzzOracle.hbShape
import io.github.yuroyami.kitepdf.epub.HarfBuzzOracle.loadFace
import io.github.yuroyami.kitepdf.epub.HarfBuzzOracle.positioned
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Urdu words lay out in Noto Nastaliq Urdu where HarfBuzz puts them (#877): the letters of a word
 * join by GPOS cursive attachment, after the font's contextual lookups have adjusted their
 * advances, so each rises on its slope, and the marks go with the letters they sit on. Glyph ids
 * alone cannot see a glyph in the wrong place (#622), so this compares each glyph's offsets and
 * advance. Skips without `hb-shape` or the Noto fonts of the MuPDF resources.
 */
class NastaliqOracleTest {

    private fun nastaliq(): File {
        val dir = fontsDir().orOracle("The Noto fonts of mupdf-master/resources")
        return listOf("NotoNastaliqUrdu.ttf", "NotoNastaliqUrdu-Regular.ttf", "NotoNastaliqUrdu-Regular.otf")
            .map { File(dir, it) }.firstOrNull { it.exists() }.orOracle("NotoNastaliqUrdu")
    }

    /** Common Urdu words, with and without marks, the issue's `نستعلیق` first. */
    private val words = listOf(
        "نستعلیق", "اردو", "پاکستان", "کتاب", "محبت", "زندگی", "خوبصورت", "لاہور", "کراچی", "دل", "سلام", "شکریہ",
        "آسمان", "زمین", "پانی", "روشنی", "دوست", "گھر", "بچہ", "کہانی", "مسکراہٹ", "حکومت", "تعلیم", "انسان", "دنیا",
        "خواب", "بارش", "ستارہ", "سمندر", "شہر", "چاند", "سورج", "ہوا", "پھول", "درخت", "ہمیشہ", "ضرور", "مہربانی",
        "بِسْمِ", "اَللّٰہ", "مُحَمَّد", "قُرْآن", "عِلْم", "سُبْحَان",
    )

    @Test
    fun urdu_words_lay_out_with_the_positions_harfbuzz_gives() {
        val tool = hbShape().orOracle("hb-shape")
        val font = nastaliq()
        val face = loadFace(font)
        // One word per paragraph, small enough that no word wraps, so each paragraph is one glyph run.
        val css = "@font-face{font-family:'N';src:url(n.ttf)} p{font-family:'N';font-size:40px;direction:rtl;margin:0 0 30px 0}"
        val body = "<body><style>$css</style>" + words.joinToString("") { "<p>$it</p>" } + "</body>"
        val doc = EpubDocument.open(EpubFixtures.epub(body, listOf("OEBPS/n.ttf" to font.readBytes()), language = "ur"))
        val runs = doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }
        assertEquals(words.size, runs.size, "one glyph run per word")
        val theirs = positioned(tool, font, words)
        val failures = ArrayList<String>()
        var rising = 0
        for ((i, word) in words.withIndex()) {
            val ours = runs[i].glyphs
            val hb = theirs[i]
            if (ours.map { it.gid } != hb.map { it.gid }) {
                failures += "$word: glyphs KitePDF ${ours.map { it.gid }}, HarfBuzz ${hb.map { it.gid }}"
                continue
            }
            if (hb.any { it.dy > 100 }) rising++
            for ((g, ref) in ours.zip(hb)) {
                // The advance is in thousandths of an em, the offsets in font units, as hb-shape prints them.
                val advance = ref.advance * 1000.0 / face.unitsPerEm
                if (abs(g.xOffset - ref.dx) > 1.0 || abs(g.yOffset - ref.dy) > 1.0 || abs(g.advanceWidth - advance) > 1.5) {
                    failures += "$word glyph ${g.gid}: KitePDF (${g.xOffset}, ${g.yOffset}, ${g.advanceWidth}), HarfBuzz (${ref.dx}, ${ref.dy}, $advance)"
                }
            }
        }
        println("nastaliq positions: ${failures.size} glyphs differ in ${words.size} words")
        // The words must rise, or this compares flat text with flat text.
        assertTrue(rising > words.size / 2, "only $rising of ${words.size} words rise in HarfBuzz")
        assertTrue(failures.isEmpty(), failures.take(30).joinToString("\n"))
    }
}
