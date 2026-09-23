package io.github.yuroyami.kitepdf.nativerenderer

import kotlin.test.Test

/**
 * Shadings on Canvas2D: the /Extend flags decide whether an axial shading paints past
 * its ends (#70), and a radial shading under a stretched matrix paints an ellipse (#69).
 * The expected pixels are mutool's for the same pages.
 */
class Canvas2dShadingTest {

    /** A page that paints the shading [shading] with `sh` under the matrix [cm]. */
    private fun page(shading: String, cm: String = "") = testPage("$cm /Sh1 sh", "/Shading << /Sh1 5 0 R >>", listOf(shading))

    private val redToBlue = "<< /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >>"

    private fun axial(extend: Boolean) =
        "<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [50 0 150 0] /Function $redToBlue /Extend [$extend $extend] >>"

    @Test
    fun an_axial_shading_without_extend_paints_nothing_past_its_ends() {
        val ctx = renderOnCanvas(page(axial(extend = false)))
        ctx.assertPixel(20, 100, listOf(255, 255, 255))
        ctx.assertPixel(100, 100, listOf(126, 0, 128), tolerance = 4)
        ctx.assertPixel(180, 100, listOf(255, 255, 255))
    }

    @Test
    fun an_axial_shading_with_extend_pads_with_its_end_colours() {
        val ctx = renderOnCanvas(page(axial(extend = true)))
        ctx.assertPixel(20, 100, listOf(255, 0, 0))
        ctx.assertPixel(180, 100, listOf(0, 0, 255))
    }

    @Test
    fun a_radial_shading_under_a_stretched_matrix_paints_an_ellipse() {
        // A circle of radius 40, stretched twice as wide: 80 across, 40 up.
        val shading = "<< /ShadingType 3 /ColorSpace /DeviceRGB /Coords [50 100 0 50 100 40] /Function $redToBlue /Extend [false false] >>"
        val ctx = renderOnCanvas(page(shading, "2 0 0 1 0 0 cm"))
        ctx.assertPixel(160, 100, listOf(61, 0, 193), tolerance = 6)
        ctx.assertPixel(100, 160, listOf(255, 255, 255))
    }
}
