package io.github.yuroyami.kitepdf.nativerenderer

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Soft masks on the Android canvas (ISO 32000-1, 11.6.5.2): the alpha or the luminosity of the
 * mask group gates a red square, and the mask group's own colours never reach the page (#161).
 * The /BC backdrop and the /TR transfer function apply (#68), a nonlinear one through the mask
 * group's pixels (#445). The expected pixels are mutool's for the same pages, as in
 * `Canvas2dSoftMaskTest` (#893).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29, 35])
class AndroidSoftMaskTest {

    /**
     * A red square under a soft mask of [kind] whose group draws [mask] with [maskResources].
     * The mask dictionary also holds [entries].
     */
    private fun page(kind: String, mask: String, maskResources: String = "", entries: String = "") = testPage(
        "q /GS1 gs 1 0 0 rg 20 20 160 160 re f Q",
        "/ExtGState << /GS1 << /SMask << /S /$kind /G 5 0 R $entries >> >> >>",
        listOf(groupForm(mask, maskResources)),
    )

    /** A /TR entry that inverts each mask value, which a colour matrix gives. */
    private val inverter = "/TR << /FunctionType 2 /Domain [0 1] /C0 [1] /C1 [0] /N 1 >>"

    @Test
    fun a_luminosity_mask_shows_its_backdrop_colour_where_its_group_painted_nothing() {
        // A white backdrop lets the content through outside the box of the group (#68).
        val mask = "0 g 40 40 80 80 re f"
        val group = "<< /Type /XObject /Subtype /Form /BBox [40 40 120 120] /Group << /S /Transparency /CS /DeviceGray >> " +
            "/Length ${mask.length} >>\nstream\n$mask\nendstream"
        val bitmap = renderOnBitmap(
            testPage(
                "q /GS1 gs 1 0 0 rg 20 20 160 160 re f Q",
                "/ExtGState << /GS1 << /SMask << /S /Luminosity /BC [1] /G 5 0 R >> >> >>",
                listOf(group),
            ),
        )
        bitmap.assertPixel(30, 30, listOf(255, 0, 0))
        bitmap.assertPixel(150, 150, listOf(255, 0, 0))
        bitmap.assertPixel(80, 80, listOf(255, 255, 255))
        bitmap.assertPixel(10, 10, listOf(255, 255, 255))
    }

    @Test
    fun a_transfer_function_maps_each_luminosity_mask_value() {
        // The inverter hides the content where the group is white and shows it where the group painted nothing (#68).
        val bitmap = renderOnBitmap(page("Luminosity", "1 g 0 0 100 200 re f", entries = inverter))
        bitmap.assertPixel(60, 100, listOf(255, 255, 255))
        bitmap.assertPixel(140, 100, listOf(255, 0, 0))
    }

    @Test
    fun a_nonlinear_transfer_function_maps_each_alpha_mask_value() {
        // Squaring maps the half alpha of the group to a quarter. No colour matrix gives a square,
        // so the canvas gates by the mask group's pixels (#68, #445).
        val square = "/TR << /FunctionType 2 /Domain [0 1] /C0 [0] /C1 [1] /N 2 >>"
        val bitmap = renderOnBitmap(page("Alpha", "/A gs 0 g 0 0 200 200 re f", "/ExtGState << /A << /ca 0.5 >> >>", entries = square))
        bitmap.assertPixel(100, 100, listOf(255, 191, 191))
    }

    @Test
    fun an_alpha_mask_shows_the_content_where_its_group_painted() {
        // The group paints blue on the left half. Blue must not reach the page.
        val bitmap = renderOnBitmap(page("Alpha", "0 0 1 rg 0 0 100 200 re f"))
        bitmap.assertPixel(60, 100, listOf(255, 0, 0))
        bitmap.assertPixel(140, 100, listOf(255, 255, 255))
        bitmap.assertPixel(10, 10, listOf(255, 255, 255))
    }

    @Test
    fun an_alpha_mask_takes_the_alpha_its_group_painted_with() {
        // Black at half alpha: the square shows at half strength, not darkened.
        val bitmap = renderOnBitmap(page("Alpha", "/A gs 0 g 0 0 200 200 re f", "/ExtGState << /A << /ca 0.5 >> >>"))
        bitmap.assertPixel(100, 100, listOf(255, 129, 129))
    }

    @Test
    fun a_luminosity_mask_shows_the_content_by_the_brightness_of_its_group() {
        val bitmap = renderOnBitmap(page("Luminosity", "0.25 g 0 0 100 200 re f 1 g 100 0 100 200 re f"))
        bitmap.assertPixel(60, 100, listOf(255, 192, 192))
        bitmap.assertPixel(140, 100, listOf(255, 0, 0))
    }

    @Test
    fun a_luminosity_mask_hides_the_content_where_its_group_painted_nothing() {
        // Unpainted parts of the group show the black backdrop, whose luminosity is zero.
        val bitmap = renderOnBitmap(page("Luminosity", "1 g 0 0 100 200 re f"))
        bitmap.assertPixel(60, 100, listOf(255, 0, 0))
        bitmap.assertPixel(140, 100, listOf(255, 255, 255))
    }

    @Test
    fun a_luminosity_mask_group_painted_at_half_alpha_composites_over_black() {
        // ISO 32000-1, 11.6.5.2: the group composites over its black backdrop before its
        // luminosity is read, so white at half alpha gives a mask of half, not of one.
        val bitmap = renderOnBitmap(page("Luminosity", "/A gs 1 g 0 0 200 200 re f", "/ExtGState << /A << /ca 0.5 >> >>"))
        bitmap.assertPixel(100, 100, listOf(255, 128, 128))
    }

    @Test
    fun a_coloured_mask_group_gates_by_the_pdf_weights() {
        // ISO 32000-1, 10.3.2 weighs a colour 0.30 R + 0.59 G + 0.11 B (#799). A black square
        // under the mask leaves 255 * (1 - mask); Poppler 24.02 draws 178, 105 and 227.
        for ((color, expected) in listOf("1 0 0 rg" to 178, "0 1 0 rg" to 105, "0 0 1 rg" to 227)) {
            val bitmap = renderOnBitmap(
                testPage(
                    "q /GS1 gs 0 g 0 0 200 200 re f Q",
                    "/ExtGState << /GS1 << /SMask << /S /Luminosity /G 5 0 R >> >> >>",
                    listOf(groupForm("$color 0 0 200 200 re f")),
                ),
            )
            bitmap.assertPixel(100, 100, listOf(expected, expected, expected), tolerance = 2)
        }
    }
}
