package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.difftest.ImageFixtures
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Images on AWT, drawn smaller and larger than their pixels, checked pixel by pixel
 * without mutool: an image drawn small is averaged, and an enlarged one keeps hard
 * edges unless it asks for /Interpolate (#122, #123).
 */
class ImageSamplingRasterTest {

    private fun render(name: String): BufferedImage =
        AwtPdfRasterizer.renderToImage(PdfDocument.open(ImageFixtures.all().single { it.name == name }.bytes).pages[0])

    private fun rgb(image: BufferedImage, x: Int, y: Int): List<Int> {
        val c = image.getRGB(x, y)
        return listOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    }

    @Test
    fun single_pixel_squares_drawn_at_a_quarter_average_to_grey() {
        val image = render("image-checkerboard-shrunk")
        // The image covers device pixels 68 to 131 in both directions.
        for (y in 70..129) for (x in 70..129) {
            val level = rgb(image, x, y)[0]
            assertTrue(abs(level - 128) <= 8, "pixel ($x, $y) must be grey, got $level")
        }
    }

    @Test
    fun thin_lines_drawn_at_a_quarter_fade_to_grey() {
        val image = render("image-thin-lines-shrunk")
        val column = (70..129).map { y -> rgb(image, 100, y)[0] }
        assertTrue(column.all { it > 150 }, "no line may stay black: $column")
        assertTrue(column.count { it < 240 } >= 25, "every line must leave some grey: $column")
    }

    @Test
    fun enlarged_pixels_keep_hard_edges() {
        val image = render("image-enlarged-hard-edges")
        // Red covers device columns 20 to 99 of the top half, green 100 to 179.
        assertTrue(rgb(image, 99, 60).zip(listOf(255, 0, 0)).all { (a, e) -> abs(a - e) <= 2 }, "red up to the edge: ${rgb(image, 99, 60)}")
        assertTrue(rgb(image, 100, 60).zip(listOf(0, 255, 0)).all { (a, e) -> abs(a - e) <= 2 }, "green from the edge: ${rgb(image, 100, 60)}")
    }

    @Test
    fun the_interpolate_flag_blends_enlarged_pixels() {
        val (r, g, _) = rgb(render("image-interpolate"), 99, 60)
        assertTrue(r < 200 && g > 50, "red and green blend at the edge, got ($r, $g)")
    }
}
