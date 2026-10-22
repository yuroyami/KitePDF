package io.github.yuroyami.kitepdf.epub.script

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KitePath.Segment
import io.github.yuroyami.kitepdf.core.render.strokeOutline
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A path of a canvas: the current path of a 2D context, or a `Path2D` (HTML, 4.12.5.1.5). Each
 * method takes the point through a matrix as it adds it, so the context path holds canvas pixels
 * and a `Path2D` holds its own units. A non-finite argument adds nothing, as HTML says.
 */
internal class CanvasPath {
    private val segments = ArrayList<Segment>()
    private var hasSubpath = false
    private var lastX = 0.0
    private var lastY = 0.0
    private var startX = 0.0
    private var startY = 0.0
    private var built: KitePath? = null

    val isEmpty: Boolean get() = segments.isEmpty()

    fun copy(): CanvasPath = CanvasPath().also { it.append(this, null) }

    fun clear() {
        segments.clear()
        hasSubpath = false
        built = null
    }

    /** The path as drawing code reads it. */
    fun path(): KitePath = built ?: KitePath(segments.toList()).also { built = it }

    private fun add(s: Segment) {
        segments.add(s)
        built = null
    }

    private fun moveDevice(x: Double, y: Double) {
        add(Segment.MoveTo(x, y))
        lastX = x; lastY = y; startX = x; startY = y
        hasSubpath = true
    }

    // A line of no length stays, since a round cap on it paints a dot.
    private fun lineDevice(x: Double, y: Double) {
        add(Segment.LineTo(x, y))
        lastX = x; lastY = y
    }

    private fun ensure(m: KiteMatrix, x: Double, y: Double) {
        if (!hasSubpath) moveDevice(m.transformX(x, y), m.transformY(x, y))
    }

    fun moveTo(m: KiteMatrix, x: Double, y: Double) {
        if (!finite(x, y)) return
        moveDevice(m.transformX(x, y), m.transformY(x, y))
    }

    fun lineTo(m: KiteMatrix, x: Double, y: Double) {
        if (!finite(x, y)) return
        if (!hasSubpath) moveTo(m, x, y) else lineDevice(m.transformX(x, y), m.transformY(x, y))
    }

    fun quadraticCurveTo(m: KiteMatrix, cx: Double, cy: Double, x: Double, y: Double) {
        if (!finite(cx, cy, x, y)) return
        ensure(m, cx, cy)
        val ex = m.transformX(x, y)
        val ey = m.transformY(x, y)
        add(Segment.QuadTo(m.transformX(cx, cy), m.transformY(cx, cy), ex, ey))
        lastX = ex; lastY = ey
    }

    fun bezierCurveTo(m: KiteMatrix, c1x: Double, c1y: Double, c2x: Double, c2y: Double, x: Double, y: Double) {
        if (!finite(c1x, c1y, c2x, c2y, x, y)) return
        ensure(m, c1x, c1y)
        curveDevice(m, c1x, c1y, c2x, c2y, x, y)
    }

    private fun curveDevice(m: KiteMatrix, c1x: Double, c1y: Double, c2x: Double, c2y: Double, x: Double, y: Double) {
        val ex = m.transformX(x, y)
        val ey = m.transformY(x, y)
        add(Segment.CurveTo(m.transformX(c1x, c1y), m.transformY(c1x, c1y), m.transformX(c2x, c2y), m.transformY(c2x, c2y), ex, ey))
        lastX = ex; lastY = ey
    }

    fun closePath() {
        if (!hasSubpath) return
        if (segments.last() is Segment.Close) return
        add(Segment.Close)
        lastX = startX; lastY = startY
    }

    /** `arcTo`: a line to the first tangent point, then the arc of radius [r] between both lines. [r] is not negative. */
    fun arcTo(m: KiteMatrix, x1: Double, y1: Double, x2: Double, y2: Double, r: Double) {
        if (!finite(x1, y1, x2, y2, r)) return
        ensure(m, x1, y1)
        val inverse = m.invert()
        if (inverse == null) { lineTo(m, x1, y1); return }
        val x0 = inverse.transformX(lastX, lastY)
        val y0 = inverse.transformY(lastX, lastY)
        val cross = (x0 - x1) * (y2 - y1) - (y0 - y1) * (x2 - x1)
        if ((x0 == x1 && y0 == y1) || (x1 == x2 && y1 == y2) || r == 0.0 || cross == 0.0) {
            lineTo(m, x1, y1)
            return
        }
        val ax = x0 - x1
        val ay = y0 - y1
        val bx = x2 - x1
        val by = y2 - y1
        val al = hypot(ax, ay)
        val bl = hypot(bx, by)
        val cosTheta = ((ax * bx + ay * by) / (al * bl)).coerceIn(-1.0, 1.0)
        val half = kotlin.math.acos(cosTheta) / 2.0
        val tangent = r / tan(half)
        val t1x = x1 + ax / al * tangent
        val t1y = y1 + ay / al * tangent
        val t2x = x1 + bx / bl * tangent
        val t2y = y1 + by / bl * tangent
        // The centre lies on the bisector, r / sin(half) from the corner.
        val mx = ax / al + bx / bl
        val my = ay / al + by / bl
        val ml = hypot(mx, my)
        val d = r / sin(half)
        val cx = x1 + mx / ml * d
        val cy = y1 + my / ml * d
        val a0 = atan2(t1y - cy, t1x - cx)
        val a1 = atan2(t2y - cy, t2x - cx)
        lineTo(m, t1x, t1y)
        var sweep = a1 - a0
        while (sweep > PI) sweep -= 2 * PI
        while (sweep < -PI) sweep += 2 * PI
        arcSegments(m, cx, cy, r, r, 0.0, a0, sweep)
    }

    /** `ellipse` and `arc`, whose radii are not negative. */
    fun ellipse(m: KiteMatrix, x: Double, y: Double, rx: Double, ry: Double, rotation: Double, start: Double, end: Double, anticlockwise: Boolean) {
        if (!finite(x, y, rx, ry, rotation, start, end)) return
        val sweep = sweepOf(start, end, anticlockwise)
        val cr = cos(rotation)
        val sr = sin(rotation)
        val px = rx * cos(start)
        val py = ry * sin(start)
        val sx = x + px * cr - py * sr
        val sy = y + px * sr + py * cr
        if (hasSubpath) lineDevice(m.transformX(sx, sy), m.transformY(sx, sy)) else moveTo(m, sx, sy)
        arcSegments(m, x, y, rx, ry, rotation, start, sweep)
    }

    /**
     * Cubic pieces along the ellipse, from angle [start] through [sweep]. A quarter turn strays
     * 2.7e-4 radii from the ellipse and the error falls with the sixth power of the angle, so a
     * large ellipse gets shorter pieces and stays within [ARC_ERROR] pixels, as `isPointInPath` needs.
     */
    private fun arcSegments(m: KiteMatrix, x: Double, y: Double, rx: Double, ry: Double, rotation: Double, start: Double, sweep: Double) {
        if (sweep == 0.0) return
        val radius = max(rx, ry) * max(hypot(m.a, m.b), hypot(m.c, m.d))
        val perQuarter = ceil((radius * 2.7e-4 / ARC_ERROR).pow(1.0 / 6.0)).coerceIn(1.0, 16.0)
        val n = max(1, ceil(abs(sweep) / (PI / 2) * perQuarter - 1e-9).toInt())
        val step = sweep / n
        val k = 4.0 / 3.0 * tan(step / 4.0)
        val cr = cos(rotation)
        val sr = sin(rotation)
        fun ux(a: Double) = rx * cos(a)
        fun uy(a: Double) = ry * sin(a)
        fun dx(a: Double) = -rx * sin(a)
        fun dy(a: Double) = ry * cos(a)
        var a = start
        for (i in 0 until n) {
            val b = if (i == n - 1) start + sweep else a + step
            val p1x = ux(a) + k * dx(a)
            val p1y = uy(a) + k * dy(a)
            val p2x = ux(b) - k * dx(b)
            val p2y = uy(b) - k * dy(b)
            val p3x = ux(b)
            val p3y = uy(b)
            curveDevice(
                m,
                x + p1x * cr - p1y * sr, y + p1x * sr + p1y * cr,
                x + p2x * cr - p2y * sr, y + p2x * sr + p2y * cr,
                x + p3x * cr - p3y * sr, y + p3x * sr + p3y * cr,
            )
            a = b
        }
    }

    fun rect(m: KiteMatrix, x: Double, y: Double, w: Double, h: Double) {
        if (!finite(x, y, w, h)) return
        moveTo(m, x, y)
        add(Segment.LineTo(m.transformX(x + w, y), m.transformY(x + w, y)))
        add(Segment.LineTo(m.transformX(x + w, y + h), m.transformY(x + w, y + h)))
        add(Segment.LineTo(m.transformX(x, y + h), m.transformY(x, y + h)))
        add(Segment.Close)
        moveTo(m, x, y)
    }

    /**
     * `roundRect` with radii already checked: one to four corners, each an x and a y radius, none
     * negative (HTML, 4.12.5.1.5). A radius that is not finite adds nothing.
     */
    fun roundRect(m: KiteMatrix, x0: Double, y0: Double, w0: Double, h0: Double, radii: List<DoubleArray>) {
        if (!finite(x0, y0, w0, h0) || radii.any { !finite(it[0], it[1]) }) return
        var ul: DoubleArray
        var ur: DoubleArray
        var lr: DoubleArray
        var ll: DoubleArray
        when (radii.size) {
            4 -> { ul = radii[0]; ur = radii[1]; lr = radii[2]; ll = radii[3] }
            3 -> { ul = radii[0]; ur = radii[1]; ll = radii[1]; lr = radii[2] }
            2 -> { ul = radii[0]; lr = radii[0]; ur = radii[1]; ll = radii[1] }
            else -> { ul = radii[0]; ur = radii[0]; lr = radii[0]; ll = radii[0] }
        }
        var x = x0
        var y = y0
        var w = w0
        var h = h0
        if (w < 0) {
            x += w; w = -w
            ul = ur.also { ur = ul }
            ll = lr.also { lr = ll }
        }
        if (h < 0) {
            y += h; h = -h
            ul = ll.also { ll = ul }
            ur = lr.also { lr = ur }
        }
        val top = ul[0] + ur[0]
        val right = ur[1] + lr[1]
        val bottom = lr[0] + ll[0]
        val left = ul[1] + ll[1]
        var scale = 1.0
        if (top > 0) scale = min(scale, w / top)
        if (right > 0) scale = min(scale, h / right)
        if (bottom > 0) scale = min(scale, w / bottom)
        if (left > 0) scale = min(scale, h / left)
        fun s(r: DoubleArray) = doubleArrayOf(r[0] * scale, r[1] * scale)
        ul = s(ul); ur = s(ur); lr = s(lr); ll = s(ll)
        moveTo(m, x + ul[0], y)
        lineDeviceUser(m, x + w - ur[0], y)
        corner(m, x + w - ur[0], y + ur[1], ur, -PI / 2)
        lineDeviceUser(m, x + w, y + h - lr[1])
        corner(m, x + w - lr[0], y + h - lr[1], lr, 0.0)
        lineDeviceUser(m, x + ll[0], y + h)
        corner(m, x + ll[0], y + h - ll[1], ll, PI / 2)
        lineDeviceUser(m, x, y + ul[1])
        corner(m, x + ul[0], y + ul[1], ul, PI)
        add(Segment.Close)
        lastX = startX; lastY = startY
        moveTo(m, x, y)
    }

    private fun lineDeviceUser(m: KiteMatrix, x: Double, y: Double) = lineDevice(m.transformX(x, y), m.transformY(x, y))

    private fun corner(m: KiteMatrix, cx: Double, cy: Double, r: DoubleArray, from: Double) {
        if (r[0] == 0.0 || r[1] == 0.0) return
        arcSegments(m, cx, cy, r[0], r[1], 0.0, from, PI / 2)
    }

    /** Adds the subpaths of [other], each point through [matrix] when there is one. */
    fun append(other: CanvasPath, matrix: KiteMatrix?) {
        fun tx(x: Double, y: Double) = matrix?.transformX(x, y) ?: x
        fun ty(x: Double, y: Double) = matrix?.transformY(x, y) ?: y
        for (s in other.segments) when (s) {
            is Segment.MoveTo -> moveDevice(tx(s.x, s.y), ty(s.x, s.y))
            is Segment.LineTo -> { add(Segment.LineTo(tx(s.x, s.y), ty(s.x, s.y))); lastX = tx(s.x, s.y); lastY = ty(s.x, s.y) }
            is Segment.QuadTo -> { add(Segment.QuadTo(tx(s.x1, s.y1), ty(s.x1, s.y1), tx(s.x2, s.y2), ty(s.x2, s.y2))); lastX = tx(s.x2, s.y2); lastY = ty(s.x2, s.y2) }
            is Segment.CurveTo -> {
                add(Segment.CurveTo(tx(s.x1, s.y1), ty(s.x1, s.y1), tx(s.x2, s.y2), ty(s.x2, s.y2), tx(s.x3, s.y3), ty(s.x3, s.y3)))
                lastX = tx(s.x3, s.y3); lastY = ty(s.x3, s.y3)
            }
            Segment.Close -> { add(Segment.Close); lastX = startX; lastY = startY }
        }
    }

    /** Adds the segments of a parsed SVG path. */
    fun appendParsed(path: KitePath) {
        val parsed = CanvasPath()
        parsed.segments.addAll(path.segments)
        append(parsed, null)
    }

    companion object {
        /** Most pixels that a cubic piece of an arc strays from the ellipse. */
        private const val ARC_ERROR = 1e-4

        /** Most pixels that a line of a flattened curve strays from it, for the point tests. */
        private const val FLATNESS = 2e-5

        /** A point this close to an edge is on it, which covers the two errors above. */
        private const val ON_EDGE = 2e-4

        private fun finite(vararg v: Double): Boolean = v.all { it.isFinite() }

        /** How far an arc turns from [start] to [end]: the whole turn when the angles are a turn apart or more (HTML, 4.12.5.1.5). */
        fun sweepOf(start: Double, end: Double, anticlockwise: Boolean): Double {
            val turn = 2 * PI
            if (!anticlockwise && end - start >= turn) return turn
            if (anticlockwise && start - end >= turn) return -turn
            var s = (end - start) % turn
            if (!anticlockwise && s < 0) s += turn
            if (anticlockwise && s > 0) s -= turn
            return s
        }

        /** Whether ([x], [y]) is inside [path] by its fill rule. A point on an edge is inside, as Chromium answers. */
        fun contains(path: KitePath, x: Double, y: Double, evenOdd: Boolean): Boolean {
            if (!x.isFinite() || !y.isFinite()) return false
            var winding = 0
            var onEdge = false
            forEachEdge(path) { ax, ay, bx, by ->
                if (onSegment(ax, ay, bx, by, x, y)) onEdge = true
                if (ay <= y) {
                    if (by > y && side(ax, ay, bx, by, x, y) > 0) winding++
                } else if (by <= y && side(ax, ay, bx, by, x, y) < 0) {
                    winding--
                }
            }
            if (onEdge) return true
            return if (evenOdd) winding % 2 != 0 else winding != 0
        }

        /** Whether ([x], [y]) is in the area that stroking [path] paints, both in user units. */
        fun strokeContains(
            path: KitePath, x: Double, y: Double, lineWidth: Double, cap: Int, join: Int, miterLimit: Double,
            dash: List<Double>, dashOffset: Double,
        ): Boolean {
            val outline = path.strokeOutline(lineWidth, cap, join, miterLimit, dash.takeIf { it.isNotEmpty() }, dashOffset, tolerance = FLATNESS)
            return contains(outline, x, y, evenOdd = false)
        }

        private fun side(ax: Double, ay: Double, bx: Double, by: Double, x: Double, y: Double): Double =
            (bx - ax) * (y - ay) - (x - ax) * (by - ay)

        private fun onSegment(ax: Double, ay: Double, bx: Double, by: Double, x: Double, y: Double): Boolean {
            if (x < min(ax, bx) - ON_EDGE || x > max(ax, bx) + ON_EDGE || y < min(ay, by) - ON_EDGE || y > max(ay, by) + ON_EDGE) return false
            val len = hypot(bx - ax, by - ay)
            if (len == 0.0) return hypot(x - ax, y - ay) < ON_EDGE
            return abs(side(ax, ay, bx, by, x, y)) / len < ON_EDGE
        }

        /** Each edge of [path] with curves as lines, every subpath closed, as a fill reads it. */
        private inline fun forEachEdge(path: KitePath, edge: (Double, Double, Double, Double) -> Unit) {
            var sx = 0.0; var sy = 0.0; var cx = 0.0; var cy = 0.0
            var open = false
            for (s in path.segments) when (s) {
                is Segment.MoveTo -> {
                    if (open && (cx != sx || cy != sy)) edge(cx, cy, sx, sy)
                    sx = s.x; sy = s.y; cx = s.x; cy = s.y; open = true
                }
                is Segment.LineTo -> { edge(cx, cy, s.x, s.y); cx = s.x; cy = s.y }
                is Segment.QuadTo -> {
                    val n = steps(sqrt(hypot(cx - 2 * s.x1 + s.x2, cy - 2 * s.y1 + s.y2) / (4 * FLATNESS)))
                    var px = cx; var py = cy
                    for (i in 1..n) {
                        val t = i.toDouble() / n
                        val u = 1 - t
                        val qx = u * u * cx + 2 * u * t * s.x1 + t * t * s.x2
                        val qy = u * u * cy + 2 * u * t * s.y1 + t * t * s.y2
                        edge(px, py, qx, qy); px = qx; py = qy
                    }
                    cx = s.x2; cy = s.y2
                }
                is Segment.CurveTo -> {
                    val dd = max(
                        hypot(cx - 2 * s.x1 + s.x2, cy - 2 * s.y1 + s.y2),
                        hypot(s.x1 - 2 * s.x2 + s.x3, s.y1 - 2 * s.y2 + s.y3),
                    )
                    val n = steps(sqrt(0.75 * dd / FLATNESS))
                    var px = cx; var py = cy
                    for (i in 1..n) {
                        val t = i.toDouble() / n
                        val u = 1 - t
                        val qx = u * u * u * cx + 3 * u * u * t * s.x1 + 3 * u * t * t * s.x2 + t * t * t * s.x3
                        val qy = u * u * u * cy + 3 * u * u * t * s.y1 + 3 * u * t * t * s.y2 + t * t * t * s.y3
                        edge(px, py, qx, qy); px = qx; py = qy
                    }
                    cx = s.x3; cy = s.y3
                }
                Segment.Close -> {
                    if (cx != sx || cy != sy) edge(cx, cy, sx, sy)
                    cx = sx; cy = sy
                }
            }
            if (open && (cx != sx || cy != sy)) edge(cx, cy, sx, sy)
        }

        /** Lines for a curve, from the estimate its flatness gives. */
        private fun steps(estimate: Double): Int =
            if (estimate.isFinite()) ceil(estimate).toInt().coerceIn(1, 4096) else 4096
    }
}
