package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** While zoomed in a continuous strip, the reader can still drag to both ends of the strip (#397). */
class StripEndsSceneTest {

    /** Three 300 x 300 pages: a red band across the top of the first, a blue one across the bottom of the last. */
    private fun bandedPdf(): ByteArray {
        val first = "1 0 0 rg 0 250 300 50 re f"
        val last = "0 0 1 rg 0 0 300 50 re f"
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 /MediaBox [0 0 300 300] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Contents 6 0 R >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Contents 7 0 R >>")
        add("<< /Length ${first.length} >>\nstream\n$first\nendstream")
        add("<< /Length ${last.length} >>\nstream\n$last\nendstream")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    private fun red(pixels: PixelMap, y: Int) = pixels[150, y].let { it.red > 0.8f && it.blue < 0.3f }
    private fun blue(pixels: PixelMap, y: Int) = pixels[150, y].let { it.blue > 0.8f && it.red < 0.3f }

    private var clock = 10_000L

    /** A drag that ends with the finger held still, so it carries no fling. */
    private fun drag(scene: ImageComposeScene, driver: SceneTestDriver, from: Float, to: Float) {
        clock += 1_000
        scene.sendPointerEvent(PointerEventType.Press, Offset(150f, from), timeMillis = clock, type = PointerType.Touch)
        driver.pumpFrames(1)
        for (step in 1..8) {
            clock += 50
            scene.sendPointerEvent(PointerEventType.Move, Offset(150f, from + (to - from) * step / 8f), timeMillis = clock, type = PointerType.Touch)
            driver.pumpFrames(1)
        }
        clock += 500
        scene.sendPointerEvent(PointerEventType.Move, Offset(150f, to), timeMillis = clock, type = PointerType.Touch)
        scene.sendPointerEvent(PointerEventType.Release, Offset(150f, to), timeMillis = clock + 10, type = PointerType.Touch)
        driver.pumpFrames(10)
    }

    @Test
    fun both_ends_of_a_zoomed_strip_can_be_reached() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(bandedPdf())
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(300, 600, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
                    pageSpacing = 0.dp,
                )
            }
            scene.use {
                driver.pumpUntil { red(it, 25) }
                onTestUiThread { state.setZoom(2f) }
                driver.runOnUi { state.scrollToPage(0) }
                // At zoom 2 the first page's top band sits 300 px above the viewport.
                driver.pumpUntil { !red(it, 50) }

                // At the start of the list a downward drag moves the page instead, up to the band.
                drag(scene, driver, from = 100f, to = 500f)
                assertEquals(300f, state.panOffset.y, 1f)
                driver.pumpUntil { red(it, 50) }

                // A drag back spends that pan first, and the list stays at its start. The drag loses
                // its touch slop, so it moves the page a little less than the finger.
                drag(scene, driver, from = 500f, to = 300f)
                assertTrue(state.panOffset.y in 100f..160f, "the pan after a drag back: ${state.panOffset.y}")
                assertEquals(0, state.currentScrollPosition.offsetPx, "the list scrolled before the pan was spent")
                assertEquals(0, state.currentPage)

                // At the end of the list, an upward drag spends the pan and then reaches the last band.
                driver.runOnUi { state.scrollToPage(2) }
                driver.pumpFrames(10)
                drag(scene, driver, from = 550f, to = 50f)
                assertEquals(-300f, state.panOffset.y, 1f)
                driver.pumpUntil { blue(it, 560) }
            }
        }
    }
}
