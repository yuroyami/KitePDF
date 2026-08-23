package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Long-press-drag selection through the real composed layout. The
 * gesture callbacks are driven directly (begin/extend), the same way the
 * link-tap acceptance drives its handler; geometry, hit-testing, the char
 * index math and the selection model all run for real.
 */
class SelectionSceneTest {

    private fun twoLineDoc() = KitePDF.open(
        PdfBuilder()
            .page(width = 200.0, height = 200.0) {
                text(StandardFont.Helvetica, 12.0, 20.0, 150.0, "hello world")
                text(StandardFont.Helvetica, 12.0, 20.0, 134.0, "second line")
            }
            .build(compress = false),
    )

    @Test
    fun drag_across_two_lines_selects_their_text_with_one_quad_per_line() {
        val doc = twoLineDoc()
        val kite = doc.pages[0].textContent()
        val lines = kite.blocks.flatMap { it.lines }
        assertEquals(listOf("hello world", "second line"), lines.map { it.text })

        lateinit var state: KiteDocViewState
        val changes = mutableListOf<KiteTextSelection?>()
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            state.onSelectionChange = { changes.add(it) }
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0))
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { state.pageGeometry.isNotEmpty() }

            // Display space == viewport space here (200pt page, 200px slot).
            fun mid(line: io.github.yuroyami.kitepdf.core.KiteTextLine, edge: Int) = Offset(
                line.charEdges[edge].toFloat(),
                ((line.bounds.bottom + line.bounds.top) / 2).toFloat(),
            )

            // Long-press on the first char of line 1, drag to the end of line 2.
            state.beginSelection(mid(lines[0], 0) + Offset(1f, 0f))
            assertNotNull(state.selection, "long-press anchors a selection")
            state.extendSelection(mid(lines[1], lines[1].text.length) + Offset(-1f, 0f))

            val sel = assertNotNull(state.selection)
            assertEquals(0, sel.pageIndex)
            assertEquals("hello world\nsecond line", sel.text, "text matches the extraction exactly")
            assertEquals(2, sel.quads.size, "one quad per line touched")
            for (q in sel.quads) {
                assertTrue(q.width > 0 && q.height > 0)
                assertTrue(q.left >= 0 && q.right <= 200.0 && q.bottom >= 0 && q.top <= 200.0)
            }
            assertTrue(changes.count { it != null } >= 2, "onSelectionChange fired for anchor and extension")

            // The overlay paints: a pixel inside the first quad turns blue-ish.
            val q = sel.quads[0]
            val px = ((q.left + q.right) / 2).toInt()
            val py = ((q.bottom + q.top) / 2).toInt()
            val img = driver.pumpUntil { map -> map[px, py].blue > map[px, py].red }
            val p = img.toComposeImageBitmap().toPixelMap()[px, py]
            assertTrue(p.blue > p.red, "selection overlay tints the page ($p)")

            // Clearing resets state and notifies.
            state.clearSelection()
            assertNull(state.selection)
            assertNull(changes.last())
        }
    }

    @Test
    fun selection_drag_is_a_noop_off_text_and_across_pages() {
        val doc = twoLineDoc()
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0))
        }.use { scene ->
            SceneTestDriver(scene).pumpUntil { state.pageGeometry.isNotEmpty() }
            // Long-press on an empty page region: no crash, no selection.
            state.beginSelection(Offset(100f, 190f))
            assertNull(state.selection)
            // Extending without an anchor is inert too.
            state.extendSelection(Offset(50f, 60f))
            assertNull(state.selection)
        }
    }

    /**
     * The selection-active lock covers the whole gesture, including the window
     * between the long press firing and a selection object existing, and it
     * outlives the finger. A gesture that anchored nothing hands it straight
     * back so an unlucky long press on a margin cannot freeze the viewer.
     */
    @Test
    fun selection_lock_spans_the_gesture_and_releases_on_clear() {
        val doc = twoLineDoc()
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0))
        }.use { scene ->
            SceneTestDriver(scene).pumpUntil { state.pageGeometry.isNotEmpty() }
            assertFalse(state.isSelectionActive, "no selection at rest")

            // Long press on bare paper: locked while the drag runs even though
            // no selection ever materializes, released when the drag ends.
            state.beginSelection(Offset(100f, 190f))
            assertTrue(state.isSelectionActive, "the lock is on from the long press, before any selection exists")
            assertNull(state.selection)
            state.endSelectionGesture()
            assertFalse(state.isSelectionActive, "a gesture that anchored nothing gives pan back")

            // Long press on text: locked, and it survives the finger lifting.
            val line = doc.pages[0].textContent().blocks.first().lines.first()
            val onText = Offset(
                (line.charEdges[0] + 1).toFloat(),
                ((line.bounds.bottom + line.bounds.top) / 2).toFloat(),
            )
            state.beginSelection(onText)
            assertNotNull(state.selection)
            assertTrue(state.isSelectionActive)
            state.endSelectionGesture()
            assertTrue(state.isSelectionActive, "the page stays put while the selection is on screen")

            state.clearSelection()
            assertFalse(state.isSelectionActive, "clearing the selection gives pan back")
        }
    }

    /**
     * `selectionInProgress` is the menu-gating flag: true only while the finger
     * is still building the selection, false the moment it lifts, even though
     * `isSelectionActive` stays true to keep the page from drifting. A menu
     * gated on it appears exactly when the user finishes choosing.
     */
    @Test
    fun selection_in_progress_tracks_the_finger_not_the_selection() {
        val doc = twoLineDoc()
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0))
        }.use { scene ->
            SceneTestDriver(scene).pumpUntil { state.pageGeometry.isNotEmpty() }
            assertFalse(state.selectionInProgress, "nothing in progress at rest")

            val line = doc.pages[0].textContent().blocks.first().lines.first()
            val onText = Offset(
                (line.charEdges[0] + 1).toFloat(),
                ((line.bounds.bottom + line.bounds.top) / 2).toFloat(),
            )
            state.beginSelection(onText)
            assertTrue(state.selectionInProgress, "in progress from the long press")
            assertNotNull(state.selection)

            state.endSelectionGesture()
            assertFalse(state.selectionInProgress, "the lift ends the drag")
            assertTrue(state.isSelectionActive, "but the selection lock stays for the menu")
            assertNotNull(state.selection)

            // A fresh drag over the same selection goes back into progress.
            state.beginSelection(onText)
            assertTrue(state.selectionInProgress)
            state.endSelectionGesture()
            assertFalse(state.selectionInProgress)

            state.clearSelection()
            assertFalse(state.selectionInProgress)
        }
    }

    /**
     * The actual regression: a one-finger drag pans a zoomed page, but not
     * while a selection owns the gesture.
     */
    @Test
    fun an_active_selection_stops_a_one_finger_drag_from_panning() {
        val doc = twoLineDoc()
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0))
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { state.pageGeometry.isNotEmpty() }
            // One-finger pan only engages while zoomed in.
            state.setZoom(2f)
            driver.pumpUntil(maxFrames = 2) { false }

            /** Presses, drags 30px up, and reports the pan taken BEFORE the release. */
            fun dragUp(): Offset {
                state.panOffset = Offset.Zero
                scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 120f), type = PointerType.Touch)
                driver.pumpUntil(maxFrames = 2) { false }
                scene.sendPointerEvent(PointerEventType.Move, Offset(100f, 90f), type = PointerType.Touch)
                driver.pumpUntil(maxFrames = 2) { false }
                val panned = state.panOffset
                scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 90f), type = PointerType.Touch)
                driver.pumpUntil(maxFrames = 2) { false }
                return panned
            }

            val free = dragUp()
            assertTrue(free.y < -1f, "a plain one-finger drag pans the zoomed page (got $free)")

            val line = doc.pages[0].textContent().blocks.first().lines.first()
            state.beginSelection(
                Offset(
                    (line.charEdges[0] + 1).toFloat(),
                    ((line.bounds.bottom + line.bounds.top) / 2).toFloat(),
                ),
            )
            assertTrue(state.isSelectionActive)
            val locked = dragUp()
            assertEquals(Offset.Zero, locked, "the page must not pan under an active selection (got $locked)")

            state.clearSelection()
            driver.pumpUntil(maxFrames = 2) { false }
            val again = dragUp()
            assertTrue(again.y < -1f, "clearing the selection restores panning (got $again)")
        }
    }

    /**
     * The strip's own scrolling yields too: suppressing pan alone would still
     * let the list scroll the page out from under the selection.
     */
    @Test
    fun an_active_selection_stops_the_continuous_strip_from_scrolling() {
        // Page 0 red, page 1 blue, 200px each in a 200px viewport: whatever
        // shows at the bottom of the viewport says how far the strip travelled.
        val doc = KitePDF.open(
            PdfBuilder()
                .page(width = 200.0, height = 200.0) {
                    setFillRgb(1.0, 0.0, 0.0); rectangle(0.0, 0.0, 200.0, 200.0); fill()
                }
                .page(width = 200.0, height = 200.0) {
                    setFillRgb(0.0, 0.0, 1.0); rectangle(0.0, 0.0, 200.0, 200.0); fill()
                }
                .build(),
        )
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { px -> px[100, 190].red > 0.8f }

            /** Drags the strip up ~140px, past the touch slop, in steps. */
            fun dragUp() {
                scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 180f), type = PointerType.Touch)
                driver.pumpUntil(maxFrames = 2) { false }
                for (y in intArrayOf(160, 130, 100, 70, 40)) {
                    scene.sendPointerEvent(PointerEventType.Move, Offset(100f, y.toFloat()), type = PointerType.Touch)
                    driver.pumpUntil(maxFrames = 2) { false }
                }
                scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 40f), type = PointerType.Touch)
            }

            // The fixture has no text layer, so this long press sets the lock
            // without ever producing a selection: exactly the in-between state
            // the gate has to cover.
            state.beginSelection(Offset(100f, 100f))
            assertTrue(state.isSelectionActive)
            dragUp()
            val locked = driver.pumpUntil(maxFrames = 40) { false }.toComposeImageBitmap().toPixelMap()
            assertTrue(
                locked[100, 190].red > 0.8f && locked[100, 190].blue < 0.2f,
                "the strip must not scroll under an active selection (bottom pixel ${locked[100, 190]})",
            )
            assertEquals(0, state.currentPage)

            state.clearSelection()
            driver.pumpUntil(maxFrames = 2) { false }
            dragUp()
            val free = driver.pumpUntil { px -> px[100, 190].blue > 0.8f }.toComposeImageBitmap().toPixelMap()
            assertTrue(
                free[100, 190].blue > 0.8f,
                "clearing the selection restores scrolling (bottom pixel ${free[100, 190]})",
            )
        }
    }
}
