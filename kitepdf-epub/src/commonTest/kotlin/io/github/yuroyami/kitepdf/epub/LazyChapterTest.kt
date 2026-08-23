package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chapters lay out on demand. Opening at chapter 20 must not touch chapters 0
 * to 19, and what you get must match what an eager layout would have produced.
 */
class LazyChapterTest {

    private val settings = EpubSettings(pageWidth = 400.0, pageHeight = 640.0)

    private fun book(chapters: Int = 8): EpubDocument = EpubDocument.open(
        EpubFixtures.epubMultiSpine(
            List(chapters) { c ->
                "<h1 id=\"head$c\">Chapter ${c + 1}</h1>" +
                    (0 until 25).joinToString("") {
                        "<p>Chapter ${c + 1} paragraph $it, long enough to wrap onto a second line of text.</p>"
                    }
            },
        ),
        settings,
    )

    @Test
    fun opening_lays_nothing_out() {
        val doc = book()
        assertEquals(8, doc.chapterCount)
        for (c in 0 until doc.chapterCount) assertFalse(doc.isChapterReady(c), "chapter $c was laid out at open")
        assertFalse(doc.isComplete)
        assertEquals(0, doc.knownPageCount)
    }

    @Test
    fun preparing_a_late_chapter_leaves_the_earlier_ones_alone() {
        val doc = book()
        doc.prepareChapter(6)
        assertTrue(doc.isChapterReady(6))
        for (c in 0 until 6) assertFalse(doc.isChapterReady(c), "chapter $c should still be untouched")
        assertTrue(doc.pageCountIn(6) > 0)
        assertFalse(doc.isComplete)
    }

    @Test
    fun a_page_is_readable_from_its_location_alone() {
        val doc = book()
        val page = doc.page(KiteLocation(5, 0))
        assertTrue(page.textContent().plainText.contains("Chapter 6"))
        for (c in 0 until 5) assertFalse(doc.isChapterReady(c))
    }

    /** A global index cannot exist while the pages before it are uncounted. */
    @Test
    fun the_global_index_appears_only_once_the_prefix_is_laid_out() {
        val doc = book()
        doc.prepareChapter(3)
        assertNull(doc.pageIndexOf(KiteLocation(3, 0)), "chapters 0 to 2 are not counted yet")
        assertNull(doc.locationOf(0), "no chapter is ready, so no index resolves")

        for (c in 0 until 3) doc.prepareChapter(c)
        val index = assertNotNull(doc.pageIndexOf(KiteLocation(3, 0)))
        val expected = (0 until 3).sumOf { doc.pageCountIn(it) }
        assertEquals(expected, index)
        assertEquals(KiteLocation(3, 0), doc.locationOf(index))
    }

    /** Lazy and eager must agree, page for page. */
    @Test
    fun lazy_pages_match_eager_pages() {
        val bytes = EpubFixtures.epubMultiSpine(
            List(6) { c -> (0 until 20).joinToString("") { "<p>c$c p$it some words here to fill a line</p>" } },
        )
        val eager = EpubDocument.open(bytes, settings)
        val eagerText = (0 until eager.pageCount).map { eager.pages[it].textContent().plainText }

        // Same book, chapters prepared back to front.
        val lazyDoc = EpubDocument.open(bytes, settings)
        for (c in (lazyDoc.chapterCount - 1) downTo 0) lazyDoc.prepareChapter(c)
        val lazyText = buildList {
            for (c in 0 until lazyDoc.chapterCount) {
                for (p in 0 until lazyDoc.pageCountIn(c)) {
                    add(lazyDoc.page(KiteLocation(c, p)).textContent().plainText)
                }
            }
        }
        assertEquals(eagerText, lazyText, "preparing chapters back to front changed the pages")
    }

    @Test
    fun a_bookmark_survives_a_font_size_change() {
        val doc = book()
        val here = KiteLocation(4, 2)
        val words = doc.page(here).textContent().plainText.take(40)
        val mark = doc.bookmarkOf(here)
        assertEquals(4, mark.chapter)

        val bigger = doc.withFontSize(18.0)
        val moved = bigger.locate(mark)
        assertEquals(4, moved.chapter, "a bookmark never leaves its chapter")
        // The same words, on whatever page they landed on.
        val text = bigger.page(moved).textContent().plainText
        assertTrue(
            text.contains(words.take(20)),
            "expected to land near '${words.take(20)}' but got '${text.take(60)}'",
        )
    }

    @Test
    fun an_href_becomes_a_bookmark_without_laying_anything_out() {
        val doc = book()
        val mark = assertNotNull(doc.bookmarkOf("OEBPS/chapter4.xhtml#head3"))
        assertEquals(3, mark.chapter)
        assertEquals("head3", mark.fragment)
        for (c in 0 until doc.chapterCount) assertFalse(doc.isChapterReady(c), "chapter $c was laid out")

        val where = doc.locate(mark)
        assertEquals(KiteLocation(3, 0), where)
        assertTrue(doc.isChapterReady(3))
        for (c in 0 until doc.chapterCount) {
            if (c != 3) assertFalse(doc.isChapterReady(c), "locate touched chapter $c")
        }
    }

    @Test
    fun an_unknown_href_has_no_bookmark() {
        val doc = book()
        assertNull(doc.bookmarkOf("OEBPS/nope.xhtml"))
        assertNull(doc.bookmarkOf("https://example.org/x"))
    }

    /** A fragment beats an offset, and neither may throw when out of range. */
    @Test
    fun locate_clamps_instead_of_failing() {
        val doc = book(3)
        assertEquals(KiteLocation(2, 0), doc.locate(KiteBookmark.Flow(99, 0)))
        val last = doc.locate(KiteBookmark.Flow(1, charOffset = 10_000_000))
        assertEquals(1, last.chapter)
        assertEquals(doc.pageCountIn(1) - 1, last.page)
        assertEquals(KiteLocation(1, 0), doc.locate(KiteBookmark.Flow(1, 10_000_000, fragment = "head1")))
    }

    /** The table of contents must open without paginating the book. */
    @Test
    fun the_outline_carries_targets_and_forces_no_layout() {
        val doc = EpubDocument.open(EpubFixtures.epubWithToc(), settings)
        val outline = doc.outline
        assertTrue(outline.isNotEmpty())
        assertTrue(outline.all { it.target != null }, "every entry needs a target")
        assertTrue(outline.all { it.pageIndex == null }, "indices are unknown before layout")
        for (c in 0 until doc.chapterCount) assertFalse(doc.isChapterReady(c), "chapter $c was laid out")
    }
}
