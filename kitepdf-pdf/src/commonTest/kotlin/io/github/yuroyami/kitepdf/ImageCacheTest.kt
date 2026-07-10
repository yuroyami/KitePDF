package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.RecordingCanvas
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.PdfImage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T-12: one image XObject drawn three times on one page and once on another
 * decodes exactly ONCE per document; dropping the cache re-decodes lazily.
 */
class ImageCacheTest {

    private fun doc(): PdfDocument {
        val pixels = ByteArray(8 * 8 * 3) { (it * 7).toByte() }
        val logo = PdfImage.rgb(pixels, 8, 8)
        return KitePDF.open(
            PdfBuilder()
                .page(width = 200.0, height = 200.0) {
                    drawImage(logo, 10.0, 10.0, 50.0, 50.0)
                    drawImage(logo, 70.0, 10.0, 50.0, 50.0)
                    drawImage(logo, 130.0, 10.0, 50.0, 50.0)
                }
                .page(width = 200.0, height = 200.0) {
                    drawImage(logo, 10.0, 100.0, 80.0, 80.0)
                }
                .build(compress = false),
        )
    }

    @Test
    fun repeated_draws_decode_once_per_document() {
        val doc = doc()
        for (page in doc.pages) {
            val canvas = RecordingCanvas()
            page.renderTo(canvas, Matrix.IDENTITY)
            // Every draw still happens; only the decode is shared.
            assertEquals(
                if (page.index == 0) 3 else 1,
                canvas.calls.count { it is RecordingCanvas.Call.Image },
            )
        }
        assertEquals(1, doc.imageDecodeCount, "one decode across 4 draws on 2 pages")
    }

    @Test
    fun dropping_the_cache_re_decodes_on_next_render() {
        val doc = doc()
        doc.pages[0].renderTo(RecordingCanvas(), Matrix.IDENTITY)
        assertEquals(1, doc.imageDecodeCount)
        doc.dropDecodedImageCache()
        doc.pages[1].renderTo(RecordingCanvas(), Matrix.IDENTITY)
        assertEquals(2, doc.imageDecodeCount, "cleared cache decodes again, exactly once")
    }
}
