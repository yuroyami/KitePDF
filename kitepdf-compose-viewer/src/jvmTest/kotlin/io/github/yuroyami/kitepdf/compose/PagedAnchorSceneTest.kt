package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
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
 * the raw index. pumpUntilState returns silently on its frame budget, so
 * every test re-asserts its completion condition afterwards; a timeout can
 * never pass vacuously.
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

    private fun paged(doc: EpubDocument, mark: KiteBookmark, body: (SceneTestDriver, KiteDocViewState) -> Unit) {
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 260, density = Density(1f)) {
            state = rememberKiteDocViewState(doc, mark)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged())
        }.use { scene -> body(SceneTestDriver(scene), state) }
    }

    @Test
    fun chapters_landing_before_the_reader_never_move_the_content() {
        val doc = book()
        paged(doc, KiteBookmark.Flow(chapter = 9)) { driver, state ->
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
        val doc = book()
        paged(doc, KiteBookmark.Flow(chapter = 7)) { driver, state ->
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
        val doc = book()
        paged(doc, KiteBookmark.Flow(chapter = 5)) { driver, state ->
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
        val doc = book(chapters = 4, parasIn = { c -> if (c == 0) 600 else 12 })
        paged(doc, KiteBookmark.Flow(chapter = 3)) { driver, state ->
            driver.pumpUntilState { state.currentLocation.chapter == 3 && state.openAt == null }
            val settled = state.currentLocation
            assertEquals(3, settled.chapter, "open never reached the bookmark")
            driver.pumpUntilState { doc.isComplete }
            assertTrue(doc.isComplete, "the loader never finished")
            assertTrue(doc.pageCountIn(0) > 130, "fixture must exceed the key window, got ${doc.pageCountIn(0)}")
            assertEquals(settled, state.currentLocation, "a giant landing must not move the reader")
        }
    }
}
