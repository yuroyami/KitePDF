package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Text that sits directly in a floated or a cleared block. Its anonymous text box shares the style
 * of the block, so the float and the clear of the block must not apply to the text box again (#454).
 */
class FloatTextTest {

    private val settings = EpubSettings(pageWidth = 400.0, pageHeight = 600.0, margin = 36.0)

    /** The page text of [body], with white space folded, and the text of every glyph run drawn. */
    private fun textAndGlyphs(body: String): Pair<String, String> {
        val page = EpubDocument.open(EpubFixtures.epub(body), settings).page(KiteLocation(0, 0))
        val canvas = RecordingCanvas()
        page.renderTo(canvas)
        val drawn = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().joinToString(" ") { it.text }
        return page.textContent().plainText.replace(Regex("\\s+"), " ") to drawn
    }

    @Test
    fun text_directly_in_a_floated_block_is_laid_out_and_drawn() {
        for (style in listOf("float:left", "float:right", "float:left;width:100px")) {
            val (text, drawn) = textAndGlyphs("""<div style="$style">Floated words</div><p>After.</p>""")
            assertTrue("Floated words" in text, "$style: the page text is [$text]")
            assertTrue("Floated" in drawn && "words" in drawn, "$style: the glyphs drawn are [$drawn]")
            assertTrue(text.indexOf("After.") >= 0, "$style: the text after the float is laid out")
        }
    }

    @Test
    fun text_directly_in_a_cleared_block_wraps_beside_a_float_inside_it() {
        // 80 by 100 CSS pixels is 60 by 75 points. The text of the block starts beside the float.
        val doc = EpubDocument.open(
            EpubFixtures.epub(
                """<div style="clear:left;margin:0"><div style="float:left;width:80px;height:100px;margin:0"></div>Beside the float.</div>""",
            ),
            settings,
        )
        val line = doc.page(KiteLocation(0, 0)).textContent().blocks.flatMap { it.lines }.first { "Beside" in it.text }
        assertTrue(line.bounds.top < 36.0 + 75.0 - 5.0, "the text starts beside the float, not below it: top=${line.bounds.top}")
        assertTrue(line.bounds.left >= 36.0 + 60.0 - 1.0, "the text starts right of the float: left=${line.bounds.left}")
    }
}
