package io.github.yuroyami.kitepdf.core.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [imageSampling] picks one sampling for every canvas, and [shrinkRgba] and [shrinkArgb] average an image down. */
class ImageSamplingTest {

    /** An image drawn [w] by [h] device pixels, upright. */
    private fun drawn(w: Double, h: Double) = KiteMatrix(w, 0.0, 0.0, h, 0.0, 0.0)

    @Test
    fun an_image_drawn_at_an_eighth_is_averaged_down_eight_times() {
        val s = imageSampling(256, 256, drawn(32.0, 32.0), interpolate = false)
        assertEquals(8, s.shrinkX)
        assertEquals(8, s.shrinkY)
        assertTrue(s.smooth)
        assertEquals(32, s.shrunkWidth(256))
    }

    @Test
    fun the_average_keeps_no_fewer_pixels_than_the_image_covers() {
        // 256 pixels over 40: halving three times would leave 32, fewer than 40.
        assertEquals(4, imageSampling(256, 256, drawn(40.0, 40.0), false).shrinkX)
        // Drawn at more than half its size, nothing is averaged.
        assertFalse(imageSampling(256, 256, drawn(129.0, 129.0), false).shrinks)
    }

    @Test
    fun each_direction_is_averaged_on_its_own() {
        val s = imageSampling(1000, 10, drawn(100.0, 40.0), false)
        assertEquals(8, s.shrinkX)
        assertEquals(1, s.shrinkY)
        // Rows are enlarged four times, so their pixels keep hard edges.
        assertFalse(s.smooth)
    }

    @Test
    fun an_image_enlarged_more_than_twice_keeps_hard_edges() {
        assertFalse(imageSampling(2, 2, drawn(160.0, 160.0), interpolate = false).smooth)
        // Even when rotated.
        val turn = KiteMatrix(160 * cos(PI / 6), 160 * sin(PI / 6), -160 * sin(PI / 6), 160 * cos(PI / 6), 0.0, 0.0)
        assertFalse(imageSampling(2, 2, turn, interpolate = false).smooth)
    }

    @Test
    fun the_interpolate_flag_smooths_a_large_enlargement() {
        assertTrue(imageSampling(2, 2, drawn(160.0, 160.0), interpolate = true).smooth)
    }

    @Test
    fun an_image_enlarged_up_to_twice_is_smoothed() {
        val s = imageSampling(100, 100, drawn(150.0, 150.0), false)
        assertTrue(s.smooth)
        assertFalse(s.shrinks)
    }

    @Test
    fun an_image_at_its_own_size_takes_the_nearest_pixel_unless_rotated() {
        assertFalse(imageSampling(100, 100, drawn(100.0, -100.0), false).smooth)
        // A quarter turn keeps pixels on the grid.
        assertFalse(imageSampling(100, 100, KiteMatrix(0.0, 100.0, -100.0, 0.0, 0.0, 0.0), false).smooth)
        val turn = KiteMatrix(100 * cos(PI / 6), 100 * sin(PI / 6), -100 * sin(PI / 6), 100 * cos(PI / 6), 0.0, 0.0)
        assertTrue(imageSampling(100, 100, turn, false).smooth)
    }

    @Test
    fun a_degenerate_matrix_changes_nothing() {
        for (m in listOf(drawn(0.0, 10.0), drawn(Double.NaN, 10.0), drawn(Double.POSITIVE_INFINITY, 10.0))) {
            val s = imageSampling(10, 10, m, false)
            assertFalse(s.shrinks)
            assertFalse(s.smooth)
        }
    }

    @Test
    fun shrinking_weights_colours_by_their_alpha() {
        // Opaque red beside transparent black: the average is red at half alpha, not dark red.
        val rgba = byteArrayOf(255.toByte(), 0, 0, 255.toByte(), 0, 0, 0, 0)
        val out = shrinkRgba(rgba, 2, 1, 2, 1)
        assertEquals(listOf(255, 0, 0, 128), out.map { it.toInt() and 0xFF })
    }

    @Test
    fun a_block_at_the_edge_averages_the_pixels_it_has() {
        // Three grey levels in a row, averaged by two: (0 + 100) / 2, then 200 alone.
        val rgba = byteArrayOf(0, 0, 0, 255.toByte(), 100, 100, 100, 255.toByte(), 200.toByte(), 200.toByte(), 200.toByte(), 255.toByte())
        val out = shrinkRgba(rgba, 3, 1, 2, 1).map { it.toInt() and 0xFF }
        assertEquals(listOf(50, 50, 50, 255, 200, 200, 200, 255), out)
    }

    @Test
    fun shrinking_argb_rows_matches_shrinking_rgba() {
        val width = 13
        val height = 11
        val argb = IntArray(width * height) { i -> (i * 2654435761L).toInt() }
        val rgba = ByteArray(width * height * 4)
        for (i in argb.indices) {
            val c = argb[i]
            rgba[i * 4] = (c shr 16).toByte()
            rgba[i * 4 + 1] = (c shr 8).toByte()
            rgba[i * 4 + 2] = c.toByte()
            rgba[i * 4 + 3] = (c ushr 24).toByte()
        }
        val fromRows = shrinkArgb(width, height, 4, 2) { pixels, y, rows ->
            argb.copyInto(pixels, 0, y * width, (y + rows) * width)
        }
        assertEquals(shrinkRgba(rgba, width, height, 4, 2).toList(), fromRows.toList())
    }
}
