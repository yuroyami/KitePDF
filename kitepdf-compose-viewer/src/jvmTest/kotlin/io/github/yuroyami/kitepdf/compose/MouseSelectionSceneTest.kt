package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A mouse press and drag on text selects at once. It needed a press held for the long-press
 * timeout, as a finger does (#411).
 */
@OptIn(ExperimentalComposeUiApi::class)
class MouseSelectionSceneTest {

    private fun withTextPage(block: (ImageComposeScene, KiteDocViewState, SceneTestDriver) -> Unit) {
        val builder = PdfBuilder()
        builder.page(width = 200.0, height = 300.0) { text(StandardFont.Helvetica, 14.0, 20.0, 250.0, "Select these words") }
        val state = KiteDocViewState(KitePDF.open(builder.build()))
        ImageComposeScene(width = 200, height = 300, density = Density(1f)) {
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            block(scene, state, driver)
        }
    }

    /** Presses at [from], moves to [to] in steps, and releases, as [type]. The text runs along display y 40 to 50. */
    private fun drag(scene: ImageComposeScene, from: Offset, to: Offset, type: PointerType) {
        val pressed = PointerButtons(isPrimaryPressed = true)
        scene.sendPointerEvent(PointerEventType.Press, from, type = type, buttons = pressed)
        for (step in 1..6) {
            scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (step / 6f), type = type, buttons = pressed)
        }
        scene.sendPointerEvent(PointerEventType.Release, to, type = type, buttons = PointerButtons())
    }

    @Test
    fun a_mouse_drag_on_text_selects_at_once() {
        withTextPage { scene, state, driver ->
            val from = assertNotNull(state.displayToViewport(0, 21.0, 45.0))
            val to = assertNotNull(state.displayToViewport(0, 130.0, 45.0))
            drag(scene, from, to, PointerType.Mouse)
            driver.pumpFrames(3)
            val text = assertNotNull(state.selection?.text, "the mouse drag selected nothing")
            assertTrue(text.startsWith("Sel"), "selected \"$text\"")
        }
    }

    @Test
    fun a_finger_drag_does_not_select_without_the_long_press() {
        withTextPage { scene, state, driver ->
            val from = assertNotNull(state.displayToViewport(0, 21.0, 45.0))
            val to = assertNotNull(state.displayToViewport(0, 130.0, 45.0))
            drag(scene, from, to, PointerType.Touch)
            driver.pumpFrames(3)
            assertNull(state.selection)
        }
    }

    @Test
    fun a_mouse_drag_off_the_text_and_a_click_select_nothing() {
        withTextPage { scene, state, driver ->
            val blankFrom = assertNotNull(state.displayToViewport(0, 60.0, 200.0))
            val blankTo = assertNotNull(state.displayToViewport(0, 150.0, 200.0))
            drag(scene, blankFrom, blankTo, PointerType.Mouse)
            driver.pumpFrames(3)
            assertNull(state.selection, "a drag off the text selected")
            assertEquals(false, state.isSelectionActive, "a drag off the text kept the selection lock")
            val on = assertNotNull(state.displayToViewport(0, 21.0, 45.0))
            drag(scene, on, on, PointerType.Mouse)
            driver.pumpFrames(3)
            assertNull(state.selection, "a click selected")
        }
    }
}
