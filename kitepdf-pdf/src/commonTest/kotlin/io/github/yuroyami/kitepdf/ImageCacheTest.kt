package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.PdfImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One image XObject drawn three times on one page and once on another
 * decodes exactly ONCE per document; dropping the cache re-decodes lazily.
 * The cache keeps the images used most recently within its byte budget (#116).
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
            page.renderTo(canvas, KiteMatrix.IDENTITY)
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
        doc.pages[0].renderTo(RecordingCanvas(), KiteMatrix.IDENTITY)
        assertEquals(1, doc.imageDecodeCount)
        doc.dropDecodedImageCache()
        assertEquals(0L, doc.decodedImageBytes)
        doc.pages[1].renderTo(RecordingCanvas(), KiteMatrix.IDENTITY)
        assertEquals(2, doc.imageDecodeCount, "cleared cache decodes again, exactly once")
    }

    /** Three pages, each drawing its own image. */
    private fun threeImages(): PdfDocument {
        val builder = PdfBuilder()
        repeat(3) { n ->
            val image = PdfImage.rgb(ByteArray(32 * 32 * 3) { (it * (n + 3)).toByte() }, 32, 32)
            builder.page(width = 100.0, height = 100.0) { drawImage(image, 10.0, 10.0, 50.0, 50.0) }
        }
        return PdfDocument.open(builder.build(compress = false))
    }

    private fun PdfDocument.render(index: Int) = pages[index].renderTo(RecordingCanvas(), KiteMatrix.IDENTITY)

    @Test
    fun the_cache_keeps_the_images_used_last_within_its_budget() {
        val doc = threeImages()
        doc.render(0)
        val one = doc.decodedImageBytes
        assertTrue(one > 0, "the first image is kept")
        doc.imageCacheBudgetBytes = 2 * one + one / 2
        doc.render(1)
        doc.render(2)
        assertEquals(3, doc.imageDecodeCount)
        assertEquals(2 * one, doc.decodedImageBytes, "the image of page 0 left first")
        doc.render(2)
        doc.render(1)
        assertEquals(3, doc.imageDecodeCount, "pages 1 and 2 are kept")
        doc.render(0)
        assertEquals(4, doc.imageDecodeCount)
        // Page 0 came back and pushed out page 2, the image used least recently.
        doc.render(1)
        assertEquals(4, doc.imageDecodeCount)
        doc.render(2)
        assertEquals(5, doc.imageDecodeCount)
    }

    @Test
    fun an_image_larger_than_the_budget_is_not_kept() {
        val doc = doc()
        doc.imageCacheBudgetBytes = 10
        doc.render(0)
        assertEquals(3, doc.imageDecodeCount, "each draw decodes when nothing can be kept")
        assertEquals(0L, doc.decodedImageBytes)
    }

    @Test
    fun lowering_the_budget_frees_the_images_at_once() {
        val doc = threeImages()
        for (i in 0..2) doc.render(i)
        assertTrue(doc.decodedImageBytes > 0)
        doc.imageCacheBudgetBytes = 0
        assertEquals(0L, doc.decodedImageBytes)
        doc.render(0)
        assertEquals(4, doc.imageDecodeCount)
    }
}
