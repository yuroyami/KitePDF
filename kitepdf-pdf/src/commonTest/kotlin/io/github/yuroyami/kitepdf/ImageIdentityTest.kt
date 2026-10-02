package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteBitmapCache
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.imageSampling
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame

/** Converted images can outlive decoded images, without confusing documents or drawing states (#371). */
class ImageIdentityTest {

    private class Bitmaps {
        private val cache = KiteBitmapCache<ByteArray>()
        var conversions = 0
            private set

        fun convert(image: KiteImageData): ByteArray = cache.getOrPut(
            image,
            imageSampling(image.width, image.height, KiteMatrix.IDENTITY, false),
            { it.size.toLong() },
        ) {
            conversions++
            image.toRgbaBytes()
        }!!
    }

    private fun PdfDocument.images(): List<KiteImageData> = RecordingCanvas().also {
        pages[0].renderTo(it, KiteMatrix.IDENTITY)
    }.calls.filterIsInstance<RecordingCanvas.Call.Image>().map { it.image }

    private fun ordinaryImage(sample: String = "80"): ByteArray = TestPdf.onePage(
        "/Im1 Do",
        resources = "/XObject << /Im1 5 0 R >>",
        extra = listOf(TestPdf.stream(
            "$sample$sample>",
            "/Type /XObject /Subtype /Image /Width 2 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /ASCIIHexDecode",
        )),
    )

    @Test
    fun converted_pixels_survive_dropping_decoded_images() {
        val document = PdfDocument.open(ordinaryImage())
        val bitmaps = Bitmaps()
        val first = document.images().single()
        val expected = bitmaps.convert(first)
        document.dropDecodedImageCache()
        val second = document.images().single()
        assertNotSame(first, second, "the decoded image really was reconstructed")
        assertContentEquals(expected, bitmaps.convert(second))
        assertEquals(2, document.imageDecodeCount)
        assertEquals(1, bitmaps.conversions, "reconstruction must not convert the same pixels again")
    }

    @Test
    fun zero_or_too_small_decoded_budgets_keep_the_source_identity() {
        for (budget in listOf(0L, 1L)) {
            val document = PdfDocument.open(ordinaryImage()).also { it.imageCacheBudgetBytes = budget }
            val bitmaps = Bitmaps()
            repeat(3) { bitmaps.convert(document.images().single()) }
            assertEquals(0L, document.decodedImageBytes)
            assertEquals(3, document.imageDecodeCount)
            assertEquals(1, bitmaps.conversions, "budget $budget must not invalidate converted pixels")
        }
    }

    @Test
    fun equal_object_numbers_in_different_documents_do_not_share_pixels() {
        val bitmaps = Bitmaps()
        val dark = bitmaps.convert(PdfDocument.open(ordinaryImage("00")).images().single())
        val light = bitmaps.convert(PdfDocument.open(ordinaryImage("FF")).images().single())
        assertEquals(2, bitmaps.conversions)
        assertFalse(dark.contentEquals(light), "a reopened or edited document must have a fresh namespace")
    }

    @Test
    fun stencil_uses_keep_their_current_fill_color() {
        val document = PdfDocument.open(TestPdf.onePage(
            "1 0 0 rg /Im1 Do 0 0 1 rg /Im1 Do",
            resources = "/XObject << /Im1 5 0 R >>",
            extra = listOf(TestPdf.stream(
                "00>",
                "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ImageMask true /BitsPerComponent 1 /Filter /ASCIIHexDecode",
            )),
        ))
        val bitmaps = Bitmaps()
        val pixels = document.images().map(bitmaps::convert)
        assertEquals(2, pixels.size)
        assertEquals(2, bitmaps.conversions)
        assertContentEquals(byteArrayOf(-1, 0, 0, -1), pixels[0])
        assertContentEquals(byteArrayOf(0, 0, -1, -1), pixels[1])
    }

    @Test
    fun same_image_in_different_resource_defaults_does_not_share_pixels() {
        val document = PdfDocument.open(TestPdf.onePage(
            "/Im1 Do /Fm1 Do",
            resources = "/XObject << /Fm1 5 0 R /Im1 6 0 R >>",
            extra = listOf(
                TestPdf.stream(
                    "/Im1 Do",
                    "/Type /XObject /Subtype /Form /BBox [0 0 100 100] /Resources << " +
                        "/XObject << /Im1 6 0 R >> /ColorSpace << /DefaultGray " +
                        "[/CalGray << /WhitePoint [0.9505 1 1.089] /Gamma 1 >>] >> >>",
                ),
                TestPdf.stream(
                    "80>",
                    "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /ASCIIHexDecode",
                ),
            ),
        ))
        val bitmaps = Bitmaps()
        val pixels = document.images().map(bitmaps::convert)
        assertEquals(2, pixels.size)
        assertEquals(2, bitmaps.conversions)
        assertFalse(pixels[0].contentEquals(pixels[1]), "the form's calibrated grey must differ from device grey")
    }

    private val stencil = TestPdf.stream(
        "00>",
        "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ImageMask true /BitsPerComponent 1 /Filter /ASCIIHexDecode",
    )

    @Test
    fun a_stencil_drawn_again_in_the_same_colour_reuses_its_bitmap() {
        // Stencils are decoded on each draw, but their pixels only change with the fill colour (#465).
        val document = PdfDocument.open(TestPdf.onePage("1 0 0 rg /Im1 Do", resources = "/XObject << /Im1 5 0 R >>", extra = listOf(stencil)))
        val bitmaps = Bitmaps()
        repeat(2) { document.images().forEach(bitmaps::convert) }
        assertEquals(2, document.imageDecodeCount)
        assertEquals(1, bitmaps.conversions)
    }

    @Test
    fun an_image_with_a_mask_reuses_its_bitmap_across_draws() {
        // The ink layer of a scan with a /Mask is not kept decoded, but its pixels never change (#465).
        val document = PdfDocument.open(TestPdf.onePage(
            "/Im1 Do",
            resources = "/XObject << /Im1 5 0 R >>",
            extra = listOf(
                TestPdf.stream(
                    "80>",
                    "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8 " +
                        "/Mask 6 0 R /Filter /ASCIIHexDecode",
                ),
                stencil,
            ),
        ))
        val bitmaps = Bitmaps()
        repeat(2) { document.images().forEach(bitmaps::convert) }
        assertEquals(1, bitmaps.conversions)
    }

    @Test
    fun an_inline_image_in_a_device_space_reuses_its_bitmap() {
        val document = PdfDocument.open(TestPdf.onePage(
            "BI /W 1 /H 1 /CS /G /BPC 8 /F /AHx ID 80> EI BI /W 1 /H 1 /CS /G /BPC 8 /F /AHx ID 40> EI",
        ))
        val bitmaps = Bitmaps()
        repeat(2) { document.images().forEach(bitmaps::convert) }
        assertEquals(2, bitmaps.conversions, "two different inline images, each converted once")
    }

    @Test
    fun an_inline_image_in_a_resource_space_converts_on_each_draw() {
        // A named space can mean another thing in another resource dictionary, so it gets no shared bitmap.
        val document = PdfDocument.open(TestPdf.onePage(
            "BI /W 1 /H 1 /CS /CS0 /BPC 8 /F /AHx ID 80> EI",
            resources = "/ColorSpace << /CS0 /DeviceGray >>",
        ))
        val bitmaps = Bitmaps()
        repeat(2) { document.images().forEach(bitmaps::convert) }
        assertEquals(2, bitmaps.conversions)
    }
}
