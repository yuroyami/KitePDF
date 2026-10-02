package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KiteWarningSink
import io.github.yuroyami.kitepdf.core.KiteWarnings
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SinglePage` names a page of the whole document, as for a PDF. A book that is still laying out
 * shows a placeholder until that page can be placed, an index outside the document shows the
 * nearest page, none of it ends the app, and the navigation controls are off (#336).
 */
class SinglePageIndexSceneTest {

    private val settings = EpubSettings(pageWidth = 200.0, pageHeight = 200.0, margin = 10.0)

    private fun book(bodies: List<String>) = EpubDocument.open(multiSpineEpub(bodies), settings)

    private fun text(chapter: Int, paragraphs: Int) =
        (0 until paragraphs).joinToString("") { "<p>Chapter $chapter, paragraph $it, has enough words to fill a line.</p>" }

    private fun pdf(pages: Int): PdfDocument =
        PdfDocument.open(PdfBuilder().apply { repeat(pages) { page(width = 200.0, height = 200.0) {} } }.build())

    /** Pumps until [check] holds, and says [what] was expected, as it stands then, when it does not. */
    private fun SceneTestDriver.settles(what: () -> String, check: () -> Boolean) {
        try {
            pumpUntilState(check = check)
        } catch (failure: AssertionError) {
            throw AssertionError(what(), failure)
        }
    }

    @Test
    fun a_page_of_a_book_that_is_still_laying_out_shows_once_it_can() = withoutEscapes {
        val bodies = List(12) { text(it, 40) }
        // Page 15 of the whole book, from a copy laid out to the end.
        val expected = assertNotNull(book(bodies).let { it.pageCount; it.locationOf(15) })
        forBothEffectOrders { queued ->
            val state = KiteDocViewState(book(bodies))
            val (scene, driver) = drivenScene(200, 200, queued) {
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(15))
            }
            scene.use {
                driver.settles({ "the fixed page is $expected, not ${state.currentLocation}" }) { state.currentLocation == expected }
            }
        }
    }

    @Test
    fun a_placeholder_shows_until_the_chapters_before_the_page_are_laid_out() = withoutEscapes {
        val bodies = List(4) { text(it, 40) }
        val full = book(bodies).also { it.pageCount }
        // Page 1 of chapter 2, counted over the whole book.
        val index = full.pageCountIn(0) + full.pageCountIn(1) + 1
        forBothEffectOrders { queued ->
            // Chapter 0 is ready and chapter 1 waits, so no page of chapter 0 may stand in.
            val inner = book(bodies).also { it.prepareChapter(0) }
            val held = LatchedDocument(inner, held = 1)
            val state = KiteDocViewState(held)
            val (scene, driver) = drivenScene(200, 200, queued) {
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(index))
            }
            scene.use {
                driver.settles({ "the fixed page attaches" }) { state.adapter != null }
                driver.pumpFrames(10)
                assertNull(state.pageAt(state.currentPage), "a placeholder shows while chapter 1 lays out, not ${state.currentLocation}")
                held.release()
                driver.settles({ "the page shows once chapter 1 lands, not ${state.currentLocation}" }) {
                    state.currentLocation == KiteLocation(2, 1)
                }
            }
        }
    }

    @Test
    fun a_chapter_that_lands_empty_does_not_end_the_app() = withoutEscapes {
        // The second chapter has no content, so it becomes no page when it lands.
        val bodies = listOf(text(0, 40), "")
        forBothEffectOrders { queued ->
            val state = KiteDocViewState(book(bodies))
            val (scene, driver) = drivenScene(200, 200, queued) {
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(1))
            }
            scene.use {
                driver.settles({ "the book lays out and shows its page 1, not ${state.currentLocation}" }) {
                    state.isComplete && state.currentLocation == KiteLocation(0, 1)
                }
                driver.pumpFrames(5)
            }
        }
    }

    @Test
    fun an_index_outside_the_document_shows_its_nearest_page() = withoutEscapes {
        val warnings = CopyOnWriteArrayList<String>()
        val previous = KiteWarnings.sink
        KiteWarnings.sink = KiteWarningSink { warnings += it }
        try {
            for ((index, expected) in listOf(500 to 2, -1 to 0)) forBothEffectOrders { queued ->
                val state = KiteDocViewState(pdf(3))
                val (scene, driver) = drivenScene(200, 200, queued) {
                    KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(index))
                }
                scene.use {
                    driver.settles({ "SinglePage($index) shows page $expected, not ${state.currentPage}" }) {
                        state.adapter != null && state.currentPage == expected
                    }
                    assertTrue(warnings.any { "SinglePage($index)" in it }, "SinglePage($index) logs a warning: $warnings")
                }
            }
        } finally {
            KiteWarnings.sink = previous
        }
    }

    @Test
    fun the_navigation_controls_are_off_for_a_fixed_page() = withoutEscapes {
        val state = KiteDocViewState(pdf(3))
        var layout by mutableStateOf<KiteDocLayout>(KiteDocLayout.SinglePage(1))
        val (scene, driver) = drivenScene(300, 300, queued = false) {
            Column(Modifier.fillMaxSize()) {
                KiteNavigationControls(state, contentColor = Color.Red, containerColor = Color.White)
                KiteDocView(state = state, modifier = Modifier.weight(1f), layout = layout)
            }
        }
        scene.use {
            // The back chevron's point sits near (17, 20): 4 px of padding, then a 36 px button
            // with a 10 x 16 px chevron in its centre.
            fun backIsOn(pixels: PixelMap) = (15..20).any { x -> (17..23).any { y -> pixels[x, y].let { it.red > 0.8f && it.green < 0.3f } } }
            driver.settles({ "the fixed page attaches on page 1" }) { state.adapter != null && state.currentPage == 1 }
            val fixed = driver.pumpFrames(3).toComposeImageBitmap().toPixelMap()
            assertFalse(backIsOn(fixed), "the back button is off: a fixed page does not turn")
            // The same button on page 1 of a pager is on, so the probe sees it.
            layout = KiteDocLayout.Paged()
            driver.pumpUntil { backIsOn(it) }
            Unit
        }
    }
}
