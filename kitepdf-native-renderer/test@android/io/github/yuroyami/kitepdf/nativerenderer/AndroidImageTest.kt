package io.github.yuroyami.kitepdf.nativerenderer

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Images on the Android canvas, as `Canvas2dImageBlendTest` and `Canvas2dImageSamplingTest` check
 * them on Canvas2D (#893): an image composites in the blend mode of the graphics state
 * (ISO 32000-1, 11.3.5, #113), an image drawn small is averaged, and an enlarged one keeps hard
 * edges unless it asks for /Interpolate (8.9.5.3, #122, #123). The expected blends are mutool's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29, 35])
class AndroidImageTest {

    /** A two by two image of red, green, blue and yellow over the middle of a light blue page, under [state]. */
    private fun blendPage(state: String): ByteArray = testPage(
        "0.5 0.5 1 rg 0 0 200 200 re f q /GS1 gs 100 0 0 100 50 50 cm /Im1 Do Q",
        "/ExtGState << /GS1 << $state >> >> /XObject << /Im1 5 0 R >>",
        listOf(
            "<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /ColorSpace /DeviceRGB /BitsPerComponent 8 " +
                "/Filter /ASCIIHexDecode /Length 25 >>\nstream\nFF000000FF000000FFFFFF00>\nendstream",
        ),
    )

    @Test
    fun an_image_multiplies_onto_the_page() {
        val bitmap = renderOnBitmap(blendPage("/BM /Multiply"))
        bitmap.assertPixel(75, 125, listOf(127, 0, 0))
        bitmap.assertPixel(125, 125, listOf(0, 127, 0))
        bitmap.assertPixel(75, 75, listOf(0, 0, 255))
        bitmap.assertPixel(125, 75, listOf(127, 127, 0))
    }

    @Test
    fun an_image_multiplies_at_its_alpha() {
        val bitmap = renderOnBitmap(blendPage("/ca 0.5 /BM /Multiply"))
        bitmap.assertPixel(75, 125, listOf(127, 64, 128))
        bitmap.assertPixel(125, 75, listOf(127, 127, 128))
    }

    /** A page that draws one image, given as hex [samples], over the square of 40 points at the bottom left. */
    private fun imagePage(width: Int, height: Int, colorSpace: String, samples: String, interpolate: Boolean) = testPage(
        "q 40 0 0 40 0 0 cm /Im1 Do Q",
        "/XObject << /Im1 5 0 R >>",
        listOf(
            "<< /Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace $colorSpace " +
                "/BitsPerComponent 8 /Interpolate $interpolate /Filter /ASCIIHexDecode /Length ${samples.length + 1} >>\n" +
                "stream\n$samples>\nendstream",
        ),
    )

    /** Two columns of 2 by 2 pixels, red on the left and green on the right. */
    private fun redGreen(interpolate: Boolean) = imagePage(2, 2, "/DeviceRGB", "FF000000FF00FF000000FF00", interpolate)

    @Test
    fun single_pixel_squares_drawn_at_a_quarter_average_to_grey() {
        val board = buildString { for (i in 0 until 160 * 160) append(if ((i % 160 + i / 160) % 2 == 0) "00" else "FF") }
        val bitmap = renderOnBitmap(imagePage(160, 160, "/DeviceGray", board, interpolate = false))
        for (y in 2 until 38) for (x in 2 until 38) {
            val level = bitmap.rgbAt(x, y)[0]
            assertTrue(abs(level - 128) <= 10, "pixel ($x, $y) must be grey, got $level")
        }
    }

    @Test
    fun enlarged_pixels_keep_hard_edges() {
        val bitmap = renderOnBitmap(redGreen(interpolate = false))
        // Each image pixel covers 20 device pixels, so column 19 is red and column 20 green.
        bitmap.assertPixel(19, 29, listOf(255, 0, 0))
        bitmap.assertPixel(20, 29, listOf(0, 255, 0))
    }

    @Test
    fun the_interpolate_flag_blends_enlarged_pixels() {
        val (r, g) = renderOnBitmap(redGreen(interpolate = true)).rgbAt(19, 29)
        assertTrue(r < 230 && g > 25, "red and green blend at the edge, got ($r, $g)")
    }
}
