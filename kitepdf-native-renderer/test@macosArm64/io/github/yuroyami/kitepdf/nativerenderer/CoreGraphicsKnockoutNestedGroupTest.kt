package io.github.yuroyami.kitepdf.nativerenderer

import kotlin.test.Test

/**
 * A group nested in a knockout group replaces the earlier paints of its parent inside its shape
 * on CoreGraphics (ISO 32000-1, 11.4.6 and 11.4.4, #756), as `SkiaKnockoutGroupTest` checks on
 * Skia. A transparency layer composited over them, so a red square showed through a translucent
 * blue group as purple, where MuPDF shows pale blue.
 */
class CoreGraphicsKnockoutNestedGroupTest {

    private val OUTER = "1 0 0 rg 0 0 200 200 re f /GA gs /In Do"

    /** The page of #756: an isolated knockout group paints red, then [inner] at half alpha. */
    private fun page(inner: String): ByteArray = cgTestPage(
        "/Out Do", "/XObject << /Out 5 0 R >>",
        listOf(
            "<< /Type /XObject /Subtype /Form /BBox [0 0 200 200] /Group << /S /Transparency /I true /K true >> " +
                "/Resources << /ExtGState << /GA << /ca 0.5 >> >> /XObject << /In 6 0 R >> >> " +
                "/Length ${OUTER.length} >>\nstream\n$OUTER\nendstream",
            "<< /Type /XObject /Subtype /Form /BBox [0 0 200 200] /Group << /S /Transparency /I true >> " +
                "/Length ${inner.length} >>\nstream\n$inner\nendstream",
        ),
    )

    @Test
    fun a_nested_group_replaces_the_earlier_paints_under_its_shape() {
        val pixels = cgRender(page("0 0 1 rg 0 0 200 200 re f"))
        // The control: half-transparent blue over the white page, with no red under it.
        pixels.assertPixel(100, 100, listOf(127, 127, 255))
    }

    @Test
    fun the_earlier_paints_stay_where_the_nested_group_paints_nothing() {
        // The nested group covers the top half only. A bitmap read upside down would swap the halves.
        val pixels = cgRender(page("0 0 1 rg 0 100 200 100 re f"))
        pixels.assertPixel(100, 150, listOf(127, 127, 255))
        pixels.assertPixel(100, 50, listOf(255, 0, 0))
    }
}
