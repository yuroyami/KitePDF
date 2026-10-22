package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A book whose `rendition:flow` is `scrolled-continuous` or `scrolled-doc` lays each reflowable
 * chapter out as one page as tall as its content (EPUB 3.3, W3C tests lay-pkg-flow-scrolled-doc
 * and lay-pkg-flow-scrolled-continuous, #505).
 */
class ScrolledFlowTest {

    private val settings = EpubSettings(pageWidth = 300.0, pageHeight = 400.0, margin = 20.0)

    /** Enough text for several pages of 300 x 400 points, with an anchor near its end. */
    private val long = (1..60).joinToString("") { "<p${if (it == 55) " id=\"late\"" else ""}>Paragraph $it of a chapter long enough to fill several pages.</p>" }

    private fun chapter(body: String, html: String = "") =
        """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"$html><body>$body</body></html>"""

    /** A book of [chapters], each a body and the `properties` of its spine entry, whose package declares [flow]. */
    private fun book(flow: String?, chapters: List<Pair<String, String?>>, settings: EpubSettings = this.settings): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
            """<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val meta = flow?.let { """<meta property="rendition:flow">$it</meta>""" }.orEmpty()
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">""" +
            """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">x</dc:identifier>$meta</metadata>""" +
            """<manifest>${chapters.indices.joinToString("") { """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>""" }}</manifest>""" +
            """<spine>${chapters.indices.joinToString("") { i -> """<itemref idref="c$i"${chapters[i].second?.let { " properties=\"$it\"" }.orEmpty()}/>""" }}</spine></package>"""
        return EpubDocument.open(
            EpubFixtures.storedZip(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                ) + chapters.mapIndexed { i, (xhtml, _) -> "OEBPS/c$i.xhtml" to xhtml.encodeToByteArray() },
            ),
            settings,
        )
    }

    private fun EpubDocument.pagesOf(chapter: Int) = (0 until pageCountIn(chapter)).map { page(KiteLocation(chapter, it)) }

    @Test
    fun a_scrolled_book_lays_each_chapter_out_as_one_tall_page() {
        for (flow in listOf("scrolled-doc", "scrolled-continuous")) {
            val doc = book(flow, listOf(chapter(long) to null, chapter("<p>Short.</p>") to null))
            val pages = doc.pagesOf(0)
            assertEquals(1, pages.size, flow)
            assertEquals(300.0, pages[0].displayWidth, flow)
            assertTrue(pages[0].displayHeight > 3 * 400.0, "$flow: ${pages[0].displayHeight}")
            assertTrue("Paragraph 60 of" in assertNotNull(pages[0].textContent()).plainText, flow)
            // A short chapter keeps the reader's page height, so it never shows smaller than a page.
            assertEquals(1, doc.pageCountIn(1), flow)
            assertEquals(400.0, doc.pagesOf(1)[0].displayHeight, flow)
        }
    }

    @Test
    fun the_reader_setting_wins_over_the_book() {
        val paged = book("scrolled-doc", listOf(chapter(long) to null), settings.copy(scrolled = false))
        assertTrue(paged.pageCountIn(0) > 3, "${paged.pageCountIn(0)}")
        val scrolled = book(null, listOf(chapter(long) to null), settings.copy(scrolled = true))
        assertEquals(1, scrolled.pageCountIn(0))
        assertTrue(book(null, listOf(chapter(long) to null)).pageCountIn(0) > 3)
    }

    @Test
    fun a_spine_entry_overrides_the_book_flow() {
        val doc = book("scrolled-doc", listOf(chapter(long) to "rendition:flow-paginated", chapter(long) to null))
        assertTrue(doc.pageCountIn(0) > 3, "${doc.pageCountIn(0)}")
        assertEquals(1, doc.pageCountIn(1))
        val other = book("paginated", listOf(chapter(long) to "rendition:flow-scrolled-doc"))
        assertEquals(1, other.pageCountIn(0))
    }

    @Test
    fun a_page_break_does_not_cut_a_scrolled_chapter() {
        val body = "<p>Before.</p><p style=\"break-before: page\">After.</p>"
        assertEquals(2, book(null, listOf(chapter(body) to null)).pageCountIn(0))
        val doc = book("scrolled-doc", listOf(chapter(body) to null))
        assertEquals(1, doc.pageCountIn(0))
        assertEquals(400.0, doc.pagesOf(0)[0].displayHeight)
    }

    @Test
    fun a_fragment_gives_its_height_on_the_page() {
        val doc = book("scrolled-doc", listOf(chapter(long) to null))
        val mark = KiteBookmark.Flow(0, 0, fragment = "late")
        assertEquals(KiteLocation(0, 0), doc.locate(mark))
        val top = assertNotNull(doc.topOf(mark))
        val page = doc.pagesOf(0)[0]
        assertTrue(top > 0.8 * page.displayHeight && top < page.displayHeight, "$top of ${page.displayHeight}")
        // The line of paragraph 55 starts at that height, in display space with y down.
        val line = assertNotNull(page.textContent()).blocks.flatMap { it.lines }.first { "Paragraph 55 " in it.text }
        assertEquals(top, minOf(line.bounds.top, line.bounds.bottom), 16.0)
        assertNull(doc.topOf(KiteBookmark.Flow(0, 0)))
        assertNull(doc.topOf(KiteBookmark.Flow(0, 0, fragment = "nowhere")))
        // In a paginated book the height is on the anchor's own page.
        val paged = book(null, listOf(chapter(long) to null))
        val pagedTop = assertNotNull(paged.topOf(mark))
        assertTrue(paged.locate(mark).page > 0)
        assertTrue(pagedTop in 20.0..380.0, "$pagedTop")
    }

    @Test
    fun a_chapter_in_vertical_writing_stays_in_pages() {
        val doc = book("scrolled-doc", listOf(chapter("<style>html{writing-mode:vertical-rl}</style>$long") to null))
        assertTrue(doc.pageCountIn(0) > 1, "${doc.pageCountIn(0)}")
        assertNull(doc.topOf(KiteBookmark.Flow(0, 0, fragment = "late")))
    }
}
