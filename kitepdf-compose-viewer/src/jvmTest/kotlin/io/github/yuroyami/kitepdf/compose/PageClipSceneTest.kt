package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A page draws only inside its own slot in both render modes. Content outside the page, such
 * as bleed or crop marks, does not paint the gap, the next page or the letterbox (#417).
 */
class PageClipSceneTest {

    private class TwoPages(page: KitePage) : KiteDocument {
        override val pageCount: Int = 2
        override val pages: List<KitePage> = listOf(page, page)
    }

    /** A red 100 x 100 page whose content runs 50 units past each edge. */
    private class BleedingPage : KitePage {
        override val displayWidth: Double = 100.0
        override val displayHeight: Double = 100.0
        override fun displayToDeviceBase(): KiteMatrix = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, displayHeight)
        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
            canvas.beginPage(displayWidth, displayHeight, deviceCtm)
            val bleed = KitePath.Builder().apply { rectangle(-50.0, -50.0, 200.0, 200.0) }.build()
            canvas.fillPath(bleed, deviceCtm.concat(displayToDeviceBase()), RgbColor(1.0, 0.0, 0.0), evenOdd = false)
            canvas.endPage()
        }
    }

    /** Two pages whose crop box cuts 100 points off each side of a red media box. */
    private fun croppedPdf(): PdfDocument {
        val content = "1 0 0 rg 0 0 600 800 re f"
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        val page = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /CropBox [100 100 500 700] /Resources << >> /Contents 5 0 R >>"
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>")
        add(page)
        add(page)
        add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    private fun red(pixels: PixelMap, x: Int, y: Int) = pixels[x, y].let { it.red > 0.8f && it.green < 0.3f && it.blue < 0.3f }
    private fun blue(pixels: PixelMap, x: Int, y: Int) = pixels[x, y].let { it.blue > 0.8f && it.red < 0.3f && it.green < 0.3f }

    @Test
    fun content_outside_a_page_paints_neither_the_gap_nor_the_viewport() {
        for (spec in listOf(KiteRenderSpec.Vectorized(), KiteRenderSpec.Rasterized())) {
            forBothEffectOrders { queued ->
                val (scene, driver) = drivenScene(200, 700, queued) {
                    KiteDocView(
                        state = rememberKiteDocViewState(TwoPages(BleedingPage())),
                        modifier = Modifier.fillMaxSize(),
                        layout = KiteDocLayout.Continuous(),
                        renderSpec = spec,
                        colors = KiteDocViewColors(viewportBackground = Color.Blue),
                        pageSpacing = 16.dp,
                    )
                }
                scene.use {
                    // Each page fits the width, so its slot is 200 x 200 px: the gap runs from
                    // y = 200 to 216, and the viewport below the second page is empty from y = 416.
                    val frame = driver.pumpUntil { red(it, 100, 100) && red(it, 100, 316) }.toComposeImageBitmap().toPixelMap()
                    assertTrue(blue(frame, 100, 208), "$spec: the gap between the pages shows the viewport")
                    assertTrue(blue(frame, 100, 480), "$spec: the viewport below the last page shows the viewport")
                }
            }
        }
    }

    @Test
    fun a_pdf_crop_box_cuts_its_page_in_the_viewer() {
        for (spec in listOf(KiteRenderSpec.Vectorized(), KiteRenderSpec.Rasterized())) {
            forBothEffectOrders { queued ->
                val (scene, driver) = drivenScene(200, 700, queued) {
                    KiteDocView(
                        state = rememberKiteDocViewState(croppedPdf()),
                        modifier = Modifier.fillMaxSize(),
                        layout = KiteDocLayout.Continuous(),
                        renderSpec = spec,
                        colors = KiteDocViewColors(viewportBackground = Color.Blue),
                        pageSpacing = 16.dp,
                    )
                }
                scene.use {
                    // The crop box is 400 x 600 points, so each slot is 200 x 300 px and the gap
                    // runs from y = 300 to 316 (ISO 32000-1, 14.11.2).
                    val frame = driver.pumpUntil { red(it, 100, 150) && red(it, 100, 400) }.toComposeImageBitmap().toPixelMap()
                    assertTrue(blue(frame, 100, 308), "$spec: the gap between the pages shows the viewport")
                    assertTrue(blue(frame, 100, 650), "$spec: the viewport below the last page shows the viewport")
                }
            }
        }
    }

    @Test
    fun content_outside_a_letterboxed_page_does_not_paint_the_letterbox() {
        for (spec in listOf(KiteRenderSpec.Vectorized(), KiteRenderSpec.Rasterized())) {
            forBothEffectOrders { queued ->
                val (scene, driver) = drivenScene(200, 700, queued) {
                    KiteDocView(
                        state = rememberKiteDocViewState(TwoPages(BleedingPage())),
                        modifier = Modifier.fillMaxSize(),
                        layout = KiteDocLayout.SinglePage(0),
                        renderSpec = spec,
                        colors = KiteDocViewColors(viewportBackground = Color.Blue),
                    )
                }
                scene.use {
                    // The page is 200 x 200 px, centred from y = 250 to 450.
                    val frame = driver.pumpUntil { red(it, 100, 350) }.toComposeImageBitmap().toPixelMap()
                    assertTrue(blue(frame, 100, 220), "$spec: the letterbox above the page shows the viewport")
                    assertTrue(blue(frame, 100, 480), "$spec: the letterbox below the page shows the viewport")
                }
            }
        }
    }
}
