package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * T-85: right-to-left page progression and two-page spreads. Colour-coded
 * pages make the visual order pixel-assertable: page 0 red, 1 blue, 2 green.
 */
class SpreadRtlSceneTest {

    private fun rgbDoc(n: Int): ByteArray = PdfBuilder().apply {
        val colors = listOf(
            Triple(1.0, 0.0, 0.0),
            Triple(0.0, 0.0, 1.0),
            Triple(0.0, 1.0, 0.0),
        )
        repeat(n) { i ->
            val (r, g, b) = colors[i % 3]
            page(width = 100.0, height = 200.0) {
                setFillRgb(r, g, b); rectangle(0.0, 0.0, 100.0, 200.0); fill()
            }
        }
    }.build()

    @Test
    fun spread_shows_two_pages_and_rtl_swaps_their_visual_order() {
        val doc = KitePDF.open(rgbDoc(2))
        // LTR spread: page 0 (red) left, page 1 (blue) right.
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            PdfView(
                state = rememberPdfViewState(doc),
                modifier = Modifier.fillMaxSize(),
                layout = PdfLayout.Spread(),
            )
        }.use { scene ->
            SceneTestDriver(scene).pumpUntil { px ->
                px[50, 100].red > 0.8f && px[150, 100].blue > 0.8f
            }.toComposeImageBitmap().toPixelMap().let { px ->
                assertTrue(px[50, 100].red > 0.8f, "LTR: page 0 (red) on the left")
                assertTrue(px[150, 100].blue > 0.8f, "LTR: page 1 (blue) on the right")
            }
        }
        // RTL spread: page 0 (red) RIGHT, page 1 (blue) LEFT.
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            PdfView(
                state = rememberPdfViewState(doc),
                modifier = Modifier.fillMaxSize(),
                layout = PdfLayout.Spread(reverseLayout = true),
            )
        }.use { scene ->
            SceneTestDriver(scene).pumpUntil { px ->
                px[50, 100].blue > 0.8f && px[150, 100].red > 0.8f
            }.toComposeImageBitmap().toPixelMap().let { px ->
                assertTrue(px[50, 100].blue > 0.8f, "RTL: page 1 (blue) visually left")
                assertTrue(px[150, 100].red > 0.8f, "RTL: page 0 (red) visually right")
            }
        }
    }

    @Test
    fun spread_navigation_stays_logical_and_advances_by_spread() {
        val doc = KitePDF.open(rgbDoc(3))
        lateinit var state: PdfViewState
        lateinit var scope: CoroutineScope
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberPdfViewState(doc)
            scope = rememberCoroutineScope()
            PdfView(state = state, modifier = Modifier.fillMaxSize(), layout = PdfLayout.Spread())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { px -> px[50, 100].red > 0.8f }
            assertEquals(0, state.currentPage)

            // +1 stays on spread 0 (page 1 is already visible there)...
            scope.launch { state.nextPage() }
            driver.pumpUntil { state.currentPage == 1 }
            assertEquals(1, state.currentPage, "logical +1")

            // ...and the next +1 advances to spread 1, the lone green page 2.
            scope.launch { state.nextPage() }
            driver.pumpUntil { state.currentPage == 2 }
            driver.pumpUntil { px -> px[100, 100].green > 0.8f }
            assertEquals(2, state.currentPage)
            val px = driver.pumpUntil { true }.toComposeImageBitmap().toPixelMap()
            assertTrue(px[100, 100].green > 0.8f, "odd trailing page centres alone")
            assertFalse(px[20, 100].red > 0.8f, "no half-spread ghost")

            // previousPage steps back logically too.
            scope.launch { state.previousPage() }
            driver.pumpUntil { state.currentPage == 1 }
            assertEquals(1, state.currentPage)
        }
    }

    @Test
    fun paged_for_follows_the_document_direction() {
        // A raw PDF flagged /Direction /R2L.
        val sb = StringBuilder()
        val offsets = ArrayList<Int>()
        fun add(s: String) {
            offsets.add(sb.length)
            sb.append(s)
        }
        sb.append("%PDF-1.4\n")
        add("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /ViewerPreferences << /Direction /R2L >> >>\nendobj\n")
        add("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 100 100] >>\nendobj\n")
        add("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")
        val xref = sb.length
        sb.append("xref\n0 4\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")

        val rtl = KitePDF.open(sb.toString().encodeToByteArray())
        assertTrue(rtl.metadata.rightToLeft, "R2L viewer preference surfaces on KiteMetadata")
        assertTrue(PdfLayout.pagedFor(rtl).reverseLayout)

        val ltr = KitePDF.open(rgbDoc(1))
        assertFalse(ltr.metadata.rightToLeft)
        assertFalse(PdfLayout.pagedFor(ltr).reverseLayout)
    }
}
