package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteBitmapCache
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.imageSampling
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A live converted bitmap must not keep the source document through its identity (#371). */
class ImageIdentityLifetimeTest {

    private fun retainedBitmap(): Pair<WeakReference<PdfDocument>, KiteBitmapCache<ByteArray>> {
        val document = PdfDocument.open(TestPdf.onePage(
            "/Im1 Do",
            resources = "/XObject << /Im1 5 0 R >>",
            extra = listOf(TestPdf.stream(
                "80>",
                "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /ASCIIHexDecode",
            )),
        ))
        val canvas = RecordingCanvas()
        document.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        val image = canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>().single().image
        val cache = KiteBitmapCache<ByteArray>()
        cache.getOrPut(image, imageSampling(1, 1, KiteMatrix.IDENTITY, false), { it.size.toLong() }) {
            image.toRgbaBytes()
        }
        document.dropDecodedImageCache()
        return WeakReference(document) to cache
    }

    @Test
    fun converted_bitmap_does_not_retain_the_document() {
        val (document, cache) = retainedBitmap()
        for (attempt in 0 until 20) {
            if (document.get() == null) break
            System.gc()
            Thread.sleep(10)
        }
        assertNull(document.get(), "the bitmap identity retains its source document")
        assertTrue(cache.heldBytes > 0L, "the converted bitmap must remain resident during collection")
    }
}
