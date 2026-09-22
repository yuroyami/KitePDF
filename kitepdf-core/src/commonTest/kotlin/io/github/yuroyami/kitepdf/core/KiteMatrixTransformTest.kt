package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Affine coordinate and numerical-compatibility checks for the scalar API (#185). */
class KiteMatrixTransformTest {
    @Test
    fun scalar_coordinates_cover_translation_rotation_reflection_and_shear() {
        checkPoint(KiteMatrix.IDENTITY, 4.0, -8.0, 4.0, -8.0)
        checkPoint(KiteMatrix.translation(13.0, -7.0), 4.0, -8.0, 17.0, -15.0)
        checkPoint(KiteMatrix.scaling(-3.0, 0.25), 4.0, -8.0, -12.0, -2.0)
        checkPoint(KiteMatrix(0.0, 1.0, -1.0, 0.0, 0.0, 0.0), 2.0, 3.0, -3.0, 2.0)
        checkPoint(KiteMatrix(2.0, 3.0, 4.0, 5.0, 6.0, 7.0), 8.0, 9.0, 58.0, 76.0)
    }

    @Test
    fun scalars_follow_composition_order_and_invert_back_to_page_space() {
        val matrix = KiteMatrix.translation(10.0, 20.0).concat(KiteMatrix.scaling(2.0, 3.0))
        checkPoint(matrix, 4.0, 5.0, 18.0, 35.0)
        val inverse = assertNotNull(matrix.invert())
        val x = matrix.transformX(4.0, 5.0)
        val y = matrix.transformY(4.0, 5.0)
        assertEquals(4.0, inverse.transformX(x, y), 1e-12)
        assertEquals(5.0, inverse.transformY(x, y), 1e-12)
    }

    @Test
    fun legacy_pair_signature_and_coordinate_values_remain_available() {
        val point: Pair<Double, Double> = KiteMatrix(2.0, 3.0, 4.0, 5.0, 6.0, 7.0).transformPoint(8.0, 9.0)
        assertEquals(58.0, point.first)
        assertEquals(76.0, point.second)
    }

    @Test
    fun arithmetic_order_keeps_cancellation_signed_zero_and_nonfinite_results() {
        // Adding the translation before cancelling the large terms loses this 1.
        val cancellation = KiteMatrix(1.0, 0.0, -1.0, 1.0, 1.0, 0.0)
        assertSameBits(1.0, cancellation.transformX(1e16, 1e16))
        val negativeZero = KiteMatrix(-0.0, -0.0, -0.0, -0.0, -0.0, -0.0)
        assertSameBits(-0.0, negativeZero.transformX(1.0, 1.0))
        assertSameBits(-0.0, negativeZero.transformY(1.0, 1.0))
        val values = doubleArrayOf(0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE, 1.0, -1.0, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN)
        val matrices = listOf(
            KiteMatrix.IDENTITY, negativeZero, cancellation,
            KiteMatrix(Double.MAX_VALUE, -Double.MAX_VALUE, -2.0, 2.0, Double.MIN_VALUE, -Double.MIN_VALUE),
            KiteMatrix(Double.POSITIVE_INFINITY, 1.0, 2.0, Double.NaN, 3.0, Double.NEGATIVE_INFINITY),
        )
        for (matrix in matrices) for (x in values) for (y in values) checkLegacyArithmetic(matrix, x, y)
    }

    @Test
    fun finite_affine_points_match_the_previous_arithmetic_bit_for_bit() {
        val random = Random(185)
        repeat(512) {
            fun coefficient(): Double = random.nextDouble(-1_000_000.0, 1_000_000.0)
            val matrix = KiteMatrix(coefficient(), coefficient(), coefficient(), coefficient(), coefficient(), coefficient())
            checkLegacyArithmetic(matrix, coefficient(), coefficient())
        }
    }

    private fun checkPoint(matrix: KiteMatrix, x: Double, y: Double, expectedX: Double, expectedY: Double) {
        assertEquals(expectedX, matrix.transformX(x, y))
        assertEquals(expectedY, matrix.transformY(x, y))
    }

    private fun checkLegacyArithmetic(matrix: KiteMatrix, x: Double, y: Double) {
        // Freeze the pre-#185 expression independently of transformPoint, which
        // now delegates to the scalar methods. This catches reassociation too.
        val expectedX = matrix.a * x + matrix.c * y + matrix.e
        val expectedY = matrix.b * x + matrix.d * y + matrix.f
        assertSameBits(expectedX, matrix.transformX(x, y))
        assertSameBits(expectedY, matrix.transformY(x, y))
        val paired = matrix.transformPoint(x, y)
        assertSameBits(expectedX, paired.first)
        assertSameBits(expectedY, paired.second)
    }

    private fun assertSameBits(expected: Double, actual: Double) {
        if (expected.isNaN()) assertTrue(actual.isNaN())
        else assertEquals(expected.toRawBits(), actual.toRawBits(), "expected $expected, actual $actual")
    }
}
