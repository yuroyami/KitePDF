package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlin.test.assertEquals
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

    /**
     * A [PdfHighlight] paints in its own colour, and one without a colour is
     * pixel-for-pixel what the plain [PdfViewState.searchHighlights] channel
     * already produced.
     */
    @Test
    fun per_highlight_colour_overrides_the_default_and_null_matches_it() {
        val doc = KitePDF.open(redPagePdf())
        val quad = Rectangle(left = 50.0, bottom = 20.0, right = 150.0, top = 80.0)
        val hit = KiteSearchHit(0, listOf(quad), "x")
        lateinit var state: PdfViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberPdfViewState(doc)
            PdfView(state = state, modifier = Modifier.fillMaxSize(), layout = PdfLayout.SinglePage(0))
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            val plain = driver.pumpUntil { px ->
                val a = px[100, 50]
                a.red > 0.95f && a.green < 0.05f
            }.toComposeImageBitmap().toPixelMap()[100, 50]

            // 1. The existing single-colour channel.
            state.searchHighlights = listOf(hit)
            val default = driver.pumpUntil { px -> abs(px[100, 50].green - plain.green) > 0.1f }
                .toComposeImageBitmap().toPixelMap()[100, 50]
            assertTrue(abs(default.green - plain.green) > 0.1f, "the default channel still paints")

            // 2. The same quad through the new channel, no colour given: the
            //    default must stay exactly colors.searchHighlight. Go back to
            //    the bare page in between, so the sample below cannot be the
            //    frame the old channel left behind.
            state.searchHighlights = emptyList()
            driver.pumpUntil { px -> abs(px[100, 50].green - plain.green) < 0.05f }
            state.highlights = listOf(PdfHighlight(hit))
            val inherited = driver.pumpUntil { px -> abs(px[100, 50].green - plain.green) > 0.1f }
                .toComposeImageBitmap().toPixelMap()[100, 50]
            assertEquals(default.red, inherited.red, 0.01f, "colourless highlight != searchHighlight (red)")
            assertEquals(default.green, inherited.green, 0.01f, "colourless highlight != searchHighlight (green)")
            assertEquals(default.blue, inherited.blue, 0.01f, "colourless highlight != searchHighlight (blue)")

            // 3. Its own colour: opaque blue over red paper.
            state.highlights = listOf(PdfHighlight(hit, color = Color(0xFF0000FF)))
            val tinted = driver.pumpUntil { px -> px[100, 50].blue > 0.9f }
                .toComposeImageBitmap().toPixelMap()[100, 50]
            assertTrue(tinted.blue > 0.9f && tinted.red < 0.1f, "per-highlight colour ignored (got $tinted)")

            // Two highlights, two colours, in one pass.
            state.highlights = listOf(
                PdfHighlight(hit, color = Color(0xFF0000FF)),
                PdfHighlight(
                    KiteSearchHit(0, listOf(Rectangle(left = 50.0, bottom = 120.0, right = 150.0, top = 180.0)), "y"),
                    color = Color(0xFF00FF00),
                ),
            )
            val both = driver.pumpUntil { px -> px[100, 150].green > 0.9f }
                .toComposeImageBitmap().toPixelMap()
            assertTrue(both[100, 50].blue > 0.9f, "first highlight lost its colour (${both[100, 50]})")
            assertTrue(both[100, 150].green > 0.9f, "second highlight lost its colour (${both[100, 150]})")

            state.highlights = emptyList()
            val cleared = driver.pumpUntil { px -> abs(px[100, 50].green - plain.green) < 0.05f }
                .toComposeImageBitmap().toPixelMap()
            assertTrue(abs(cleared[100, 50].red - plain.red) < 0.05f, "clearing removes the overlay")
        }
    }

    /**
     * The margin marker sits at the page's outer edge, level with the
     * highlighted text, clear of it, and it scales with the rendered page.
     */
    @Test
    fun edge_marker_paints_in_the_margin_level_with_its_highlight() {
        val doc = KitePDF.open(redPagePdf())
        // Text quad x 50..150, y 20..80. Marker: 2% of the page width, half a
        // width off the edge, so 194..198 in a 200px slot, y 20..80.
        val hit = KiteSearchHit(0, listOf(Rectangle(left = 50.0, bottom = 20.0, right = 150.0, top = 80.0)), "x")
        lateinit var state: PdfViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberPdfViewState(doc)
            PdfView(state = state, modifier = Modifier.fillMaxSize(), layout = PdfLayout.SinglePage(0))
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            // Wait for the real red raster, not the white placeholder under it:
            // the overlay draws on frame one, the page fades in behind it.
            driver.pumpUntil { px -> px[196, 50].red > 0.95f && px[196, 50].green < 0.05f }

            // Off by default: the same highlight without the flag paints nothing
            // in the margin.
            state.highlights = listOf(PdfHighlight(hit, color = Color(0x660000FF)))
            val noMarker = driver.pumpUntil { px -> px[100, 50].blue > 0.2f }
                .toComposeImageBitmap().toPixelMap()
            assertTrue(noMarker[196, 50].red > 0.95f, "no marker without the flag (${noMarker[196, 50]})")

            state.highlights = listOf(
                PdfHighlight(hit, color = Color(0x660000FF), edgeMarker = true, edgeMarkerColor = Color(0xFF00FF00)),
            )
            val px = driver.pumpUntil { p -> p[196, 50].green > 0.9f }.toComposeImageBitmap().toPixelMap()

            assertTrue(px[196, 50].green > 0.9f, "no marker at the page edge (${px[196, 50]})")
            // Level with the text: nothing above or below the highlight's band.
            assertTrue(px[196, 150].red > 0.95f, "marker leaks below its highlight (${px[196, 150]})")
            assertTrue(px[196, 5].red > 0.95f, "marker leaks above its highlight (${px[196, 5]})")
            // Clear of the words: the gap between the quad and the marker is
            // plain paper, and the highlighted text keeps its own fill.
            assertTrue(px[170, 50].red > 0.95f, "marker reaches into the text (${px[170, 50]})")
            assertTrue(px[100, 50].blue > 0.2f, "the highlight fill itself is gone (${px[100, 50]})")
        }
    }

    /** Same document, twice the rendered size: the marker doubles with it. */
    @Test
    fun edge_marker_scales_with_the_rendered_page() {
        val doc = KitePDF.open(redPagePdf())
        val hit = KiteSearchHit(0, listOf(Rectangle(left = 50.0, bottom = 20.0, right = 150.0, top = 80.0)), "x")
        lateinit var state: PdfViewState
        // 400x400 viewport: the 200pt page renders at 2x, so the marker band
        // moves from 194..198 to 388..396 and the highlight band to y 40..160.
        ImageComposeScene(width = 400, height = 400, density = Density(1f)) {
            state = rememberPdfViewState(doc)
            PdfView(state = state, modifier = Modifier.fillMaxSize(), layout = PdfLayout.SinglePage(0))
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { px -> px[392, 100].red > 0.95f && px[196, 100].green < 0.05f }
            // A red fill over red paper, so the only green anywhere is the marker.
            state.highlights = listOf(
                PdfHighlight(hit, color = Color(0x66FF0000), edgeMarker = true, edgeMarkerColor = Color(0xFF00FF00)),
            )
            val px = driver.pumpUntil { p -> p[392, 100].green > 0.9f }.toComposeImageBitmap().toPixelMap()
            assertTrue(px[392, 100].green > 0.9f, "marker did not scale with the page (${px[392, 100]})")
            // 196 was inside the marker at 1x; at 2x it is page again.
            assertTrue(px[196, 100].green < 0.1f, "marker kept its 1x position (${px[196, 100]})")
            assertTrue(px[392, 300].green < 0.1f, "marker band did not scale vertically (${px[392, 300]})")
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
