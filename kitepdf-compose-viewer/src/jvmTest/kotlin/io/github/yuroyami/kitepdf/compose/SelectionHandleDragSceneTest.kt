package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.KiteTextLine
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The selection thumbs are grab targets, not just paint: either one can be
 * dragged to reshape a finished selection while the other end stays put.
 *
 * The fixture is the same 200pt page in a 200px slot the rest of the selection
 * suite uses, so display space and viewport space are the same numbers and a
 * thumb's expected position can be written down by hand.
 */
class SelectionHandleDragSceneTest {

    private fun twoLineDoc() = KitePDF.open(
        PdfBuilder()
            .page(width = 200.0, height = 200.0) {
                text(StandardFont.Helvetica, 12.0, 20.0, 150.0, "hello world")
                text(StandardFont.Helvetica, 12.0, 20.0, 134.0, "second line")
            }
            .build(compress = false),
    )

    private fun lines(doc: io.github.yuroyami.kitepdf.PdfDocument): List<KiteTextLine> =
        doc.pages[0].textContent().blocks.flatMap { it.lines }

    /** A point inside char [index] of [line], in display == viewport space. */
    private fun charPoint(line: KiteTextLine, index: Int) = Offset(
        (line.charEdges[index] + 1).toFloat(),
        ((line.bounds.bottom + line.bounds.top) / 2).toFloat(),
    )

    private inline fun scene(
        doc: io.github.yuroyami.kitepdf.PdfDocument,
        body: (ImageComposeScene, PdfViewState, SceneTestDriver) -> Unit,
    ) {
        lateinit var state: PdfViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberPdfViewState(doc)
            PdfView(state = state, modifier = Modifier.fillMaxSize(), layout = PdfLayout.SinglePage(0))
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { state.pageGeometry.isNotEmpty() }
            body(scene, state, driver)
        }
    }

    /** Long-press-drags a selection over the whole of the first line. */
    private fun selectFirstLine(state: PdfViewState, line: KiteTextLine) {
        state.beginSelection(charPoint(line, 0))
        state.extendSelection(charPoint(line, line.text.length - 1))
        state.endSelectionGesture()
        assertEquals(line.text, assertNotNull(state.selection).text)
    }

    @Test
    fun a_thumb_is_grabbable_within_its_radius_and_nowhere_else() {
        val doc = twoLineDoc()
        val line = lines(doc)[0]
        scene(doc) { _, state, _ ->
            assertNull(state.handlePoint(PdfSelectionHandleEdge.Start), "no selection, no thumb")
            assertNull(state.handleAt(Offset(100f, 100f), 24f), "nothing to grab at rest")

            selectFirstLine(state, line)

            val start = assertNotNull(state.handlePoint(PdfSelectionHandleEdge.Start))
            val end = assertNotNull(state.handlePoint(PdfSelectionHandleEdge.End))
            assertTrue(end.x > start.x, "the end thumb sits after the start one ($start -> $end)")

            assertEquals(PdfSelectionHandleEdge.Start, state.handleAt(start, 24f))
            assertEquals(PdfSelectionHandleEdge.End, state.handleAt(end, 24f))
            // Just inside the radius still grabs; well outside it does not.
            assertEquals(PdfSelectionHandleEdge.Start, state.handleAt(start + Offset(0f, 20f), 24f))
            assertNull(state.handleAt(start + Offset(0f, 60f), 24f), "a press far from both thumbs grabs nothing")
        }
    }

    @Test
    fun dragging_the_start_thumb_moves_that_edge_and_leaves_the_other() {
        val doc = twoLineDoc()
        val line = lines(doc)[0]
        scene(doc) { _, state, _ ->
            selectFirstLine(state, line)

            state.beginHandleDrag(PdfSelectionHandleEdge.Start)
            assertTrue(state.selectionInProgress, "grabbing a thumb starts a drag")
            state.extendSelection(charPoint(line, 6))
            state.endSelectionGesture()

            assertEquals("world", assertNotNull(state.selection).text, "the start moved, the end stayed")
            assertFalse(state.selectionInProgress)
            assertTrue(state.isSelectionActive, "the reshaped selection still holds the page still")
        }
    }

    @Test
    fun dragging_the_end_thumb_moves_that_edge_and_leaves_the_other() {
        val doc = twoLineDoc()
        val line = lines(doc)[0]
        scene(doc) { _, state, _ ->
            selectFirstLine(state, line)

            state.beginHandleDrag(PdfSelectionHandleEdge.End)
            state.extendSelection(charPoint(line, 4))
            state.endSelectionGesture()

            assertEquals("hello", assertNotNull(state.selection).text)
        }
    }

    @Test
    fun dragging_a_thumb_past_the_other_swaps_the_ends() {
        val doc = twoLineDoc()
        val (first, second) = lines(doc)
        scene(doc) { _, state, _ ->
            selectFirstLine(state, first)

            // Grab the START thumb and haul it past the end of the selection,
            // down onto the second line: the old end becomes the new start.
            state.beginHandleDrag(PdfSelectionHandleEdge.Start)
            state.extendSelection(charPoint(second, 5))
            state.endSelectionGesture()

            val sel = assertNotNull(state.selection)
            assertEquals("d\nsecond", sel.text, "the ends swapped instead of collapsing")
            assertTrue(sel.start <= sel.end, "the selection stays ordered after a crossover")
        }
    }

    @Test
    fun dragging_a_thumb_off_the_text_keeps_the_last_good_selection() {
        val doc = twoLineDoc()
        val line = lines(doc)[0]
        scene(doc) { _, state, _ ->
            selectFirstLine(state, line)

            state.beginHandleDrag(PdfSelectionHandleEdge.End)
            state.extendSelection(charPoint(line, 4))
            state.extendSelection(Offset(190f, 195f)) // bare paper, below both lines
            state.endSelectionGesture()

            assertEquals("hello", assertNotNull(state.selection).text, "an off-text drag point changes nothing")
        }
    }

    /**
     * The whole point, driven through real pointer events: a press that lands
     * on a thumb reshapes the selection immediately. It cannot be the ordinary
     * long-press path doing this, because the press is released within a few
     * frames, far under the long-press timeout.
     */
    @Test
    fun a_press_on_a_thumb_drags_it_through_real_pointer_events() {
        val doc = twoLineDoc()
        val line = lines(doc)[0]
        scene(doc) { scene, state, driver ->
            selectFirstLine(state, line)
            val end = assertNotNull(state.handlePoint(PdfSelectionHandleEdge.End))
            val target = charPoint(line, 4)

            scene.sendPointerEvent(PointerEventType.Press, end, type = PointerType.Touch)
            driver.pumpUntil(maxFrames = 2) { false }
            assertTrue(state.selectionInProgress, "the press claimed the thumb")
            scene.sendPointerEvent(PointerEventType.Move, target, type = PointerType.Touch)
            driver.pumpUntil(maxFrames = 2) { false }
            scene.sendPointerEvent(PointerEventType.Release, target, type = PointerType.Touch)
            driver.pumpUntil(maxFrames = 2) { false }

            assertEquals("hello", assertNotNull(state.selection).text)
            assertFalse(state.selectionInProgress, "the lift ends the thumb drag")
        }
    }

    /**
     * A press that misses both thumbs must leave the selection alone: it is an
     * ordinary press, and the long-press detector underneath still owns it.
     */
    @Test
    fun a_press_away_from_the_thumbs_does_not_reshape_the_selection() {
        val doc = twoLineDoc()
        val line = lines(doc)[0]
        scene(doc) { scene, state, driver ->
            selectFirstLine(state, line)
            // Well clear of both thumbs: measured off the real one rather than
            // guessed, since the thumb's x depends on the font's metrics.
            val away = assertNotNull(state.handlePoint(PdfSelectionHandleEdge.End)) + Offset(0f, 100f)
            assertNull(state.handleAt(away, 24f), "the fixture point really is out of grab range")

            scene.sendPointerEvent(PointerEventType.Press, away, type = PointerType.Touch)
            driver.pumpUntil(maxFrames = 2) { false }
            assertFalse(state.selectionInProgress, "a press on bare paper grabs no thumb")
            scene.sendPointerEvent(PointerEventType.Move, away + Offset(20f, 0f), type = PointerType.Touch)
            driver.pumpUntil(maxFrames = 2) { false }
            scene.sendPointerEvent(PointerEventType.Release, away + Offset(20f, 0f), type = PointerType.Touch)
            driver.pumpUntil(maxFrames = 2) { false }

            assertEquals(line.text, assertNotNull(state.selection).text, "the selection survives an unrelated press")
        }
    }
}
