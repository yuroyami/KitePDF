package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A chapter made of SVG images counts toward the layout budget: each page has a floor, and each
 * SVG file counts its size. It counted as nothing, so every such chapter stayed in memory (#396).
 */
class SvgChapterBudgetTest {

    /** [chapters] chapters, each one block image of its own copy of [svg]. */
    private fun book(svg: String, chapters: Int) = EpubFixtures.epubFoldered(
        List(chapters) { """<img style="display:block" src="../images/vector$it.svg"/>""" },
        extraEntries = List(chapters) { "OEBPS/images/vector$it.svg" to svg.encodeToByteArray() },
    )

    private fun liveChapters(doc: EpubDocument) = (0 until doc.chapterCount).count { doc.isChapterLive(it) }

    @Test
    fun a_zero_budget_keeps_one_svg_chapter() {
        val small = """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" fill="red"/></svg>"""
        // SVG files, and an svg element written in each chapter, which has no file to count.
        val books = mapOf(
            "files" to book(small, 8),
            "elements" to EpubFixtures.epubFoldered(List(8) { "<div>$small</div>" }),
        )
        for ((name, bytes) in books) {
            val doc = EpubDocument.open(bytes, EpubSettings(layoutCacheBytes = 0))
            for (c in 0 until doc.chapterCount) doc.prepareChapter(c)
            assertEquals(1, liveChapters(doc), "$name: a budget of zero keeps the chapter in use and no other")
        }
    }

    @Test
    fun the_size_of_an_svg_file_counts_toward_the_budget() {
        // About 14 KB of path data in each file, and a floor for its one page. A budget of 50 KB holds
        // three such chapters, while the floors of their pages alone would let all eight stay.
        val path = (0 until 2000).joinToString(" ") { "L${it % 100} ${it / 20}" }
        val big = """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><path d="M0 0 $path" stroke="red"/></svg>"""
        val doc = EpubDocument.open(book(big, 8), EpubSettings(layoutCacheBytes = 50_000))
        for (c in 0 until doc.chapterCount) doc.prepareChapter(c)
        val live = liveChapters(doc)
        assertTrue(live in 1..3, "$live chapters stay live")
    }
}
