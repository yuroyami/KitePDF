package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.KiteStructuredText
import io.github.yuroyami.kitepdf.core.KiteTextBlock
import io.github.yuroyami.kitepdf.core.KiteTextLine
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The selection handles sit on the logical ends of the selected text in every direction: across
 * a vertical column at its top and bottom, and on the right of a right-to-left run for its start.
 * A still grab on either handle keeps the selection (#406).
 */
class SelectionHandleDirectionSceneTest {

    /** A 100 x 100 page that draws nothing and has one line of text. */
    private class TextPage(private val line: KiteTextLine) : KitePage {
        override val displayWidth: Double = 100.0
        override val displayHeight: Double = 100.0
        override fun displayToDeviceBase(): KiteMatrix = KiteMatrix.IDENTITY
        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {}
        override fun textContent(): KiteStructuredText = KiteStructuredText(listOf(KiteTextBlock(listOf(line))))
    }

    private class OnePage(page: KitePage) : KiteDocument {
        override val pageCount: Int = 1
        override val pages: List<KitePage> = listOf(page)
    }

    /** Selects from [from] to [to] on a viewer of [line], then checks the handles with [check]. */
    private fun select(line: KiteTextLine, from: Offset, to: Offset, check: (KiteDocViewState, SceneTestDriver) -> Unit) = forBothEffectOrders { queued ->
        val state = KiteDocViewState(OnePage(TextPage(line)))
        // Display space is viewport space here: a 100 pt page in a 100 px viewer.
        val (scene, driver) = drivenScene(100, 100, queued) {
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.SinglePage(0),
                colors = KiteDocViewColors(selectionHandle = Color.Red),
            )
        }
        scene.use {
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            state.beginSelection(from)
            state.extendSelection(to)
            state.endSelectionGesture()
            check(state, driver)
        }
    }

    /** A still grab on each handle leaves the selection as it was. */
    private fun assertStillGrabsKeep(state: KiteDocViewState, text: String) {
        for (edge in KiteSelectionHandleEdge.entries) {
            state.beginHandleDrag(edge)
            state.extendSelection(state.handlePoint(edge)!!)
            state.endSelectionGesture()
            assertEquals(text, state.selection?.text, "a still grab on the $edge handle keeps the selection")
        }
    }

    @Test
    fun the_handles_of_a_vertical_selection_sit_at_the_ends_of_the_column() {
        val column = KiteTextLine("abcd", KiteRectangle(20.0, 20.0, 30.0, 60.0), doubleArrayOf(20.0, 30.0, 40.0, 50.0, 60.0), vertical = true)
        select(column, Offset(25f, 25f), Offset(25f, 55f)) { state, driver ->
            assertEquals("abcd", state.selection?.text)
            val start = state.handlePoint(KiteSelectionHandleEdge.Start)!!
            val end = state.handlePoint(KiteSelectionHandleEdge.End)!!
            assertEquals(25f, start.x, 0.5f, "the start handle is across the column")
            assertTrue(start.y in 20f..25f, "the start handle is at the top of the column, not ${start.y}")
            assertEquals(25f, end.x, 0.5f, "the end handle is across the column")
            assertTrue(end.y in 55f..60f, "the end handle is at the bottom of the column, not ${end.y}")
            // The default marker turns with the column: its dot sits left of the column, level
            // with the start caret, where a horizontal marker would put it below the line.
            val frame = driver.pumpFrames(2).toComposeImageBitmap().toPixelMap()
            assertTrue(frame[17, 20].let { it.red > 0.8f && it.green < 0.3f }, "the start marker's dot is left of the column: ${frame[17, 20]}")
            assertStillGrabsKeep(state, "abcd")
        }
    }

    @Test
    fun the_start_handle_of_a_right_to_left_selection_is_on_its_right() {
        val rtl = KiteTextLine("abcd", KiteRectangle(20.0, 5.0, 60.0, 15.0), doubleArrayOf(60.0, 50.0, 40.0, 30.0, 20.0))
        select(rtl, Offset(55f, 10f), Offset(25f, 10f)) { state, _ ->
            assertEquals("abcd", state.selection?.text)
            val start = state.handlePoint(KiteSelectionHandleEdge.Start)!!
            val end = state.handlePoint(KiteSelectionHandleEdge.End)!!
            assertTrue(start.x in 55f..60f, "the logical start is on the right, not ${start.x}")
            assertTrue(end.x in 20f..25f, "the logical end is on the left, not ${end.x}")
            assertStillGrabsKeep(state, "abcd")
        }
    }

    @Test
    fun a_still_grab_in_the_middle_of_a_line_keeps_the_selection() {
        // "bcde" of "abcdef": each end shares its boundary with a char outside the selection.
        val line = KiteTextLine("abcdef", KiteRectangle(10.0, 5.0, 70.0, 15.0), doubleArrayOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0))
        select(line, Offset(25f, 10f), Offset(55f, 10f)) { state, _ ->
            assertEquals("bcde", state.selection?.text)
            assertStillGrabsKeep(state, "bcde")
        }
    }
}
