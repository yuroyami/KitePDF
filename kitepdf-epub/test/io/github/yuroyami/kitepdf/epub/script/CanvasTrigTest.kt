package io.github.yuroyami.kitepdf.epub.script

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A canvas rotates by the sine and cosine that Chromium gives. mingw-w64's x87 `cos` gave
 * 6.1230318e-17 for the double nearest pi/2, so CanvasContextTest differed only on Windows.
 */
class CanvasTrigTest {

    @Test
    fun a_quarter_turn_has_the_cosine_of_the_double_nearest_it() {
        // cos(fl(pi/2)) is pi/2 - fl(pi/2), whatever the platform's own cos says.
        assertEquals(1.0 to 6.123233995736766e-17, canvasSinCos(PI / 2))
        assertEquals(1.2246467991473532e-16 to -1.0, canvasSinCos(PI))
        assertEquals(-1.0 to -1.8369701987210297e-16, canvasSinCos(3 * PI / 2))
        assertEquals(-1.0 to 6.123233995736766e-17, canvasSinCos(-PI / 2))
        assertEquals(-2.4492935982947064e-16 to 1.0, canvasSinCos(2 * PI))
    }

    @Test
    fun other_angles_agree_with_the_platform() {
        // The x87 functions err near a multiple of pi/2 by about 1e-20, so the bound is absolute; against
        // glibc the reduction differs by at most one unit in the last place.
        var a = -1000.0
        while (a < 1000.0) {
            val (s, c) = canvasSinCos(a)
            assertTrue(abs(s - sin(a)) <= 4e-16, "sin($a): $s, not ${sin(a)}")
            assertTrue(abs(c - cos(a)) <= 4e-16, "cos($a): $c, not ${cos(a)}")
            a += 0.0137
        }
        assertEquals(sin(0.5) to cos(0.5), canvasSinCos(0.5))
        assertTrue(canvasSinCos(Double.NaN).first.isNaN())
    }
}
