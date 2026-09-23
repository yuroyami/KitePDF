package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.KiteSearchHit
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HighlightTapSceneTest {
    private fun document() = KitePDF.open(PdfBuilder().page(width = 200.0, height = 200.0) {}.build())
    private fun mark(id: String) = KiteHighlight(
        KiteSearchHit(0, listOf(KiteRectangle(30.0, 40.0, 130.0, 60.0), KiteRectangle(30.0, 80.0, 100.0, 100.0)), "two lines"),
        id = id,
    )

    @Test
    fun saved_marks_hit_test_visible_quads_in_paint_order_after_zoom_and_pan() {
        val doc = document()
        lateinit var state: KiteDocViewState
        ImageComposeScene(200, 200, Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state, Modifier.fillMaxSize())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            state.highlights = listOf(mark("first"), mark("top"))
            assertEquals("top", state.highlightAt(Offset(50f, 50f))?.id)
            assertNull(state.highlightAt(Offset(50f, 70f)), "space between lines must not activate a mark")
            state.setZoom(2f)
            state.panBy(Offset(12f, 8f))
            val point = state.displayToViewport(0, 50.0, 50.0)!!
            assertEquals("top", state.highlightAt(point)?.id)
            state.highlights = listOf(mark("first"))
            assertEquals("first", state.highlightAt(point)?.id)
            state.highlights = emptyList()
            state.searchHighlights = listOf(mark("search").hit)
            assertNull(state.highlightAt(point), "search matches are not saved marks")
        }
    }

    @Test
    fun real_tap_uses_latest_callback_and_deleted_mark_stops_intercepting_taps() {
        val doc = document()
        val revision = mutableStateOf(0)
        lateinit var state: KiteDocViewState
        val calls = mutableListOf<Int>()
        var paperTaps = 0
        ImageComposeScene(200, 200, Density(1f)) {
            val current = revision.value
            state = rememberKiteDocViewState(doc)
            KiteDocView(state, Modifier.fillMaxSize(), zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
                onHighlightTap = { calls += current; state.highlights = emptyList(); true },
                onTap = { paperTaps++ })
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            state.highlights = listOf(mark("saved"))
            revision.value = 1
            driver.pumpUntilState(maxFrames = 4, timeoutMs = 100) { false }
            fun tap() {
                scene.sendPointerEvent(PointerEventType.Press, Offset(50f, 50f), type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Release, Offset(50f, 50f), type = PointerType.Touch)
            }
            tap()
            driver.pumpUntilState { calls.isNotEmpty() }
            assertEquals(listOf(1), calls)
            assertEquals(0, paperTaps)
            tap()
            driver.pumpUntilState { paperTaps == 1 }
            assertEquals(1, paperTaps)
            assertNull(state.highlightAt(Offset(50f, 50f)))
        }
    }
}
