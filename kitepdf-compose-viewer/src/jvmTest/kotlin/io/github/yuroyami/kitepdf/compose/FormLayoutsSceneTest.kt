package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfScriptHandler
import kotlin.test.Test

/**
 * The live form shows in every layout and in both render modes: a field that a script hides
 * disappears, and the file's own widget does not stay underneath (#358).
 */
class FormLayoutsSceneTest {

    /** Two 200 x 200 pages; the first has a black push button at [20 20 90 60], centred at display (55, 160). */
    private fun buttonPdf(): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [5 0 R] >> >>")
        add("<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 200 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [5 0 R] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> >>")
        add("<< /Type /Annot /Subtype /Widget /FT /Btn /Ff 65536 /T (press) /Rect [20 20 90 60] /MK << /BG [0] >> >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    private fun dark(pixels: PixelMap, x: Int, y: Int): Boolean {
        val c = pixels[x, y]
        return c.red + c.green + c.blue < 0.6f
    }

    @Test
    fun a_field_a_script_hides_disappears_in_every_layout_and_render_mode() {
        val layouts = listOf(
            KiteDocLayout.Continuous() to 200,
            KiteDocLayout.Paged() to 200,
            KiteDocLayout.Spread() to 400,
        )
        for ((layout, width) in layouts) {
            for (render in listOf(KiteRenderSpec.Rasterized(), KiteRenderSpec.Vectorized())) {
                val doc = PdfDocument.open(buttonPdf())
                val scripts = object : PdfScriptHandler {
                    override val formState: PdfFormState = PdfFormState(doc)
                }
                lateinit var state: KiteDocViewState
                val (scene, driver) = drivenScene(width, 200, queued = false) {
                    state = rememberKiteDocViewState(doc)
                    KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = layout, renderSpec = render, scripts = scripts)
                }
                scene.use {
                    val case = "${layout::class.simpleName} ${render::class.simpleName}"
                    driver.pumpUntil { dark(it, 55, 160) }
                    // Past the raster's arrival and fade, so the page under the form is final.
                    driver.pumpFrames(40)
                    scripts.formState.setHidden("press", true)
                    try {
                        driver.pumpUntil { !dark(it, 55, 160) }
                        val settled = driver.pumpFrames(40).toComposeImageBitmap().toPixelMap()
                        check(!dark(settled, 55, 160))
                    } catch (failure: Throwable) {
                        throw AssertionError("$case: the hidden button still shows", failure)
                    }
                }
            }
        }
    }
}
