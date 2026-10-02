package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.KiteSearchHit
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [KiteDocViewState.hitTest] maps viewport points to page user-space
 * points through the real composed layout (the geometry map is populated by
 * the actual page slots) and the inverted zoom/pan layer transform.
 * [KiteDocViewState.hitTestDisplay] maps them to display points, where a
 * highlight marks the page (#432).
 */
class HitTestSceneTest {

    private fun redPagePdf(n: Int = 1) = PdfBuilder().apply {
        repeat(n) {
            page(width = 200.0, height = 200.0) {
                setFillRgb(1.0, 0.0, 0.0); rectangle(0.0, 0.0, 200.0, 200.0); fill()
            }
        }
    }.build()

    private fun assertHit(hit: KitePageHit?, page: Int, x: Double, y: Double, tolerance: Double = 1.0) {
        assertNotNull(hit, "expected a page hit")
        assertEquals(page, hit.pageIndex)
        assertTrue(abs(hit.x - x) <= tolerance, "x: expected $x, got ${hit.x}")
        assertTrue(abs(hit.y - y) <= tolerance, "y: expected $y, got ${hit.y}")
    }

    @Test
    fun single_page_hit_test_at_zoom_1_and_zoom_2() = forBothEffectOrders { queued ->
        val doc = KitePDF.open(redPagePdf())
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(400, 400, queued) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0))
        }
        scene.use {
            driver.pumpUntil { px -> px[200, 200].red > 0.8f }

            // Zoom 1: the 200x200pt page letterboxes to the full 400x400
            // viewport; the visual centre is the page centre, and PDF user
            // space is y-up so the point is (100, 100).
            assertHit(state.hitTest(Offset(200f, 200f)), 0, 100.0, 100.0)
            // Quarter point: display (50, 50)pt -> user y = 200 - 50.
            assertHit(state.hitTest(Offset(100f, 100f)), 0, 50.0, 150.0)

            // Zoom 2 around a focal: the page point under the focal must not
            // move (that is the definition of focal zoom), and the viewport
            // centre still maps somewhere consistent.
            onTestUiThread { state.setZoom(2f, focal = Offset(100f, 100f)) }
            driver.pumpUntil { true }
            assertHit(state.hitTest(Offset(100f, 100f)), 0, 50.0, 150.0)

            // Zooming around the centre keeps the centre fixed on the page centre.
            onTestUiThread { state.resetZoom() }
            onTestUiThread { state.setZoom(2f) }
            driver.pumpUntil { true }
            assertHit(state.hitTest(Offset(200f, 200f)), 0, 100.0, 100.0)
        }
    }

    /**
     * Two 200 x 200 pages whose crop box starts at (100, 100) of a 400 x 400 media box: the
     * first upright, the second turned a quarter clockwise.
     */
    private fun croppedPdf(): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 400 400] /CropBox [100 100 300 300] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> >>")
        add("<< /Type /Page /Parent 2 0 R /Rotate 90 /Resources << >> >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    @Test
    fun a_display_hit_marks_the_spot_that_was_tapped() = forBothEffectOrders { queued ->
        // The user space point under the tap on each page: the crop box moves it, and the turn swaps its axes.
        for ((pageIndex, user) in listOf(0 to (150.0 to 270.0), 1 to (130.0 to 150.0))) {
            val doc = KitePDF.open(croppedPdf())
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(400, 400, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(pageIndex))
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                // The page shows at twice its size, so the tap is 50 points right of its left edge and 30 below its top.
                val tap = Offset(100f, 60f)
                val display = state.hitTestDisplay(tap)
                assertHit(display, pageIndex, 50.0, 30.0)
                assertHit(state.hitTest(tap), pageIndex, user.first, user.second)
                // A mark made at the display point is the one under the tap, where the host paints it.
                val mark = KiteRectangle(display!!.x - 5, display.y - 5, display.x + 5, display.y + 5)
                state.highlights = listOf(KiteHighlight(KiteSearchHit(pageIndex, listOf(mark), ""), id = "mark"))
                assertEquals("mark", state.highlightAt(tap)?.id, "page $pageIndex")
            }
        }
    }

    @Test
    fun continuous_strip_maps_each_page_and_misses_the_gap() = forBothEffectOrders { queued ->
        val doc = KitePDF.open(redPagePdf(2))
        lateinit var state: KiteDocViewState
        // 200x320 viewport, 8dp spacing at density 1: page 0 at y 0..200,
        // page 1 from y 208 (the KiteDocViewSceneTest geometry).
        val (scene, driver) = drivenScene(200, 320, queued) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }
        scene.use {
            driver.pumpUntil { px ->
                px[100, 100].red > 0.8f && px[100, 300].red > 0.8f
            }

            assertHit(state.hitTest(Offset(100f, 100f)), 0, 100.0, 100.0)
            // Viewport y 300 is 92px into page 1 -> user y = 200 - 92 = 108.
            assertHit(state.hitTest(Offset(100f, 300f)), 1, 100.0, 108.0)
            // The spacing gap between pages hits nothing.
            assertNull(state.hitTest(Offset(100f, 204f)), "gap between pages")
            assertNull(state.hitTest(Offset(100f, -5f)), "outside the viewport")
        }
    }
}
