package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test

/**
 * A Vectorized page draws again once the zoom settles, so a hairline stays about one screen
 * pixel wide and an image keeps the detail the zoom shows. The page was drawn once at zoom 1 and
 * the zoom layer stretched it (#418).
 */
class VectorZoomSceneTest {

    /** A 100 x 100 page with [content]. */
    private fun pdf(content: String): PdfDocument {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << >> /Contents 4 0 R >>")
        add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    /** Shows [doc] in Vectorized mode, zooms to 8 once the page is drawn, and pumps until [zoomed] holds. */
    private fun zoomTo8(doc: PdfDocument, drawn: (PixelMap) -> Boolean, zoomed: (PixelMap) -> Boolean) = forBothEffectOrders { queued ->
        val state = KiteDocViewState(doc)
        val (scene, driver) = drivenScene(100, 100, queued) {
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.SinglePage(0),
                renderSpec = KiteRenderSpec.Vectorized(),
            )
        }
        scene.use {
            driver.pumpUntil(check = drawn)
            onTestUiThread { state.setZoom(8f) }
            driver.pumpUntil(check = zoomed)
        }
    }

    @Test
    fun a_hairline_stays_about_one_screen_pixel_wide_at_zoom_8() {
        // A zero-width line through the centre, where the zoom is centred, so it stays at y = 50.
        fun inkedRows(pixels: PixelMap) = (0 until 100).count { pixels[50, it].red < 0.8f }
        zoomTo8(
            pdf("0 w 10 50 m 90 50 l S"),
            drawn = { inkedRows(it) >= 1 },
            zoomed = { inkedRows(it) in 1..2 },
        )
    }

    @Test
    fun an_image_keeps_its_detail_at_zoom_8() {
        // A 64 x 64 checker of single pixels drawn 32 pt wide: at zoom 1 it averages to grey, and at
        // zoom 8 each of its pixels covers four screen pixels, so the centre shows black and white.
        val checker = (0 until 64).joinToString("") { row -> if (row % 2 == 0) "AA".repeat(8) else "55".repeat(8) }
        fun contrast(pixels: PixelMap): Float {
            var low = 1f
            var high = 0f
            for (x in 40 until 60) for (y in 40 until 60) {
                low = minOf(low, pixels[x, y].red)
                high = maxOf(high, pixels[x, y].red)
            }
            return high - low
        }
        zoomTo8(
            pdf("q 32 0 0 32 34 34 cm BI /W 64 /H 64 /BPC 1 /CS /G /F /AHx ID $checker> EI Q"),
            drawn = { it[50, 50].red < 0.9f },
            zoomed = { contrast(it) > 0.6f },
        )
    }

    @Test
    fun an_image_edge_stays_where_it_is_at_zoom_8() {
        // A black square from x = 50.4 pt. At zoom 1 its edge snaps out to the whole pixel at 50, and
        // zoom 8 would move that snap to x = 50 on screen. Unsnapped, the edge is at 50 + 0.4 x 8 = 53.2.
        fun black(pixels: PixelMap, x: Int) = pixels[x, 50].red < 0.3f
        zoomTo8(
            pdf("q 10 0 0 10 50.4 45 cm BI /W 1 /H 1 /BPC 8 /CS /G /F /AHx ID 00> EI Q"),
            drawn = { black(it, 55) },
            zoomed = { !black(it, 51) && black(it, 56) },
        )
    }
}
