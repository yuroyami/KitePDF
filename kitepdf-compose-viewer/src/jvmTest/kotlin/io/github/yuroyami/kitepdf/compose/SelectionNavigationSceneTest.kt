package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A navigation that leaves the page of a selection drops the selection, so its scroll lock does
 * not stay on the page the reader went to (#407).
 */
class SelectionNavigationSceneTest {

    /** Three 200 x 200 pages, each with a line of text. */
    private fun threePages() = KitePDF.open(
        PdfBuilder().apply {
            repeat(3) { page(width = 200.0, height = 200.0) { text(StandardFont.Helvetica, 12.0, 20.0, 150.0, "hello world $it") } }
        }.build(compress = false),
    )

    @Test
    fun a_selection_left_behind_by_a_navigation_is_dropped() {
        forBothEffectOrders { queued ->
            val doc = threePages()
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged())
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                // The line's baseline is at 150 pt, so display y 50 minus half the text height.
                state.beginSelection(Offset(22f, 46f))
                state.extendSelection(Offset(60f, 46f))
                state.endSelectionGesture()
                assertNotNull(state.selection, "the test selected nothing")
                // A navigation to the selection's own page keeps it.
                runBlocking { state.scrollToPage(0) }
                driver.pumpFrames(5)
                assertNotNull(state.selection)

                runBlocking { state.scrollToPage(1) }
                driver.pumpFrames(5)
                assertNull(state.selection)
                assertFalse(state.isSelectionActive, "the scroll lock stayed on the new page")

                // The swipe back works again.
                scene.sendPointerEvent(PointerEventType.Press, Offset(20f, 100f), type = PointerType.Touch)
                for (x in listOf(60f, 100f, 140f, 180f)) {
                    scene.sendPointerEvent(PointerEventType.Move, Offset(x, 100f), type = PointerType.Touch)
                    driver.pumpFrames(1)
                }
                scene.sendPointerEvent(PointerEventType.Release, Offset(180f, 100f), type = PointerType.Touch)
                driver.pumpUntilState { state.currentPage == 0 }
                assertEquals(0, state.currentPage)
            }
        }
    }
}
