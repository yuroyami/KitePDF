package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A zoomed page pans within its own bounds, not the viewport's: a letterboxed page that still
 * fits one axis does not move on it, and never slides into empty margins (#400).
 */
class PanContentSceneTest {

    /** [count] pages of 400 x 300 pt. */
    private fun landscapePdf(count: Int): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        val kids = (0 until count).joinToString(" ") { "${it + 3} 0 R" }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [$kids] /Count $count /MediaBox [0 0 400 300] >>")
        repeat(count) { add("<< /Type /Page /Parent 2 0 R /Resources << >> >>") }
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** A layout, its viewport width, and the widest pan at zoom 2: half of what the content overflows. */
    private data class Case(val layout: KiteDocLayout, val width: Int, val maxPanX: Float)

    @Test
    fun a_letterboxed_page_pans_only_as_far_as_it_overflows() {
        val cases = listOf(
            // The page fits 400 x 300 in a 400 x 800 viewport: at zoom 2 it is 800 wide and 600 tall.
            Case(KiteDocLayout.SinglePage(0), width = 400, maxPanX = 200f),
            Case(KiteDocLayout.Paged(), width = 400, maxPanX = 200f),
            // Two such pages side by side in an 800 x 800 viewport: 1,600 wide at zoom 2.
            Case(KiteDocLayout.Spread(), width = 800, maxPanX = 400f),
        )
        for (case in cases) {
            forBothEffectOrders { queued ->
                val doc = PdfDocument.open(landscapePdf(2))
                lateinit var state: KiteDocViewState
                val (scene, driver) = drivenScene(case.width, 800, queued) {
                    state = rememberKiteDocViewState(doc)
                    KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = case.layout)
                }
                scene.use {
                    val name = case.layout::class.simpleName
                    driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                    driver.pumpFrames(4)
                    onTestUiThread { state.setZoom(2f) }
                    onTestUiThread { state.panBy(Offset(0f, 1000f)) }
                    assertEquals(0f, state.panOffset.y, 0.5f, "$name: a page that fits the height moved vertically")
                    onTestUiThread { state.panBy(Offset(10_000f, 0f)) }
                    assertEquals(case.maxPanX, state.panOffset.x, 0.5f, "$name: the pan went past the page")
                    onTestUiThread { state.panBy(Offset(-20_000f, 0f)) }
                    assertEquals(-case.maxPanX, state.panOffset.x, 0.5f, "$name: the pan went past the page")
                }
            }
        }
    }
}
