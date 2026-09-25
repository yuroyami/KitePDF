package io.github.yuroyami.kitepdf.core.render

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** [gridFitImage] moves image edges outwards onto whole pixels, as MuPDF's fz_gridfit_matrix does (#300). */
class ImageGridFitTest {

    private fun assertMatrix(expected: KiteMatrix, actual: KiteMatrix) {
        for ((e, a) in listOf(expected.a to actual.a, expected.b to actual.b, expected.c to actual.c,
            expected.d to actual.d, expected.e to actual.e, expected.f to actual.f)) {
            assertEquals(e, a, 1e-9, "expected $expected, got $actual")
        }
    }

    @Test
    fun an_unrotated_image_covers_every_pixel_it_touches() {
        // x from 2.3 to 12.7 becomes 2 to 13, and y from 7.6 to 12.9 becomes 7 to 13.
        assertMatrix(KiteMatrix(11.0, 0.0, 0.0, 6.0, 2.0, 7.0), gridFitImage(KiteMatrix(10.4, 0.0, 0.0, 5.3, 2.3, 7.6)))
    }

    @Test
    fun a_flipped_image_grows_away_from_its_origin() {
        // The image runs up from y 20.25 to 14.75, so it grows to 21 and 14.
        assertMatrix(KiteMatrix(10.0, 0.0, 0.0, -7.0, 3.0, 21.0), gridFitImage(KiteMatrix(10.0, 0.0, 0.0, -5.5, 3.0, 20.25)))
    }

    @Test
    fun an_image_turned_by_90_degrees_fits_on_the_swapped_axes() {
        // y = b·u + f runs from 4.5 to 14.5, and x = c·v + e runs from 30.2 down to 20.2.
        assertMatrix(KiteMatrix(0.0, 11.0, -11.0, 0.0, 31.0, 4.0), gridFitImage(KiteMatrix(0.0, 10.0, -10.0, 0.0, 30.2, 4.5)))
    }

    @Test
    fun an_edge_within_a_thousandth_of_a_pixel_does_not_move() {
        assertMatrix(KiteMatrix(10.0, 0.0, 0.0, 10.0, 2.0, 5.0), gridFitImage(KiteMatrix(9.9997, 0.0, 0.0, 10.0004, 2.0004, 5.0)))
    }

    @Test
    fun a_whole_pixel_image_and_a_rotated_one_do_not_change() {
        val whole = KiteMatrix(8.0, 0.0, 0.0, -4.0, 1.0, 9.0)
        assertMatrix(whole, gridFitImage(whole))
        val turned = KiteMatrix(10 * cos(0.5), 10 * sin(0.5), -10 * sin(0.5), 10 * cos(0.5), 3.3, 4.4)
        assertSame(turned, gridFitImage(turned))
    }
}
