package io.github.yuroyami.kitepdf.core.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The parameter of a gradient between two circles at a point, for a canvas that draws its pixels (#413, #591). */
class TwoCircleParameterTest {

    @Test
    fun circles_with_one_centre_grow_with_the_distance() {
        // From radius 10 at s = 0 to 90 at s = 1, so a point 50 from the centre lies on s = 0.5.
        assertEquals(0.5, assertNotNull(twoCircleParameter(150.0, 100.0, 100.0, 100.0, 10.0, 100.0, 100.0, 90.0)), 1e-12)
    }

    @Test
    fun the_spotlight_runs_from_its_start_circle_to_its_end_circle() {
        // The spotlight of #591: a small circle at (70, 130) inside one of radius 85 at (100, 100).
        val spot = doubleArrayOf(70.0, 130.0, 5.0, 100.0, 100.0, 85.0)
        fun at(x: Double, y: Double) = assertNotNull(twoCircleParameter(x, y, spot[0], spot[1], spot[2], spot[3], spot[4], spot[5]))
        assertEquals(0.0, at(75.0, 130.0), 1e-12)
        assertEquals(0.30571, at(100.0, 100.0), 1e-5)
        assertEquals(1.0, at(185.0, 100.0), 1e-12)
    }

    @Test
    fun a_point_that_no_circle_reaches_has_none() {
        // Two circles apart, each outside the other: the cone they span misses a point beside it.
        assertNull(twoCircleParameter(100.0, 190.0, 50.0, 100.0, 20.0, 150.0, 100.0, 40.0))
    }
}
