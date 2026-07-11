package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.render.Matrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round-trips the two new writer primitives — image XObjects and clipping —
 * through the parser/renderer: build a PDF with [PdfBuilder], reopen it with
 * KitePDF, render to a [RecordingCanvas], and assert the pixels/operators
 * survive. Proves the bytes [PdfBuilder] emits are something KitePDF itself can
 * read back (the whole point of generating reports with the writer).
 */
class WriterImageClipTest {

    @Test
    fun rgba_image_round_trips_with_colors_and_soft_mask() {
        // 2×2 RGBA: red, green, blue, transparent-black.
        val pixels = byteArrayOf(
            0xFF.toByte(), 0, 0, 0xFF.toByte(),
            0, 0xFF.toByte(), 0, 0xFF.toByte(),
            0, 0, 0xFF.toByte(), 0xFF.toByte(),
            0, 0, 0, 0x00,
        )
        val image = PdfImage.rgba(pixels, width = 2, height = 2)
        val bytes = PdfBuilder()
            .page(width = 100.0, height = 100.0) {
                drawImage(image, x = 10.0, y = 20.0, width = 40.0, height = 50.0)
            }
            .build()

        val doc = KitePDF.open(bytes)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)

        val images = canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>()
        assertEquals(1, images.size, "expected exactly one image draw")
        val img = images[0].image
        assertEquals(2, img.width)
        assertEquals(2, img.height)
        assertEquals("DeviceRGB", img.colorSpace)

        // Placement: cm = [w 0 0 h x y] → recorded ctm carries the rect.
        assertEquals(40.0, images[0].ctm.a, 1e-6)
        assertEquals(50.0, images[0].ctm.d, 1e-6)
        assertEquals(10.0, images[0].ctm.e, 1e-6)
        assertEquals(20.0, images[0].ctm.f, 1e-6)

        // Colour samples survived FlateDecode round-trip (RGB, alpha stripped to SMask).
        val rgb = assertNotNull(img.pixelBytes, "RAW image should expose pixelBytes")
        assertEquals(2 * 2 * 3, rgb.size)
        assertEquals(0xFF.toByte(), rgb[0]); assertEquals(0, rgb[1]); assertEquals(0, rgb[2]) // red
        assertEquals(0, rgb[3]); assertEquals(0xFF.toByte(), rgb[4]); assertEquals(0, rgb[5]) // green

        // Alpha became a /SMask: 3 opaque + 1 transparent.
        val mask = assertNotNull(img.softMaskAlpha, "rgba with a transparent pixel must produce a SMask")
        assertEquals(4, mask.size)
        assertEquals(0xFF.toByte(), mask[0])
        assertEquals(0x00, mask[3])
    }

    @Test
    fun opaque_rgba_omits_soft_mask() {
        val pixels = ByteArray(2 * 2 * 4) { i -> if (i % 4 == 3) 0xFF.toByte() else 0x40 }
        val image = PdfImage.rgba(pixels, 2, 2)
        val bytes = PdfBuilder().page(width = 50.0, height = 50.0) {
            drawImage(image, 0.0, 0.0, 50.0, 50.0)
        }.build()
        val doc = KitePDF.open(bytes)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)
        val img = canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>().single().image
        assertTrue(img.softMaskAlpha == null, "fully-opaque image must not carry a SMask")
    }

    @Test
    fun clip_emits_push_clip_with_rectangle() {
        val bytes = PdfBuilder()
            .page(width = 100.0, height = 100.0) {
                save()
                rectangle(10.0, 10.0, 30.0, 30.0)
                clip()
                endPath()
                setFillRgb(1.0, 0.0, 0.0)
                rectangle(0.0, 0.0, 100.0, 100.0)
                fill()
                restore()
            }
            .build()

        val doc = KitePDF.open(bytes)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)

        val clips = canvas.calls.filterIsInstance<RecordingCanvas.Call.PushClip>()
        assertEquals(1, clips.size, "clip() should produce one pushClip")
        assertTrue(!clips[0].evenOdd, "clip() is nonzero-winding (W), not even-odd")
        // The clip path is the 30×30 rectangle (M + 3 L + Close).
        assertEquals(5, clips[0].path.segments.size)
        // The fill after the clip still renders.
        assertEquals(1, canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().size)
    }

    @Test
    fun jpeg_passthrough_preserves_encoded_bytes() {
        // Not a real JPEG — we only assert the writer stores the bytes verbatim
        // under /DCTDecode and the reader classifies the XObject as JPEG.
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val image = PdfImage.jpeg(fakeJpeg, width = 8, height = 8)
        val bytes = PdfBuilder().page(width = 20.0, height = 20.0) {
            drawImage(image, 0.0, 0.0, 8.0, 8.0)
        }.build()
        val doc = KitePDF.open(bytes)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)
        val img = canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>().single().image
        assertEquals(8, img.width)
        assertEquals("DeviceRGB", img.colorSpace)
        assertTrue(fakeJpeg.contentEquals(img.encodedBytes), "DCTDecode bytes must pass through unchanged")
    }

    @Test
    fun same_image_instance_shared_across_pages() {
        val image = PdfImage.gray(ByteArray(4) { 0x7F }, 2, 2)
        val bytes = PdfBuilder()
            .page(width = 50.0, height = 50.0) { drawImage(image, 0.0, 0.0, 10.0, 10.0) }
            .page(width = 50.0, height = 50.0) { drawImage(image, 5.0, 5.0, 10.0, 10.0) }
            .build()
        // Both pages parse and each draws the (shared) image.
        val doc = KitePDF.open(bytes)
        for (p in 0 until 2) {
            val canvas = RecordingCanvas()
            doc.pages[p].renderTo(canvas, Matrix.IDENTITY)
            assertEquals(1, canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>().size, "page $p missing image")
        }
    }
}
