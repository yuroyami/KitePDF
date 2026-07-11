package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.render.ImageXObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [ImageXObject.fromEncodedImage] — the public entry other handlers (EPUB, CBZ)
 * use to build an image from a self-contained encoded file.
 */
class ImageXObjectEncodedTest {

    // SOI + SOF0 declaring 48x32 + EOI. Dimensions live in the SOF0 segment.
    private val jpeg48x32 = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08,
        0x00, 0x20, 0x00, 0x30,
        0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
        0xFF.toByte(), 0xD9.toByte(),
    )

    @Test
    fun jpeg_dimensions_are_sniffed_and_bytes_kept() {
        val img = ImageXObject.fromEncodedImage(jpeg48x32)
        assertTrue(img != null)
        assertEquals(48, img!!.width)
        assertEquals(32, img.height)
        assertEquals(ImageXObject.Kind.JPEG, img.kind)
        assertSame(jpeg48x32, img.encodedBytes, "encoded file handed to the platform decoder verbatim")
    }

    @Test
    fun jpeg_dimensions_survive_a_leading_app0_segment() {
        // Real JFIF files put an APP0 segment before SOF0; the sniffer must skip it.
        val app0 = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, // APP0, len=16
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08, 0x01, 0x00, 0x02, 0x00, // SOF0 h=256 w=512
            0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
            0xFF.toByte(), 0xD9.toByte(),
        )
        val img = ImageXObject.fromEncodedImage(app0)
        assertEquals(512, img?.width)
        assertEquals(256, img?.height)
    }

    @Test
    fun header_only_png_returns_null() {
        // Valid signature but no IHDR/IDAT: nothing to decode. (Real PNG decoding
        // is covered by PngDecoderTest.)
        val pngSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13)
        assertNull(ImageXObject.fromEncodedImage(pngSig))
    }

    @Test
    fun garbage_returns_null() {
        assertNull(ImageXObject.fromEncodedImage(byteArrayOf(1, 2, 3, 4)))
        assertNull(ImageXObject.fromEncodedImage(ByteArray(0)))
    }
}
