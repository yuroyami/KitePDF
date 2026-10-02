package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.PdfDocument
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * After a zoom settles in the Continuous layout, only the pages the reader sees at that zoom
 * rasterize at the zoomed size. Every composed page did, three pages of 3,200 x 1,600 where the
 * reader saw part of one, and they pushed the zoom-1 bitmaps out of the cache (#376).
 */
class ZoomedRasterSceneTest {

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
    fun a_settled_zoom_rasterizes_only_the_pages_in_view() {
        forBothEffectOrders { queued ->
            val rendered = Collections.synchronizedList(ArrayList<Pair<Int, Int>>())
            val state = KiteDocViewState(pagesPdf(6))
            val (scene, driver) = drivenScene(400, 600, queued) {
                KiteDocView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    onPageRendered = { index, bitmap -> rendered += index to bitmap.width },
                )
            }
            scene.use {
                // Pages 0 to 2 fill the 600 px of the viewport at 400 x 200 each.
                driver.pumpUntilState { rendered.map { it.first }.toSet().containsAll(listOf(0, 1, 2)) }
                driver.pumpFrames(10)
                rendered.clear()
                // Zoom 8 about the centre: the reader sees the middle of page 1 only.
                onTestUiThread { state.setZoom(8f) }
                driver.pumpUntilState { rendered.any { it.second > 400 } }
                Thread.sleep(500)
                driver.pumpFrames(30)
                val zoomed = rendered.filter { it.second > 400 }.map { it.first }.toSet()
                assertEquals(setOf(1), zoomed, "pages rasterized at the zoomed size")
                assertTrue(rendered.none { it.first != 1 && it.second > 400 })
            }
        }
    }
}
