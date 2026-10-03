package io.github.yuroyami.kitepdf.webview

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The regions of a page that a web engine shows, and the ones it leaves to the library (#41). */
class WebIslandTest {

    @Test
    fun a_scripted_fixed_layout_page_is_one_island_over_the_page() {
        val doc = WebBooks.scriptedPage()
        val page = doc.page(KiteLocation(0, 0))
        val island = page.webIslands().single()
        assertEquals(EpubWebIslandKind.PAGE, island.kind)
        assertEquals("OEBPS/page.xhtml", island.href)
        // The viewport of 300 by 200 CSS pixels is the page, 225 by 150 points.
        assertEquals(225.0, island.rect.right - island.rect.left, 0.01)
        assertEquals(150.0, island.rect.top - island.rect.bottom, 0.01)
        assertEquals(300.0, island.contentWidth, 0.01)
        assertEquals(200.0, island.contentHeight, 0.01)
    }

    @Test
    fun an_inline_frame_is_an_island_over_its_box_and_the_rest_of_the_chapter_is_not() {
        val doc = WebBooks.quizChapter()
        val page = doc.page(KiteLocation(0, 0))
        val island = page.webIslands().single()
        val embed = page.embeds.single()
        assertEquals(EpubWebIslandKind.FRAME, island.kind)
        assertEquals("OEBPS/quiz.xhtml", island.href)
        assertEquals(embed.rect, island.rect)
        assertEquals(200.0, island.contentWidth, 0.5)
        assertEquals(100.0, island.contentHeight, 0.5)
        val text = page.textContent().blocks.flatMap { it.lines }.joinToString(" ") { it.text }
        assertTrue("Before the quiz." in text && "After the quiz." in text, "the chapter's own text stays the library's: $text")
    }

    @Test
    fun a_document_outside_the_book_is_an_island_only_when_asked_for_and_only_over_https() {
        val doc = WebBooks.book(
            items = listOf(WebBooks.Item("c.xhtml", "application/xhtml+xml", spine = true)),
            files = mapOf(
                "c.xhtml" to WebBooks.xhtml(
                    body = """<iframe src="https://example.org/quiz.html"></iframe><iframe src="http://example.org/quiz.html"></iframe>""" +
                        """<iframe src="javascript:alert(1)"></iframe><iframe src="missing.xhtml"></iframe><iframe></iframe>""",
                ),
            ),
            settings = EpubSettings(pageWidth = 400.0, pageHeight = 2000.0, margin = 36.0),
        )
        val page = doc.page(KiteLocation(0, 0))
        assertEquals(5, page.embeds.size)
        assertTrue(page.webIslands().isEmpty(), "nothing from the network, no script URL, no missing file, no empty frame")
        assertEquals(listOf("https://example.org/quiz.html"), page.webIslands(allowRemote = true).map { it.href })
    }

    @Test
    fun an_html_object_is_an_island_and_one_taller_than_a_page_keeps_its_fallback() {
        val fallback = (1..40).joinToString("") { "<p>Fallback $it.</p>" }
        val doc = WebBooks.book(
            items = listOf(
                WebBooks.Item("c.xhtml", "application/xhtml+xml", spine = true),
                WebBooks.Item("tall.xhtml", "application/xhtml+xml", spine = true),
                WebBooks.Item("quiz.xhtml", "application/xhtml+xml"),
            ),
            files = mapOf(
                "c.xhtml" to WebBooks.xhtml(body = """<object data="quiz.xhtml" type="application/xhtml+xml" width="200" height="100"><p>Fallback.</p></object>"""),
                "tall.xhtml" to WebBooks.xhtml(body = """<object data="quiz.xhtml" type="application/xhtml+xml" width="200" height="2000">$fallback</object>"""),
                "quiz.xhtml" to WebBooks.xhtml(body = "<p>Quiz.</p>"),
            ),
            settings = EpubSettings(pageWidth = 400.0, pageHeight = 600.0, margin = 36.0),
        )
        assertEquals(EpubWebIslandKind.OBJECT, doc.page(KiteLocation(0, 0)).webIslands().single().kind)
        assertTrue(doc.page(KiteLocation(1, 0)).webIslands().isEmpty())
    }
}
