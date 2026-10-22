package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On the desktop and the web, Ctrl or Cmd with the wheel zooms, and the keyboard pages and zooms
 * once a press gives the view the focus. The view took only touch gestures (#411). The plain
 * wheel turns the page of a horizontal layout, which took only the sideways wheel (#592).
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class KeyboardAndWheelSceneTest {

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

    private fun withViewer(
        layout: KiteDocLayout = KiteDocLayout.Paged(),
        direction: LayoutDirection = LayoutDirection.Ltr,
        block: (ImageComposeScene, KiteDocViewState, SceneTestDriver) -> Unit,
    ) {
        val state = KiteDocViewState(pagesPdf(4))
        ImageComposeScene(width = 200, height = 300, density = Density(1f)) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = layout)
            }
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            block(scene, state, driver)
        }
    }

    private fun wheel(scene: ImageComposeScene, deltaY: Float, ctrl: Boolean) {
        scene.sendPointerEvent(
            PointerEventType.Scroll, Offset(100f, 150f), scrollDelta = Offset(0f, deltaY), type = PointerType.Mouse,
            keyboardModifiers = PointerKeyboardModifiers(isCtrlPressed = ctrl),
        )
    }

    /** A plain wheel event at [time], as a mouse notch or a part of a trackpad flick sends. */
    private fun plainWheel(scene: ImageComposeScene, deltaY: Float, time: Long) {
        scene.sendPointerEvent(
            PointerEventType.Scroll, Offset(100f, 150f), scrollDelta = Offset(0f, deltaY), timeMillis = time,
            type = PointerType.Mouse,
        )
    }

    /** A click on the view, which gives it the focus. */
    private fun click(scene: ImageComposeScene) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 150f), type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 150f), type = PointerType.Mouse)
    }

    private fun key(scene: ImageComposeScene, key: Key, ctrl: Boolean = false) {
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl))
    }

    @Test
    fun ctrl_with_the_wheel_zooms_and_the_wheel_alone_does_not() {
        withViewer { scene, state, driver ->
            wheel(scene, -1f, ctrl = false)
            driver.pumpFrames(3)
            assertEquals(1f, state.zoom, "the wheel alone zoomed")
            wheel(scene, -3f, ctrl = true)
            driver.pumpFrames(3)
            assertTrue(state.zoom > 1.2f, "Ctrl with the wheel did not zoom in: ${state.zoom}")
            val zoomedIn = state.zoom
            wheel(scene, 1f, ctrl = true)
            driver.pumpFrames(3)
            assertTrue(state.zoom < zoomedIn, "Ctrl with the wheel down did not zoom out")
        }
    }

    @Test
    fun page_keys_turn_the_page_once_a_click_gives_the_view_the_focus() {
        withViewer { scene, state, driver ->
            click(scene)
            driver.pumpFrames(30)
            key(scene, Key.PageDown)
            driver.pumpUntilState { state.currentPage == 1 }
            key(scene, Key.MoveEnd)
            driver.pumpUntilState { state.currentPage == 3 }
            key(scene, Key.PageUp)
            driver.pumpUntilState { state.currentPage == 2 }
            key(scene, Key.MoveHome)
            driver.pumpUntilState { state.currentPage == 0 }
        }
    }

    @Test
    fun page_keys_turn_a_whole_spread_in_the_spread_layout() {
        withViewer(layout = KiteDocLayout.Spread()) { scene, state, driver ->
            driver.pumpUntilState { state.stripSettled }
            click(scene)
            driver.pumpFrames(30)
            key(scene, Key.PageDown)
            driver.pumpUntilState { state.currentPage == 2 }
            key(scene, Key.PageUp)
            driver.pumpUntilState { state.currentPage == 0 }
        }
    }

    @Test
    fun a_field_with_the_caret_keeps_the_page_keys() {
        withViewer { scene, state, driver ->
            click(scene)
            driver.pumpFrames(30)
            onTestUiThread { state.focusField("name") }
            driver.pumpFrames(5)
            key(scene, Key.PageDown)
            driver.pumpFrames(60)
            assertEquals(0, state.currentPage, "a page key turned the page while a field had the caret")
        }
    }

    @Test
    fun the_left_arrow_goes_forward_where_pages_advance_to_the_left() {
        withViewer(direction = LayoutDirection.Rtl) { scene, state, driver ->
            click(scene)
            driver.pumpFrames(30)
            key(scene, Key.DirectionLeft)
            driver.pumpUntilState { state.currentPage == 1 }
            key(scene, Key.DirectionRight)
            driver.pumpUntilState { state.currentPage == 0 }
        }
    }

    @Test
    fun ctrl_with_plus_zooms_in_and_ctrl_with_0_goes_back_to_fit() {
        withViewer { scene, state, driver ->
            click(scene)
            driver.pumpFrames(30)
            key(scene, Key.Equals, ctrl = true)
            driver.pumpUntilState { state.zoom > 1.2f }
            key(scene, Key.Zero, ctrl = true)
            driver.pumpUntilState { state.zoom == 1f }
        }
    }

    @Test
    fun the_wheel_turns_the_page_of_a_horizontal_paged_layout() {
        withViewer { scene, state, driver ->
            plainWheel(scene, 1f, time = 1_000)
            driver.pumpUntilState { state.currentPage == 1 }
            plainWheel(scene, 1f, time = 2_000)
            driver.pumpUntilState { state.currentPage == 2 }
            plainWheel(scene, -1f, time = 3_000)
            driver.pumpUntilState { state.currentPage == 1 }
        }
    }

    @Test
    fun a_trackpad_flick_turns_one_page() {
        withViewer { scene, state, driver ->
            // A flick and the tail of events that follows it, 10 ms apart.
            for (i in 0 until 40) {
                plainWheel(scene, 0.4f, time = 1_000L + 10 * i)
                driver.pumpFrames(1)
            }
            driver.pumpUntilState { state.currentPage == 1 }
            driver.pumpFrames(60)
            assertEquals(1, state.currentPage, "one flick turned more than one page")
        }
    }

    @Test
    fun the_wheel_down_goes_forward_where_pages_advance_to_the_left() {
        withViewer(direction = LayoutDirection.Rtl) { scene, state, driver ->
            plainWheel(scene, 1f, time = 1_000)
            driver.pumpUntilState { state.currentPage == 1 }
        }
    }

    @Test
    fun the_wheel_turns_a_whole_spread_in_the_spread_layout() {
        withViewer(layout = KiteDocLayout.Spread()) { scene, state, driver ->
            driver.pumpUntilState { state.stripSettled }
            plainWheel(scene, 1f, time = 1_000)
            driver.pumpUntilState { state.currentPage == 2 }
        }
    }

    @Test
    fun a_sideways_swipe_that_drifts_down_stays_sideways() {
        withViewer { scene, state, driver ->
            // A short swipe to the side that the pager takes, then a tail that goes only down.
            var time = 1_000L
            repeat(10) {
                scene.sendPointerEvent(
                    PointerEventType.Scroll, Offset(100f, 150f), scrollDelta = Offset(0.1f, 0f), timeMillis = time,
                    type = PointerType.Mouse,
                )
                time += 10
                driver.pumpFrames(1)
            }
            repeat(4) {
                plainWheel(scene, 0.2f, time)
                time += 10
                driver.pumpFrames(1)
            }
            driver.pumpFrames(60)
            assertEquals(0, state.currentPage, "the tail of a sideways swipe turned the page")
        }
    }

    @Test
    fun a_zoomed_page_keeps_the_wheel() {
        withViewer { scene, state, driver ->
            onTestUiThread { state.setZoom(2f) }
            driver.pumpFrames(5)
            plainWheel(scene, 1f, time = 1_000)
            driver.pumpFrames(60)
            assertEquals(0, state.currentPage, "the wheel turned a zoomed page")
        }
    }
}
