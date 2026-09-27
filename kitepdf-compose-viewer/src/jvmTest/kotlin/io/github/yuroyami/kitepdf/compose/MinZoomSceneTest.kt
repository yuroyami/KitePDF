package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A zoom range that does not start at 1 routes the gestures by what shows: a drag pans a page
 * that overflows the viewport and turns one that fits, and a pager opens at fit (#398).
 */
class MinZoomSceneTest {

    /** A PDF of [count] empty 200 x 200 pages. */
    private fun pagesPdf(count: Int): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        val kids = (0 until count).joinToString(" ") { "${it + 3} 0 R" }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [$kids] /Count $count /MediaBox [0 0 200 200] >>")
        repeat(count) { add("<< /Type /Page /Parent 2 0 R /Resources << >> >>") }
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** A one-finger drag from x = [from] to x = [to] across the middle of the viewport. */
    private fun drag(scene: ImageComposeScene, driver: SceneTestDriver, from: Float, to: Float) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(from, 100f), type = PointerType.Touch)
        driver.pumpFrames(1)
        for (step in 1..6) {
            scene.sendPointerEvent(PointerEventType.Move, Offset(from + (to - from) * step / 6f, 100f), type = PointerType.Touch)
            driver.pumpFrames(1)
        }
        scene.sendPointerEvent(PointerEventType.Release, Offset(to, 100f), type = PointerType.Touch)
    }

    @Test
    fun a_minimum_above_fit_pans_the_page_with_one_finger() {
        for (layout in listOf(KiteDocLayout.Paged(), KiteDocLayout.SinglePage(0))) {
            forBothEffectOrders { queued ->
                val doc = PdfDocument.open(pagesPdf(8))
                lateinit var state: KiteDocViewState
                val (scene, driver) = drivenScene(200, 200, queued) {
                    state = rememberKiteDocViewState(doc)
                    KiteDocView(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        layout = layout,
                        zoomSpec = KiteZoomSpec(minZoom = 2f, maxZoom = 4f, doubleTapEnabled = false, pinchEnabled = false),
                    )
                }
                scene.use {
                    val name = layout::class.simpleName
                    driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                    driver.pumpFrames(10)
                    assertEquals(2f, state.zoom, "$name: the zoom is clamped to the minimum")
                    drag(scene, driver, from = 130f, to = 100f)
                    driver.pumpFrames(30)
                    assertEquals(-30f, state.panOffset.x, 1f, "$name: the drag did not pan the page")
                    assertEquals(0, state.currentPage, "$name: the drag turned the page")
                }
            }
        }
    }

    @Test
    fun a_page_that_fits_turns_at_any_zoom_below_fit() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(pagesPdf(8))
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    layout = KiteDocLayout.Paged(),
                    zoomSpec = KiteZoomSpec(
                        minZoom = 0.5f, maxZoom = 0.8f, doubleTapEnabled = false, pinchEnabled = false, panEnabled = false,
                    ),
                )
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                driver.pumpFrames(10)
                drag(scene, driver, from = 180f, to = 20f)
                driver.pumpUntilState { state.currentPage == 1 }
                state.setZoom(0.6f)
                driver.pumpFrames(10)
                drag(scene, driver, from = 180f, to = 20f)
                driver.pumpUntilState { state.currentPage == 2 }
            }
        }
    }

    @Test
    fun a_minimum_below_fit_still_opens_and_turns_at_fit() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(pagesPdf(5))
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    layout = KiteDocLayout.Paged(),
                    zoomSpec = KiteZoomSpec(minZoom = 0.5f, doubleTapEnabled = false),
                )
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                driver.pumpFrames(10)
                assertEquals(1f, state.zoom, "the page opened away from fit")
                drag(scene, driver, from = 180f, to = 20f)
                driver.pumpUntilState { state.currentPage == 1 }
                driver.pumpFrames(30)
                assertEquals(1f, state.zoom, "the page turned to a zoom other than fit")
            }
        }
    }

    @Test
    fun a_strip_does_not_zoom_out_below_fit() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(pagesPdf(3))
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), zoomSpec = KiteZoomSpec(minZoom = 0.5f))
            }
            scene.use {
                driver.pumpFrames(10)
                state.setZoom(0.5f)
                driver.pumpFrames(2)
                assertEquals(1f, state.zoom)
            }
        }
    }
}
