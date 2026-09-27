package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * The viewer state is the only saved position. A new state opens at its own place (#346), a
 * place set while the viewer is away wins over what the scroll containers restore (#345), and a
 * switch of the strip's orientation keeps the reader's place (#352).
 */
class PositionOwnershipSceneTest {

    private val layouts = listOf(KiteDocLayout.Continuous(), KiteDocLayout.Paged(), KiteDocLayout.Spread())

    private fun pdf(pages: Int, width: Double = 200.0, height: Double = 200.0): PdfDocument =
        PdfDocument.open(PdfBuilder().apply { repeat(pages) { page(width = width, height = height) {} } }.build())

    /** Pumps until [check] holds, and says [what] was expected, as it stands then, when it does not. */
    private fun SceneTestDriver.settles(what: () -> String, check: () -> Boolean) {
        try {
            pumpUntilState(check = check)
        } catch (failure: AssertionError) {
            throw AssertionError(what(), failure)
        }
    }

    @Test
    fun a_new_state_under_the_same_viewer_opens_at_its_own_page() = withoutEscapes {
        for (layout in layouts) forBothEffectOrders { queued ->
            val first = KiteDocViewState(pdf(8), initialPage = 4)
            var supplied by mutableStateOf(first)
            val (scene, driver) = drivenScene(200, 200, queued) {
                KiteDocView(state = supplied, modifier = Modifier.fillMaxSize(), layout = layout)
            }
            scene.use {
                val name = layout::class.simpleName
                driver.settles({ "$name: the first state opens at page 4" }) { first.adapter != null && first.currentPage == 4 }
                // The same length, then a shorter document.
                for ((pages, start) in listOf(8 to 0, 3 to 1)) {
                    val next = KiteDocViewState(pdf(pages), initialPage = start)
                    supplied = next
                    driver.settles({ "$name: a new state of $pages pages opens at its page $start, not at ${next.currentPage} (adapter ${next.adapter}, old adapter ${first.adapter})" }) {
                        next.adapter != null && next.currentPage == start
                    }
                }
            }
        }
    }

    @Test
    fun a_place_set_while_the_viewer_is_away_wins_over_the_restored_container() = withoutEscapes {
        for (layout in layouts) forBothEffectOrders { queued ->
            val state = KiteDocViewState(pdf(10))
            var show by mutableStateOf(true)
            val (scene, driver) = drivenScene(200, 200, queued) {
                val holder = rememberSaveableStateHolder()
                if (show) holder.SaveableStateProvider("reader") {
                    KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = layout)
                }
            }
            scene.use {
                val name = layout::class.simpleName
                driver.settles({ "$name: the viewer attaches" }) { state.adapter != null }
                runBlocking { state.scrollTo(KiteLocation(0, 4)) }
                driver.settles({ "$name: the viewer goes to page 4" }) { state.currentPage == 4 }
                show = false
                driver.settles({ "$name: the viewer leaves" }) { state.adapter == null }
                runBlocking { state.scrollTo(KiteLocation(0, 8)) }
                assertEquals(8, state.currentPage, "$name: the state holds the place set while the viewer is away")
                show = true
                driver.settles({ "$name: the viewer comes back on page 8" }) { state.adapter != null && state.currentPage == 8 }
            }
        }
    }

    @Test
    fun a_saved_state_reopens_a_book_at_its_passage_in_a_new_layout() = withoutEscapes {
        val bodies = List(6) { c -> (0 until 40).joinToString("") { "<p>Chapter $c, paragraph $it, has enough words to fill a line.</p>" } }
        val bytes = multiSpineEpub(bodies)
        val wide = EpubSettings(pageWidth = 200.0, pageHeight = 200.0, margin = 10.0)
        val narrow = wide.copy(pageWidth = 100.0)
        val target = KiteLocation(4, 2)
        for (layout in listOf(KiteDocLayout.Continuous(), KiteDocLayout.Paged())) forBothEffectOrders { queued ->
            var show by mutableStateOf(true)
            var settings by mutableStateOf(wide)
            val current = AtomicReference<KiteDocViewState>()
            val (scene, driver) = drivenScene(200, 200, queued) {
                val holder = rememberSaveableStateHolder()
                if (show) holder.SaveableStateProvider("reader") {
                    // A new document each time, as after a rotation or the end of the process:
                    // nothing is laid out, and the page may have another size.
                    val book = remember { EpubDocument.open(bytes, settings) }
                    val state = rememberKiteDocViewState(book)
                    current.set(state)
                    KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = layout)
                }
            }
            scene.use {
                val name = layout::class.simpleName
                val before = current.get()
                driver.settles({ "$name: the viewer attaches" }) { before.adapter != null }
                runBlocking { before.scrollTo(target) }
                driver.settles({ "$name: the reader reaches $target" }) { before.currentLocation == target }
                val mark = before.currentBookmark()
                val expected = EpubDocument.open(bytes, narrow).locate(mark)
                kotlin.test.assertNotEquals(target, expected, "$name: the narrow layout moves the passage, or the test proves nothing")
                show = false
                driver.settles({ "$name: the viewer leaves" }) { before.adapter == null }
                settings = narrow
                show = true
                driver.settles({ "$name: the restored state reopens at the passage, $expected, not at ${current.get().currentLocation} (a new state: ${current.get() !== before})" }) {
                    val after = current.get()
                    after !== before && after.adapter != null && after.currentLocation == expected
                }
            }
        }
    }

    @Test
    fun switching_the_strip_orientation_keeps_the_place_and_drops_the_pan() = withoutEscapes {
        forBothEffectOrders { queued ->
            val state = KiteDocViewState(pdf(5, width = 200.0, height = 300.0))
            var orientation by mutableStateOf(Orientation.Vertical)
            val (scene, driver) = drivenScene(200, 320, queued) {
                KiteDocView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    layout = KiteDocLayout.Continuous(orientation),
                    pageSpacing = 0.dp,
                )
            }
            scene.use {
                driver.settles({ "the viewer attaches" }) { state.adapter != null }
                val place = KiteScrollPosition(KiteLocation(0, 1), 250)
                runBlocking { state.scrollTo(place) }
                driver.settles({ "the strip scrolls to $place" }) { state.currentScrollPosition == place }
                orientation = Orientation.Horizontal
                // A 200 x 300 page is 213 px wide in a strip 320 px tall: 250 of 300 px is 178 of 213.
                driver.settles({ "the horizontal strip keeps page 1 at 178 px, not ${state.currentScrollPosition}" }) {
                    val now = state.currentScrollPosition
                    now.location == KiteLocation(0, 1) && abs(now.offsetPx - 178) <= 2
                }

                orientation = Orientation.Vertical
                driver.settles({ "the vertical strip keeps page 1" }) { state.currentScrollPosition.location == KiteLocation(0, 1) }
                state.setZoom(2f)
                state.panBy(Offset(80f, 0f))
                assertEquals(80f, state.panOffset.x, 0.5f, "a vertical strip pans across")
                orientation = Orientation.Horizontal
                driver.pumpFrames(3)
                assertEquals(Offset.Zero, state.panOffset, "the pan across the vertical strip is dropped")
            }
        }
    }
}
