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
 * SVG gradients on AWT, checked against the colours that SVG 1.1, 13.2 gives for
 * each pixel centre: the spread method (#179), stop opacity (#180) and gradient
 * strokes.
 */
class SvgGradientRasterTest {

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

    private val redToBlue = """<stop offset="0" stop-color="#f00"/><stop offset="1" stop-color="#00f"/>"""

    /** A 200 by 100 rectangle filled with a gradient that has [attrs] and [stops]. */
    private fun linear(attrs: String, stops: String = redToBlue) = render(
        """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100">
            <linearGradient id="g" $attrs>$stops</linearGradient>
            <rect width="200" height="100" fill="url(#g)"/></svg>""",
    )

    @Test
    fun repeat_starts_the_gradient_again() {
        // The gradient runs from x = 0 to x = 50. Pixel 62 has its centre a quarter into the second period.
        val image = linear("""x2="0.25" spreadMethod="repeat"""")
        assertPixel(image, 12, 50, 191, 0, 64)
        assertPixel(image, 62, 50, 191, 0, 64)
        assertPixel(image, 162, 50, 191, 0, 64)
    }

    @Test
    fun reflect_runs_the_gradient_back() {
        val image = linear("""x2="0.25" spreadMethod="reflect"""")
        assertPixel(image, 12, 50, 191, 0, 64)
        assertPixel(image, 62, 50, 64, 0, 191)
        assertPixel(image, 112, 50, 191, 0, 64)
    }

    @Test
    fun a_radial_gradient_repeats_in_rings() {
        val image = render(
            """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200">
                <radialGradient id="g" r="0.25" spreadMethod="repeat">$redToBlue</radialGradient>
                <rect width="200" height="200" fill="url(#g)"/></svg>""",
        )
        // Pixel 175 has its centre 75.5 from the centre: one and a half radii, halfway into the second ring.
        assertPixel(image, 175, 100, 125, 0, 130)
    }

    @Test
    fun stop_opacity_fades_the_gradient() {
        val image = linear("", """<stop offset="0" stop-color="#f00"/><stop offset="1" stop-color="#f00" stop-opacity="0"/>""")
        assertPixel(image, 0, 50, 255, 1, 1)
        assertPixel(image, 100, 50, 255, 128, 128)
        assertPixel(image, 199, 50, 255, 254, 254)
    }

    @Test
    fun the_alpha_of_a_stop_colour_fades_the_gradient() {
        val image = linear("", """<stop offset="0" stop-color="rgba(0, 0, 255, 0.5)"/><stop offset="1" stop-color="rgba(0, 0, 255, 0.5)"/>""")
        assertPixel(image, 100, 50, 128, 128, 255)
    }

    @Test
    fun a_gradient_stroke_paints_the_gradient_along_the_stroke() {
        val image = render(
            """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100">
                <linearGradient id="g">$redToBlue</linearGradient>
                <rect x="20" y="20" width="160" height="60" fill="none" stroke="url(#g)" stroke-width="10"/></svg>""",
        )
        // The gradient spans the rectangle itself, from x = 20 to x = 180.
        assertPixel(image, 20, 50, 254, 0, 1)
        assertPixel(image, 179, 50, 1, 0, 254)
        assertPixel(image, 100, 20, 128, 0, 128)
        assertPixel(image, 100, 50, 255, 255, 255)
    }
}
