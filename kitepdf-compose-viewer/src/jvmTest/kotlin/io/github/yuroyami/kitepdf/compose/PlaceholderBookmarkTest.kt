package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A bookmark taken on a chapter that is not laid out yet is that chapter's start, and taking it
 * lays nothing out on the caller's thread, which is often the main thread in `onPause` (#379).
 */
class PlaceholderBookmarkTest {

    private val text = (0 until 30).joinToString("") { "<p>Paragraph $it has enough words to fill a line.</p>" }

    private fun book() = EpubDocument.open(multiSpineEpub(listOf(text, text, text)), EpubSettings(pageWidth = 200.0, pageHeight = 200.0, margin = 10.0))

    @Test
    fun a_bookmark_on_a_chapter_placeholder_lays_nothing_out() {
        val doc = book()
        // Opened at chapter 1, which the strip holds as a placeholder until it is laid out.
        val state = KiteDocViewState(doc, KiteBookmark.Flow(chapter = 1, charOffset = 0))
        assertEquals(KiteLocation(1, 0), state.currentLocation)
        assertEquals(KiteBookmark.Flow(1, 0), state.currentBookmark())
        assertFalse(doc.isChapterReady(1), "taking the bookmark laid the chapter out on the caller's thread")
    }

    @Test
    fun next_page_from_a_chapter_placeholder_lays_out_off_the_callers_thread() {
        val doc = book()
        val caller = Thread.currentThread()
        val laidOutHere = AtomicBoolean(false)
        // Records a layout of a chapter that is not ready, on the thread that calls nextPage.
        val spy = object : KiteDocument by doc {
            override fun prepareChapter(chapter: Int) {
                if (Thread.currentThread() === caller && !doc.isChapterReady(chapter)) laidOutHere.set(true)
                doc.prepareChapter(chapter)
            }

            override fun pageCountIn(chapter: Int): Int {
                if (Thread.currentThread() === caller && !doc.isChapterReady(chapter)) laidOutHere.set(true)
                return doc.pageCountIn(chapter)
            }
        }
        val state = KiteDocViewState(spy, KiteBookmark.Flow(chapter = 1, charOffset = 0))
        runBlocking { state.nextPage() }
        assertEquals(KiteLocation(1, 1), state.currentLocation, "next goes to page 1 of the chapter")
        assertFalse(laidOutHere.get(), "next laid a chapter out on the caller's thread")
    }
}
