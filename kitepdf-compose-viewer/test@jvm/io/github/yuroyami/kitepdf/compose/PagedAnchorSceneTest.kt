package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #5 at the Compose level: keyed retention plus the publication
 * correction, observed frame by frame. Every test drives a real book through
 * a real KiteDocView in Paged mode and asserts the reader's LOCATION, never
 * the raw index.
 */
class PagedAnchorSceneTest {

    private val settings = EpubSettings(pageWidth = 200.0, pageHeight = 200.0)

    private fun book(chapters: Int = 12, parasIn: (Int) -> Int = { 18 }): EpubDocument =
        EpubDocument.open(
            multiSpineEpub(
                List(chapters) { c ->
                    "<h1>Chapter ${c + 1}</h1>" +
                        (0 until parasIn(c)).joinToString("") {
                            "<p>Chapter ${c + 1} paragraph $it with words enough to wrap.</p>"
                        }
                },
            ),
            settings,
        )

    /** Runs [body] on a fresh book from [doc] in Paged mode, once in each effect order (#328). */
    private fun paged(doc: () -> EpubDocument, mark: KiteBookmark, body: (SceneTestDriver, KiteDocViewState, EpubDocument) -> Unit) {
        forBothEffectOrders { queued ->
            val book = doc()
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 260, queued) {
                state = rememberKiteDocViewState(book, mark)
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged())
            }
            scene.use { body(driver, state, book) }
        }
    }

    @Test
    fun chapters_landing_before_the_reader_never_move_the_content() {
        paged({ book() }, KiteBookmark.Flow(chapter = 9)) { driver, state, doc ->
            driver.pumpUntilState { state.currentLocation.chapter == 9 && state.openAt == null }
            val settled = state.currentLocation
            assertEquals(9, settled.chapter, "open never reached the bookmark")
            // Every earlier chapter lands above the reader from here on.
            driver.pumpUntilState { doc.isComplete }
            assertTrue(doc.isComplete, "the loader never finished")
            assertEquals(settled, state.currentLocation, "the reader must not move while the book fills in")
        }
    }

    @Test
    fun no_observed_frame_shows_a_wrong_chapter() {
        paged({ book() }, KiteBookmark.Flow(chapter = 7)) { driver, state, doc ->
            driver.pumpUntilState { state.currentLocation.chapter == 7 }
            assertEquals(7, state.currentLocation.chapter, "open never reached the bookmark")
            // From the moment the target is reached, every observed frame
            // stays on it until the book is complete.
            driver.pumpUntilState {
                assertEquals(7, state.currentLocation.chapter, "a frame showed the wrong chapter")
                doc.isComplete
            }
            assertTrue(doc.isComplete, "the loader never finished")
        }
    }

    @Test
    fun a_placeholder_becomes_its_chapters_first_page_in_place() {
        paged({ book() }, KiteBookmark.Flow(chapter = 5)) { driver, state, doc ->
            driver.pumpUntilState { doc.isChapterReady(5) && state.openAt == null }
            assertEquals(5, state.currentLocation.chapter, "the gap key carried the reader onto the chapter")
            driver.pumpUntilState { doc.isComplete }
            assertTrue(doc.isComplete, "the loader never finished")
            assertEquals(5, state.currentLocation.chapter)
        }
    }

    @Test
    fun a_chapter_wider_than_the_key_window_still_keeps_the_page() {
        // Chapter 0 paginates to well over the pager's ~130-slot key lookup
        // window; its landing above the reader exercises the publication
        // correction rather than native key matching.
        paged({ book(chapters = 4, parasIn = { c -> if (c == 0) 600 else 12 }) }, KiteBookmark.Flow(chapter = 3)) { driver, state, doc ->
            driver.pumpUntilState { state.currentLocation.chapter == 3 && state.openAt == null }
            val settled = state.currentLocation
            assertEquals(3, settled.chapter, "open never reached the bookmark")
            driver.pumpUntilState { doc.isComplete }
            assertTrue(doc.isComplete, "the loader never finished")
            assertTrue(doc.pageCountIn(0) > 130, "fixture must exceed the key window, got ${doc.pageCountIn(0)}")
            assertEquals(settled, state.currentLocation, "a giant landing must not move the reader")
        }
    }

    /**
     * The landing of #343 with a finger held on the pager. A correction that waits for the
     * pager's scroll lock cannot run under a finger, so it must not need that lock.
     */
    @Test
    fun a_giant_landing_under_a_held_finger_keeps_the_page() {
        forBothEffectOrders { queued ->
            val doc = book(chapters = 22, parasIn = { c -> if (c == 19) 360 else 12 })
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 260, queued) {
                state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 20))
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged())
            }
            scene.use {
                driver.pumpUntilState { state.currentLocation.chapter == 20 && state.openAt == null }
                val settled = state.currentLocation
                scene.sendPointerEvent(
                    androidx.compose.ui.input.pointer.PointerEventType.Press,
                    androidx.compose.ui.geometry.Offset(100f, 130f),
                    type = androidx.compose.ui.input.pointer.PointerType.Touch,
                )
                driver.pumpUntilState { doc.isComplete }
                driver.pumpFrames(4)
                assertTrue(doc.pageCountIn(19) > 130, "fixture must exceed the key window, got ${doc.pageCountIn(19)}")
                assertEquals(settled, state.currentLocation, "the landing under the finger moved the reader")
                scene.sendPointerEvent(
                    androidx.compose.ui.input.pointer.PointerEventType.Release,
                    androidx.compose.ui.geometry.Offset(100f, 130f),
                    type = androidx.compose.ui.input.pointer.PointerType.Touch,
                )
                driver.pumpFrames(10)
                assertEquals(settled, state.currentLocation)
            }
        }
    }
}
