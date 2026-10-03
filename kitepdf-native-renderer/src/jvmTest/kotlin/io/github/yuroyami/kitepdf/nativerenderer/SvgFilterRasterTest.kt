package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.svg.SvgImage
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SVG filters on AWT (Filter Effects 1, #209), checked against what the spec's formulas give.
 * Filters work in linearRGB unless `color-interpolation-filters` asks for sRGB, so a value that
 * a test computes in linear light goes back to sRGB before it is compared.
 */
class SvgFilterRasterTest {

    private fun render(svg: String, scale: Double = 1.0): BufferedImage {
        val image = assertNotNull(SvgImage.parse(svg.encodeToByteArray()), "the SVG parses")
        val w = (image.width * scale).toInt()
        val h = (image.height * scale).toInt()
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, w, h)
            g.clip = java.awt.Rectangle(0, 0, w, h)
            val canvas = AwtCanvas(g)
            canvas.beginPage(image.width, image.height, KiteMatrix.IDENTITY)
            image.render(canvas, KiteMatrix.scaling(scale, scale))
            canvas.endPage()
        } finally {
            g.dispose()
        }
        return out
    }

    private fun rgb(image: BufferedImage, x: Int, y: Int): List<Int> {
        val c = image.getRGB(x, y)
        return listOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    }

    private fun assertPixel(image: BufferedImage, x: Int, y: Int, r: Int, g: Int, b: Int, tolerance: Int = 3) {
        val actual = rgb(image, x, y)
        assertTrue(
            actual.zip(listOf(r, g, b)).all { (a, e) -> abs(a - e) <= tolerance },
            "pixel ($x, $y): expected ($r, $g, $b), got $actual",
        )
    }

    private fun svg(body: String) = """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">$body</svg>"""

    /** A filter over the whole image, so that nothing it moves or spreads is cut off. */
    private fun filter(primitives: String, attrs: String = "") =
        """<filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="100" height="100" $attrs>$primitives</filter>"""

    /** The sRGB level of a linear-light value from 0 to 1. */
    private fun srgb(linear: Double): Int = ((if (linear <= 0.0031308) linear * 12.92 else 1.055 * Math.pow(linear, 1 / 2.4) - 0.055) * 255).let { Math.round(it).toInt() }

    @Test
    fun a_flood_fills_the_filter_region() {
        val image = render(svg(
            """<filter id="f" filterUnits="userSpaceOnUse" x="20" y="20" width="40" height="40"><feFlood flood-color="#0f0"/></filter>""" +
                """<rect width="10" height="10" fill="#f00" filter="url(#f)"/>""",
        ))
        assertPixel(image, 40, 40, 0, 255, 0)
        // The element itself does not show, and nothing shows outside the region.
        assertPixel(image, 5, 5, 255, 255, 255)
        assertPixel(image, 70, 70, 255, 255, 255)
    }

    @Test
    fun an_offset_moves_the_element() {
        val image = render(svg(filter("""<feOffset dx="30" dy="10"/>""") + """<rect x="20" y="20" width="20" height="20" fill="#f00" filter="url(#f)"/>"""))
        assertPixel(image, 55, 35, 255, 0, 0)
        assertPixel(image, 25, 25, 255, 255, 255)
    }

    @Test
    fun a_blur_spreads_the_element_and_keeps_its_middle() {
        val image = render(svg(
            """<filter id="f"><feGaussianBlur stdDeviation="4"/></filter><rect x="30" y="30" width="40" height="40" fill="#00f" filter="url(#f)"/>""",
        ))
        assertPixel(image, 50, 50, 0, 0, 255)
        // About half the blue at the edge, some just outside it, and none past the region, which ends at 26.
        val edge = rgb(image, 30, 50)[0]
        assertTrue(edge in 90..140, "the edge is half covered, red level $edge")
        val outside = rgb(image, 28, 50)[0]
        assertTrue(outside in 141..250, "the blur reaches past the shape, red level $outside")
        assertPixel(image, 24, 50, 255, 255, 255)
    }

    @Test
    fun a_blur_scales_with_the_user_space() {
        val shape = svg("""<filter id="f"><feGaussianBlur stdDeviation="3"/></filter><rect x="30" y="30" width="40" height="40" fill="#00f" filter="url(#f)"/>""")
        val one = render(shape)
        val two = render(shape, scale = 2.0)
        for (x in listOf(27, 29, 31, 33)) {
            // Pixel x at one scale covers the two pixels 2x and 2x + 1 at two.
            val a = rgb(one, x, 50)[0]
            val b = (rgb(two, 2 * x, 101)[0] + rgb(two, 2 * x + 1, 101)[0]) / 2
            assertTrue(abs(a - b) <= 8, "x $x: $a at one scale, $b at two")
        }
    }

    @Test
    fun a_colour_matrix_works_in_linear_light_unless_told_otherwise() {
        val image = render(svg(
            """<filter id="l"><feColorMatrix type="saturate" values="0"/></filter>""" +
                """<filter id="s" color-interpolation-filters="sRGB"><feColorMatrix type="saturate" values="0"/></filter>""" +
                """<rect x="10" y="10" width="30" height="30" fill="#f00" filter="url(#l)"/>""" +
                """<rect x="60" y="10" width="30" height="30" fill="#f00" filter="url(#s)"/>""",
        ))
        // Red at saturation 0 keeps 0.213 of its value (15.11), in linear light or in sRGB.
        val linear = srgb(0.213)
        assertPixel(image, 25, 25, linear, linear, linear)
        assertPixel(image, 75, 25, 54, 54, 54)
    }

    @Test
    fun a_drop_shadow_lies_under_the_element() {
        val image = render(svg(
            filter("""<feDropShadow dx="10" dy="10" stdDeviation="0" flood-color="#00f"/>""") +
                """<rect x="20" y="20" width="20" height="20" fill="#f00" filter="url(#f)"/>""",
        ))
        assertPixel(image, 45, 45, 0, 0, 255)
        assertPixel(image, 35, 35, 255, 0, 0)
        assertPixel(image, 15, 15, 255, 255, 255)
    }

    @Test
    fun a_blend_multiplies_the_element_onto_a_flood() {
        val image = render(svg(
            filter("""<feFlood flood-color="#ff0" result="y"/><feBlend in="SourceGraphic" in2="y" mode="multiply"/>""") +
                """<rect x="20" y="20" width="20" height="20" fill="#0ff" filter="url(#f)"/>""",
        ))
        assertPixel(image, 30, 30, 0, 255, 0)
        assertPixel(image, 70, 70, 255, 255, 0)
    }

    @Test
    fun a_composite_cuts_the_element_out_of_a_flood() {
        val image = render(svg(
            filter("""<feFlood flood-color="#00f"/><feComposite in2="SourceGraphic" operator="out"/>""") +
                """<rect x="20" y="20" width="20" height="20" fill="#f00" filter="url(#f)"/>""",
        ))
        assertPixel(image, 30, 30, 255, 255, 255)
        assertPixel(image, 70, 70, 0, 0, 255)
    }

    @Test
    fun a_dilation_grows_the_element_by_its_radius() {
        val image = render(svg(filter("""<feMorphology operator="dilate" radius="5"/>""") + """<rect x="40" y="40" width="20" height="20" fill="#f00" filter="url(#f)"/>"""))
        assertPixel(image, 37, 50, 255, 0, 0)
        assertPixel(image, 33, 50, 255, 255, 255)
    }

    @Test
    fun a_component_transfer_inverts_through_a_table() {
        val image = render(svg(
            filter(
                """<feComponentTransfer><feFuncR type="table" tableValues="1 0"/><feFuncG type="table" tableValues="1 0"/>""" +
                    """<feFuncB type="table" tableValues="1 0"/></feComponentTransfer>""",
            ) + """<rect x="20" y="20" width="20" height="20" fill="#f00" filter="url(#f)"/>""",
        ))
        assertPixel(image, 30, 30, 0, 255, 255)
    }

    @Test
    fun a_tile_repeats_its_input() {
        val image = render(svg(
            filter(
                """<feFlood flood-color="#00f" x="0" y="0" width="10" height="10" result="b"/>""" +
                    """<feMerge x="0" y="0" width="20" height="20"><feMergeNode in="b"/></feMerge><feTile/>""",
            ) + """<rect width="10" height="10" filter="url(#f)"/>""",
        ))
        assertPixel(image, 5, 5, 0, 0, 255)
        assertPixel(image, 25, 5, 0, 0, 255)
        assertPixel(image, 45, 45, 0, 0, 255)
        assertPixel(image, 15, 5, 255, 255, 255)
        assertPixel(image, 35, 45, 255, 255, 255)
    }

    @Test
    fun a_distant_light_lights_a_flat_surface_by_the_sine_of_its_elevation() {
        val lit = """<feDiffuseLighting in="SourceAlpha" lighting-color="#fff"><feDistantLight azimuth="0" elevation="30"/></feDiffuseLighting>"""
        val image = render(svg(
            """<filter id="l">$lit</filter><filter id="s" color-interpolation-filters="sRGB">$lit</filter>""" +
                """<rect x="10" y="10" width="30" height="30" filter="url(#l)"/><rect x="60" y="10" width="30" height="30" filter="url(#s)"/>""",
        ))
        // The normal of a flat surface points up, so the light is sin 30° = 0.5 of its colour.
        val linear = srgb(0.5)
        assertPixel(image, 25, 25, linear, linear, linear)
        assertPixel(image, 75, 25, 128, 128, 128)
    }

    @Test
    fun turbulence_follows_the_reference_noise_of_the_spec() {
        val image = render(svg(
            filter(
                """<feTurbulence type="fractalNoise" baseFrequency="0.04" numOctaves="3" seed="11"/>""" +
                    """<feColorMatrix values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 0 1"/>""",
                attrs = """color-interpolation-filters="sRGB"""",
            ) + """<rect width="10" height="10" filter="url(#f)"/>""",
        ))
        // Measured in Chromium 140, which seeds the same generator. It keeps 8 bits of premultiplied colour, so a few levels differ.
        assertPixel(image, 10, 10, 165, 152, 122, tolerance = 12)
        assertPixel(image, 30, 70, 153, 85, 100, tolerance = 12)
        assertPixel(image, 50, 50, 110, 133, 120, tolerance = 12)
        assertPixel(image, 75, 25, 133, 114, 133, tolerance = 12)
        assertPixel(image, 90, 90, 161, 130, 150, tolerance = 12)
    }

    @Test
    fun the_functions_of_the_filter_property_work_in_srgb() {
        val image = render(svg(
            """<rect x="10" y="10" width="30" height="30" fill="#f00" style="filter: grayscale(1)"/>""" +
                """<rect x="60" y="10" width="30" height="30" fill="#f00" style="filter: opacity(50%)"/>""" +
                """<rect x="10" y="60" width="30" height="30" fill="#f00" style="filter: drop-shadow(5px 5px 0 #00f)"/>""",
        ))
        assertPixel(image, 25, 25, 54, 54, 54)
        assertPixel(image, 75, 25, 255, 128, 128)
        assertPixel(image, 42, 92, 0, 0, 255)
        assertPixel(image, 25, 75, 255, 0, 0)
    }

    @Test
    fun the_element_opacity_applies_after_its_filter() {
        // The matrix makes every pixel of the region opaque, so its black shows where the element did not paint.
        val image = render(svg(
            """<filter id="f"><feColorMatrix values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 0 1"/></filter>""" +
                """<rect x="20" y="20" width="60" height="60" fill="#f00" opacity="0.5" filter="url(#f)"/>""",
        ))
        assertPixel(image, 50, 50, 255, 128, 128)
        assertPixel(image, 16, 50, 128, 128, 128)
    }

    @Test
    fun an_feimage_draws_the_element_it_names_moved_to_its_subregion() {
        val image = render(svg(
            filter("""<feImage href="#dot" x="40" y="30"/>""") +
                """<defs><rect id="dot" width="10" height="10" fill="#0f0"/></defs><rect width="5" height="5" filter="url(#f)"/>""",
        ))
        assertPixel(image, 45, 35, 0, 255, 0)
        assertPixel(image, 5, 5, 255, 255, 255)
    }

    @Test
    fun a_filter_without_a_region_or_without_primitives_shows_nothing() {
        val image = render(svg(
            """<filter id="b"><feGaussianBlur stdDeviation="2"/></filter><filter id="e"/>""" +
                // A horizontal line has no height, so its bounding box gives an empty filter region.
                """<line x1="10" y1="20" x2="90" y2="20" stroke="#000" stroke-width="6" filter="url(#b)"/>""" +
                """<rect x="10" y="40" width="20" height="20" fill="#000" filter="url(#e)"/>""" +
                // A filter that names nothing is ignored.
                """<rect x="60" y="40" width="20" height="20" fill="#f00" filter="url(#missing)"/>""",
        ))
        assertPixel(image, 50, 20, 255, 255, 255)
        assertPixel(image, 20, 50, 255, 255, 255)
        assertPixel(image, 70, 50, 255, 0, 0)
    }
}
