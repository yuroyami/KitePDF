package io.github.yuroyami.kitepdf.nativerenderer

import kotlin.test.Test

/**
 * Transparency groups on Canvas2D (ISO 32000-1, 11.4.5): a group's alpha and blend mode
 * apply once, to the whole group, so two overlapping red squares in a group look like one
 * shape (#77). The expected pixels are mutool's for the same pages.
 */
class Canvas2dGroupTest {

    /** A light blue page, then a group of two overlapping red squares drawn under the graphics state [state]. */
    private fun page(state: String) = testPage(
        "0.5 0.5 1 rg 0 0 200 200 re f /GS1 gs /Fm1 Do",
        "/ExtGState << /GS1 << $state >> >> /XObject << /Fm1 5 0 R >>",
        listOf(groupForm("1 0 0 rg 40 40 80 80 re f 80 80 80 80 re f")),
    )

    @Test
    fun a_group_at_half_alpha_is_no_darker_where_its_shapes_overlap() {
        val ctx = renderOnCanvas(page("/ca 0.5"))
        ctx.assertPixel(60, 60, listOf(191, 64, 128))
        ctx.assertPixel(100, 100, listOf(191, 64, 128))
        ctx.assertPixel(10, 10, listOf(127, 127, 255))
    }

    @Test
    fun a_multiply_group_multiplies_onto_the_page() {
        val ctx = renderOnCanvas(page("/BM /Multiply"))
        ctx.assertPixel(60, 60, listOf(127, 0, 0))
        ctx.assertPixel(100, 100, listOf(127, 0, 0))
    }

    @Test
    fun a_group_applies_its_alpha_and_blend_mode_together() {
        val ctx = renderOnCanvas(page("/ca 0.5 /BM /Multiply"))
        ctx.assertPixel(60, 60, listOf(127, 64, 128))
        ctx.assertPixel(100, 100, listOf(127, 64, 128))
    }

    @Test
    fun an_image_in_a_group_takes_the_group_alpha() {
        // One red pixel drawn over the middle of the page, inside a group at half alpha.
        val ctx = renderOnCanvas(
            testPage(
                "/GS1 gs /Fm1 Do",
                "/ExtGState << /GS1 << /ca 0.5 >> >> /XObject << /Fm1 5 0 R >>",
                listOf(
                    groupForm("q 100 0 0 100 50 50 cm /Im1 Do Q", "/XObject << /Im1 6 0 R >>"),
                    "<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB " +
                        "/BitsPerComponent 8 /Filter /ASCIIHexDecode /Length 7 >>\nstream\nFF0000>\nendstream",
                ),
            ),
        )
        ctx.assertPixel(100, 100, listOf(255, 128, 128))
    }
}
