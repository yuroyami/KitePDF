package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.PdfImage
import io.github.yuroyami.kitepdf.writer.StandardFont
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A Vectorized draw on the UI thread takes no render lock, while the rasters of the pool take it.
 * Both read the same document caches at the same time, as a thumbnail strip beside a Vectorized
 * viewer does, and both must draw what they draw alone (#392).
 */
class VectorAndRasterStressTest {

    private val width = 150
    private val height = 150

    private fun pdfBytes(): ByteArray {
        val image = PdfImage.rgb(ByteArray(16 * 16 * 3) { (it * 31).toByte() }, 16, 16)
        val builder = PdfBuilder()
        repeat(4) { i ->
            builder.page(width = 300.0, height = 300.0) {
                setFillRgb(0.2 * i, 0.5, 1.0 - 0.2 * i)
                rectangle(10.0, 10.0, 280.0, 280.0)
                fill()
                drawImage(image, 40.0, 40.0, 100.0, 100.0)
                text(StandardFont.Helvetica, 24.0, 30.0, 250.0, "page $i")
                text(StandardFont.TimesRoman, 18.0, 30.0, 200.0, "shared font caches")
            }
        }
        return builder.build()
    }

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    /** What KitePageVector does: a new canvas over the page, in the calling thread, without a lock. */
    private fun vectorDraw(page: KitePage): IntArray {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
            drawRect(Color.White)
            val scale = width / page.displayWidth
            page.renderTo(ComposeCanvas(this, measurer), io.github.yuroyami.kitepdf.core.render.KiteMatrix.scaling(scale, scale).concat(page.displayToDeviceBase()))
        }
        return bitmap.pixels()
    }

    private fun ImageBitmap.pixels(): IntArray = IntArray(width * height).also { readPixels(it) }

    private fun raster(rasterizer: KitePageRasterizer, page: KitePage): IntArray =
        runBlocking { rasterizer.rasterizeOffMain(page, width, height) }.pixels()

    @Test
    fun a_vector_draw_and_pool_rasters_of_one_document_draw_what_they_draw_alone() {
        val bytes = pdfBytes()
        val rasterizer = KitePageRasterizer(Density(1f), LayoutDirection.Ltr, measurer)
        val alone = KitePDF.open(bytes)
        val vectorBaseline = alone.pages.map { vectorDraw(it) }
        val rasterBaseline = KitePDF.open(bytes).pages.map { raster(rasterizer, it) }
        assertTrue(vectorBaseline.all { pixels -> pixels.any { it != -1 } }, "the baseline draws nothing")

        repeat(10) { iteration ->
            // A cold document each time, so the caches fill while both paths read them.
            val doc: PdfDocument = KitePDF.open(bytes)
            val errors = ConcurrentLinkedQueue<String>()
            val start = CountDownLatch(1)
            val pool = (0 until 4).map { t ->
                thread {
                    start.await()
                    try {
                        for ((i, page) in doc.pages.withIndex()) {
                            if (!raster(rasterizer, page).contentEquals(rasterBaseline[i])) errors += "iteration $iteration, raster $t, page $i differs"
                        }
                    } catch (failure: Throwable) {
                        errors += "iteration $iteration, raster $t threw $failure"
                    }
                }
            }
            start.countDown()
            // The test thread plays the UI thread.
            repeat(3) {
                for ((i, page) in doc.pages.withIndex()) {
                    if (!vectorDraw(page).contentEquals(vectorBaseline[i])) errors += "iteration $iteration, vector draw of page $i differs"
                }
            }
            pool.forEach { it.join() }
            assertTrue(errors.isEmpty(), errors.joinToString("\n"))
        }
    }
}
