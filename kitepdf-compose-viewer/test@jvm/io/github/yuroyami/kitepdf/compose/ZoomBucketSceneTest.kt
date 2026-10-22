package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.PdfDocument
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A settled zoom rounds up to a quarter of an octave before it sizes the raster, so pinches that
 * settle near each other share one raster. Each settled value made a full-page raster of its own (#375).
 */
class ZoomBucketSceneTest {

    @Test
    fun a_zoom_rounds_up_to_the_next_quarter_octave() {
        assertEquals(1f, zoomBucket(1f))
        assertEquals(1f, zoomBucket(1.0000001f), "a rounding error above 1 made a larger raster")
        assertEquals(zoomBucket(1.37f), zoomBucket(1.41f))
        assertEquals(1.4142135f, zoomBucket(1.37f), 1e-5f)
        assertEquals(1.6817929f, zoomBucket(1.44f), 1e-5f)
        assertEquals(2f, zoomBucket(2f))
        assertEquals(8f, zoomBucket(8f))
        assertEquals(0.5f, zoomBucket(0.5f))
    }

    /** One 200 x 100 page with a black square. */
    private fun onePagePdf(): PdfDocument {
        val content = "0 g 20 20 60 60 re f"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 100] >>",
            "<< /Type /Page /Parent 2 0 R /Resources << >> /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
        )
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = objects.mapIndexed { i, body -> sb.length.also { sb.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    @Test
    fun pinches_that_settle_near_each_other_share_one_raster() {
        val widths = Collections.synchronizedList(ArrayList<Int>())
        val state = KiteDocViewState(onePagePdf())
        val (scene, driver) = drivenScene(400, 600, queued = true) {
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), onPageRendered = { _, bitmap -> widths += bitmap.width })
        }
        scene.use {
            driver.pumpUntilState { widths.isNotEmpty() }
            fun settleAt(zoom: Float): List<Int> {
                widths.clear()
                onTestUiThread { state.setZoom(zoom) }
                Thread.sleep(300)
                driver.pumpFrames(40)
                Thread.sleep(300)
                driver.pumpFrames(10)
                return widths.toList()
            }
            // The page is 400 px wide at zoom 1, so the bucket of 1.41 is about 566 px wide.
            val first = settleAt(1.37f)
            assertEquals(1, first.size, "rasters at zoom 1.37: $first")
            assertEquals(566f, first.single().toFloat(), 1f)
            assertEquals(emptyList(), settleAt(1.41f), "a zoom in the same bucket rasterized again")
            val next = settleAt(1.44f)
            assertEquals(1, next.size, "rasters at zoom 1.44: $next")
            assertTrue(next.single() > first.single(), "the next bucket is not larger: $next")
        }
    }
}
