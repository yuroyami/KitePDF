package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `text-orientation` and `-epub-text-orientation` in vertical text (CSS Writing Modes 3, 5.1, #508). */
class TextOrientationTest {

    private val settings = EpubSettings(pageWidth = 300.0, pageHeight = 400.0, fontSize = 10.0, margin = 20.0)

    private fun open(body: String, mode: String = "vertical-rl") =
        EpubDocument.open(EpubFixtures.epub("<style>html{writing-mode:$mode}</style>$body"), settings)

    private fun calls(style: String, text: String = "\u65E5\u672C\u8A9EABC", mode: String = "vertical-rl") =
        RecordingCanvas().also { open("""<p style="$style">$text</p>""", mode).pages[0].renderTo(it) }
            .calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()

    private val RecordingCanvas.Call.Glyphs.turned: Boolean get() = abs(textToDevice.b) > 1e-9

    private fun List<RecordingCanvas.Call.Glyphs>.latin() = filter { r -> r.text.any { it in 'A'..'Z' } }

    private fun List<RecordingCanvas.Call.Glyphs>.cjk() = filter { r -> r.text.any { it.code >= 0x3000 } }

    @Test
    fun mixed_stands_cjk_up_and_turns_latin() {
        for (style in listOf("", "text-orientation:mixed", "-epub-text-orientation:vertical-right")) {
            val runs = calls(style)
            assertTrue(runs.cjk().isNotEmpty() && runs.cjk().none { it.turned }, "$style: ${runs.map { it.text to it.turned }}")
            assertTrue(runs.latin().isNotEmpty() && runs.latin().all { it.turned }, "$style: ${runs.map { it.text to it.turned }}")
        }
    }

    @Test
    fun upright_stands_latin_letters_up_one_em_apart() {
        for (style in listOf("text-orientation:upright", "-epub-text-orientation:upright")) {
            val runs = calls(style)
            assertTrue(runs.isNotEmpty() && runs.none { it.turned }, "$style: ${runs.map { it.text to it.turned }}")
            val line = open("<p style=\"$style\">\u65E5\u672C\u8A9EABC</p>").pages[0].textContent().blocks.single().lines.single()
            assertEquals("\u65E5\u672C\u8A9EABC", line.text)
            for (k in 3 until 6) assertEquals(10.0, line.charEdges[k + 1] - line.charEdges[k], 0.01, "$style: ${line.charEdges.toList()}")
        }
    }

    @Test
    fun upright_letters_centre_on_the_column() {
        val runs = calls("text-orientation:upright", "IW")
        val x = runs.associate { it.text to it.textToDevice.e }
        assertTrue(x.getValue("I") > x.getValue("W") + 1.0, "a narrow letter starts further right to stand centred: $x")
    }

    @Test
    fun sideways_turns_cjk_as_well() {
        for (style in listOf("text-orientation:sideways", "-epub-text-orientation:sideways", "-epub-text-orientation:sideways-right")) {
            val runs = calls(style)
            assertTrue(runs.cjk().isNotEmpty() && runs.all { it.turned }, "$style: ${runs.map { it.text to it.turned }}")
        }
    }

    @Test
    fun it_inherits_and_an_unknown_value_leaves_it() {
        val runs = RecordingCanvas().also {
            open("<div style=\"text-orientation:upright\"><p style=\"text-orientation:bogus\">\u65E5\u672C\u8A9EABC</p></div>").pages[0].renderTo(it)
        }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        assertTrue(runs.latin().isNotEmpty() && runs.none { it.turned }, "${runs.map { it.text to it.turned }}")
    }

    @Test
    fun horizontal_text_does_not_change() {
        val plain = calls("", mode = "horizontal-tb")
        val upright = calls("text-orientation:upright", mode = "horizontal-tb")
        assertEquals(plain.map { it.text to it.textToDevice }, upright.map { it.text to it.textToDevice })
    }

    @Test
    fun an_embedded_font_stands_up_one_letter_an_em_with_no_ligature() {
        val css = "@font-face{font-family:'L';src:url(f.ttf)}p{font-family:'L';text-orientation:upright}"
        val doc = EpubDocument.open(
            EpubFixtures.epub("<style>html{writing-mode:vertical-rl}$css</style><p>fif</p>", listOf("OEBPS/f.ttf" to EpubFixtures.ligatureTtf())),
            settings,
        )
        val runs = RecordingCanvas().also { doc.pages[0].renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        assertTrue(runs.isNotEmpty() && runs.all { it.hasOutlines && !it.turned }, "${runs.map { it.text to it.turned }}")
        val glyphs = runs.flatMap { it.glyphs }
        assertEquals(listOf("f", "i", "f"), glyphs.map { it.text }, "upright letters do not join")
        assertTrue(glyphs.all { it.xOffset > 0.0 }, "each letter moves toward the middle of its em: ${glyphs.map { it.xOffset }}")
        val line = doc.pages[0].textContent().blocks.single().lines.single()
        for (k in 0 until 3) assertEquals(10.0, line.charEdges[k + 1] - line.charEdges[k], 0.01, "${line.charEdges.toList()}")
    }
}
