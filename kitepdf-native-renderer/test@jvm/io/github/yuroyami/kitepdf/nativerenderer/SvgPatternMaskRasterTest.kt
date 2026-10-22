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
 * SVG patterns (SVG 1.1, 13.3) and masks (14.4) on AWT, checked at pixel centres against
 * the geometry the spec gives (#209).
 */
class SvgPatternMaskRasterTest {

    private fun render(svg: String): BufferedImage {
        val image = assertNotNull(SvgImage.parse(svg.encodeToByteArray()), "the SVG parses")
        val out = BufferedImage(image.width.toInt(), image.height.toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, out.width, out.height)
            g.clip = java.awt.Rectangle(0, 0, out.width, out.height)
            val canvas = AwtCanvas(g)
            canvas.beginPage(image.width, image.height, KiteMatrix.IDENTITY)
            image.render(canvas, KiteMatrix.IDENTITY)
            canvas.endPage()
        } finally {
            g.dispose()
        }
        return out
    }

    private fun assertPixel(image: BufferedImage, x: Int, y: Int, r: Int, g: Int, b: Int) {
        val c = image.getRGB(x, y)
        val actual = listOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        assertTrue(
            actual.zip(listOf(r, g, b)).all { (a, e) -> abs(a - e) <= 6 },
            "pixel ($x, $y): expected ($r, $g, $b), got $actual",
        )
    }

    private fun svg(body: String) = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="100" height="100">$body</svg>"""

    /** A 20 by 20 tile with a red square at its top-left and a blue one at its bottom-right. */
    private fun checker(id: String, attrs: String = "") =
        """<pattern id="$id" width="20" height="20" patternUnits="userSpaceOnUse" $attrs>""" +
            """<rect width="10" height="10" fill="#f00"/><rect x="10" y="10" width="10" height="10" fill="#00f"/></pattern>"""

    @Test
    fun a_pattern_repeats_its_tile_over_the_shape() {
        val image = render(svg(checker("p") + """<rect width="100" height="100" fill="url(#p)"/>"""))
        assertPixel(image, 5, 5, 255, 0, 0)
        assertPixel(image, 15, 15, 0, 0, 255)
        assertPixel(image, 15, 5, 255, 255, 255)
        assertPixel(image, 85, 85, 255, 0, 0)
        assertPixel(image, 95, 95, 0, 0, 255)
    }

    @Test
    fun the_content_starts_at_the_top_left_of_each_tile() {
        // Tiles start at 5, so the tile before covers -15 to 5, and pixel 2 lies 17 into it, in blue.
        val image = render(svg(checker("p", """x="5" y="5"""") + """<rect width="100" height="100" fill="url(#p)"/>"""))
        assertPixel(image, 2, 2, 0, 0, 255)
        assertPixel(image, 10, 10, 255, 0, 0)
        assertPixel(image, 20, 20, 0, 0, 255)
    }

    @Test
    fun bounding_box_units_scale_the_tile_and_the_content_to_the_shape() {
        // The shape runs from 20 to 80, so the tiles are 30 wide from 20, each with a 15 wide square.
        val image = render(
            svg(
                """<pattern id="q" width="0.5" height="50%" patternContentUnits="objectBoundingBox">""" +
                    """<rect width="0.25" height="0.25" fill="#0f0"/></pattern>""" +
                    """<rect x="20" y="20" width="60" height="60" fill="url(#q)"/>""",
            ),
        )
        assertPixel(image, 25, 25, 0, 255, 0)
        assertPixel(image, 55, 55, 0, 255, 0)
        assertPixel(image, 40, 40, 255, 255, 255)
        assertPixel(image, 70, 25, 255, 255, 255)
    }

    @Test
    fun a_tile_clips_content_that_runs_past_it() {
        // The square runs from 15 to 25 in a 20 wide tile, so only 15 to 20 of it shows.
        val image = render(
            svg(
                """<pattern id="o" width="20" height="20" patternUnits="userSpaceOnUse"><rect x="15" width="10" height="5" fill="#f00"/></pattern>""" +
                    """<rect width="100" height="100" fill="url(#o)"/>""",
            ),
        )
        assertPixel(image, 17, 2, 255, 0, 0)
        assertPixel(image, 22, 2, 255, 255, 255)
        assertPixel(image, 37, 2, 255, 0, 0)
    }

    @Test
    fun a_view_box_maps_the_content_onto_the_tile_and_a_transform_moves_the_tiles() {
        val image = render(
            svg(
                """<pattern id="v" width="20" height="20" patternUnits="userSpaceOnUse" viewBox="0 0 2 2" patternTransform="translate(5 0)">""" +
                    """<rect width="1" height="1" fill="#f00"/></pattern>""" +
                    """<rect width="100" height="100" fill="url(#v)"/>""",
            ),
        )
        assertPixel(image, 10, 5, 255, 0, 0)
        assertPixel(image, 2, 5, 255, 255, 255)
        assertPixel(image, 10, 15, 255, 255, 255)
    }

    @Test
    fun a_pattern_takes_what_it_lacks_from_the_pattern_it_names_and_fades_as_one_fill() {
        val image = render(
            svg(
                checker("p") + """<pattern id="b" href="#p"/>""" +
                    """<rect width="100" height="100" fill="url(#b)" fill-opacity="0.5"/>""",
            ),
        )
        assertPixel(image, 5, 5, 255, 128, 128)
        assertPixel(image, 15, 15, 128, 128, 255)
    }

    @Test
    fun a_pattern_stroke_fills_the_outline_of_the_stroke() {
        val image = render(
            svg(checker("p") + """<line x1="0" y1="5" x2="100" y2="5" stroke="url(#p)" stroke-width="10"/>"""),
        )
        assertPixel(image, 5, 5, 255, 0, 0)
        assertPixel(image, 15, 5, 255, 255, 255)
        assertPixel(image, 5, 15, 255, 255, 255)
    }

    @Test
    fun a_pattern_inside_its_own_tile_falls_back_instead_of_repeating_without_end() {
        val image = render(
            svg(
                """<pattern id="n" width="20" height="20" patternUnits="userSpaceOnUse">""" +
                    """<rect width="10" height="10" fill="url(#n) #0f0"/></pattern>""" +
                    """<rect width="100" height="100" fill="url(#n)"/>""",
            ),
        )
        // The inner fill cannot use the pattern, so the rectangle draws in the colour it inherits, black.
        assertPixel(image, 5, 5, 0, 0, 0)
        assertPixel(image, 15, 15, 255, 255, 255)
    }

    @Test
    fun a_mask_shows_the_element_where_its_content_is_light() {
        val image = render(
            svg(
                """<mask id="m"><rect width="50" height="100" fill="white"/><rect x="50" width="50" height="100" fill="#808080"/></mask>""" +
                    """<rect width="100" height="100" fill="#00f" mask="url(#m)"/>""",
            ),
        )
        assertPixel(image, 25, 50, 0, 0, 255)
        // Mid grey passes about half the blue.
        assertPixel(image, 75, 50, 127, 127, 255)
    }

    @Test
    fun an_alpha_mask_uses_the_opacity_of_its_content() {
        val image = render(
            svg(
                """<mask id="a" style="mask-type: alpha"><rect width="100" height="100" fill="black" fill-opacity="0.5"/></mask>""" +
                    """<rect width="100" height="100" fill="#00f" mask="url(#a)"/>""",
            ),
        )
        assertPixel(image, 50, 50, 128, 128, 255)
    }

    @Test
    fun mask_units_place_the_region_and_the_content() {
        val image = render(
            svg(
                """<mask id="c" maskContentUnits="objectBoundingBox"><rect width="0.5" height="1" fill="white"/></mask>""" +
                    """<mask id="r" maskUnits="userSpaceOnUse" x="0" y="0" width="30" height="100"><rect width="100" height="100" fill="white"/></mask>""" +
                    """<rect x="20" width="60" height="50" fill="#00f" mask="url(#c)"/>""" +
                    """<rect y="50" width="100" height="50" fill="#f00" mask="url(#r)"/>""",
            ),
        )
        // The content is in fractions of the rectangle from 20 to 80, so it ends at 50.
        assertPixel(image, 30, 25, 0, 0, 255)
        assertPixel(image, 70, 25, 255, 255, 255)
        // The region clips a mask whose content covers everything.
        assertPixel(image, 20, 75, 255, 0, 0)
        assertPixel(image, 40, 75, 255, 255, 255)
    }

    @Test
    fun a_mask_attribute_holds_next_to_a_style_attribute() {
        val image = render(
            svg(
                """<mask id="m"><rect width="50" height="100" fill="white"/></mask>""" +
                    """<rect width="100" height="100" style="fill: #00f" mask="url(#m)"/>""",
            ),
        )
        assertPixel(image, 25, 50, 0, 0, 255)
        assertPixel(image, 75, 50, 255, 255, 255)
    }

    @Test
    fun a_mask_that_names_itself_ends() {
        val image = render(
            svg(
                """<mask id="s"><rect width="100" height="100" fill="white" mask="url(#s)"/></mask>""" +
                    """<rect width="100" height="100" fill="#00f" mask="url(#s)"/>""",
            ),
        )
        assertPixel(image, 50, 50, 0, 0, 255)
    }
}
