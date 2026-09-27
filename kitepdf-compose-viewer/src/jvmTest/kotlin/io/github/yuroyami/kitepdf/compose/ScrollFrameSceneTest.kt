package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A scroll frame inside one page recomposes nothing above the list: not the viewer, not its
 * layout and not the navigation widgets. The page they show changes only when the reader
 * crosses a page, but they read the list's layout, which changes on every frame (#374).
 */
class ScrollFrameSceneTest {

    /** [count] empty 200 x 200 pages. */
    private fun pagesPdf(count: Int): PdfDocument {
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
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    @OptIn(androidx.compose.runtime.InternalComposeTracingApi::class)
    @Test
    fun a_scroll_frame_inside_a_page_recomposes_nothing_above_the_list() {
        val starts = ConcurrentHashMap<String, Int>()
        val counting = java.util.concurrent.atomic.AtomicBoolean(false)
        androidx.compose.runtime.Composer.setTracer(object : androidx.compose.runtime.CompositionTracer {
            override fun isTraceInProgress() = true
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                if (counting.get()) starts.merge(info.substringBefore(" ("), 1, Int::plus)
            }
            override fun traceEventEnd() {}
        })
        try {
            val state = KiteDocViewState(pagesPdf(60))
            val (scene, driver) = drivenScene(200, 400, queued = false) {
                Column {
                    KiteDocView(
                        state = state,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        overlay = { KitePageIndicator(it) },
                    )
                    KiteNavigationControls(state)
                    KiteThumbnailStrip(state, Modifier.fillMaxWidth().height(90.dp))
                }
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                driver.pumpFrames(10)
                val page = state.currentPage
                starts.clear()
                counting.set(true)
                // A slow drag, 3 px a frame, that keeps the first page nearest the centre.
                scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 150f), type = PointerType.Touch)
                for (i in 1..20) {
                    scene.sendPointerEvent(PointerEventType.Move, Offset(100f, 150f - 3f * i), type = PointerType.Touch)
                    driver.pumpFrames(1)
                }
                counting.set(false)
                scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 90f), type = PointerType.Touch)
                assertEquals(page, state.currentPage, "the drag stays on the page")
                // The scroll flag flips as the drag starts, which may recompose a reader of it once.
                val kite = starts.filterKeys { it.startsWith("io.github.yuroyami.kitepdf") }
                assertTrue(kite.values.all { it <= 2 }, "recomposed in 20 scroll frames: $kite")
            }
        } finally {
            androidx.compose.runtime.Composer.setTracer(null)
        }
    }
}
