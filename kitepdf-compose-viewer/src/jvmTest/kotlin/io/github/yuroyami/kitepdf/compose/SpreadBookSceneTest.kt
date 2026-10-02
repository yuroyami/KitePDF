package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spreads pair the pages of the whole book, so a book in `Spread` shows a chapter placeholder
 * while it lays out behind the reader, then its pages in pairs. The layout never lays the book out
 * on the composition's thread, and a book with no pages does not end the app (#337).
 */
class SpreadBookSceneTest {

    private val settings = EpubSettings(pageWidth = 200.0, pageHeight = 200.0, margin = 10.0)

    private fun text(chapter: Int) =
        (0 until 30).joinToString("") { "<p>Chapter $chapter, paragraph $it, has enough words to fill a line.</p>" }

    private fun book(bodies: List<String>) = EpubDocument.open(multiSpineEpub(bodies), settings)

    /** Delegates everything, and records any read of the two members that lay out the whole book. */
    private class WholeDocumentSpy(private val inner: KiteDocument) : KiteDocument by inner {
        @Volatile var touched: String? = null
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

    @Test
    fun a_book_in_spreads_lays_out_behind_the_reader_and_then_pairs_its_pages() = withoutEscapes {
        forBothEffectOrders { queued ->
            val spy = WholeDocumentSpy(book(List(6) { text(it) }))
            val state = KiteDocViewState(spy)
            val (scene, driver) = drivenScene(400, 200, queued) {
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Spread())
            }
            scene.use {
                assertEquals(null, spy.touched, "composition read ${spy.touched}")
                // Both halves of the first spread draw a page once the book is laid out.
                driver.pumpUntilState { state.isComplete && state.pageGeometry.keys.toSet() == setOf(0, 1) }
                assertEquals(null, spy.touched, "the viewer read ${spy.touched}")
            }
        }
    }

    @Test
    fun a_spread_shows_the_chapter_placeholder_while_the_book_lays_out() = withoutEscapes {
        forBothEffectOrders { queued ->
            val held = LatchedDocument(book(List(4) { text(it) }), held = 2)
            val state = KiteDocViewState(held)
            val (scene, driver) = drivenScene(400, 200, queued) {
                KiteDocView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    layout = KiteDocLayout.Spread(),
                    chapterPlaceholder = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                )
            }
            scene.use {
                driver.pumpUntil { it[200, 100].let { p -> p.red > 0.8f && p.green < 0.3f } }
                held.release()
                driver.pumpUntilState { state.isComplete && state.pageGeometry.keys.toSet() == setOf(0, 1) }
            }
        }
    }

    /** Delegates everything, but laying out chapter [failing] throws, as a damaged chapter does. */
    private class FailingChapter(private val inner: KiteDocument, private val failing: Int) : KiteDocument by inner {
        override fun prepareChapter(chapter: Int) {
            if (chapter == failing) throw IllegalStateException("chapter $failing is damaged")
            inner.prepareChapter(chapter)
        }

        override fun pageCountIn(chapter: Int): Int {
            if (chapter == failing) throw IllegalStateException("chapter $failing is damaged")
            return inner.pageCountIn(chapter)
        }
    }

    @Test
    fun a_chapter_that_fails_to_lay_out_keeps_its_placeholder_in_its_half() = withoutEscapes {
        val bodies = List(3) { text(it) }
        // The failed chapter holds the slot after chapter 0's pages.
        val gap = book(bodies).pageCountIn(0)
        forBothEffectOrders { queued ->
            val state = KiteDocViewState(FailingChapter(book(bodies), failing = 1))
            val (scene, driver) = drivenScene(400, 200, queued) {
                KiteDocView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    layout = KiteDocLayout.Spread(),
                    chapterPlaceholder = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                )
            }
            scene.use {
                // The book never completes, so the spreads wait for the loader to do all it can.
                driver.pumpUntilState { state.stripSettled }
                driver.runOnUi { state.scrollToPage(gap) }
                val half = if (gap % 2 == 0) 100 else 300
                driver.pumpUntil { it[half, 100].let { p -> p.red > 0.8f && p.green < 0.3f } }
            }
        }
    }

    @Test
    fun a_book_with_no_pages_shows_nothing_and_does_not_end_the_app() = withoutEscapes {
        forBothEffectOrders { queued ->
            val state = KiteDocViewState(book(listOf("", "")))
            val (scene, driver) = drivenScene(400, 200, queued) {
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Spread())
            }
            scene.use {
                driver.pumpUntilState { state.isComplete }
                driver.pumpFrames(5)
                assertEquals(0, state.pageGeometry.size, "no page drew")
            }
        }
    }
}
