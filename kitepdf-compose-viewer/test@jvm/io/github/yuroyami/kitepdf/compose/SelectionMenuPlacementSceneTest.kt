package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The selection menu sits above the selection, or below it when there is no room above, so it
 * does not cover the words. It sat at the top centre and covered a selection there (#408).
 */
class SelectionMenuPlacementSceneTest {

    /** Page 1 has a line at the top and a line at the bottom. Page 2 has no text. */
    private fun doc() = KitePDF.open(
        PdfBuilder().apply {
            page(width = 200.0, height = 300.0) {
                text(StandardFont.Helvetica, 14.0, 20.0, 280.0, "Words at the top")
                text(StandardFont.Helvetica, 14.0, 20.0, 20.0, "Words at the bottom")
            }
            page(width = 200.0, height = 300.0) {
                rectangle(20.0, 20.0, 100.0, 100.0)
                fill()
            }
        }.build(),
    )

    private fun withMenu(alignment: Alignment?, block: (KiteDocViewState, SceneTestDriver, () -> Rect?) -> Unit) {
        val state = KiteDocViewState(doc())
        var menu: Rect? = null
        ImageComposeScene(width = 200, height = 300, density = Density(1f)) {
            KiteDocView(
                state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged(),
                overlay = {
                    KiteSelectionMenu(
                        state = state,
                        items = listOf(KiteSelectionMenuItem("Copy") {}),
                        alignment = alignment,
                        container = { _, content -> Box(Modifier.onGloballyPositioned { menu = it.boundsInRoot() }) { content() } },
                    )
                },
            )
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.containsKey(0) }
            block(state, driver) { menu }
        }
    }

    /** Selects the line on page 1 at display y [y], from x 22 to x 90. */
    private fun select(state: KiteDocViewState, y: Double) {
        onTestUiThread { state.beginSelection(assertNotNull(state.displayToViewport(0, 22.0, y))) }
        onTestUiThread { state.extendSelection(assertNotNull(state.displayToViewport(0, 90.0, y))) }
        onTestUiThread { state.endSelectionGesture() }
    }

    @Test
    fun the_menu_goes_below_a_selection_at_the_top_and_above_one_at_the_bottom() {
        withMenu(alignment = null) { state, driver, menu ->
            select(state, 15.0)
            driver.pumpUntilState { menu() != null }
            driver.pumpFrames(3)
            val top = assertNotNull(state.selectionBounds)
            val below = assertNotNull(menu())
            assertTrue(below.top >= top.bottom, "the menu at $below covers the selection at $top")

            onTestUiThread { state.clearSelection() }
            select(state, 275.0)
            driver.pumpFrames(5)
            val bottom = assertNotNull(state.selectionBounds)
            val above = assertNotNull(menu())
            assertTrue(above.bottom <= bottom.top, "the menu at $above covers the selection at $bottom")
        }
    }

    @Test
    fun an_alignment_still_places_the_menu() {
        withMenu(alignment = Alignment.BottomCenter) { state, driver, menu ->
            select(state, 15.0)
            driver.pumpUntilState { menu() != null }
            driver.pumpFrames(3)
            val rect = assertNotNull(menu())
            assertEquals(300f - 10f, rect.bottom, 1f, "the menu is not at the bottom: $rect")
        }
    }

    @Test
    fun a_long_press_on_a_page_without_text_takes_no_lock() {
        withMenu(alignment = null) { state, driver, _ ->
            driver.runOnUi { state.scrollToPage(1) }
            driver.pumpUntilState { state.pageGeometry.containsKey(1) && state.currentPage == 1 }
            onTestUiThread { state.beginSelection(assertNotNull(state.displayToViewport(1, 50.0, 250.0))) }
            assertFalse(state.isSelectionActive, "a page without text took the pan lock")
            onTestUiThread { state.endSelectionGesture() }
        }
    }
}
