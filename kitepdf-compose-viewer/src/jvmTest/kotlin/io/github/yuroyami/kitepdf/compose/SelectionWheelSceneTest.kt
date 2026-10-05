package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The wheel scrolls a continuous layout while text is selected. A selection holds the list's own
 * scrolling for as long as it is on screen, so a finger cannot slide the page from under it, and
 * that hold took the wheel as well: after a mouse selection the page did not move until a click
 * cleared the selection (#594).
 */
@OptIn(ExperimentalComposeUiApi::class)
class SelectionWheelSceneTest {

    /** Four 200 x 300 pages, each with a line of text along display y 40 to 50. */
    private fun doc() = KitePDF.open(
        PdfBuilder().apply {
            repeat(4) { page(width = 200.0, height = 300.0) { text(StandardFont.Helvetica, 14.0, 20.0, 250.0, "Select these words $it") } }
        }.build(),
    )

    private fun withViewer(block: (ImageComposeScene, KiteDocViewState, SceneTestDriver) -> Unit) {
        val state = KiteDocViewState(doc())
        ImageComposeScene(width = 200, height = 300, density = Density(1f)) {
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Continuous())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            block(scene, state, driver)
        }
    }

    /** Where the top of the first page is in the viewport, which moves up as the list scrolls down. */
    private fun firstPageTop(state: KiteDocViewState): Float = assertNotNull(state.displayToViewport(0, 0.0, 0.0), "the first page left the strip").y

    /** Presses at [from], moves to [to] in steps, and releases, as [type]. */
    private fun drag(scene: ImageComposeScene, from: Offset, to: Offset, type: PointerType) {
        val pressed = PointerButtons(isPrimaryPressed = true)
        scene.sendPointerEvent(PointerEventType.Press, from, type = type, buttons = pressed)
        for (step in 1..6) {
            scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (step / 6f), type = type, buttons = pressed)
        }
        scene.sendPointerEvent(PointerEventType.Release, to, type = type, buttons = PointerButtons())
    }

    private fun wheelDown(scene: ImageComposeScene, driver: SceneTestDriver) {
        scene.sendPointerEvent(PointerEventType.Scroll, Offset(100f, 200f), scrollDelta = Offset(0f, 1f), type = PointerType.Mouse)
        driver.pumpFrames(20)
    }

    /** Selects the first words of the first page as a long press and a drag of the finger do, and lets a frame show it. */
    private fun selectByFinger(state: KiteDocViewState, driver: SceneTestDriver) {
        onTestUiThread { state.beginSelection(assertNotNull(state.displayToViewport(0, 21.0, 45.0))) }
        onTestUiThread { state.extendSelection(assertNotNull(state.displayToViewport(0, 130.0, 45.0))) }
        onTestUiThread { state.endSelectionGesture() }
        assertNotNull(state.selection, "the test selected nothing")
        driver.pumpFrames(3)
    }

    @Test
    fun the_wheel_scrolls_a_continuous_layout_after_a_mouse_selection() {
        withViewer { scene, state, driver ->
            val from = assertNotNull(state.displayToViewport(0, 21.0, 45.0))
            val to = assertNotNull(state.displayToViewport(0, 130.0, 45.0))
            drag(scene, from, to, PointerType.Mouse)
            driver.pumpFrames(3)
            assertNotNull(state.selection, "the mouse drag selected nothing")
            val before = firstPageTop(state)
            wheelDown(scene, driver)
            val after = firstPageTop(state)
            assertTrue(after < before - 10f, "the wheel did not scroll the page under a selection: top $before -> $after")
            assertNotNull(state.selection, "the wheel dropped the selection")
        }
    }

    @Test
    fun the_wheel_scrolls_a_selection_made_by_finger_once_a_mouse_is_over_the_view() {
        withViewer { scene, state, driver ->
            selectByFinger(state, driver)
            scene.sendPointerEvent(PointerEventType.Move, Offset(100f, 200f), type = PointerType.Mouse)
            driver.pumpFrames(3)
            val before = firstPageTop(state)
            wheelDown(scene, driver)
            val after = firstPageTop(state)
            assertTrue(after < before - 10f, "the wheel did not scroll the page under a selection: top $before -> $after")
        }
    }

    @Test
    fun a_finger_swipe_still_leaves_a_page_with_a_selection_in_place() {
        withViewer { scene, state, driver ->
            fun swipeUp() {
                scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 250f), type = PointerType.Touch)
                for (y in listOf(220f, 180f, 140f, 100f, 60f)) {
                    scene.sendPointerEvent(PointerEventType.Move, Offset(100f, y), type = PointerType.Touch)
                    driver.pumpFrames(1)
                }
                scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 60f), type = PointerType.Touch)
                driver.pumpFrames(20)
            }
            selectByFinger(state, driver)
            val before = firstPageTop(state)
            swipeUp()
            assertEquals(before, firstPageTop(state), "a finger swipe slid the page under a selection")
            // The same swipe without a selection scrolls.
            driver.runOnUi { state.clearSelection() }
            driver.pumpFrames(3)
            swipeUp()
            val after = state.displayToViewport(0, 0.0, 0.0)?.y
            assertTrue(after == null || after < before - 10f, "the swipe of the test does not scroll at all: top $before -> $after")
        }
    }
}
