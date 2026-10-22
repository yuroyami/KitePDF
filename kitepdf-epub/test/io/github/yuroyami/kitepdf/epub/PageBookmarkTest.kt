package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteBookmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * A page bookmark counts the pages of the whole book, so on a fresh book it lays out the chapters
 * before its page, and only those. It read as the start of the book instead (#349).
 */
class PageBookmarkTest {

    private val settings = EpubSettings(pageWidth = 200.0, pageHeight = 200.0, margin = 10.0)

    private val bodies = List(8) { c ->
        (0 until 30).joinToString("") { "<p>Chapter $c, paragraph $it, has enough words to fill a line.</p>" }
    }

    private fun open() = EpubDocument.open(EpubFixtures.epubMultiSpine(bodies), settings)

    @Test
    fun a_page_bookmark_finds_its_page_on_a_fresh_book() {
        val full = open().also { it.pageCount }
        for (index in listOf(0, 30, full.pageCount - 1)) {
            val expected = assertNotNull(full.locationOf(index))
            val fresh = open()
            assertEquals(expected, fresh.locate(KiteBookmark.Page(index)), "page $index")
            if (expected.chapter + 1 < fresh.chapterCount) {
                assertFalse(fresh.isChapterReady(expected.chapter + 1), "page $index laid out the chapters after its own")
            }
        }
    }

    @Test
    fun a_page_bookmark_past_the_end_finds_the_last_page() {
        val full = open().also { it.pageCount }
        val last = full.pageCount - 1
        assertEquals(full.locationOf(last), open().locate(KiteBookmark.Page(last + 1000)))
    }
}
