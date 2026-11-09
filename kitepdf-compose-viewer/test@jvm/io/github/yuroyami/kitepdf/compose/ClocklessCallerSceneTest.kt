package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An animated call from a coroutine on the viewer's thread that no composition started, as
 * `viewModelScope` or `MainScope()` are, moves the view instead of throwing for want of a frame
 * clock, and one with no view applies its target (#659).
 */
class ClocklessCallerSceneTest {

    private class Blank : KitePage {
        override val displayWidth: Double = 100.0
        override val displayHeight: Double = 100.0
        override fun displayToDeviceBase(): KiteMatrix = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, displayHeight)
        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) = Unit
    }

    private class Pages(count: Int) : KiteDocument {
        override val pageCount: Int = count
        override val pages: List<KitePage> = List(count) { Blank() }
    }

    @Test
    fun an_animated_call_from_a_scope_without_a_clock_moves_the_view() {
        val state = KiteDocViewState(Pages(4))
        val (scene, driver) = drivenScene(200, 200, queued = false) {
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged())
        }
        scene.use {
            driver.pumpUntilState { state.adapter != null }
            // runOnUi runs on the viewer's thread under a dispatcher with no frame clock.
            driver.runOnUi { state.nextPage() }
            driver.pumpUntilState { state.currentPage == 1 }
            driver.runOnUi { state.animateZoomTo(2f) }
            driver.pumpUntilState { kotlin.math.abs(state.zoom - 2f) < 0.01f }
        }
    }

    @Test
    fun an_animated_zoom_with_no_view_applies_its_target() {
        val state = KiteDocViewState(Pages(1))
        val expected = 2f.coerceIn(state.zoomRange.start, state.zoomRange.endInclusive)
        runBlocking { state.animateZoomTo(2f) }
        assertEquals(expected, state.zoom, "the zoom range is ${state.zoomRange}")
    }
}
