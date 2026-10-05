package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An `<svg>` written in a chapter is content: its text is the page's text, and its ids are fragments (#523). */
class InlineSvgTextTest {

    private val figure = """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="60">""" +
        """<text id="label" x="10" y="30" font-size="20">Inside the figure</text></svg>"""

    @Test
    fun the_text_of_an_svg_in_a_chapter_reads_where_the_svg_stands() {
        val doc = EpubDocument.open(EpubFixtures.epub("<p>Before it</p>$figure<p>After it</p>"))
        assertEquals("Before it\n\nInside the figure\n\nAfter it", doc.pages.single().textContent().plainText)
    }

    @Test
    fun an_svg_that_an_img_shows_is_a_picture_whose_text_is_not_the_page_text() {
        val doc = EpubDocument.open(
            EpubFixtures.epub(
                """<p>Before it</p><img src="figure.svg" alt="A figure"/><p>After it</p>""",
                extraEntries = listOf("OEBPS/figure.svg" to figure.encodeToByteArray()),
            ),
        )
        val page = doc.pages.single()
        val drawn = RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        assertTrue(drawn.any { it.text == "Inside the figure" }, "the picture draws its text")
        assertEquals("Before it\n\nAfter it", page.textContent().plainText)
    }

    @Test
    fun an_element_inside_the_svg_is_a_fragment_on_the_page_that_shows_it() {
        val filler = (1..60).joinToString("") { "<p>Paragraph $it of the text before the figure.</p>" }
        val doc = EpubDocument.open(EpubFixtures.epub("$filler$figure<p>After it</p>"))
        val shown = doc.pages.indexOfFirst { "Inside the figure" in it.textContent().plainText }
        assertTrue(shown > 0, "the figure is on a later page: $shown of ${doc.pages.size}")
        assertEquals(shown, doc.pageOf("OEBPS/chapter1.xhtml#label"))
        val box = doc.locateFragment("OEBPS/chapter1.xhtml#label")
        assertEquals(KiteLocation(0, shown), box?.location)
        assertEquals(1, box?.rects?.size)
    }

    @Test
    fun an_svg_on_a_line_gives_its_text_and_its_ids() {
        val icon = """<svg xmlns="http://www.w3.org/2000/svg" width="60" height="20">""" +
            """<text id="mark" x="0" y="15" font-size="12">icon</text></svg>"""
        val doc = EpubDocument.open(EpubFixtures.epub("<p>Start <span>$icon</span> end</p>"))
        val text = doc.pages.single().textContent()
        assertEquals(listOf("Start end", "icon"), text.blocks.map { b -> b.lines.joinToString("\n") { it.text } })
        assertEquals(1, doc.locateFragment("OEBPS/chapter1.xhtml#mark")?.rects?.size)
    }
}
