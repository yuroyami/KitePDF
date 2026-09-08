package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Laid-out pages stay within a memory budget. A chapter the reader left drops
 * its pages and keeps only what navigation needs; the pages come back, equal,
 * when the reader returns (#218).
 */
class LayoutBudgetTest {

    private fun book(chapters: Int = 6, budget: Long): EpubDocument = EpubDocument.open(
        EpubFixtures.epubMultiSpine(
            List(chapters) { c ->
                "<h1 id=\"head$c\">Chapter ${c + 1}</h1>" +
                    (0 until 25).joinToString("") {
                        "<p>Chapter ${c + 1} paragraph $it, long enough to wrap onto a second line of text.</p>"
                    }
            },
        ),
        EpubSettings(pageWidth = 400.0, pageHeight = 640.0, layoutCacheBytes = budget),
    )

    private fun draws(page: EpubPage): List<RecordingCanvas.Call> =
        RecordingCanvas().also { page.renderTo(it) }.calls

    @Test
    fun a_zero_budget_keeps_only_the_last_chapter_laid_out() {
        val doc = book(budget = 0)
        for (c in 0 until doc.chapterCount) doc.prepareChapter(c)
        assertTrue(doc.isComplete, "every chapter counts as ready once it has been laid out")
        assertEquals(1, doc.liveChapterCount, "only one chapter's pages may stay in memory")
        assertEquals((0 until doc.chapterCount).sumOf { doc.pageCountIn(it) }, doc.knownPageCount)
    }

    @Test
    fun an_evicted_chapter_renders_the_same_pages_when_it_comes_back() {
        val doc = book(budget = 0)
        val page = doc.page(KiteLocation(0, 1))
        val before = draws(page)
        val textBefore = page.textContent().plainText
        for (c in 1 until doc.chapterCount) doc.prepareChapter(c)
        // Reading another chapter makes chapter 0 the least recently used.
        draws(doc.page(KiteLocation(1, 0)))
        assertFalse(doc.isChapterLive(0), "chapter 0 should have been dropped")

        assertEquals(before, draws(page), "the page must paint the same after it is laid out again")
        assertEquals(textBefore, page.textContent().plainText)
        assertTrue(doc.isChapterLive(0), "rendering it brought the chapter back")
    }

    @Test
    fun the_page_object_survives_eviction() {
        val doc = book(budget = 0)
        val here = KiteLocation(0, 2)
        val page = doc.page(here)
        for (c in 1 until doc.chapterCount) doc.prepareChapter(c)
        assertFalse(doc.isChapterLive(0))
        assertSame(page, doc.page(here))
        assertEquals(here, page.location)
        assertEquals(400.0, page.displayWidth)
        assertEquals(640.0, page.displayHeight)
    }

    @Test
    fun navigation_reads_the_summary_and_lays_nothing_out() {
        val doc = book(budget = 0)
        val here = KiteLocation(0, 2)
        doc.prepareChapter(0)
        val expectedMark = doc.bookmarkOf(here)
        for (c in 1 until doc.chapterCount) doc.prepareChapter(c)
        assertFalse(doc.isChapterLive(0))

        assertEquals(2, doc.pageIndexOf(here))
        assertEquals(here, doc.locationOf(2))
        assertEquals(expectedMark, doc.bookmarkOf(here))
        assertEquals(here, doc.locate(expectedMark))
        assertEquals(0, doc.pageOf("OEBPS/chapter1.xhtml#head0"))
        assertEquals(doc.pageIndexOf(KiteLocation(3, 0)), doc.pageOf("OEBPS/chapter4.xhtml#head3"))
        assertFalse(doc.isChapterLive(0), "none of that needed the pages")
    }

    @Test
    fun a_chapter_in_use_outlives_chapters_laid_out_behind_the_reader() {
        // Room for about two chapters of this fixture.
        val doc = book(budget = 600_000)
        assertTrue(doc.page(KiteLocation(2, 0)).textContent().plainText.contains("Chapter 3"))
        for (c in listOf(0, 1, 3, 4, 5)) doc.prepareChapter(c)
        assertTrue(doc.isChapterLive(2), "the chapter being read must not be dropped for chapters laid out behind it")
        assertTrue(doc.liveChapterCount < doc.chapterCount, "the budget must have dropped something")
    }

    @Test
    fun a_lone_chapter_is_never_dropped() {
        val doc = book(chapters = 1, budget = 0)
        doc.prepareChapter(0)
        assertTrue(doc.isChapterLive(0))
        assertEquals(1, doc.liveChapterCount)
    }

    @Test
    fun search_reaches_an_evicted_chapter() {
        val doc = book(budget = 0)
        for (c in 0 until doc.chapterCount) doc.prepareChapter(c)
        assertFalse(doc.isChapterLive(0))
        val hit = assertNotNull(doc.search("Chapter 1 paragraph 3").firstOrNull())
        assertEquals(KiteLocation(0, 0), assertNotNull(doc.locationOf(hit.pageIndex)).let { KiteLocation(it.chapter, 0) })
    }
}
