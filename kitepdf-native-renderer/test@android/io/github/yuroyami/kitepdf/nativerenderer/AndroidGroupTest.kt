package io.github.yuroyami.kitepdf.nativerenderer

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Transparency groups on the Android canvas (ISO 32000-1, 11.4.5): a group's alpha and blend
 * mode apply once, to the whole group, so two overlapping red squares in a group look like one
 * shape (#77). A non-isolated group and a knockout group go through the raster step (#308). The
 * expected pixels are mutool's for the same pages, as in `Canvas2dGroupTest` (#893).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29, 35])
class AndroidGroupTest {

    /** A light blue page, then a group of two overlapping red squares drawn under the graphics state [state]. */
    private fun page(state: String) = testPage(
        "0.5 0.5 1 rg 0 0 200 200 re f /GS1 gs /Fm1 Do",
        "/ExtGState << /GS1 << $state >> >> /XObject << /Fm1 5 0 R >>",
        listOf(groupForm("1 0 0 rg 40 40 80 80 re f 80 80 80 80 re f")),
    )

    @Test
    fun a_group_at_half_alpha_is_no_darker_where_its_shapes_overlap() {
        val bitmap = renderOnBitmap(page("/ca 0.5"))
        bitmap.assertPixel(60, 60, listOf(191, 64, 128))
        bitmap.assertPixel(100, 100, listOf(191, 64, 128))
        bitmap.assertPixel(10, 10, listOf(127, 127, 255))
    }

    @Test
    fun a_multiply_group_multiplies_onto_the_page() {
        val bitmap = renderOnBitmap(page("/BM /Multiply"))
        bitmap.assertPixel(60, 60, listOf(127, 0, 0))
        bitmap.assertPixel(100, 100, listOf(127, 0, 0))
    }

    @Test
    fun a_group_applies_its_alpha_and_blend_mode_together() {
        val bitmap = renderOnBitmap(page("/ca 0.5 /BM /Multiply"))
        bitmap.assertPixel(60, 60, listOf(127, 64, 128))
        bitmap.assertPixel(100, 100, listOf(127, 64, 128))
    }

    @Test
    fun a_non_isolated_group_takes_the_page_out_before_its_own_blend_mode() {
        // ISO 32000-1, 11.4.8: the yellow square multiplies onto the light green page inside
        // the group, and the group then composites in Screen without the page counted twice (#308).
        val bitmap = renderOnBitmap(
            testPage(
                "0.5 1 0.5 rg 0 0 200 200 re f /GS1 gs /Fm1 Do",
                "/ExtGState << /GS1 << /BM /Screen >> >> /XObject << /Fm1 5 0 R >>",
                listOf(nonIsolatedForm("/GM gs 1 1 0 rg 40 40 120 120 re f", "/ExtGState << /GM << /BM /Multiply >> >>")),
            ),
        )
        bitmap.assertPixel(100, 100, listOf(191, 255, 127))
        bitmap.assertPixel(10, 10, listOf(128, 255, 128))
    }

    @Test
    fun a_non_isolated_knockout_group_blends_each_object_with_the_page() {
        // ISO 32000-1, 11.4.6: the yellow square multiplies onto the green page, not onto the red
        // square it knocks out, so the overlap is green (#308).
        val bitmap = renderOnBitmap(
            testPage(
                "0 1 0 rg 0 0 200 200 re f /Fm1 Do",
                "/XObject << /Fm1 5 0 R >>",
                listOf(
                    nonIsolatedForm(
                        "1 0 0 rg 20 20 100 100 re f /GM gs 1 1 0 rg 80 80 100 100 re f",
                        "/ExtGState << /GM << /BM /Multiply >> >>", knockout = true,
                    ),
                ),
            ),
        )
        bitmap.assertPixel(100, 100, listOf(0, 255, 0))
        bitmap.assertPixel(50, 50, listOf(255, 0, 0))
        bitmap.assertPixel(150, 150, listOf(0, 255, 0))
    }

    @Test
    fun an_isolated_knockout_group_lets_each_paint_replace_the_last() {
        // ISO 32000-1, 11.4.6: the half-transparent blue square replaces the red one where they
        // overlap, so the page shows half blue over white there, not over red (#125).
        val content = "1 0 0 rg 20 20 100 100 re f /GA gs 0 0 1 rg 80 80 100 100 re f"
        val bitmap = renderOnBitmap(
            testPage(
                "/Fm1 Do",
                "/XObject << /Fm1 5 0 R >>",
                listOf(
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 $SIDE $SIDE] /Group << /S /Transparency /I true /K true >> " +
                        "/Resources << /ExtGState << /GA << /ca 0.5 >> >> >> /Length ${content.length} >>\nstream\n$content\nendstream",
                ),
            ),
        )
        bitmap.assertPixel(100, 100, listOf(128, 128, 255), tolerance = 4)
        bitmap.assertPixel(50, 50, listOf(255, 0, 0))
    }

    /** A form over the whole page that is a non-isolated transparency group, [knockout] or not. */
    private fun nonIsolatedForm(content: String, resources: String, knockout: Boolean = false): String =
        "<< /Type /XObject /Subtype /Form /BBox [0 0 $SIDE $SIDE] /Group << /S /Transparency /I false /K $knockout >> " +
            "/Resources << $resources >> /Length ${content.length} >>\nstream\n$content\nendstream"

    @Test
    fun an_image_in_a_group_takes_the_group_alpha() {
        // One red pixel drawn over the middle of the page, inside a group at half alpha.
        val bitmap = renderOnBitmap(
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
        bitmap.assertPixel(100, 100, listOf(255, 128, 128))
    }
}
