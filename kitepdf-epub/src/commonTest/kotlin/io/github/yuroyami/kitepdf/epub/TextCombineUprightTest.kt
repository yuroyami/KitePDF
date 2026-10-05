package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `text-combine-upright` and its `-epub-` names, tate-chu-yoko (CSS Writing Modes 3, 9.1, #508). */
class TextCombineUprightTest {

    private val settings = EpubSettings(pageWidth = 300.0, pageHeight = 400.0, fontSize = 10.0, margin = 20.0)

    private fun open(body: String, mode: String = "vertical-rl") =
        EpubDocument.open(EpubFixtures.epub("<style>html{writing-mode:$mode}</style>$body"), settings)

    private fun calls(body: String, mode: String = "vertical-rl") =
        RecordingCanvas().also { open(body, mode).pages[0].renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()

    private val RecordingCanvas.Call.Glyphs.turned: Boolean get() = abs(textToDevice.b) > 1e-9

    private fun RecordingCanvas.Call.Glyphs.advance() = glyphs.sumOf { it.advanceWidth } * fontSize / 1000.0

    /** Where the call's pen starts and ends across the page. */
    private fun RecordingCanvas.Call.Glyphs.across() =
        textToDevice.transformX(0.0, 0.0)..textToDevice.transformX(advance(), 0.0)

    private val names = listOf(
        "text-combine-upright:all", "-webkit-text-combine-upright:all", "-epub-text-combine-horizontal:all",
        "-epub-text-combine:horizontal", "-webkit-text-combine:horizontal", "-epub-text-combine:horizontal 2",
    )

    @Test
    fun the_digits_stand_upright_side_by_side_in_one_em() {
        for (style in names) {
            val runs = calls("<p>\u65E5<span style='$style'>10</span>\u672C</p>")
            val tcy = runs.single { it.text == "10" }
            assertTrue(!tcy.turned && tcy.textToDevice.a > 0.0, "$style: upright, ${runs.map { it.text to it.turned }}")
            val nichi = runs.single { it.text == "\u65E5" }
            val hon = runs.single { it.text == "\u672C" }
            // One em down the column each: the pair takes the place of one character.
            assertEquals(10.0, abs(nichi.textToDevice.f - tcy.textToDevice.f), 0.01, "$style: after \u65E5")
            assertEquals(10.0, abs(tcy.textToDevice.f - hon.textToDevice.f), 0.01, "$style: before \u672C")
            // Across the column, the pair is no wider than an em and centred where the ideograph is.
            val x = tcy.across()
            assertTrue(x.endInclusive - x.start <= 10.0 + 1e-9, "$style: $x")
            val centre = nichi.across().let { (it.start + it.endInclusive) / 2 }
            assertEquals(centre, (x.start + x.endInclusive) / 2, 0.01, "$style: centred on the column")
        }
    }

    @Test
    fun none_sets_the_digits_one_by_one() {
        for (style in listOf("", "text-combine-upright:none", "-epub-text-combine-horizontal:none", "-epub-text-combine:none")) {
            val runs = calls("<p>\u65E5<span style='$style'>10</span>\u672C</p>")
            val digits = runs.filter { r -> r.text.any { it.isDigit() } }
            assertTrue(digits.isNotEmpty() && digits.all { it.turned }, "$style: ${runs.map { it.text to it.turned }}")
        }
    }

    @Test
    fun a_long_number_is_squeezed_into_its_em() {
        val runs = calls("<p>\u65E5<span style='text-combine-upright:all'>2026</span>\u672C</p>")
        val tcy = runs.single { it.text == "2026" }
        val x = tcy.across()
        assertEquals(10.0, x.endInclusive - x.start, 0.01, "four digits fill the em: $x")
        assertTrue(tcy.textToDevice.a < 0.9, "drawn narrower than their own width: ${tcy.textToDevice}")
        assertEquals(1.0, abs(tcy.textToDevice.d), 1e-9, "at full height")
        val hon = runs.single { it.text == "\u672C" }
        assertEquals(10.0, abs(tcy.textToDevice.f - hon.textToDevice.f), 0.01, "and still one em")
    }

    @Test
    fun each_element_is_its_own_composition_and_children_inherit() {
        val runs = calls("<p>\u65E5<span style='-epub-text-combine-horizontal:all'>10</span><span style='-epub-text-combine-horizontal:all'>11</span>\u672C</p>")
        val pairs = runs.filter { r -> r.text.any { it.isDigit() } }
        assertEquals(listOf("10", "11"), pairs.map { it.text })
        assertEquals(10.0, abs(pairs[0].textToDevice.f - pairs[1].textToDevice.f), 0.01)
        val inner = calls("<p>\u65E5<span style='text-combine-upright:all'><b>10</b></span>\u672C</p>")
        assertTrue(inner.single { it.text == "10" }.let { !it.turned }, "${inner.map { it.text to it.turned }}")
    }

    @Test
    fun the_page_text_keeps_the_digits_inside_their_em() {
        val line = open("<p>\u65E5 <span style='text-combine-upright:all'>2026</span>\u672C</p>").pages[0].textContent().blocks.single().lines.single()
        assertEquals("\u65E5 2026\u672C", line.text)
        val e = line.charEdges
        assertEquals(10.0, abs(e[6] - e[2]), 0.01, "four digits in one em: ${e.toList()}")
        for (k in 3..5) assertTrue(e[k] > minOf(e[2], e[6]) && e[k] < maxOf(e[2], e[6]), "${e.toList()}")
        assertEquals(10.0, abs(e[7] - e[6]), 0.01, "${e.toList()}")
    }

    @Test
    fun one_emphasis_mark_for_the_pair() {
        val runs = calls("<p><span style='text-combine-upright:all;text-emphasis-style:dot'>10</span></p>")
        assertEquals(1, runs.filter { it.text == "\u2022" }.sumOf { it.glyphs.size }, "${runs.map { it.text }}")
    }

    @Test
    fun an_embedded_font_draws_the_composition_from_its_outlines() {
        val css = "@font-face{font-family:'L';src:url(f.ttf)}p{font-family:'L'}"
        val doc = EpubDocument.open(
            EpubFixtures.epub(
                "<style>html{writing-mode:vertical-rl}$css</style><p><span style='text-combine-upright:all'>fi</span></p>",
                listOf("OEBPS/f.ttf" to EpubFixtures.ligatureTtf()),
            ),
            settings,
        )
        val runs = RecordingCanvas().also { doc.pages[0].renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        val tcy = runs.single { it.text == "fi" }
        assertTrue(!tcy.turned && tcy.hasOutlines && tcy.glyphs.all { it.outline != null }, "${runs.map { it.text to it.hasOutlines }}")
        val x = tcy.across()
        assertTrue(x.endInclusive - x.start <= 10.0 + 1e-9, "$x")
    }

    @Test
    fun horizontal_text_does_not_change() {
        val plain = calls("<p>\u65E5<span>10</span>\u672C</p>", "horizontal-tb")
        val combined = calls("<p>\u65E5<span style='text-combine-upright:all'>10</span>\u672C</p>", "horizontal-tb")
        assertEquals(plain.map { it.text to it.textToDevice }, combined.map { it.text to it.textToDevice })
    }
}
