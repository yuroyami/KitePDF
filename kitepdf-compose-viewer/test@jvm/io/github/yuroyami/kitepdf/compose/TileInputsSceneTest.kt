package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfScriptHandler
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The deep-zoom tiles follow the inputs of the whole page: the form layer (#662), and the hairline
 * of their own resolution at a quality above 1 (#665).
 */
class TileInputsSceneTest {

    /** A page of [width] x [height] with [content], and [extra] objects from 5 on, [annots] on the page. */
    private fun pdf(width: Int, height: Int, content: String, annots: String = "", acroForm: String = "", extra: List<String> = emptyList()): PdfDocument {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R $acroForm >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] /Resources << >> /Contents 4 0 R $annots >>")
        add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        extra.forEach(::add)
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    private class Scripts(document: PdfDocument) : PdfScriptHandler {
        override val formState: PdfFormState = PdfFormState(document)
    }

    private fun red(pixels: PixelMap, x: Int, y: Int) = pixels[x, y].let { it.red > 0.8f && it.green < 0.3f && it.blue < 0.3f }
    private fun white(pixels: PixelMap, x: Int, y: Int) = pixels[x, y].let { it.red > 0.9f && it.green > 0.9f && it.blue > 0.9f }

    @Test
    fun a_form_layer_that_comes_drops_the_tiles_drawn_without_it() = forBothEffectOrders { queued ->
        val appearance = "1 0 0 rg 0 0 100 100 re f"
        val doc = pdf(
            200, 200, "",
            annots = "/Annots [5 0 R]",
            acroForm = "/AcroForm << /Fields [5 0 R] >>",
            extra = listOf(
                "<< /Type /Annot /Subtype /Widget /FT /Btn /Ff 65536 /T (button) /Rect [50 50 150 150] /AP << /N 6 0 R >> >>",
                "<< /Type /XObject /Subtype /Form /BBox [0 0 100 100] /Length ${appearance.length} >>\nstream\n$appearance\nendstream",
            ),
        )
        var scripts by mutableStateOf<PdfScriptHandler?>(null)
        val state = KiteDocViewState(doc)
        val (scene, driver) = drivenScene(200, 200, queued) {
            KiteDocView(
                state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0),
                renderSpec = KiteRenderSpec.Rasterized(maxBitmapLongSide = 32), scripts = scripts,
            )
        }
        scene.use {
            driver.pumpUntil { red(it, 100, 100) }
            driver.pumpFrames(40)
            val handler = Scripts(doc).also { it.formState.setHidden("button", true) }
            onTestUiThread { scripts = handler }
            driver.pumpFrames(80)
            driver.pumpUntil { white(it, 100, 100) }
        }
    }

    /** How dark column [x] of [pixels] is from [top] to [bottom]: the sum of one less the red. */
    private fun darkness(pixels: PixelMap, x: Int, top: Int, bottom: Int): Float = (top until bottom).sumOf { (1f - pixels[x, it].red).toDouble() }.toFloat()

    @Test
    fun a_tile_at_quality_2_draws_a_hairline_one_screen_pixel_wide() {
        val doc = pdf(100, 100, "0 w 0 g 10 50 m 90 50 l S")
        val dark = HashMap<Float, Float>()
        for (quality in listOf(1f, 2f)) {
            forBothEffectOrders { queued ->
                val state = KiteDocViewState(doc)
                val (scene, driver) = drivenScene(100, 100, queued) {
                    KiteDocView(
                        state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0),
                        renderSpec = KiteRenderSpec.Rasterized(quality = quality, maxBitmapLongSide = 64),
                    )
                }
                scene.use {
                    driver.pumpUntil { darkness(it, 50, 40, 60) > 0.2f }
                    driver.pumpFrames(60)
                    dark[quality] = darkness(driver.pumpFrames(1).toComposeImageBitmap().toPixelMap(), 50, 40, 60)
                }
            }
        }
        val one = dark.getValue(1f)
        val two = dark.getValue(2f)
        assertTrue(two > one * 0.75f, "the hairline at quality 2 is $two dark against $one at quality 1")
    }
}
