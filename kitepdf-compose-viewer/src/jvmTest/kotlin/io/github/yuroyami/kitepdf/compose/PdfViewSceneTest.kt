package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end test of the [PdfView] composable itself — layout, the lazy
 * whole-document list, the per-page raster cache and the export callback —
 * run headlessly through [ImageComposeScene] (no window needed).
 *
 * The fixture document is built with KitePDF's own writer: red page, then
 * blue page, so each page's identity is visible in the final pixels.
 */
class PdfViewSceneTest {

    @Test
    fun pdfView_displays_pages_and_reports_saveable_images() {
        val bytes = PdfBuilder()
            .page(width = 200.0, height = 200.0) {
                setFillRgb(1.0, 0.0, 0.0); rectangle(0.0, 0.0, 200.0, 200.0); fill()
            }
            .page(width = 200.0, height = 200.0) {
                setFillRgb(0.0, 0.0, 1.0); rectangle(0.0, 0.0, 200.0, 200.0); fill()
            }
            .build()
        val doc = KitePDF.open(bytes)

        val exported = mutableMapOf<Int, ByteArray?>()
        // Viewport 200×320: page 0 fully visible, page 1 from y=208 down.
        val frame = ImageComposeScene(width = 200, height = 320, density = Density(1f)) {
            PdfView(
                document = doc,
                modifier = Modifier.fillMaxSize(),
                onPageRendered = { index, image -> exported[index] = image.encodeToPng() },
            )
        }.use { scene ->
            // Pages rasterize off-thread and fade in, so wait until both are
            // actually painted (and their callbacks have fired) rather than a
            // fixed frame count.
            SceneTestDriver(scene).pumpUntil { px ->
                val a = px[100, 100]
                val b = px[100, 300]
                a.red > 0.8f && a.blue < 0.2f && b.blue > 0.8f && b.red < 0.2f &&
                    0 in exported && 1 in exported
            }
        }

        val px = frame.toComposeImageBitmap().toPixelMap()
        val onPage0 = px[100, 100]
        assertTrue(onPage0.red > 0.8f && onPage0.blue < 0.2f, "page 1 not painted red: $onPage0")
        val onPage1 = px[100, 300]
        assertTrue(onPage1.blue > 0.8f && onPage1.red < 0.2f, "page 2 not painted blue: $onPage1")

        assertTrue(0 in exported, "page 0 never hit onPageRendered")
        assertTrue(1 in exported, "page 1 never hit onPageRendered")
        val png = assertNotNull(exported[0], "page 0 export encoded to null")
        assertTrue(png.size > 8 && png[1].toInt() == 0x50, "page 0 export is not a PNG")
    }
}
