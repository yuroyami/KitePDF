package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Cancelling the coroutine of [KitePageRasterizer.rasterizeOffMain] stops the page and returns no bitmap (#188). */
class RasterCancellationTest {

    private fun manyFills(count: Int): PdfDocument {
        val content = StringBuilder("1 0 0 rg\n")
        repeat(count) { content.append("${it % 60 * 10} ${it / 60 % 80 * 10} 8 8 re f\n") }
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
        )
        val out = StringBuilder("%PDF-1.7\n")
        val offsets = objects.mapIndexed { i, body -> out.length.also { out.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) out.append("${o.toString().padStart(10, '0')} 00000 n \n")
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(out.toString().encodeToByteArray())
    }

    @Test
    fun a_cancelled_page_stops_and_returns_no_bitmap() = runBlocking {
        val density = Density(1f)
        val rasterizer = KitePageRasterizer(density, LayoutDirection.Ltr, TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr))
        val page = manyFills(20_000).pages[0]
        val fills = AtomicInteger()
        val render = async(Dispatchers.Default) {
            val self = coroutineContext.job
            // The page cancels its own coroutine after 100 fills, so the test does not race a clock.
            rasterizer.rasterizeOffMain(page, 300, 400, canvasDecorator = { inner ->
                object : KiteCanvas by inner {
                    override fun fillPath(path: KitePath, ctm: KiteMatrix, color: RgbColor, evenOdd: Boolean, alpha: Double, blendMode: KiteBlendMode) {
                        if (fills.incrementAndGet() == 100) self.cancel()
                        inner.fillPath(path, ctm, color, evenOdd, alpha, blendMode)
                    }
                }
            })
        }
        assertFailsWith<CancellationException> { render.await() }
        assertTrue(fills.get() < 200, "the page stopped soon after the cancel: ${fills.get()} fills of 20,000")
    }
}
