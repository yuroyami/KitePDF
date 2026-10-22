package io.github.yuroyami.kitepdf.compose

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import io.github.yuroyami.kitepdf.PdfDocument
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A finger, a zoom request or a new animation stops a zoom animation that still runs (#405). */
class ZoomAnimationSceneTest {

    /** One empty 200 x 200 page. */
    private fun pagePdf(): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** A viewer with two zoom animations the test starts: to 3 and then to 1, each over a second. */
    private class Animated(
        val scene: ImageComposeScene,
        val driver: SceneTestDriver,
        val state: () -> KiteDocViewState,
        private val firstFlag: androidx.compose.runtime.MutableState<Boolean>,
        private val secondFlag: androidx.compose.runtime.MutableState<Boolean>,
        private val ended: java.util.concurrent.atomic.AtomicReference<String?>,
    ) {
        var first: Boolean by firstFlag
        var second: Boolean by secondFlag
        val firstEnded: String? get() = ended.get()
    }

    private fun animated(queued: Boolean): Animated {
        val doc = PdfDocument.open(pagePdf())
        lateinit var state: KiteDocViewState
        val first = mutableStateOf(false)
        val second = mutableStateOf(false)
        val ended = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val (scene, driver) = drivenScene(200, 200, queued) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.SinglePage(0),
                zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
            )
            LaunchedEffect(first.value) {
                if (!first.value) return@LaunchedEffect
                try {
                    state.animateZoomTo(3f, animationSpec = tween(1000))
                    ended.set("done")
                } catch (stopped: CancellationException) {
                    ended.set("stopped")
                    throw stopped
                }
            }
            LaunchedEffect(second.value) {
                if (second.value) state.animateZoomTo(1f, animationSpec = tween(1000))
            }
        }
        return Animated(scene, driver, { state }, first, second, ended)
    }

    @Test
    fun a_zoom_request_stops_a_running_zoom_animation() {
        forBothEffectOrders { queued ->
            animated(queued).run {
                scene.use {
                    driver.pumpUntilState { state().pageGeometry.isNotEmpty() }
                    first = true
                    driver.pumpFrames(4)
                    state().setZoom(1.5f)
                    driver.pumpFrames(80)
                    assertEquals(1.5f, state().zoom, 0.001f, "the animation went on after the zoom request")
                    assertEquals("stopped", firstEnded)
                }
            }
        }
    }

    @Test
    fun a_pan_stops_a_running_zoom_animation() {
        forBothEffectOrders { queued ->
            animated(queued).run {
                scene.use {
                    driver.pumpUntilState { state().pageGeometry.isNotEmpty() }
                    first = true
                    driver.pumpFrames(4)
                    state().panBy(Offset(1f, 0f))
                    val panned = state().zoom
                    driver.pumpFrames(80)
                    assertEquals(panned, state().zoom, 0.001f, "the animation went on after the pan")
                }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
    @Test
    fun a_pinch_takes_over_from_a_running_zoom_animation() {
        forBothEffectOrders { queued ->
            animated(queued).run {
                scene.use {
                    driver.pumpUntilState { state().pageGeometry.isNotEmpty() }
                    first = true
                    driver.pumpFrames(4)
                    fun fingers(spread: Float, pressed: Boolean) = listOf(
                        ComposeScenePointer(PointerId(1), Offset(100f - spread, 100f), pressed, PointerType.Touch),
                        ComposeScenePointer(PointerId(2), Offset(100f + spread, 100f), pressed, PointerType.Touch),
                    )
                    scene.sendPointerEvent(PointerEventType.Press, fingers(20f, true))
                    for (spread in listOf(24f, 28f, 32f)) {
                        scene.sendPointerEvent(PointerEventType.Move, fingers(spread, true))
                        driver.pumpFrames(1)
                    }
                    scene.sendPointerEvent(PointerEventType.Release, fingers(32f, false))
                    driver.pumpFrames(1)
                    val pinched = state().zoom
                    driver.pumpFrames(80)
                    assertEquals(pinched, state().zoom, 0.001f, "the animation went on after the pinch")
                    assertTrue(pinched < 3f)
                }
            }
        }
    }

    @Test
    fun a_new_zoom_animation_takes_over_from_the_old_one() {
        forBothEffectOrders { queued ->
            animated(queued).run {
                scene.use {
                    driver.pumpUntilState { state().pageGeometry.isNotEmpty() }
                    first = true
                    driver.pumpFrames(4)
                    second = true
                    driver.pumpFrames(3)
                    assertEquals("stopped", firstEnded, "the first animation still runs")
                    driver.pumpFrames(80)
                    assertEquals(1f, state().zoom, 0.001f)
                }
            }
        }
    }
}
