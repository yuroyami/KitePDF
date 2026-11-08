package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.PdfDocument
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Switching a layer in the state draws the page again, with no stale bitmap (#938). */
class LayerSceneTest {

    private fun pdf(): PdfDocument {
        val content = "/OC /A BDC 1 0 0 rg 0 0 200 200 re f EMC"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R /OCProperties << /OCGs [5 0 R] /D << /OFF [5 0 R] >> >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R /Resources << /Properties << /A 5 0 R >> >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /OCG /Name (Red) >>",
        )
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = objects.mapIndexed { i, body -> sb.length.also { sb.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    @Test
    fun a_switched_layer_draws_again() {
        val rendered = Collections.synchronizedList(ArrayList<Int>())
        val state = KiteDocViewState(pdf())
        val (scene, driver) = drivenScene(200, 200, queued = true) {
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged(), onPageRendered = { index, _ -> rendered += index })
        }
        scene.use {
            driver.pumpUntilState { 0 in rendered }
            fun red(): Int {
                val c = driver.pumpFrames(2).toComposeImageBitmap().toPixelMap()[100, 100]
                return ((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
            }
            assertEquals(0xFFFFFF, red(), "the layer is off")
            assertEquals(listOf("5"), state.layers?.groups?.map { it.id })
            rendered.clear()
            driver.runOnUi { state.setLayerVisible("5", true) }
            driver.pumpUntilState { 0 in rendered }
            driver.pumpFrames(5)
            val on = red()
            assertTrue((on shr 16) > 0xF0 && (on shr 8 and 0xFF) < 0x10 && (on and 0xFF) < 0x10, "the page drew again with the layer on: ${on.toString(16)}")
        }
    }
}
