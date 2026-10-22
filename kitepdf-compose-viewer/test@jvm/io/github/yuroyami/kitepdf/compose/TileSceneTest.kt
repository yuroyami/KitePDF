package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteLocation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A page whose bitmap the long-side cap cuts down draws the part on screen again at full
 * resolution in tiles, so it stays sharp at deep zoom, and a very tall page stays sharp at
 * zoom 1 (#375).
 */
class TileSceneTest {

    /** A [width] x [height] page with [content]. */
    private fun pdf(width: Int, height: Int, content: String): PdfDocument {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] /Resources << >> /Contents 4 0 R >>")
        add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    /** The difference between the lightest and the darkest red in [area] of [pixels]. */
    private fun contrast(pixels: PixelMap, area: IntRect): Float {
        var low = 1f
        var high = 0f
        for (x in area.left until area.right) for (y in area.top until area.bottom) {
            low = minOf(low, pixels[x, y].red)
            high = maxOf(high, pixels[x, y].red)
        }
        return high - low
    }

    @Test
    fun a_page_past_the_cap_is_sharp_at_zoom_8() = forBothEffectOrders { queued ->
        // A 64 x 64 checker of single pixels drawn 32 pt wide. The capped bitmap of 128 pixels
        // averages it to grey; at zoom 8 each of its pixels covers four screen pixels.
        val checker = (0 until 64).joinToString("") { row -> if (row % 2 == 0) "AA".repeat(8) else "55".repeat(8) }
        val doc = pdf(100, 100, "q 32 0 0 32 34 34 cm BI /W 64 /H 64 /BPC 1 /CS /G /F /AHx ID $checker> EI Q")
        val state = KiteDocViewState(doc)
        val (scene, driver) = drivenScene(100, 100, queued) {
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.SinglePage(0),
                renderSpec = KiteRenderSpec.Rasterized(maxBitmapLongSide = 128),
            )
        }
        scene.use {
            driver.pumpUntil { it[50, 50].red < 0.9f }
            onTestUiThread { state.setZoom(8f) }
            driver.pumpUntil { contrast(it, IntRect(40, 40, 60, 60)) > 0.6f }
        }
    }

    @Test
    fun a_tall_page_is_sharp_at_zoom_1() = forBothEffectOrders { queued ->
        // A strip 50 pt wide and 1,000 pt tall, in a slot 200 pixels wide: 4,000 pixels tall in
        // full, cut down to 512 by the cap. A band of lines one point apart lies from 3,040 to
        // 3,200 pixels down, far from the first tile, and needs the full resolution.
        val lines = (0 until 20).joinToString(" ") { "0 ${200 + it * 2} 50 1 re" } + " f"
        val doc = pdf(50, 1000, "0 g $lines")
        val state = KiteDocViewState(doc, KiteScrollPosition(KiteLocation(0, 0), 3000))
        val (scene, driver) = drivenScene(200, 260, queued) {
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                renderSpec = KiteRenderSpec.Rasterized(maxBitmapLongSide = 512),
            )
        }
        scene.use {
            // The band shows from 40 to 200 pixels down the view, and the page above it is blank.
            driver.pumpUntil { contrast(it, IntRect(90, 100, 110, 140)) > 0.6f && it[100, 10].red > 0.9f }
        }
    }

    @Test
    fun the_tiles_cover_the_part_on_screen_and_stop_at_the_page() {
        val full = IntSize(1600, 1600)
        assertEquals(listOf(IntRect(0, 0, 1024, 1024)), tilesOver(Rect(10f, 10f, 100f, 100f), 8f, 8f, full))
        assertEquals(
            listOf(IntRect(0, 0, 1024, 1024), IntRect(1024, 0, 1600, 1024), IntRect(0, 1024, 1024, 1600), IntRect(1024, 1024, 1600, 1600)),
            tilesOver(Rect(50f, 50f, 150f, 150f), 8f, 8f, full),
        )
        // A part that ends on a tile edge does not take the next tile.
        assertEquals(listOf(IntRect(0, 0, 1024, 1024)), tilesOver(Rect(0f, 0f, 128f, 128f), 8f, 8f, full))
    }
}
