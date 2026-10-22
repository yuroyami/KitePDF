package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An `<svg>` is inline, as an `<img>` is, so one in a sentence flows on its line (#580). */
class SvgOnTheLineTest {

    private val icon = """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="12">""" +
        """<rect id="dot" width="20" height="12" fill="#ff0000"/></svg>"""

    /** The baseline of each word drawn, and the box of the red picture, in display space. */
    private class Drawn(val words: Map<String, Pair<Double, Double>>, val picture: KiteRectangle)

    private fun draw(body: String): Drawn {
        val page = EpubDocument.open(EpubFixtures.epub(body)).pages.single()
        val calls = RecordingCanvas().also { page.renderTo(it, page.displayToDeviceBase()) }.calls
        val words = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().associate { it.text to (it.textToDevice.e to it.textToDevice.f) }
        val red = calls.filterIsInstance<RecordingCanvas.Call.Fill>().single { it.color.r > 0.9 && it.color.g < 0.1 }
        return Drawn(words, checkNotNull(red.path.bounds(red.ctm)))
    }

    @Test
    fun an_svg_in_a_sentence_sits_on_the_line_between_its_words() {
        val drawn = draw("<p>Press $icon to go on</p>")
        val baseline = drawn.words.getValue("Press").second
        assertTrue(drawn.words.values.all { it.second == baseline }, "one line: ${drawn.words}")
        val p = drawn.picture
        assertTrue(p.left > drawn.words.getValue("Press").first && p.right < drawn.words.getValue("to").first, "between the words: $p, ${drawn.words}")
        assertEquals(baseline, p.top, 1e-6)
    }

    @Test
    fun text_in_an_inline_element_beside_it_keeps_it_on_the_line() {
        val drawn = draw("<p><b>Press</b> $icon <i>to go on</i></p>")
        assertEquals(1, drawn.words.values.map { it.second }.distinct().size, "${drawn.words}")
        assertEquals(drawn.words.getValue("Press").second, drawn.picture.top, 1e-6)
    }

    @Test
    fun an_svg_that_a_style_sheet_makes_a_block_still_breaks_the_line() {
        val drawn = draw("""<p>Press ${icon.replace("<svg ", """<svg style="display: block" """)} to go on</p>""")
        val press = drawn.words.getValue("Press").second
        val to = drawn.words.getValue("to").second
        assertTrue(to > press, "the words after the block start a new line: ${drawn.words}")
        assertTrue(drawn.picture.bottom > press && drawn.picture.top < to, "the picture sits between the lines: ${drawn.picture}")
    }

    @Test
    fun an_svg_alone_in_its_block_keeps_a_box_of_its_own() {
        // As a cover page has it: no line, so no strut below the picture.
        val alone = draw("<div>$icon</div><p>After</p>")
        val inline = draw("<p>Press <span>$icon</span></p><p>After</p>")
        assertTrue(alone.words.getValue("After").second < inline.words.getValue("After").second, "no line height around it: ${alone.words} ${inline.words}")
    }

    @Test
    fun an_id_inside_an_svg_on_the_line_is_a_fragment() {
        val doc = EpubDocument.open(EpubFixtures.epub("<p>Press $icon to go on</p>"))
        val box = doc.locateFragment("OEBPS/chapter1.xhtml#dot")
        assertEquals(1, box?.rects?.size)
    }
}
