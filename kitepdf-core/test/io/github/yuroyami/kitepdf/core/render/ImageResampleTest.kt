package io.github.yuroyami.kitepdf.core.render

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageResampleTest {
    @Test
    fun fractional_filtering_preserves_constant_colour_and_alpha_at_the_edges() {
        val pixel = byteArrayOf(20, 80, 180.toByte(), 77)
        val rgba = ByteArray(17 * 13 * 4) { pixel[it % 4] }
        for (weighted in listOf(true, false)) {
            val result = resampleRgba(rgba, 17, 13, 10, 8, weighted)
            assertContentEquals(ByteArray(10 * 8 * 4) { pixel[it % 4] }, result)
        }
    }

    @Test
    fun embedded_opacity_keeps_hidden_colours_out_of_visible_edges() {
        val rgba = ByteArray(5 * 3 * 4) { i ->
            val x = i / 4 % 5
            when (i % 4) {
                0, 3 -> if (x == 2) 255.toByte() else 0
                else -> 0
            }
        }
        val result = resampleRgba(rgba, 5, 3, 3, 2, alphaWeighted = true)
        for (p in result.indices step 4) {
            if (result[p + 3] != 0.toByte()) assertEquals(255, result[p].toInt() and 255)
            assertEquals(0, result[p + 1].toInt())
            assertEquals(0, result[p + 2].toInt())
        }
    }

    @Test
    fun a_line_between_destination_centres_contributes_to_both_neighbours() {
        val rgba = ByteArray(7 * 3 * 4) { i -> if (i % 4 == 3 || i / 4 % 7 != 3) 255.toByte() else 0 }
        val result = resampleRgba(rgba, 7, 3, 4, 2, alphaWeighted = true)
        assertEquals(255, result[0].toInt() and 255)
        assertTrue((result[4].toInt() and 255) in 128..220)
        assertEquals(result[4], result[8])
        assertEquals(255, result[12].toInt() and 255)
    }
}
