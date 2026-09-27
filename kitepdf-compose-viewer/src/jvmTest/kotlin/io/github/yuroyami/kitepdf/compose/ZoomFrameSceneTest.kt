package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A zoom or pan frame only moves the page's layer, in every layout: it recomposes no layout and
 * no page slot, and it draws no page again. Paged, SinglePage and Spread read the zoom in
 * composition, so each gesture frame recomposed the layout and its page slot, 30 times in 30
 * frames (#373).
 */
class ZoomFrameSceneTest {

    /** Two 200 x 200 pages, each with a black square. */
    private fun twoPages(): PdfDocument {
        val content = "0 g 50 50 100 100 re f"
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 200 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Contents 5 0 R >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Contents 5 0 R >>")
        add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    @Test
    fun a_zoom_frame_draws_no_page_again() {
        val layouts = listOf(KiteDocLayout.Paged(), KiteDocLayout.SinglePage(0), KiteDocLayout.Spread(), KiteDocLayout.Continuous())
        for (layout in layouts) {
            forBothEffectOrders { queued ->
                val paints = AtomicInteger()
                // Each page paint wraps the canvas once, so the decorator counts paints.
                val spec = KiteRenderSpec.Vectorized(canvasDecorator = { inner -> paints.incrementAndGet(); inner })
                val state = KiteDocViewState(twoPages())
                val (scene, driver) = drivenScene(200, 200, queued) {
                    KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = layout, renderSpec = spec)
                }
                scene.use {
                    driver.pumpUntilState { state.pageGeometry.isNotEmpty() && paints.get() > 0 }
                    driver.pumpFrames(5)
                    val before = paints.get()
                    // One zoom step per frame, as a pinch gives. The zoom never rests long enough to settle.
                    repeat(30) { i ->
                        state.setZoom(1f + (i + 1) * 0.05f)
                        driver.pumpFrames(1)
                    }
                    val during = paints.get() - before
                    assertTrue(during <= 1, "${layout::class.simpleName}: $during page paints in 30 zoom frames")
                }
            }
        }
    }

    @OptIn(androidx.compose.runtime.InternalComposeTracingApi::class)
    @Test
    fun a_zoom_frame_recomposes_no_layout_and_no_page_slot() {
        val starts = java.util.concurrent.ConcurrentHashMap<String, Int>()
        var counting = false
        androidx.compose.runtime.Composer.setTracer(object : androidx.compose.runtime.CompositionTracer {
            override fun isTraceInProgress() = true
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                if (counting) starts.merge(info.substringBefore(" ("), 1, Int::plus)
            }
            override fun traceEventEnd() {}
        })
        try {
            for (layout in listOf(KiteDocLayout.Paged(), KiteDocLayout.SinglePage(0), KiteDocLayout.Spread(), KiteDocLayout.Continuous())) {
                val state = KiteDocViewState(twoPages())
                val (scene, driver) = drivenScene(200, 200, queued = false) {
                    KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = layout, renderSpec = KiteRenderSpec.Vectorized())
                }
                scene.use {
                    driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                    driver.pumpFrames(5)
                    starts.clear()
                    counting = true
                    repeat(30) { i ->
                        state.setZoom(1f + (i + 1) * 0.05f)
                        driver.pumpFrames(1)
                    }
                    counting = false
                    // The first step may flip the pager's scroll flag, which recomposes the layout once.
                    val slots = starts.filterKeys { name -> WATCHED.any { name.endsWith(".$it") } }
                    assertTrue(slots.values.all { it <= 1 }, "${layout::class.simpleName}: recomposed $slots in 30 zoom frames")
                }
            }
        } finally {
            androidx.compose.runtime.Composer.setTracer(null)
        }
    }

    private companion object {
        /** The layouts and page slots that a zoom frame must not recompose. */
        val WATCHED = listOf("PagedLayout", "SinglePageLayout", "SpreadLayout", "ContinuousLayout", "PageBox", "SpreadBox", "ChapterGapSlot")
    }
}
