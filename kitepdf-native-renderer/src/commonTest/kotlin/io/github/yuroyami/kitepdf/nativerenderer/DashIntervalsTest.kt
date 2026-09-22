package io.github.yuroyami.kitepdf.nativerenderer

import kotlin.test.Test
import kotlin.test.assertContentEquals

class DashIntervalsTest {
    @Test
    fun a_single_dash_has_equal_on_and_off_intervals() {
        assertContentEquals(floatArrayOf(24f, 24f), scaledDashIntervals(listOf(12.0), 2.0))
    }

    @Test
    fun odd_arrays_repeat_to_preserve_alternating_on_and_off_lengths() {
        assertContentEquals(
            floatArrayOf(3f, 6f, 9f, 3f, 6f, 9f),
            scaledDashIntervals(listOf(1.0, 2.0, 3.0), 3.0),
        )
    }

    @Test
    fun even_and_solid_patterns_keep_their_original_cycle() {
        assertContentEquals(floatArrayOf(4f, 8f), scaledDashIntervals(listOf(2.0, 4.0), 2.0))
        assertContentEquals(floatArrayOf(), scaledDashIntervals(emptyList(), 2.0))
    }
}
