package io.github.yuroyami.kitepdf.compose

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class ComposeDashTest {

    @Test
    fun dash_zeros_are_kept_and_odd_arrays_pair_up() {
        assertContentEquals(floatArrayOf(0f, 12f), composeDashIntervals(listOf(0.0, 12.0), 1.0), "a dotted line")
        assertContentEquals(floatArrayOf(12f, 0f), composeDashIntervals(listOf(12.0, 0.0), 1.0), "a solid line")
        assertContentEquals(floatArrayOf(6f, 6f), composeDashIntervals(listOf(3.0), 2.0), "an odd array repeats")
        assertNull(composeDashIntervals(listOf(0.0, 0.0), 1.0))
    }
}
