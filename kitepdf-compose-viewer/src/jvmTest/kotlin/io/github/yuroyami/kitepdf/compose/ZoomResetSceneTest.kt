package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A pager resets the zoom when the reader lands on another page, and only then: the page it first
 * shows is not a change, so a zoom set before it appears stays (#403).
 */
class ZoomResetSceneTest {

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

    @Test
    fun a_zoom_set_before_the_pager_appears_stays_until_the_page_changes() {
        for (layout in listOf(KiteDocLayout.Paged(), KiteDocLayout.Spread())) {
            forBothEffectOrders { queued ->
                val doc = PdfDocument.open(pagesPdf(6))
                val state = KiteDocViewState(doc)
                onTestUiThread { state.setZoom(2f) }
                val width = if (layout is KiteDocLayout.Spread) 400 else 200
                val (scene, driver) = drivenScene(width, 200, queued) {
                    KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = layout)
                }
                scene.use {
                    val name = layout::class.simpleName
                    driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                    driver.pumpFrames(10)
                    assertEquals(2f, state.zoom, "$name: the zoom was reset as the pager appeared")
                    // Between frames, as an app's main thread runs it.
                    driver.runOnUi { state.scrollToPage(4) }
                    driver.pumpUntilState { state.zoom == 1f }
                }
            }
        }
    }
}
