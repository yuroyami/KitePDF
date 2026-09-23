package io.github.yuroyami.kitepdf.nativerenderer

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Images on Canvas2D, drawn smaller and larger than their pixels: an image drawn small
 * is averaged, and an enlarged one keeps hard edges unless it asks for /Interpolate
 * (#122, #123).
 */
class Canvas2dImageSamplingTest {

    /** A page that draws one image, given as hex [samples], over the square of 40 points at the bottom left. */
    private fun page(width: Int, height: Int, colorSpace: String, samples: String, interpolate: Boolean) = testPage(
        "q 40 0 0 40 0 0 cm /Im1 Do Q",
        "/XObject << /Im1 5 0 R >>",
        listOf(
            "<< /Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace $colorSpace " +
                "/BitsPerComponent 8 /Interpolate $interpolate /Filter /ASCIIHexDecode /Length ${samples.length + 1} >>\n" +
                "stream\n$samples>\nendstream",
        ),
    )

    /** Two columns of 2 by 2 pixels, red on the left and green on the right. */
    private fun redGreen(interpolate: Boolean) = page(2, 2, "/DeviceRGB", "FF000000FF00FF000000FF00", interpolate)

    @Test
    fun single_pixel_squares_drawn_at_a_quarter_average_to_grey() {
        val board = buildString { for (i in 0 until 160 * 160) append(if ((i % 160 + i / 160) % 2 == 0) "00" else "FF") }
        val ctx = renderOnCanvas(page(160, 160, "/DeviceGray", board, interpolate = false))
        for (y in 2 until 38) for (x in 2 until 38) {
            val data = ctx.getImageData(x.toDouble(), (SIDE - 1 - y).toDouble(), 1.0, 1.0).data.asDynamic()
            val level = data[0] as Int
            assertTrue(abs(level - 128) <= 10, "pixel ($x, $y) must be grey, got $level")
        }
    }

    @Test
    fun enlarged_pixels_keep_hard_edges() {
        val ctx = renderOnCanvas(redGreen(interpolate = false))
        // Each image pixel covers 20 device pixels, so column 19 is red and column 20 green.
        ctx.assertPixel(19, 29, listOf(255, 0, 0))
        ctx.assertPixel(20, 29, listOf(0, 255, 0))
    }

    @Test
    fun the_interpolate_flag_blends_enlarged_pixels() {
        val data = renderOnCanvas(redGreen(interpolate = true)).getImageData(19.0, (SIDE - 1 - 29).toDouble(), 1.0, 1.0).data.asDynamic()
        val r = data[0] as Int
        val g = data[1] as Int
        assertTrue(r < 230 && g > 25, "red and green blend at the edge, got ($r, $g)")
    }
}
