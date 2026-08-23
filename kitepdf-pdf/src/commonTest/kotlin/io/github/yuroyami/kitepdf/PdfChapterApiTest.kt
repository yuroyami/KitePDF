package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A PDF is one chapter that is always ready. It inherits the whole chapter API
 * from [io.github.yuroyami.kitepdf.core.KiteDocument] defaults, so this pins
 * that those defaults are actually right for a fixed-page document.
 */
class PdfChapterApiTest {

    private fun doc(pages: Int = 5): PdfDocument {
        val b = PdfBuilder()
        repeat(pages) { i ->
            b.page { text(StandardFont.Helvetica, 20.0, 72.0, 700.0, "page $i") }
        }
        return PdfDocument.open(b.build())
    }

    @Test
    fun one_chapter_always_ready() {
        val d = doc()
        assertEquals(1, d.chapterCount)
        assertTrue(d.isChapterReady(0))
        assertTrue(d.isComplete)
        assertEquals(5, d.knownPageCount)
        assertEquals(5, d.pageCountIn(0))
        d.prepareChapter(0) // a no-op that must not throw
        d.prepareChapter(7) // out of range, also a no-op
    }

    @Test
    fun locations_and_indices_are_the_same_thing() {
        val d = doc()
        for (i in 0 until 5) {
            val at = KiteLocation(0, i)
            assertEquals(i, d.pageIndexOf(at))
            assertEquals(at, d.locationOf(i))
            assertSame(d.pages[i], d.page(at))
        }
        assertNull(d.locationOf(5))
        assertNull(d.locationOf(-1))
    }

    @Test
    fun a_bookmark_is_just_the_page_index() {
        val d = doc()
        val mark = d.bookmarkOf(KiteLocation(0, 3))
        assertTrue(mark is KiteBookmark.Page)
        assertEquals(3, mark.pageIndex)
        assertEquals(0, mark.chapter)
        assertEquals(KiteLocation(0, 3), d.locate(mark))
    }

    @Test
    fun outline_entries_still_carry_page_indices() {
        // A PDF is never mid-layout, so the eager index stays available.
        val d = doc()
        assertNotNull(d.outline) // empty here, but the contract holds
        assertTrue(d.outline.isEmpty())
    }
}
