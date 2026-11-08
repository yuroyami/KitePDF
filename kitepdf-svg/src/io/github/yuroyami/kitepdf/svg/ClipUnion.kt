package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KitePath

/**
 * The union of clip regions, each under its own fill rule, as one path under the nonzero rule
 * (CSS Masking 1, 6.1; #731). A canvas clip intersects, so the regions cannot be pushed one by one,
 * and their contours joined as they are add up: two shapes wound in opposite directions cancel
 * where they overlap, and an even-odd hole fills.
 *
 * So each region's contours are oriented first. A nonzero region keeps its windings, turned over
 * as a whole when its total area runs the other way. An even-odd region gives each contour the
 * direction of its depth: one inside an even number of the region's other contours runs forward,
 * one inside an odd number, a hole, runs back. Under the nonzero rule the union then holds every
 * point some region holds, exactly for regions whose contours do not cross one another.
 */
internal object ClipUnion {

    private class Contour(val startX: Double, val startY: Double, val segments: List<KitePath.Segment>, val closed: Boolean) {
        /** The vertices: each segment's points, control points included, which is enough for a direction and a nesting test. */
        val points: List<Pair<Double, Double>> by lazy {
            val out = ArrayList<Pair<Double, Double>>()
            out += startX to startY
            for (s in segments) when (s) {
                is KitePath.Segment.LineTo -> out += s.x to s.y
                is KitePath.Segment.CurveTo -> { out += s.x1 to s.y1; out += s.x2 to s.y2; out += s.x3 to s.y3 }
                is KitePath.Segment.QuadTo -> { out += s.x1 to s.y1; out += s.x2 to s.y2 }
                else -> {}
            }
            out
        }

        val area: Double by lazy {
            var a = 0.0
            val p = points
            for (i in p.indices) {
                val (x1, y1) = p[i]
                val (x2, y2) = p[(i + 1) % p.size]
                a += x1 * y2 - x2 * y1
            }
            a / 2
        }

        fun contains(x: Double, y: Double): Boolean {
            var inside = false
            val p = points
            var j = p.size - 1
            for (i in p.indices) {
                val (xi, yi) = p[i]
                val (xj, yj) = p[j]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }
    }

    fun of(regions: List<Pair<KitePath, Boolean>>): KitePath {
        val out = KitePath.Builder()
        for ((path, evenOdd) in regions) {
            val contours = contoursOf(path)
            val total = contours.sumOf { it.area }
            for ((k, c) in contours.withIndex()) {
                val forward = if (evenOdd) {
                    val depth = contours.indices.count { j -> j != k && contours[j].contains(c.startX, c.startY) }
                    (c.area >= 0) == (depth % 2 == 0)
                } else {
                    total >= 0
                }
                emit(out, c, reversed = !forward)
            }
        }
        return out.build()
    }

    private fun contoursOf(path: KitePath): List<Contour> {
        val out = ArrayList<Contour>()
        var sx = 0.0
        var sy = 0.0
        var current: ArrayList<KitePath.Segment>? = null
        var closed = false
        fun finish() {
            current?.let { if (it.isNotEmpty()) out += Contour(sx, sy, it, closed) }
            current = null
            closed = false
        }
        for (s in path.segments) when (s) {
            is KitePath.Segment.MoveTo -> { finish(); sx = s.x; sy = s.y; current = ArrayList() }
            KitePath.Segment.Close -> { closed = true; finish() }
            else -> (current ?: ArrayList<KitePath.Segment>().also { current = it }).add(s)
        }
        finish()
        return out
    }

    private fun emit(out: KitePath.Builder, c: Contour, reversed: Boolean) {
        if (!reversed) {
            out.moveTo(c.startX, c.startY)
            for (s in c.segments) when (s) {
                is KitePath.Segment.LineTo -> out.lineTo(s.x, s.y)
                is KitePath.Segment.CurveTo -> out.curveTo(s.x1, s.y1, s.x2, s.y2, s.x3, s.y3)
                is KitePath.Segment.QuadTo -> out.quadTo(s.x1, s.y1, s.x2, s.y2)
                else -> {}
            }
            if (c.closed) out.close()
            return
        }
        // Each segment's start point, so the contour can run from its end back to its start.
        val starts = ArrayList<Pair<Double, Double>>()
        var x = c.startX
        var y = c.startY
        for (s in c.segments) {
            starts += x to y
            when (s) {
                is KitePath.Segment.LineTo -> { x = s.x; y = s.y }
                is KitePath.Segment.CurveTo -> { x = s.x3; y = s.y3 }
                is KitePath.Segment.QuadTo -> { x = s.x2; y = s.y2 }
                else -> {}
            }
        }
        out.moveTo(x, y)
        for (i in c.segments.indices.reversed()) {
            val (px, py) = starts[i]
            when (val s = c.segments[i]) {
                is KitePath.Segment.LineTo -> out.lineTo(px, py)
                is KitePath.Segment.CurveTo -> out.curveTo(s.x2, s.y2, s.x1, s.y1, px, py)
                is KitePath.Segment.QuadTo -> out.quadTo(s.x1, s.y1, px, py)
                else -> {}
            }
        }
        if (c.closed) out.close()
    }
}
