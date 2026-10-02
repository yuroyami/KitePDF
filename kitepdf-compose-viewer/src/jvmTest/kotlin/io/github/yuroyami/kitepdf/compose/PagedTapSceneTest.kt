package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every tap on a paged reader reaches the host, also while a page turn is still moving (#455). */
class PagedTapSceneTest {

    /** A complete book of twelve square pages, one per chapter. */
    private fun book(): EpubDocument =
        EpubDocument.open(
            multiSpineEpub((1..12).map { "<p>Page $it.</p>" }),
            EpubSettings(pageWidth = 200.0, pageHeight = 200.0),
        ).also { doc -> repeat(12) { doc.prepareChapter(it) } }

    private fun tap(scene: ImageComposeScene, at: Offset, time: Long) {
        scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, type = PointerType.Touch)
        scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time + 40, type = PointerType.Touch)
    }

    @Test
    fun a_tap_during_a_page_turn_reaches_the_host() {
        for (layout in listOf(KiteDocLayout.Paged(), KiteDocLayout.Spread())) forBothEffectOrders { queued ->
            val doc = book()
            val taps = AtomicInteger()
            val xs = java.util.Collections.synchronizedList(ArrayList<Float>())
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                val scope = rememberCoroutineScope()
                KiteDocView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    layout = layout,
                    zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
                    // As the reporting app does: each tap on the right edge starts a page turn.
                    onTap = { at ->
                        taps.incrementAndGet()
                        xs.add(at.x)
                        scope.launch { state.nextPage() }
                    },
                )
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                var time = 1_000L
                repeat(TAPS) { sent ->
                    tap(scene, Offset(185f, 100f), time)
                    time += 120
                    // Three frames: the turn that this tap started is still moving at the next tap.
                    driver.pumpFrames(3)
                    if (taps.get() != sent + 1) {
                        throw AssertionError("${layout::class.simpleName}: tap ${sent + 1} of $TAPS reached onTap ${taps.get() - sent} times")
                    }
                }
                // The host finds the edge from the position, so it is the place in the viewer, not in a moving page.
                for (x in xs) assertEquals(185f, x, 0.5f, "${layout::class.simpleName}: the tap positions are $xs")
            }
        }
    }

    @Test
    fun a_tap_at_rest_reaches_the_host_once_and_a_swipe_not_at_all() {
        for (layout in listOf(KiteDocLayout.Paged(), KiteDocLayout.Spread())) forBothEffectOrders { queued ->
            val doc = book()
            val taps = AtomicInteger()
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    layout = layout,
                    zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
                    onTap = { taps.incrementAndGet() },
                )
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                tap(scene, Offset(100f, 100f), 1_000)
                driver.pumpFrames(30)
                assertEquals(1, taps.get(), "${layout::class.simpleName}: one tap on a page at rest")

                var time = 2_000L
                scene.sendPointerEvent(PointerEventType.Press, Offset(170f, 100f), timeMillis = time, type = PointerType.Touch)
                for (step in 1..8) {
                    time += 16
                    scene.sendPointerEvent(PointerEventType.Move, Offset(170f - step * 15f, 100f), timeMillis = time, type = PointerType.Touch)
                }
                scene.sendPointerEvent(PointerEventType.Release, Offset(50f, 100f), timeMillis = time + 16, type = PointerType.Touch)
                driver.pumpFrames(60)
                assertEquals(1, taps.get(), "${layout::class.simpleName}: a swipe is no tap")
            }
        }
    }

    private companion object {
        const val TAPS = 6
    }
}
