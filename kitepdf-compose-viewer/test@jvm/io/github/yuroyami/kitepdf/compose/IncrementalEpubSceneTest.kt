package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The viewer half of incremental layout: open at a saved position without
 * paginating the book first, and do not move the page under the reader when a
 * chapter lands above them. Every scene test runs in both effect orders (#328).
 */
class IncrementalEpubSceneTest {

    private val settings = EpubSettings(pageWidth = 200.0, pageHeight = 200.0)

    private fun book(chapters: Int = 12, parasIn: (Int) -> Int = { 18 }): EpubDocument = EpubDocument.open(
        multiSpineEpub(
            List(chapters) { c ->
                if (parasIn(c) == 0) {
                    ""
                } else {
                    "<h1 id=\"head$c\">Chapter ${c + 1}</h1>" +
                        (0 until parasIn(c)).joinToString("") {
                            "<p>Chapter ${c + 1} paragraph $it with words enough to wrap.</p>"
                        }
                }
            },
        ),
        settings,
    )

    /** The headline: a late chapter is on screen while the earlier ones are not laid out. */
    @Test
    fun opening_at_a_bookmark_shows_that_page_before_the_book_is_paginated() = forBothEffectOrders { queued ->
        val doc = book()
        val mark = KiteBookmark.Flow(chapter = 9, charOffset = 0)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(doc, mark)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }
        scene.use {
            // The target chapter is laid out and the reader is on it. A chapter not laid out
            // yet is a placeholder slot that also reports its chapter, so wait for both.
            driver.pumpUntilState { doc.isChapterReady(9) && state.currentLocation.chapter == 9 }
            assertFalse(doc.isComplete, "the whole book should not be laid out yet")
        }
    }

    /** The loader fills the book in behind the reader, and finishes. */
    @Test
    fun the_rest_of_the_book_lays_out_in_the_background() = forBothEffectOrders { queued ->
        val doc = book(8)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 5))
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }
        scene.use {
            driver.pumpUntilState { doc.isComplete }
            assertTrue(state.isComplete)
            assertEquals(doc.pageCount, state.knownPageCount)
        }
    }

    /** A chapter landing ABOVE the reader must not shove their page away. */
    @Test
    fun a_chapter_landing_above_does_not_move_the_visible_page() = forBothEffectOrders { queued ->
        val doc = book(10)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 7))
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.Continuous(Orientation.Vertical),
            )
        }
        scene.use {
            driver.pumpUntilState { state.currentLocation.chapter == 7 && state.openAt == null }
            val before = state.currentLocation
            val slotBefore = state.currentPage
            driver.pumpUntilState { doc.isComplete }
            driver.pumpFrames(4)
            assertEquals(before, state.currentLocation, "the reader moved while chapters landed")
            assertTrue(
                state.currentPage > slotBefore,
                "the slot index must grow as earlier chapters expand: was $slotBefore, now ${state.currentPage}",
            )
        }
    }

    /**
     * A placeholder above the reader's page is the list's first visible item, which the list
     * anchors on. Its chapter lands with ten pages, and the reader's page stays where it is (#342).
     */
    @Test
    fun a_landing_above_a_page_under_a_visible_placeholder_keeps_the_page() = forBothEffectOrders { queued ->
        val doc = LatchedDocument(book(12), held = 4)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 5))
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), pageSpacing = androidx.compose.ui.unit.Dp(0f))
        }
        scene.use {
            driver.pumpUntilState { state.currentLocation == KiteLocation(5, 0) && state.openAt == null }
            // Drag the strip down, so chapter 4's placeholder comes into view above page 5:0.
            drag(scene, driver, from = Offset(100f, 60f), to = Offset(100f, 190f))
            driver.pumpFrames(4)
            assertEquals(KiteLocation(5, 0), state.currentLocation)
            assertEquals(KiteLocation(4, 0), state.currentScrollPosition.location, "the placeholder is the first visible item")

            doc.release()
            driver.pumpUntilState { doc.isChapterReady(4) && state.items.none { it is DocItem.ChapterGap && it.chapter == 4 } }
            driver.pumpFrames(4)
            assertEquals(KiteLocation(5, 0), state.currentLocation, "the landing moved the reader's page")
        }
    }

    /** A chapter wider than the list's key window lands above the reader, in both orientations (#342). */
    @Test
    fun a_giant_chapter_landing_above_keeps_the_page_in_continuous_mode() {
        for (orientation in Orientation.entries) {
            forBothEffectOrders { queued ->
                val doc = book(22, parasIn = { c -> if (c == 19) 360 else 18 })
                lateinit var state: KiteDocViewState
                val (scene, driver) = drivenScene(200, 260, queued) {
                    state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 20))
                    KiteDocView(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        layout = KiteDocLayout.Continuous(orientation),
                    )
                }
                scene.use {
                    driver.pumpUntilState { state.currentLocation.chapter == 20 && state.openAt == null }
                    val settled = state.currentLocation
                    driver.pumpUntilState { doc.isComplete }
                    driver.pumpFrames(4)
                    assertTrue(doc.pageCountIn(19) > 130, "the fixture must exceed the key window, got ${doc.pageCountIn(19)}")
                    assertEquals(settled, state.currentLocation, "$orientation: a giant landing moved the reader")
                }
            }
        }
    }

    /** Same, in a pager, where the index shift has to be corrected by hand. */
    @Test
    fun a_pager_stays_on_its_page_while_chapters_land() = forBothEffectOrders { queued ->
        val doc = book(10)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 6))
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.Paged(Orientation.Horizontal),
            )
        }
        scene.use {
            driver.pumpUntilState { state.currentLocation.chapter == 6 && state.openAt == null }
            val before = state.currentLocation
            driver.pumpUntilState { doc.isComplete }
            driver.pumpFrames(4)
            assertEquals(before, state.currentLocation, "the pager slid off its page")
        }
    }

    /**
     * A reader can scroll ahead onto a chapter that is not laid out yet and sit
     * on its placeholder. When that chapter lands, the placeholder becomes its
     * pages and the reader must end up at the start of the chapter they were
     * waiting for, not back where they came from.
     */
    @Test
    fun landing_on_a_placeholder_puts_the_reader_at_that_chapter() = forBothEffectOrders { queued ->
        val doc = LatchedDocument(book(8), held = 5)
        lateinit var state: KiteDocViewState
        var goTo by mutableStateOf(-1)
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 4))
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.Paged(Orientation.Horizontal),
            )
            if (goTo >= 0) LaunchedEffect(goTo) { state.scrollToPage(goTo) }
        }
        scene.use {
            driver.pumpUntilState { state.currentLocation == KiteLocation(4, 0) && state.openAt == null }
            val gap = state.items.indexOfFirst { it is DocItem.ChapterGap && it.chapter == 5 }
            assertTrue(gap >= 0, "chapter 6 should be holding a placeholder slot")
            goTo = gap
            driver.pumpUntilState { state.currentPage == gap }
            assertEquals(KiteLocation(5, 0), state.currentLocation, "the reader is on chapter 6's placeholder")

            doc.release()
            driver.pumpUntilState { doc.isChapterReady(5) && state.items.none { it is DocItem.ChapterGap && it.chapter == 5 } }
            driver.pumpFrames(4)
            assertEquals(KiteLocation(5, 0), state.currentLocation, "the chapter landed and the reader was pulled off it")
        }
    }

    /** Paging back onto a chapter that is still loading ends on its last page once it lands (#348). */
    @Test
    fun paging_back_onto_a_loading_chapter_lands_on_its_last_page() = forBothEffectOrders { queued ->
        val doc = LatchedDocument(book(8), held = 4)
        lateinit var state: KiteDocViewState
        var back by mutableStateOf(false)
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 5))
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged())
            if (back) LaunchedEffect(Unit) { state.animateScrollToPage(state.currentPage - 1) }
        }
        scene.use {
            driver.pumpUntilState { state.currentLocation == KiteLocation(5, 0) && state.openAt == null }
            back = true
            driver.pumpUntilState { state.chapterAt(state.currentPage) == 4 }

            doc.release()
            driver.pumpUntilState { doc.isChapterReady(4) && state.items.none { it is DocItem.ChapterGap && it.chapter == 4 } }
            driver.pumpFrames(4)
            assertEquals(KiteLocation(4, doc.pageCountIn(4) - 1), state.currentLocation)
        }
    }

    /** A host label that lays the whole book out does not leave the viewer on placeholders (#341). */
    @Test
    fun chapters_laid_out_by_the_host_are_shown() = forBothEffectOrders { queued ->
        val doc = book(12)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(doc)
            Column(Modifier.fillMaxSize()) {
                KiteDocView(state = state, modifier = Modifier.weight(1f))
                BasicText("${state.pageCount}")
            }
        }
        scene.use {
            driver.pumpUntilState { state.items.none { it is DocItem.ChapterGap } }
            assertEquals(doc.pageCount, state.itemCount)
        }
    }

    /**
     * A drag while the saved position resolves means the reader chose where to be: the saved
     * position is dropped, the rest of the book still loads, and a later jump is not undone (#344).
     */
    @Test
    fun a_drag_while_opening_drops_the_saved_position_and_loading_goes_on() = forBothEffectOrders { queued ->
        val doc = LatchedDocument(book(12), held = 5)
        lateinit var state: KiteDocViewState
        var jump by mutableStateOf(false)
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 5, charOffset = 300))
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
            if (jump) LaunchedEffect(Unit) { state.scrollTo(KiteLocation(9, 0)) }
        }
        scene.use {
            driver.pumpFrames(4)
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 150f), type = PointerType.Touch)
            for (step in 1..4) {
                Thread.sleep(16)
                scene.sendPointerEvent(PointerEventType.Move, Offset(100f, 150f - 8f * step), type = PointerType.Touch)
                driver.pumpFrames(0)
            }
            doc.release()
            driver.pumpUntilState { state.openAt == null }
            Thread.sleep(200)
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 118f), type = PointerType.Touch)
            driver.pumpUntilState { doc.isComplete }
            driver.pumpFrames(10)
            val saved = doc.locate(KiteBookmark.Flow(chapter = 5, charOffset = 300))
            assertTrue(saved.page > 0, "the fixture's saved position must be past the chapter's first page")
            assertEquals(5, state.currentLocation.chapter, "the drag started on chapter 5")
            assertTrue(state.currentLocation != saved, "the saved position overrode the reader's drag")

            jump = true
            driver.pumpUntilState { state.currentLocation == KiteLocation(9, 0) }
            driver.pumpFrames(30)
            assertEquals(KiteLocation(9, 0), state.currentLocation, "the dropped position pulled the reader back")
        }
    }

    /** Next and previous step over a chapter that lays out to no pages (#347). */
    @Test
    fun next_and_previous_skip_an_empty_chapter() = forBothEffectOrders { queued ->
        val doc = book(7, parasIn = { c -> if (c == 4) 0 else 18 })
        doc.prepareChapter(3)
        val last3 = doc.pageCountIn(3) - 1
        lateinit var state: KiteDocViewState
        var step by mutableStateOf(0)
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 3, charOffset = Int.MAX_VALUE - 1))
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged())
            LaunchedEffect(step) {
                when (step) {
                    1 -> state.nextPage()
                    2 -> state.previousPage()
                }
            }
        }
        scene.use {
            driver.pumpUntilState { doc.isComplete && state.currentLocation == KiteLocation(3, last3) }
            assertEquals(0, doc.pageCountIn(4), "the fixture's chapter 4 must be empty")
            step = 1
            driver.pumpUntilState { state.currentLocation != KiteLocation(3, last3) }
            driver.pumpFrames(30)
            assertEquals(KiteLocation(5, 0), state.currentLocation)
            step = 2
            driver.pumpUntilState { state.currentLocation != KiteLocation(5, 0) }
            driver.pumpFrames(30)
            assertEquals(KiteLocation(3, last3), state.currentLocation)
        }
    }

    /** A chapter whose layout throws stays a placeholder, and the loader goes on (#331). */
    @Test
    fun a_chapter_that_fails_to_lay_out_does_not_stop_the_book() = forBothEffectOrders { queued ->
        val inner = book(8)
        val doc = object : KiteDocument by inner {
            override fun prepareChapter(chapter: Int) {
                check(chapter != 3) { "layout failed in chapter 3" }
                inner.prepareChapter(chapter)
            }

            override fun pageCountIn(chapter: Int): Int {
                check(chapter != 3) { "layout failed in chapter 3" }
                return inner.pageCountIn(chapter)
            }
        }
        withoutEscapes {
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 260, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(state = state, modifier = Modifier.fillMaxSize())
            }
            scene.use {
                driver.pumpUntilState { (0 until 8).all { it == 3 || inner.isChapterReady(it) } }
                driver.pumpUntilState { state.items.count { it is DocItem.ChapterGap } == 1 }
                assertEquals(3, state.items.filterIsInstance<DocItem.ChapterGap>().single().chapter)
            }
        }
    }

    /** A chapter whose markup nests far too deep lays out. Its layout overflowed the stack, so it stayed a placeholder (#450). */
    @Test
    fun a_chapter_with_deeply_nested_markup_lays_out() = forBothEffectOrders { queued ->
        val deep = "<div>".repeat(3_000) + "<p>Deep words.</p>" + "</div>".repeat(3_000)
        val doc = EpubDocument.open(multiSpineEpub(listOf(deep, "<p>The next chapter.</p>")), settings)
        withoutEscapes {
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 260, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(state = state, modifier = Modifier.fillMaxSize())
            }
            scene.use {
                driver.pumpUntilState { doc.isChapterReady(1) }
                assertTrue(doc.isChapterReady(0), "the deep chapter is laid out")
                driver.pumpUntilState { state.items.none { it is DocItem.ChapterGap } }
            }
        }
    }

    /**
     * Scrolling across chapters that are not laid out does not restart the loader, which left
     * the abandoned layouts running side by side (#378).
     */
    @Test
    fun scrolling_across_placeholders_runs_one_layout_at_a_time() = forBothEffectOrders { queued ->
        val inner = book(40, parasIn = { 6 })
        val running = AtomicInteger()
        val most = AtomicInteger()
        val doc = object : KiteDocument by inner {
            override fun prepareChapter(chapter: Int) {
                if (inner.isChapterReady(chapter)) return
                most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                try {
                    Thread.sleep(15)
                    inner.prepareChapter(chapter)
                } finally {
                    running.decrementAndGet()
                }
            }
        }
        lateinit var state: KiteDocViewState
        var fling by mutableStateOf(false)
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
            if (fling) {
                LaunchedEffect(Unit) {
                    for (slot in 0 until 40) {
                        state.scrollToPage(slot)
                        withFrameNanos { }
                    }
                }
            }
        }
        scene.use {
            driver.pumpFrames(2)
            fling = true
            driver.pumpUntilState { doc.isComplete }
            assertEquals(1, most.get(), "layouts ran side by side")
        }
    }

    /**
     * A chapter landing before the page of a selection drag moves that page to another slot. The
     * drag goes on following the finger, and the host hears where the selection went (#350).
     */
    @Test
    fun a_landing_mid_drag_keeps_the_selection_following_the_finger() {
        val doc = book(2)
        doc.prepareChapter(1)
        val state = KiteDocViewState(doc)
        state.viewportSize = androidx.compose.ui.unit.IntSize(200, 200)
        val seen = ArrayList<KiteTextSelection?>()
        state.onSelectionChange = { seen += it }
        val slotBefore = state.slotForTest(KiteLocation(1, 0))
        assertEquals(1, slotBefore, "chapter 0 is one placeholder before page 1:0")
        state.pageGeometry[slotBefore] = androidx.compose.ui.geometry.Rect(0f, 0f, 200f, 200f)
        val text = assertNotNull(doc.page(KiteLocation(1, 0)).textContent())
        fun pointAt(char: Int): Offset {
            val quad = text.quadsFor(char, char).first()
            return Offset(((quad.left + quad.right) / 2).toFloat(), ((quad.top + quad.bottom) / 2).toFloat())
        }
        onTestUiThread { state.beginSelection(pointAt(2)) }
        assertEquals(2, state.selection?.start)

        doc.prepareChapter(0)
        kotlinx.coroutines.runBlocking { state.publishChapter() }
        val slotAfter = state.slotForTest(KiteLocation(1, 0))
        assertTrue(slotAfter > slotBefore, "chapter 0 landed before the page")
        assertEquals(slotAfter, state.selection?.pageIndex, "the selection moved with its page")
        assertEquals(slotAfter, seen.last()?.pageIndex, "the host heard the move")
        state.pageGeometry.clear()
        state.pageGeometry[slotAfter] = androidx.compose.ui.geometry.Rect(0f, 0f, 200f, 200f)

        onTestUiThread { state.extendSelection(pointAt(12)) }
        assertEquals(2, state.selection?.start)
        assertEquals(12, state.selection?.end, "the drag stopped following the finger")
    }

    /** A bookmark taken now must reopen at the same place later. */
    @Test
    fun a_bookmark_round_trips_through_the_state() = forBothEffectOrders { queued ->
        val doc = book()
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 4))
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }
        scene.use {
            driver.pumpUntilState { state.currentLocation.chapter == 4 }
            assertEquals(4, state.currentBookmark().chapter, "the bookmark names the chapter being read")
            // Settle first: while chapters are still landing the strip is moving,
            // and reading the location twice can straddle a change.
            driver.pumpUntilState { state.isComplete }
            val at = state.currentLocation
            assertEquals(at, doc.locate(state.currentBookmark()), "a bookmark must reopen where it was taken")
        }
    }

    /**
     * The contract that makes all of this work: composing and navigating must
     * never touch the whole-document views, which lay out every chapter.
     */
    @Test
    fun the_viewer_never_asks_for_the_whole_document() = forBothEffectOrders { queued ->
        val doc = book()
        val spy = WholeDocumentSpy(doc)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(spy, KiteBookmark.Flow(chapter = 8))
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }
        scene.use {
            driver.pumpUntilState { state.currentLocation.chapter == 8 }
            assertEquals(null, spy.touched, "composition read ${spy.touched}")
            driver.pumpUntilState { spy.isComplete }
            assertEquals(null, spy.touched, "the background loader read ${spy.touched}")
        }
    }

    /**
     * The one-page overload shows its page without laying out the whole book in composition.
     * It checked the page against `pageCount`, which lays out every chapter (#351).
     */
    @Test
    fun the_one_page_overload_never_asks_for_the_whole_document() = forBothEffectOrders { queued ->
        val spy = WholeDocumentSpy(book())
        val (scene, driver) = drivenScene(200, 260, queued) {
            KiteDocView(document = spy, page = 3, modifier = Modifier.fillMaxSize())
        }
        scene.use {
            assertEquals(null, spy.touched, "composition read ${spy.touched}")
            driver.pumpUntilState { spy.isComplete }
            driver.pumpFrames(3)
            assertEquals(null, spy.touched, "the viewer read ${spy.touched}")
        }
    }

    /** Delegates everything, and records any read of the two eager members. */
    private class WholeDocumentSpy(private val inner: KiteDocument) : KiteDocument by inner {
        var touched: String? = null
        override val pages: List<KitePage>
            get() {
                if (touched == null) touched = "pages"
                return inner.pages
            }
        override val pageCount: Int
            get() {
                if (touched == null) touched = "pageCount"
                return inner.pageCount
            }
    }

    /**
     * The page total is approximate until the book is laid out, which is what
     * the indicator's "~" reflects. Both ends of that are asserted here.
     */
    @Test
    fun the_total_is_approximate_until_the_book_is_laid_out() = forBothEffectOrders { queued ->
        val doc = book(6)
        // Before anything is composed: nothing laid out, nothing to count.
        val fresh = KiteDocViewState(doc, KiteBookmark.Flow(chapter = 3))
        assertFalse(fresh.isComplete, "a fresh book has laid nothing out")
        assertEquals(0, fresh.knownPageCount)

        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 3))
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }
        scene.use {
            driver.pumpUntilState { state.isComplete }
            assertEquals(doc.pageCount, state.knownPageCount, "the total is exact once complete")
        }
    }

    /** Navigation still works across a chapter boundary, laying the next one out. */
    @Test
    fun next_page_crosses_into_the_following_chapter() = forBothEffectOrders { queued ->
        val doc = book(5)
        doc.prepareChapter(2)
        val last = doc.pageCountIn(2) - 1
        lateinit var state: KiteDocViewState
        var go by mutableStateOf(false)
        val (scene, driver) = drivenScene(200, 260, queued) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 2, charOffset = Int.MAX_VALUE - 1))
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
            if (go) LaunchedEffect(Unit) { state.nextPage() }
        }
        scene.use {
            driver.pumpUntilState { state.currentLocation == KiteLocation(2, last) }
            go = true
            driver.pumpUntilState { state.currentLocation.chapter == 3 }
            assertEquals(KiteLocation(3, 0), state.currentLocation)
        }
    }

    /** A TOC entry opens its chapter and nothing else. */
    @Test
    fun an_outline_target_prepares_only_its_own_chapter() {
        val doc = book(9)
        val target = assertNotNull(doc.bookmarkOf("OEBPS/chapter7.xhtml#head6"))
        assertEquals(6, target.chapter)
        doc.locate(target)
        assertTrue(doc.isChapterReady(6))
        assertFalse(doc.isChapterReady(0))
        assertFalse(doc.isChapterReady(8))
    }

    /** A slow drag with a pause before the lift, so it moves the content and does not fling. */
    private fun drag(scene: ImageComposeScene, driver: SceneTestDriver, from: Offset, to: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, from, type = PointerType.Touch)
        val steps = 8
        for (step in 1..steps) {
            Thread.sleep(16)
            val t = step / steps.toFloat()
            scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * t, type = PointerType.Touch)
            driver.pumpFrames(0)
        }
        Thread.sleep(300)
        scene.sendPointerEvent(PointerEventType.Move, to, type = PointerType.Touch)
        scene.sendPointerEvent(PointerEventType.Release, to, type = PointerType.Touch)
        driver.pumpFrames(2)
    }
}
