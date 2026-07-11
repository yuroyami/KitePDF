package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.Matrix
import kotlin.test.Test
import kotlin.test.assertEquals

class MatrixTest {

    @Test
    fun identity_is_no_op() {
        val p = Matrix.IDENTITY.transformPoint(10.0, 20.0)
        assertEquals(10.0, p.first)
        assertEquals(20.0, p.second)
    }

    @Test
    fun translation_moves_point() {
        val m = Matrix.translation(5.0, 7.0)
        val (x, y) = m.transformPoint(0.0, 0.0)
        assertEquals(5.0, x)
        assertEquals(7.0, y)
    }

    @Test
    fun scaling_grows_point() {
        val m = Matrix.scaling(2.0, 3.0)
        val (x, y) = m.transformPoint(4.0, 5.0)
        assertEquals(8.0, x)
        assertEquals(15.0, y)
    }

    @Test
    fun concat_applies_operand_then_current() {
        // PDF `cm` operand: scale by 2 first, then translate by (10, 20).
        // Starting CTM is identity; new CTM should map (1,1) → (2,2)+? no wait,
        // cm semantics: new = operand × current — so first the operand is
        // applied (scale here), then the previously-current (identity).
        val current = Matrix.IDENTITY
        val operand = Matrix.scaling(2.0, 2.0)
        val result = current.concat(operand)
        val (x, y) = result.transformPoint(3.0, 4.0)
        assertEquals(6.0, x)
        assertEquals(8.0, y)
    }

    @Test
    fun scaleX_extracts_x_component() {
        // [2 0 0 5 0 0] → scaleX = 2, scaleY = 5
        val m = Matrix(2.0, 0.0, 0.0, 5.0, 0.0, 0.0)
        assertEquals(2.0, m.scaleX())
        assertEquals(5.0, m.scaleY())
    }
}
