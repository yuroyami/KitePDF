package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A large page tree is a chapter that is not ready until [PdfDocument.prepareChapter] builds it,
 * so a viewer builds it off the main thread. The first page a viewer composed built every page
 * object of the document in its first frame (#387).
 */
class PageListDeferTest {

    /** One page tree node with [pages] empty pages that declares [count], and [filler] objects of no use. */
    private fun pdf(pages: Int, count: Int = pages, filler: Int = 0): PdfDocument {
        val kids = (0 until pages).joinToString(" ") { "${it + 3} 0 R" }
        val objects = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [$kids] /Count $count >>") +
            List(pages) { "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>" } + List(filler) { "0" }
        return PdfDocument.open(TestPdf.build(objects))
    }

    @Test
    fun a_large_page_tree_waits_for_prepare_chapter() {
        val doc = pdf(300)
        assertFalse(doc.isChapterReady(0))
        assertFalse(doc.isComplete)
        assertEquals(300, doc.pageCount, "the declared count answers before the list exists")
        doc.prepareChapter(0)
        assertTrue(doc.isChapterReady(0))
        assertTrue(doc.isComplete)
        assertEquals(300, doc.pageCountIn(0))
    }

    @Test
    fun a_small_page_tree_is_ready_at_once() {
        val doc = pdf(10)
        assertTrue(doc.isChapterReady(0))
        assertTrue(doc.isComplete)
    }

    @Test
    fun the_tree_decides_how_many_pages_a_deferred_list_holds() {
        // The count claims 300 pages and the tree holds 10. Enough other objects make 300 possible.
        val doc = pdf(10, count = 300, filler = 300)
        assertFalse(doc.isChapterReady(0))
        doc.prepareChapter(0)
        assertEquals(10, doc.pageCountIn(0))
    }

    @Test
    fun an_impossible_count_builds_the_list_on_first_use() {
        val doc = pdf(10, count = 1_000_000)
        assertTrue(doc.isChapterReady(0))
        assertEquals(10, doc.pageCountIn(0))
    }

    @Test
    fun a_page_of_a_deferred_list_prepares_its_chapter() {
        val doc = pdf(300)
        assertEquals(100.0, doc.page(KiteLocation(0, 250)).displayWidth)
        assertTrue(doc.isChapterReady(0))
    }
}
