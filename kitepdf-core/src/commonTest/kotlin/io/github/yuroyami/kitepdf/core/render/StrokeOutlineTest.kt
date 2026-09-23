package io.github.yuroyami.kitepdf.core.render

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [strokeOutline] covers what a stroke paints, and nothing else. */
class StrokeOutlineTest {

    private fun path(block: KitePath.Builder.() -> Unit) = KitePath.Builder().apply(block).build()

    private val line = path { moveTo(0.0, 0.0); lineTo(10.0, 0.0) }

    /** Two lines that meet at a right angle at (10, 0). */
    private val corner = path { moveTo(0.0, 0.0); lineTo(10.0, 0.0); lineTo(10.0, 10.0) }

    @Test
    fun a_line_covers_its_width_and_no_more() {
        val o = line.strokeOutline(2.0)
        assertTrue(o.contains(5.0, 0.9))
        assertTrue(o.contains(5.0, -0.9))
        assertFalse(o.contains(5.0, 1.1))
        assertFalse(o.contains(-0.1, 0.0), "a butt cap ends at the end point")
    }

    @Test
    fun caps_extend_the_ends() {
        assertTrue(line.strokeOutline(2.0, lineCap = 1).contains(-0.9, 0.0))
        assertFalse(line.strokeOutline(2.0, lineCap = 1).contains(-0.8, 0.8), "a round cap is round")
        assertTrue(line.strokeOutline(2.0, lineCap = 2).contains(-0.8, 0.8))
        assertTrue(line.strokeOutline(2.0, lineCap = 2).contains(10.8, -0.8))
    }

    @Test
    fun joins_fill_the_outer_corner() {
        // The outer corner of the turn at (10, 0) is the square from (10, -1) to (11, 0).
        assertTrue(corner.strokeOutline(2.0, lineJoin = 0).contains(10.9, -0.9), "a miter reaches the corner")
        assertFalse(corner.strokeOutline(2.0, lineJoin = 2).contains(10.9, -0.9), "a bevel cuts the corner")
        assertTrue(corner.strokeOutline(2.0, lineJoin = 2).contains(10.4, -0.4))
        assertTrue(corner.strokeOutline(2.0, lineJoin = 1).contains(10.6, -0.6))
        assertFalse(corner.strokeOutline(2.0, lineJoin = 1).contains(10.9, -0.9), "a round join is round")
    }

    @Test
    fun a_miter_past_the_limit_becomes_a_bevel() {
        // A right angle has a miter 1.414 times the width.
        assertFalse(corner.strokeOutline(2.0, lineJoin = 0, miterLimit = 1.4).contains(10.9, -0.9))
        assertTrue(corner.strokeOutline(2.0, lineJoin = 0, miterLimit = 1.5).contains(10.9, -0.9))
    }

    @Test
    fun a_closed_square_strokes_its_edges_and_not_its_inside() {
        val square = path { moveTo(0.0, 0.0); lineTo(10.0, 0.0); lineTo(10.0, 10.0); lineTo(0.0, 10.0); close() }
        val o = square.strokeOutline(2.0)
        assertTrue(o.contains(0.0, 5.0), "the closing edge is stroked")
        assertTrue(o.contains(-0.9, -0.9), "the start corner is joined, not capped")
        assertFalse(o.contains(5.0, 5.0))
        assertFalse(o.contains(-1.1, 5.0))
    }

    @Test
    fun dashes_leave_gaps() {
        val o = line.strokeOutline(2.0, dashArray = listOf(2.0, 2.0))
        assertTrue(o.contains(1.0, 0.0))
        assertFalse(o.contains(3.0, 0.0))
        assertTrue(o.contains(5.0, 0.0))
        // The phase shifts the pattern: the first dash now covers only 0 to 1.
        val shifted = line.strokeOutline(2.0, dashArray = listOf(2.0, 2.0), dashPhase = 1.0)
        assertTrue(shifted.contains(0.5, 0.0))
        assertFalse(shifted.contains(2.0, 0.0))
        assertTrue(shifted.contains(3.5, 0.0))
    }

    @Test
    fun zero_length_dashes_with_round_caps_are_dots() {
        val o = line.strokeOutline(2.0, lineCap = 1, dashArray = listOf(0.0, 4.0))
        assertTrue(o.contains(0.0, 0.0))
        assertTrue(o.contains(4.0, 0.9))
        assertFalse(o.contains(2.0, 0.0))
        assertTrue(line.strokeOutline(2.0, dashArray = listOf(0.0, 4.0)).isEmpty(), "butt caps give zero-length dashes no area")
    }

    @Test
    fun a_degenerate_subpath_is_a_dot_only_with_round_caps() {
        val point = path { moveTo(5.0, 5.0); lineTo(5.0, 5.0) }
        assertTrue(point.strokeOutline(2.0, lineCap = 1).contains(5.9, 5.0))
        assertTrue(point.strokeOutline(2.0, lineCap = 0).isEmpty())
        assertTrue(point.strokeOutline(2.0, lineCap = 2).isEmpty())
    }

    @Test
    fun a_curve_strokes_along_the_curve() {
        // A circle of radius 10 from four quarter arcs, stroked 2 wide: the band from 9 to 11.
        val k = 0.5522847498307936 * 10
        val circle = path {
            moveTo(10.0, 0.0)
            curveTo(10.0, k, k, 10.0, 0.0, 10.0)
            curveTo(-k, 10.0, -10.0, k, -10.0, 0.0)
            curveTo(-10.0, -k, -k, -10.0, 0.0, -10.0)
            curveTo(k, -10.0, 10.0, -k, 10.0, 0.0)
            close()
        }
        val o = circle.strokeOutline(2.0, tolerance = 0.01)
        val d = 10.0 / kotlin.math.sqrt(2.0)
        assertTrue(o.contains(d + 0.9 / kotlin.math.sqrt(2.0), d + 0.9 / kotlin.math.sqrt(2.0)))
        assertFalse(o.contains(d + 1.1 / kotlin.math.sqrt(2.0), d + 1.1 / kotlin.math.sqrt(2.0)))
        assertFalse(o.contains(0.0, 0.0))
        assertFalse(o.contains(8.8, 0.0))
    }

    @Test
    fun a_width_of_zero_or_less_gives_nothing() {
        assertTrue(line.strokeOutline(0.0).isEmpty())
        assertTrue(line.strokeOutline(-1.0).isEmpty())
    }

    @Test
    fun a_negative_dash_length_draws_solid() {
        assertTrue(line.strokeOutline(2.0, dashArray = listOf(2.0, -2.0)).contains(3.0, 0.0))
    }

    @Test
    fun a_sharp_turn_leaves_no_hole_where_the_pieces_overlap() {
        // The two legs of a narrow V overlap near its tip. Every piece winds the same way,
        // so the nonzero rule fills the overlap instead of cancelling it.
        val v = path { moveTo(0.0, 0.0); lineTo(10.0, 1.0); lineTo(0.0, 2.0) }
        val o = v.strokeOutline(2.0, lineJoin = 2)
        assertTrue(o.contains(8.0, 1.0))
        assertTrue(o.contains(5.0, 1.0))
    }

    /** True when (x, y) is inside the path by the nonzero rule. Curves are split into short lines. */
    private fun KitePath.contains(x: Double, y: Double): Boolean {
        var winding = 0
        var cx = 0.0
        var cy = 0.0
        var sx = 0.0
        var sy = 0.0
        fun edge(x0: Double, y0: Double, x1: Double, y1: Double) {
            if (y0 <= y && y1 > y && (x1 - x0) * (y - y0) - (x - x0) * (y1 - y0) > 0) winding++
            if (y0 > y && y1 <= y && (x1 - x0) * (y - y0) - (x - x0) * (y1 - y0) < 0) winding--
        }
        for (s in segments) when (s) {
            is KitePath.Segment.MoveTo -> { edge(cx, cy, sx, sy); cx = s.x; cy = s.y; sx = s.x; sy = s.y }
            is KitePath.Segment.LineTo -> { edge(cx, cy, s.x, s.y); cx = s.x; cy = s.y }
            is KitePath.Segment.CurveTo -> {
                var px = cx
                var py = cy
                for (i in 1..64) {
                    val t = i / 64.0
                    val u = 1 - t
                    val nx = u * u * u * cx + 3 * u * u * t * s.x1 + 3 * u * t * t * s.x2 + t * t * t * s.x3
                    val ny = u * u * u * cy + 3 * u * u * t * s.y1 + 3 * u * t * t * s.y2 + t * t * t * s.y3
                    edge(px, py, nx, ny)
                    px = nx; py = ny
                }
                cx = s.x3; cy = s.y3
            }
            is KitePath.Segment.QuadTo -> { edge(cx, cy, s.x2, s.y2); cx = s.x2; cy = s.y2 }
            KitePath.Segment.Close -> { edge(cx, cy, sx, sy); cx = sx; cy = sy }
        }
        edge(cx, cy, sx, sy)
        return winding != 0
    }
}
