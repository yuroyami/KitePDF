package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each spine item is laid out and paginated on its own. Two things follow, and
 * everything incremental rests on them: a chapter starts on a fresh page, and a
 * chapter's pages do not depend on the chapters around it.
 */
class ChapterPaginationTest {

    private fun pageText(doc: EpubDocument, index: Int): String =
        doc.pages[index].textContent().plainText

    @Test
    fun a_short_chapter_does_not_share_its_page_with_the_next_one() {
        val doc = EpubDocument.open(
            EpubFixtures.epubMultiSpine(
                listOf(
                    "<p>ALPHA one line only</p>",
                    "<p>BRAVO also one line</p>",
                    "<p>CHARLIE the third</p>",
                ),
            ),
            EpubSettings(pageWidth = 400.0, pageHeight = 640.0),
        )

        assertEquals(3, doc.pageCount, "three one-line chapters are three pages, not one")
        assertTrue(pageText(doc, 0).contains("ALPHA"))
        assertTrue(pageText(doc, 1).contains("BRAVO"))
        assertTrue(pageText(doc, 2).contains("CHARLIE"))
        // The point of the change: no page carries two chapters.
        for (i in 0 until doc.pageCount) {
            val text = pageText(doc, i)
            val chapters = listOf("ALPHA", "BRAVO", "CHARLIE").count { text.contains(it) }
            assertEquals(1, chapters, "page $i carries content from $chapters chapters")
        }
    }

    /**
     * The invariant every later phase depends on: a chapter's pages are the same
     * whether the chapters before it were laid out or not. Proven here by
     * paginating the same book at different truncation points and comparing the
     * shared prefix of each chapter's pages.
     */
    @Test
    fun a_chapter_paginates_the_same_whatever_its_neighbours_do() {
        val bodies = List(6) { c ->
            buildString {
                append("<h1>Chapter ${c + 1}</h1>")
                repeat(12 + c * 7) { append("<p>Chapter ${c + 1} paragraph $it with enough words to wrap a line or two.</p>") }
            }
        }
        val settings = EpubSettings(pageWidth = 400.0, pageHeight = 640.0)
        val whole = EpubDocument.open(EpubFixtures.epubMultiSpine(bodies), settings)

        // Chapter boundaries in the full book, found through the anchor map.
        val starts = bodies.indices.map { requireNotNull(whole.pageOf("OEBPS/chapter${it + 1}.xhtml")) }
        val ends = starts.drop(1) + whole.pageCount

        for (c in bodies.indices) {
            // The same chapter, alone in its own book.
            val alone = EpubDocument.open(EpubFixtures.epubMultiSpine(listOf(bodies[c])), settings)
            val inBook = (starts[c] until ends[c]).map { whole.pages[it].textContent().plainText }
            val standalone = (0 until alone.pageCount).map { alone.pages[it].textContent().plainText }
            assertEquals(
                standalone, inBook,
                "chapter ${c + 1} paginates differently alone than it does inside the book",
            )
        }
    }

    /** A spine document with nothing to paint must not become a blank page. */
    @Test
    fun an_empty_chapter_contributes_no_page() {
        val doc = EpubDocument.open(
            EpubFixtures.epubMultiSpine(listOf("<p>ONE</p>", "", "<p>THREE</p>")),
            EpubSettings(pageWidth = 400.0, pageHeight = 640.0),
        )
        assertEquals(2, doc.pageCount, "the empty middle chapter adds no page")
        assertTrue(pageText(doc, 0).contains("ONE"))
        assertTrue(pageText(doc, 1).contains("THREE"))
    }

    /** Anchors still resolve to the right global page now that layout is chapter-local. */
    @Test
    fun anchors_resolve_across_chapter_boundaries() {
        val bodies = List(4) { c ->
            "<p id=\"top$c\">Chapter ${c + 1} start</p>" +
                (0 until 30).joinToString("") { "<p>filler $it for chapter ${c + 1} with several words</p>" } +
                "<p id=\"end$c\">Chapter ${c + 1} end</p>"
        }
        val doc = EpubDocument.open(
            EpubFixtures.epubMultiSpine(bodies),
            EpubSettings(pageWidth = 400.0, pageHeight = 640.0),
        )
        var previous = -1
        for (c in bodies.indices) {
            val path = "OEBPS/chapter${c + 1}.xhtml"
            val start = requireNotNull(doc.pageOf(path)) { "chapter ${c + 1} has no page" }
            val top = requireNotNull(doc.pageOf("$path#top$c"))
            val end = requireNotNull(doc.pageOf("$path#end$c"))
            assertEquals(start, top, "the chapter's first anchor is on its first page")
            assertTrue(start > previous, "chapter ${c + 1} starts after chapter $c")
            assertTrue(end >= start, "the end anchor is not before the start")
            assertTrue(doc.pages[top].textContent().plainText.contains("Chapter ${c + 1} start"))
            assertTrue(doc.pages[end].textContent().plainText.contains("Chapter ${c + 1} end"))
            previous = start
        }
    }
}
