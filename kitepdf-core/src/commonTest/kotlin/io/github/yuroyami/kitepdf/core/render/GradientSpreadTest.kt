package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [spreadOver] continues a gradient past its ends by repeating or mirroring it. */
class GradientSpreadTest {

    private val redToBlue = KiteFunction.Type2(doubleArrayOf(0.0, 1.0), null, doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), 1.0)

    /** Red at x = 0 to blue at x = 10. */
    private val axial = KiteShading.Axial(
        KiteColorSpace.DeviceRGB, null, null, doubleArrayOf(0.0, 0.0, 10.0, 0.0), doubleArrayOf(0.0, 1.0), redToBlue, true, true,
    )

    /** Red at the centre (50, 50) to blue at radius 10. */
    private val radial = KiteShading.Radial(
        KiteColorSpace.DeviceRGB, null, null, doubleArrayOf(50.0, 50.0, 0.0, 50.0, 50.0, 10.0), doubleArrayOf(0.0, 1.0), redToBlue, true, true,
    )

    /** The red part of the colour at position [s] along the result. */
    private fun KiteShading.redAt(s: Double): Double {
        val function = when (this) {
            is KiteShading.Axial -> function
            is KiteShading.Radial -> function
            else -> error("not a gradient")
        }
        return function.evaluate(doubleArrayOf(s))[0]
    }

    private fun assertNear(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1e-9, "expected $expected, got $actual")

    @Test
    fun repeat_starts_the_gradient_again_past_each_end() {
        val s = axial.spreadOver(KiteGradientSpread.Repeat, KiteRectangle(-20.0, 0.0, 30.0, 10.0)) as KiteShading.Axial
        assertEquals(listOf(-2.0, 3.0), s.domain.toList())
        assertEquals(listOf(-20.0, 0.0, 30.0, 0.0), s.coords.toList())
        assertNear(0.75, s.redAt(0.25))
        assertNear(0.75, s.redAt(1.25))
        assertNear(0.75, s.redAt(2.25))
        assertNear(0.75, s.redAt(-0.75))
    }

    @Test
    fun reflect_runs_the_gradient_back_past_each_end() {
        val s = axial.spreadOver(KiteGradientSpread.Reflect, KiteRectangle(-20.0, 0.0, 30.0, 10.0))
        assertNear(0.75, s.redAt(0.25))
        assertNear(0.25, s.redAt(1.25))
        assertNear(0.75, s.redAt(2.25))
        assertNear(0.75, s.redAt(-0.25))
        assertNear(0.25, s.redAt(-1.25))
    }

    @Test
    fun a_radial_gradient_repeats_in_rings() {
        val s = radial.spreadOver(KiteGradientSpread.Repeat, KiteRectangle(0.0, 0.0, 100.0, 100.0)) as KiteShading.Radial
        // The far corner is 50 times the square root of 2 from the centre, 7.07 radii.
        assertNear(sqrt(2.0) * 5, s.domain[1])
        assertNear(sqrt(2.0) * 50, s.coords[5])
        assertEquals(0.0, s.domain[0])
        assertNear(0.5, s.redAt(1.5))
        assertNear(0.5, s.redAt(6.5))
    }

    @Test
    fun pad_and_a_region_inside_the_gradient_change_nothing() {
        assertSame(axial, axial.spreadOver(KiteGradientSpread.Pad, KiteRectangle(-20.0, 0.0, 30.0, 10.0)))
        assertSame(axial, axial.spreadOver(KiteGradientSpread.Repeat, KiteRectangle(2.0, 0.0, 8.0, 10.0)))
    }

    @Test
    fun a_focal_point_outside_the_circle_changes_nothing() {
        val cone = KiteShading.Radial(
            KiteColorSpace.DeviceRGB, null, null, doubleArrayOf(0.0, 50.0, 0.0, 50.0, 50.0, 10.0), doubleArrayOf(0.0, 1.0), redToBlue, true, true,
        )
        assertSame(cone, cone.spreadOver(KiteGradientSpread.Repeat, KiteRectangle(0.0, 0.0, 100.0, 100.0)))
    }

    @Test
    fun a_huge_region_keeps_the_periods_nearest_the_gradient() {
        val s = axial.spreadOver(KiteGradientSpread.Repeat, KiteRectangle(-10000.0, 0.0, 10000.0, 10.0)) as KiteShading.Axial
        assertEquals(MAX_SPREAD_PERIODS.toDouble(), s.domain[1] - s.domain[0])
        assertTrue(s.domain[0] < 0.0 && s.domain[1] > 1.0)
    }
}
