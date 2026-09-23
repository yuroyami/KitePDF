package io.github.yuroyami.kitepdf.nativerenderer

import kotlin.test.Test

/**
 * Soft masks on Canvas2D (ISO 32000-1, 11.6.5.2): the alpha or the luminosity of the
 * mask group gates a red square, and the mask group's own colours never reach the page
 * (#161). The expected pixels are mutool's for the same pages.
 */
class Canvas2dSoftMaskTest {

    /** A red square under a soft mask of [kind] whose group draws [mask] with [maskResources]. */
    private fun page(kind: String, mask: String, maskResources: String = "") = testPage(
        "q /GS1 gs 1 0 0 rg 20 20 160 160 re f Q",
        "/ExtGState << /GS1 << /SMask << /S /$kind /G 5 0 R >> >> >>",
        listOf(groupForm(mask, maskResources)),
    )

    @Test
    fun an_alpha_mask_shows_the_content_where_its_group_painted() {
        // The group paints blue on the left half. Blue must not reach the page.
        val ctx = renderOnCanvas(page("Alpha", "0 0 1 rg 0 0 100 200 re f"))
        ctx.assertPixel(60, 100, listOf(255, 0, 0))
        ctx.assertPixel(140, 100, listOf(255, 255, 255))
        ctx.assertPixel(10, 10, listOf(255, 255, 255))
    }

    @Test
    fun an_alpha_mask_takes_the_alpha_its_group_painted_with() {
        // Black at half alpha: the square shows at half strength, not darkened.
        val ctx = renderOnCanvas(page("Alpha", "/A gs 0 g 0 0 200 200 re f", "/ExtGState << /A << /ca 0.5 >> >>"))
        ctx.assertPixel(100, 100, listOf(255, 129, 129))
    }

    @Test
    fun a_luminosity_mask_shows_the_content_by_the_brightness_of_its_group() {
        val ctx = renderOnCanvas(page("Luminosity", "0.25 g 0 0 100 200 re f 1 g 100 0 100 200 re f"))
        ctx.assertPixel(60, 100, listOf(255, 192, 192))
        ctx.assertPixel(140, 100, listOf(255, 0, 0))
    }

    @Test
    fun a_luminosity_mask_hides_the_content_where_its_group_painted_nothing() {
        // Unpainted parts of the group show the black backdrop, whose luminosity is zero.
        val ctx = renderOnCanvas(page("Luminosity", "1 g 0 0 100 200 re f"))
        ctx.assertPixel(60, 100, listOf(255, 0, 0))
        ctx.assertPixel(140, 100, listOf(255, 255, 255))
    }
}
