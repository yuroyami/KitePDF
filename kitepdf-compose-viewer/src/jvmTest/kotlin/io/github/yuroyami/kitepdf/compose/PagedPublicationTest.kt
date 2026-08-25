package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KitePage
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #5 behaviour at the state level, no Compose runtime needed: a fake
 * document lands chapters on command and a recording adapter plays the
 * pager. The invariant: after every publication the reader's slot resolves
 * to the same location it did before, whatever landed and in whatever order.
 */
class PagedPublicationTest {

    /** [chapters] chapters of [pagesPer] pages each; ready only when prepared. */
    private open class FakeDoc(chapters: Int, private val pagesPer: Int) : KiteDocument {
        private val ready = BooleanArray(chapters)
        override val chapterCount = chapters
        override fun isChapterReady(chapter: Int) = ready.getOrElse(chapter) { false }
        override fun prepareChapter(chapter: Int) { if (chapter in ready.indices) ready[chapter] = true }
        override fun pageCountIn(chapter: Int) = if (isChapterReady(chapter)) pagesPer else 0
        override val isComplete get() = ready.all { it }
        override val knownPageCount get() = ready.count { it } * pagesPer
        override val pageCount get() = chapterCount * pagesPer
        override val pages: List<KitePage> get() = error("not used by these tests")
        override fun page(location: KiteLocation): KitePage = error("not used by these tests")
        override fun locate(bookmark: KiteBookmark): KiteLocation = when (bookmark) {
            is KiteBookmark.Flow -> { prepareChapter(bookmark.chapter); KiteLocation(bookmark.chapter, 1) }
            is KiteBookmark.Page -> KiteLocation(0, bookmark.pageIndex)
        }
    }

    /** The pager stand-in: PagedLikeAdapter, so publications may correct it. */
    private class RecordingAdapter : PagedLikeAdapter {
        val calls = mutableListOf<Int>()
        var page = 0
        override val currentPage: Int get() = page
        override suspend fun scrollToPage(page: Int) { this.page = page; calls.add(page) }
        override suspend fun animateScrollToPage(page: Int) = scrollToPage(page)
    }

    private fun pagedState(doc: KiteDocument, mark: KiteBookmark, at: Int): Pair<KiteDocViewState, RecordingAdapter> {
        val state = KiteDocViewState(doc, mark)
        val adapter = RecordingAdapter().also { it.page = at }
        state.attachPagedForTest(adapter)
        return state to adapter
    }

    @Test
    fun a_flow_bookmark_seeds_the_pending_slot_at_construction() {
        val state = KiteDocViewState(FakeDoc(8, 3), KiteBookmark.Flow(chapter = 5))
        assertEquals(5, state.currentPage, "pre-layout the strip is one slot per chapter")
    }

    @Test
    fun opening_at_a_bookmark_lands_on_the_located_page() = runBlocking {
        val (state, adapter) = pagedState(FakeDoc(8, 3), KiteBookmark.Flow(chapter = 5), at = 5)
        state.openSavedPosition()
        // Chapters 0..4 are still gaps (5 slots); chapter 5's pages follow, so (5,1) is slot 6.
        assertEquals(6, adapter.page)
        assertNull(state.openAt, "consumed exactly once, on success")
    }

    @Test
    fun a_landing_before_the_reader_corrects_the_slot_to_the_same_content() = runBlocking {
        val doc = FakeDoc(8, 3)
        val (state, adapter) = pagedState(doc, KiteBookmark.Flow(chapter = 5), at = 5)
        state.openSavedPosition()                                  // reader on slot 6 = (5,1)
        doc.prepareChapter(2)                                      // 1 gap becomes 3 pages: +2 slots
        state.publishChapter()
        assertEquals(8, adapter.page, "slot compensated by the two inserted slots")
        assertEquals(KiteLocation(5, 1), state.currentLocation, "same content under the reader")
    }

    @Test
    fun a_landing_after_the_reader_moves_nothing() = runBlocking {
        val doc = FakeDoc(8, 3)
        val (state, adapter) = pagedState(doc, KiteBookmark.Flow(chapter = 5), at = 5)
        state.openSavedPosition()
        val callsBefore = adapter.calls.size
        doc.prepareChapter(7)
        state.publishChapter()
        assertEquals(callsBefore, adapter.calls.size, "no correction issued")
    }

    @Test
    fun a_publication_racing_a_navigation_corrects_toward_the_target() = runBlocking {
        val doc = FakeDoc(8, 3)
        val (state, adapter) = pagedState(doc, KiteBookmark.Flow(chapter = 5), at = 5)
        state.openSavedPosition()                                  // slot 6 = (5,1)
        state.navigationTarget = KiteLocation(6, 0)                // a navigation is in flight
        doc.prepareChapter(2)
        state.publishChapter()
        assertEquals(state.slotForTest(KiteLocation(6, 0)), adapter.page,
            "the in-flight target wins over the raw slot")
        state.navigationTarget = null
    }

    @Test
    fun every_landing_order_converges_on_the_same_content() = runBlocking {
        val doc = FakeDoc(6, 4)
        val (state, adapter) = pagedState(doc, KiteBookmark.Flow(chapter = 3), at = 3)
        state.openSavedPosition()                                  // (3,1)
        for (chapter in listOf(1, 5, 0, 4, 2)) {
            doc.prepareChapter(chapter)
            state.publishChapter()
            assertEquals(KiteLocation(3, 1), state.currentLocation, "after chapter $chapter lands")
        }
        assertTrue(state.isComplete)
        assertTrue(adapter.page in 0 until state.itemCount)
    }

    @Test
    fun an_idempotent_publication_issues_no_second_correction() = runBlocking {
        val doc = FakeDoc(8, 3)
        val (state, adapter) = pagedState(doc, KiteBookmark.Flow(chapter = 5), at = 5)
        state.openSavedPosition()
        doc.prepareChapter(2)
        state.publishChapter()
        val calls = adapter.calls.size
        state.publishChapter()                                     // nothing new landed
        assertEquals(calls, adapter.calls.size)
    }

    @Test
    fun a_cancelled_open_keeps_the_bookmark_and_retries_clean() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val doc = object : FakeDoc(8, 3) {
            override fun locate(bookmark: KiteBookmark): KiteLocation {
                entered.countDown()
                release.await()
                return super.locate(bookmark)
            }
        }
        val (state, adapter) = pagedState(doc, KiteBookmark.Flow(chapter = 5), at = 5)
        // Off the runBlocking thread: entered.await() below would otherwise
        // block the only thread the child could start on.
        val attempt = launch(Dispatchers.Default) { state.openSavedPosition() }
        entered.await()
        attempt.cancel()
        release.countDown()
        attempt.join()
        assertNotNull(state.openAt, "a cancelled open must not consume the bookmark")
        state.openSavedPosition()                                  // the retry
        assertEquals(6, adapter.page)
        assertNull(state.openAt)
    }

    @Test
    fun an_out_of_range_bookmark_clamps_and_still_completes() = runBlocking {
        val (state, adapter) = pagedState(FakeDoc(8, 3), KiteBookmark.Flow(chapter = 999), at = 7)
        state.openSavedPosition()
        assertNull(state.openAt, "a stale bookmark must not wedge the open loop")
        assertTrue(adapter.page in 0 until state.itemCount)
    }

    @Test
    fun a_selection_follows_its_page_across_a_landing() = runBlocking {
        val doc = FakeDoc(8, 3)
        val (state, _) = pagedState(doc, KiteBookmark.Flow(chapter = 5), at = 5)
        state.openSavedPosition()                                  // reader on slot 6 = (5,1)
        state.selection = KiteTextSelection(pageIndex = 6, start = 0, end = 3, text = "test", quads = emptyList())
        doc.prepareChapter(2)
        state.publishChapter()
        assertEquals(8, state.selection?.pageIndex, "the selection stays on its content")
    }
}
