package sample

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.document.KiteDoc
import io.github.yuroyami.kitepdf.document.KiteDocFormat
import io.github.yuroyami.kitepdf.epub.EpubDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sample's generated book has to be a real EPUB, not just valid Kotlin. */
class DemoEpubTest {

    @Test
    fun the_demo_book_opens_as_an_epub() {
        assertEquals(KiteDocFormat.Epub, KiteDoc.formatOf(DemoEpub.book))
        val doc = KiteDoc.open(DemoEpub.book) as EpubDocument
        assertEquals(24, doc.chapterCount)
        assertEquals("A Book About Pages", doc.metadata.title)
        assertEquals(24, doc.tableOfContents.entries.size, "every chapter should be in the table of contents")
    }

    @Test
    fun opening_it_lays_out_nothing() {
        val doc = KiteDoc.open(DemoEpub.book) as EpubDocument
        assertFalse(doc.isComplete)
        assertEquals(0, doc.knownPageCount, "opening must not paginate the book")
    }

    @Test
    fun a_late_chapter_reads_without_the_ones_before_it() {
        val doc = KiteDoc.open(DemoEpub.book) as EpubDocument
        doc.prepareChapter(20)
        assertTrue(doc.isChapterReady(20))
        assertFalse(doc.isChapterReady(0), "chapter 1 should still be untouched")
        val text = doc.page(KiteLocation(20, 0)).textContent().plainText
        assertTrue("Chapter 21" in text, "the chapter should name itself: $text")
    }

    @Test
    fun a_bookmark_survives_a_font_size_change() {
        val doc = KiteDoc.open(DemoEpub.book) as EpubDocument
        val at = doc.locate(doc.bookmarkOf("OEBPS/Text/chapter12.xhtml#start")!!)
        val mark = doc.bookmarkOf(at)

        val bigger = doc.withFontSize(19.0)
        val moved = bigger.locate(mark)
        assertEquals(12 - 1, moved.chapter, "the reader stays in the same chapter")
        assertTrue(
            "Chapter 12" in bigger.page(moved).textContent().plainText,
            "the reader should land on the same words",
        )
    }
}
