package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-31: [PdfViewState.hitTest] maps viewport points to page user-space
 * points through the real composed layout (the geometry map is populated by
 * the actual page slots) and the inverted zoom/pan layer transform.
 */
class HitTestSceneTest {

    private fun redPagePdf(n: Int = 1) = PdfBuilder().apply {
        repeat(n) {
            page(width = 200.0, height = 200.0) {
                setFillRgb(1.0, 0.0, 0.0); rectangle(0.0, 0.0, 200.0, 200.0); fill()
            }
        }
    }.build()

    private fun assertHit(hit: PageHit?, page: Int, x: Double, y: Double, tolerance: Double = 1.0) {
        assertNotNull(hit, "expected a page hit")
        assertEquals(page, hit.pageIndex)
        assertTrue(abs(hit.x - x) <= tolerance, "x: expected $x, got ${hit.x}")
        assertTrue(abs(hit.y - y) <= tolerance, "y: expected $y, got ${hit.y}")
    }

    @Test
    fun single_page_hit_test_at_zoom_1_and_zoom_2() {
        val doc = KitePDF.open(redPagePdf())
        lateinit var state: PdfViewState
        ImageComposeScene(width = 400, height = 400, density = Density(1f)) {
            state = rememberPdfViewState(doc)
            PdfView(state = state, modifier = Modifier.fillMaxSize(), layout = PdfLayout.SinglePage(0))
        }.use { scene ->
            val driver = SceneTestDriver(scene)
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
            state.setZoom(2f, focal = Offset(100f, 100f))
            driver.pumpUntil { true }
            assertHit(state.hitTest(Offset(100f, 100f)), 0, 50.0, 150.0)

            // Zooming around the centre keeps the centre fixed on the page centre.
            state.resetZoom()
            state.setZoom(2f)
            driver.pumpUntil { true }
            assertHit(state.hitTest(Offset(200f, 200f)), 0, 100.0, 100.0)
        }
    }

    @Test
    fun continuous_strip_maps_each_page_and_misses_the_gap() {
        val doc = KitePDF.open(redPagePdf(2))
        lateinit var state: PdfViewState
        // 200x320 viewport, 8dp spacing at density 1: page 0 at y 0..200,
        // page 1 from y 208 (the PdfViewSceneTest geometry).
        ImageComposeScene(width = 200, height = 320, density = Density(1f)) {
            state = rememberPdfViewState(doc)
            PdfView(state = state, modifier = Modifier.fillMaxSize())
        }.use { scene ->
            SceneTestDriver(scene).pumpUntil { px ->
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
