package io.github.yuroyami.kitepdf.nativerenderer

import kotlin.test.Test

/**
 * An image on Canvas2D composites with the blend mode of the graphics state
 * (ISO 32000-1, 11.3.5, #113). A two by two image of red, green, blue and yellow
 * covers the middle of a light blue page. The expected pixels are mutool's.
 */
class Canvas2dImageBlendTest {

    private fun page(state: String): ByteArray = testPage(
        "0.5 0.5 1 rg 0 0 200 200 re f q /GS1 gs 100 0 0 100 50 50 cm /Im1 Do Q",
        "/ExtGState << /GS1 << $state >> >> /XObject << /Im1 5 0 R >>",
        listOf(
            "<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /ColorSpace /DeviceRGB /BitsPerComponent 8 " +
                "/Filter /ASCIIHexDecode /Length 25 >>\nstream\nFF000000FF000000FFFFFF00>\nendstream",
        ),
    )

    @Test
    fun an_image_multiplies_onto_the_page() {
        val ctx = renderOnCanvas(page("/BM /Multiply"))
        ctx.assertPixel(75, 125, listOf(127, 0, 0))
        ctx.assertPixel(125, 125, listOf(0, 127, 0))
        ctx.assertPixel(75, 75, listOf(0, 0, 255))
        ctx.assertPixel(125, 75, listOf(127, 127, 0))
    }

    @Test
    fun an_image_multiplies_at_its_alpha() {
        val ctx = renderOnCanvas(page("/ca 0.5 /BM /Multiply"))
        ctx.assertPixel(75, 125, listOf(127, 64, 128))
        ctx.assertPixel(125, 75, listOf(127, 127, 128))
    }
}
