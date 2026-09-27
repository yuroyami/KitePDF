package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.KiteSearchHit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An overlay change, a new list of search hits or of highlights, draws the overlay again and no
 * page. In Vectorized mode the overlay and the page drew in one layer, so each change parsed and
 * painted every visible page again: 30 paints for 10 changes over three pages (#372).
 */
class OverlayRedrawSceneTest {

    /** [count] 200 x 100 pages, each with a black square. */
    private fun pagesPdf(count: Int): PdfDocument {
        val content = "0 g 20 20 60 60 re f"
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        val kids = (0 until count).joinToString(" ") { "${it + 3} 0 R" }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [$kids] /Count $count /MediaBox [0 0 200 100] >>")
        repeat(count) { add("<< /Type /Page /Parent 2 0 R /Resources << >> /Contents ${count + 3} 0 R >>") }
        add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    @Test
    fun a_new_list_of_hits_or_highlights_draws_no_page_again() {
        forBothEffectOrders { queued ->
            val paints = AtomicInteger()
            // Each page paint wraps the canvas once, so the decorator counts paints.
            val spec = KiteRenderSpec.Vectorized(canvasDecorator = { inner -> paints.incrementAndGet(); inner })
            val state = KiteDocViewState(pagesPdf(3))
            val (scene, driver) = drivenScene(200, 320, queued) {
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), renderSpec = spec)
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.size >= 3 && paints.get() >= 3 }
                driver.pumpFrames(10)
                val before = paints.get()
                repeat(10) { i ->
                    state.searchHighlights = List(3) { page ->
                        KiteSearchHit(page, listOf(KiteRectangle(10.0 + i, 10.0, 60.0 + i, 30.0)), "hit")
                    }
                    driver.pumpFrames(1)
                }
                assertTrue(paints.get() == before, "${paints.get() - before} page paints for 10 lists of hits")
                repeat(10) { i ->
                    state.highlights = List(3) { page ->
                        KiteHighlight(KiteSearchHit(page, listOf(KiteRectangle(10.0, 40.0 + i, 60.0, 60.0 + i)), "mark"))
                    }
                    driver.pumpFrames(1)
                }
                assertTrue(paints.get() == before, "${paints.get() - before} page paints for 10 lists of hits and highlights")
            }
        }
    }
}
