package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/** In a right-to-left app, a spread hit-tests the page it draws under the finger (#401). */
class SpreadRtlHitSceneTest {

    /** Two 100 x 200 pages: page 0 red, page 1 blue. */
    private fun twoColourPdf(): ByteArray {
        val red = "1 0 0 rg 0 0 100 200 re f"
        val blue = "0 0 1 rg 0 0 100 200 re f"
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 100 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Contents 5 0 R >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Contents 6 0 R >>")
        add("<< /Length ${red.length} >>\nstream\n$red\nendstream")
        add("<< /Length ${blue.length} >>\nstream\n$blue\nendstream")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** The page drawn at a pixel: 0 for red, 1 for blue, null for anything else. */
    private fun drawn(pixels: PixelMap, x: Int): Int? {
        val c = pixels[x, 100]
        return when {
            c.red > 0.8f && c.blue < 0.3f -> 0
            c.blue > 0.8f && c.red < 0.3f -> 1
            else -> null
        }
    }

    @Test
    fun a_spread_in_a_right_to_left_app_hit_tests_the_page_it_draws() {
        for (render in listOf(KiteRenderSpec.Vectorized(), KiteRenderSpec.Rasterized())) {
            for (reverse in listOf(false, true)) {
                forBothEffectOrders { queued ->
                    val doc = PdfDocument.open(twoColourPdf())
                    lateinit var state: KiteDocViewState
                    val (scene, driver) = drivenScene(200, 200, queued) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                            state = rememberKiteDocViewState(doc)
                            KiteDocView(
                                state = state,
                                modifier = Modifier.fillMaxSize(),
                                layout = KiteDocLayout.Spread(reverseLayout = reverse),
                                renderSpec = render,
                            )
                        }
                    }
                    scene.use {
                        driver.pumpUntil { drawn(it, 50) != null && drawn(it, 150) != null }
                        for (zoomed in listOf(false, true)) {
                            if (zoomed) {
                                state.setZoom(2f)
                                state.panBy(Offset(30f, 0f))
                            }
                            val map = driver.pumpFrames(30).toComposeImageBitmap().toPixelMap()
                            for (x in listOf(50, 150)) {
                                val case = "${render::class.simpleName} reverse=$reverse zoomed=$zoomed x=$x"
                                val hit = state.hitTest(Offset(x.toFloat(), 100f))?.pageIndex
                                assertEquals(drawn(map, x), hit, case)
                            }
                        }
                    }
                }
            }
        }
    }
}
