package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test

/**
 * Every accepted raster quality gives a page, however small: a side that rounds below one pixel
 * keeps one, and the page never stays a placeholder (#422).
 */
class SmallQualitySceneTest {

    /** One red page of [width] x [height] pt. */
    private fun redPdf(width: Int, height: Int): ByteArray {
        val content = "1 0 0 rg 0 0 $width $height re f"
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 $width $height] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Contents 4 0 R >>")
        add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /**
     * The page's red, or the pale red of a page that covers only part of a raster pixel. The
     * placeholder is white, and a slot with nothing in it is transparent.
     */
    private fun drawn(pixels: PixelMap, x: Int, y: Int): Boolean {
        val c = pixels[x, y]
        return c.alpha > 0.9f && c.red > 0.8f && c.green < 0.9f && c.blue < 0.9f
    }

    /** A page size, a viewport size, a quality, and the pixels that the page must cover. */
    private data class Case(val page: Pair<Int, Int>, val viewport: Int, val quality: Float, val probes: List<Pair<Int, Int>>)

    @Test
    fun a_page_at_a_tiny_quality_still_draws() {
        val cases = listOf(
            Case(200 to 200, viewport = 200, quality = 0.001f, probes = listOf(100 to 100, 10 to 10, 190 to 190)),
            // A page 100 times wider than tall: 200 x 2 px on screen, and its height rounds to 0.
            Case(1000 to 10, viewport = 200, quality = 0.1f, probes = listOf(10 to 100, 100 to 100, 190 to 100)),
            // A viewport of one pixel.
            Case(200 to 200, viewport = 1, quality = 0.3f, probes = listOf(0 to 0)),
        )
        for (case in cases) {
            forBothEffectOrders { queued ->
                val doc = PdfDocument.open(redPdf(case.page.first, case.page.second))
                lateinit var state: KiteDocViewState
                val (scene, driver) = drivenScene(case.viewport, case.viewport, queued) {
                    state = rememberKiteDocViewState(doc)
                    KiteDocView(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        layout = KiteDocLayout.SinglePage(0),
                        renderSpec = KiteRenderSpec.Rasterized(quality = case.quality),
                    )
                }
                scene.use {
                    try {
                        driver.pumpUntil { pixels -> case.probes.all { (x, y) -> drawn(pixels, x, y) } }
                    } catch (failure: AssertionError) {
                        throw AssertionError("$case: the page stayed a placeholder", failure)
                    }
                }
            }
        }
    }
}
