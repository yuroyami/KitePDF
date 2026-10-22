package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A viewport that changes size keeps the point of the page at its centre, and the pan stays
 * inside the new bounds, so a zoomed page never ends up outside a smaller viewport (#399).
 */
class PanResizeSceneTest {

    /** One page of [width] x [height] pt. */
    private fun pagePdf(width: Int, height: Int): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 $width $height] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /**
     * Shows a [page]-sized page in [layout] in a viewport of [start], zooms to [zoom], pans by
     * [pan], and then checks the pan after each size in [steps]: the size, and the pan expected.
     */
    private fun check(
        layout: KiteDocLayout,
        page: IntSize,
        start: IntSize,
        zoom: Float,
        pan: Offset,
        steps: List<Pair<IntSize, Offset>>,
    ) {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(pagePdf(page.width, page.height))
            lateinit var state: KiteDocViewState
            var box by mutableStateOf(start)
            val (scene, driver) = drivenScene(1000, 1000, queued) {
                state = rememberKiteDocViewState(doc)
                Box(Modifier.size(box.width.dp, box.height.dp)) {
                    KiteDocView(
                        state = state,
                        modifier = Modifier.size(box.width.dp, box.height.dp),
                        layout = layout,
                        zoomSpec = KiteZoomSpec(resetZoomOnPageChange = false),
                    )
                }
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() || layout is KiteDocLayout.Continuous }
                driver.pumpFrames(4)
                onTestUiThread { state.setZoom(zoom) }
                onTestUiThread { state.panBy(pan) }
                driver.pumpFrames(2)
                for ((size, expected) in steps) {
                    box = size
                    driver.pumpFrames(3)
                    val name = "${layout::class.simpleName} at $size"
                    assertEquals(expected.x, state.panOffset.x, 0.5f, "$name: pan x")
                    assertEquals(expected.y, state.panOffset.y, 0.5f, "$name: pan y")
                }
            }
        }
    }

    @Test
    fun a_pager_keeps_its_pan_inside_a_viewport_that_shrinks_grows_or_turns() {
        // A 1,000 x 200 page at zoom 3 panned to its right edge: the bound is 1,000 at 1,000 wide,
        // and the page is five times narrower at 200 wide, where the bound is 200.
        check(
            KiteDocLayout.SinglePage(0), page = IntSize(1000, 200), start = IntSize(1000, 200), zoom = 3f,
            pan = Offset(1000f, 0f),
            steps = listOf(
                IntSize(200, 200) to Offset(200f, 0f),
                IntSize(1000, 200) to Offset(1000f, 0f),
                IntSize(200, 1000) to Offset(200f, 0f),
            ),
        )
    }

    @Test
    fun a_vertical_strip_keeps_its_pan_inside_a_narrower_viewport() {
        check(
            KiteDocLayout.Continuous(), page = IntSize(400, 400), start = IntSize(400, 400), zoom = 2f,
            pan = Offset(400f, 0f),
            steps = listOf(IntSize(200, 400) to Offset(100f, 0f), IntSize(400, 400) to Offset(200f, 0f)),
        )
    }

    @Test
    fun a_horizontal_strip_keeps_its_pan_inside_a_lower_viewport() {
        check(
            KiteDocLayout.Continuous(orientation = Orientation.Horizontal), page = IntSize(400, 400), start = IntSize(400, 400),
            zoom = 2f, pan = Offset(0f, 400f),
            steps = listOf(IntSize(400, 200) to Offset(0f, 100f), IntSize(400, 400) to Offset(0f, 200f)),
        )
    }
}
