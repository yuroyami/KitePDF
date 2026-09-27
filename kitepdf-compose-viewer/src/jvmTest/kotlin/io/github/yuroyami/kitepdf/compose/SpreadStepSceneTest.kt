package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A page step inside one spread is a change every observer sees, and the page survives the
 * spread pager leaving and coming back (#402).
 */
class SpreadStepSceneTest {

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
    fun a_page_step_inside_a_spread_reaches_observers_and_survives_a_detach() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(pagesPdf(4))
            lateinit var state: KiteDocViewState
            var observed = -1
            var step by mutableStateOf(0)
            var show by mutableStateOf(true)
            val (scene, driver) = drivenScene(400, 200, queued) {
                state = rememberKiteDocViewState(doc)
                if (show) KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Spread())
                LaunchedEffect(state) { snapshotFlow { state.currentPage }.collect { observed = it } }
                LaunchedEffect(step) { if (step == 1) state.nextPage() }
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                assertEquals(0, observed)
                step = 1
                driver.pumpUntilState { observed == 1 }
                assertEquals(1, state.currentPage)
                show = false
                driver.pumpFrames(5)
                show = true
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                driver.pumpFrames(5)
                assertEquals(1, state.currentPage, "the pager came back on the spread's first page")
            }
        }
    }
}
