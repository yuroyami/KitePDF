package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.core.KiteSearchHit
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.Rectangle
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-33: [PdfViewState.searchHighlights] paints translucent quads over the
 * page, and [PdfOutlinePanel] composes and lists outline entries.
 */
class SearchHighlightSceneTest {

    private fun redPagePdf() = PdfBuilder()
        .page(width = 200.0, height = 200.0) {
            setFillRgb(1.0, 0.0, 0.0); rectangle(0.0, 0.0, 200.0, 200.0); fill()
        }
        .build()

    @Test
    fun highlight_quads_change_the_pixels_under_them() {
        val doc = KitePDF.open(redPagePdf())
        lateinit var state: PdfViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberPdfViewState(doc)
            PdfView(state = state, modifier = Modifier.fillMaxSize(), layout = PdfLayout.SinglePage(0))
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            // Wait for the raster crossfade to fully settle at both samples.
            val before = driver.pumpUntil { px ->
                val a = px[100, 50]
                val b = px[100, 150]
                a.red > 0.95f && a.green < 0.05f && b.red > 0.95f && b.green < 0.05f
            }.toComposeImageBitmap().toPixelMap()
            val insideBefore = before[100, 50]
            val outsideBefore = before[100, 150]

            // One hit: display-space quad x 50..150, y 20..80 (y-min in
            // `bottom` per the display-rect convention).
            state.searchHighlights = listOf(
                KiteSearchHit(0, listOf(Rectangle(left = 50.0, bottom = 20.0, right = 150.0, top = 80.0)), "x"),
            )
            val after = driver.pumpUntil { px ->
                abs(px[100, 50].green - insideBefore.green) > 0.1f
            }.toComposeImageBitmap().toPixelMap()

            val inside = after[100, 50]
            assertTrue(
                abs(inside.green - insideBefore.green) > 0.1f,
                "pixels under the quad blend with the highlight (before=$insideBefore after=$inside)",
            )
            val outside = after[100, 150]
            assertTrue(
                abs(outside.red - outsideBefore.red) < 0.05f && abs(outside.green - outsideBefore.green) < 0.05f,
                "pixels outside the quad stay put ($outsideBefore -> $outside)",
            )

            // Clearing restores the plain page.
            state.searchHighlights = emptyList()
            val cleared = driver.pumpUntil { px -> abs(px[100, 50].green - insideBefore.green) < 0.05f }
                .toComposeImageBitmap().toPixelMap()
            assertTrue(abs(cleared[100, 50].green - insideBefore.green) < 0.05f, "clearing removes the overlay")
        }
    }

    @Test
    fun outline_panel_composes_over_a_real_outline() {
        // 2 pages + 1 bookmark to page 2 (raw fixture; PdfBuilder has no outlines).
        val sb = StringBuilder()
        val offsets = ArrayList<Int>()
        fun add(s: String) {
            offsets.add(sb.length)
            sb.append(s)
        }
        sb.append("%PDF-1.4\n")
        add("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Outlines 3 0 R >>\nendobj\n")
        add("2 0 obj\n<< /Type /Pages /Kids [4 0 R 5 0 R] /Count 2 /MediaBox [0 0 200 200] >>\nendobj\n")
        add("3 0 obj\n<< /Type /Outlines /First 6 0 R /Last 6 0 R /Count 1 >>\nendobj\n")
        add("4 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")
        add("5 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")
        add("6 0 obj\n<< /Title (Chapter Two) /Parent 3 0 R /Dest [5 0 R /Fit] >>\nendobj\n")
        val xref = sb.length
        sb.append("xref\n0 7\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        val doc = KitePDF.open(sb.toString().encodeToByteArray())
        assertTrue(doc.outline.single().pageIndex == 1)

        lateinit var state: PdfViewState
        ImageComposeScene(width = 200, height = 300, density = Density(1f)) {
            state = rememberPdfViewState(doc)
            PdfOutlinePanel(state = state, modifier = Modifier.fillMaxSize())
        }.use { scene ->
            // Composes and renders without crashing; the entry paints pixels.
            SceneTestDriver(scene).pumpUntil { true }
        }
    }
}
