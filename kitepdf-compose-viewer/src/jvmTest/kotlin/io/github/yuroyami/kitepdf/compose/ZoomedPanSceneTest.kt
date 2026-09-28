package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A pan on a zoomed page goes on after the finger lifts, and in a pager a drag past the edge of
 * a zoomed page turns the page. The pan stopped dead, and the reader had to zoom out to turn (#410).
 */
class ZoomedPanSceneTest {

    /** [count] 200 x 300 pages. */
    private fun pagesPdf(count: Int): PdfDocument {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [${(0 until count).joinToString(" ") { "${it + 3} 0 R" }}] /Count $count /MediaBox [0 0 200 300] >>")
        repeat(count) { add("<< /Type /Page /Parent 2 0 R /Resources << >> >>") }
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    private var clock = 1_000L

    /** A finger drag from [fromX] to [toX] at y 150, [steps] moves of [msPerStep] each, and a release. */
    private fun drag(scene: ImageComposeScene, fromX: Float, toX: Float, steps: Int, msPerStep: Long) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(fromX, 150f), timeMillis = clock, type = PointerType.Touch)
        for (step in 1..steps) {
            clock += msPerStep
            scene.sendPointerEvent(PointerEventType.Move, Offset(fromX + (toX - fromX) * step / steps, 150f), timeMillis = clock, type = PointerType.Touch)
        }
        scene.sendPointerEvent(PointerEventType.Release, Offset(toX, 150f), timeMillis = clock, type = PointerType.Touch)
    }

    private fun withZoomedPager(direction: LayoutDirection = LayoutDirection.Ltr, block: (ImageComposeScene, KiteDocViewState, SceneTestDriver) -> Unit) {
        val state = KiteDocViewState(pagesPdf(3))
        ImageComposeScene(width = 200, height = 300, density = Density(1f)) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged())
            }
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            state.setZoom(3f)
            driver.pumpFrames(5)
            block(scene, state, driver)
        }
    }

    @Test
    fun a_quick_pan_goes_on_after_the_finger_lifts() {
        withZoomedPager { scene, state, driver ->
            drag(scene, 150f, 100f, steps = 5, msPerStep = 8)
            val atRelease = state.panOffset.x
            driver.pumpFrames(20)
            assertTrue(state.panOffset.x < atRelease - 5f, "the pan stopped at ${atRelease}: ${state.panOffset.x}")
            assertEquals(0, state.currentPage)
        }
    }

    @Test
    fun a_press_stops_a_fling() {
        withZoomedPager { scene, state, driver ->
            drag(scene, 150f, 100f, steps = 5, msPerStep = 8)
            driver.pumpFrames(2)
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 150f), timeMillis = clock, type = PointerType.Touch)
            driver.pumpFrames(2)
            val held = state.panOffset
            driver.pumpFrames(20)
            assertEquals(held, state.panOffset, "the fling went on under the finger")
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 150f), timeMillis = clock, type = PointerType.Touch)
        }
    }

    @Test
    fun a_drag_past_the_edge_of_a_zoomed_page_turns_it() {
        withZoomedPager { scene, state, driver ->
            // The page is 600 px wide at zoom 3, so the pan reaches the right edge 200 px from the centre.
            drag(scene, 190f, 10f, steps = 30, msPerStep = 30)
            driver.pumpFrames(30)
            assertEquals(0, state.currentPage, "a drag inside the page turned it")
            drag(scene, 190f, 10f, steps = 30, msPerStep = 30)
            driver.pumpUntilState { state.currentPage == 1 }
            assertEquals(1, state.currentPage)
        }
    }

    @Test
    fun in_a_right_to_left_pager_a_drag_past_the_left_edge_turns_forward() {
        withZoomedPager(LayoutDirection.Rtl) { scene, state, driver ->
            drag(scene, 10f, 190f, steps = 30, msPerStep = 30)
            driver.pumpFrames(30)
            drag(scene, 10f, 190f, steps = 30, msPerStep = 30)
            driver.pumpUntilState { state.currentPage == 1 }
            assertEquals(1, state.currentPage)
        }
    }
}
