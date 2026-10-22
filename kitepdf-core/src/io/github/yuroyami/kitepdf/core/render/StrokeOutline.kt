package io.github.yuroyami.kitepdf.core.render

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The area that stroking this path paints, as a path to fill with the nonzero rule.
 * The pen is round in path space, so the outline filled under a non-uniform matrix
 * gives the elliptical pen that ISO 32000-1, 8.5.3.2 describes.
 *
 * - [lineWidth] is the width of the pen in path units (8.4.3.2). A width that is not
 *   positive gives an empty path, so a caller picks its own thinnest line.
 * - [lineCap] is 0 butt, 1 round or 2 projecting square (8.4.3.3).
 * - [lineJoin] is 0 miter, 1 round or 2 bevel (8.4.3.4). A miter longer than
 *   [miterLimit] times the width becomes a bevel (8.4.3.5).
 * - [dashArray] and [dashPhase] follow 8.4.3.6. Each subpath starts the pattern again,
 *   and each dash gets its own caps. A pattern with a negative length, or with no
 *   length at all, draws a solid line.
 * - Curves become lines that stay within [tolerance] path units of the curve.
 *
 * A subpath whose points all coincide paints a dot with round caps and nothing
 * otherwise, because its direction is unknown (8.5.3.2).
 */
public fun KitePath.strokeOutline(
    lineWidth: Double,
    lineCap: Int = 0,
    lineJoin: Int = 0,
    miterLimit: Double = 10.0,
    dashArray: List<Double>? = null,
    dashPhase: Double = 0.0,
    tolerance: Double = 0.1,
): KitePath {
    if (!(lineWidth > 0.0) || !lineWidth.isFinite()) return KitePath(emptyList())
    val tol = if (tolerance > 0.0 && tolerance.isFinite()) tolerance else 0.1
    val out = OutlineBuilder(lineWidth / 2, lineCap, lineJoin, miterLimit)
    val lines = flatten(this, tol)
    val pattern = dashArray?.takeIf { d -> d.all { it >= 0.0 && it.isFinite() } && d.sum() > 0.0 }
        ?.let { if (it.size % 2 == 1) it + it else it }
        // A pattern so fine that it splits the path into more dashes than a page holds draws solid.
        ?.takeIf { d -> lines.sumOf { it.length() } / d.sum() * d.size < MAX_DASHES }
    val phase = if (dashPhase.isFinite()) dashPhase else 0.0
    for (line in lines) {
        if (line.size == 1) {
            if (lineCap == 1) out.disc(line.xs[0], line.ys[0])
            continue
        }
        if (pattern == null) out.polyline(line) else dash(line, pattern, phase, out)
    }
    return out.build()
}

/** More dashes than this in one path draw a solid line instead. */
private const val MAX_DASHES = 100_000

/** Most lines that one curve becomes. */
private const val MAX_CURVE_STEPS = 1024

/** One flattened subpath: its points without repeats, and whether it closes. */
private class Polyline(val xs: DoubleArray, val ys: DoubleArray, val size: Int, val closed: Boolean) {
    /** Unit direction of a lone point, for the square caps of a zero-length dash. */
    var dirX = 1.0
    var dirY = 0.0

    fun length(): Double {
        var sum = 0.0
        for (i in 1 until size) sum += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        if (closed) sum += hypot(xs[0] - xs[size - 1], ys[0] - ys[size - 1])
        return sum
    }
}

/** Collects points, drops repeats, and ends each subpath as a [Polyline]. */
private class PointList {
    var xs = DoubleArray(16)
    var ys = DoubleArray(16)
    var size = 0

    fun add(x: Double, y: Double) {
        if (size > 0 && xs[size - 1] == x && ys[size - 1] == y) return
        if (size == xs.size) {
            xs = xs.copyOf(size * 2)
            ys = ys.copyOf(size * 2)
        }
        xs[size] = x
        ys[size] = y
        size++
    }

    fun take(closed: Boolean): Polyline {
        // A closed subpath that already ends on its start needs no closing line.
        if (closed && size > 1 && xs[size - 1] == xs[0] && ys[size - 1] == ys[0]) size--
        val line = Polyline(xs.copyOf(size), ys.copyOf(size), size, closed && size > 1)
        size = 0
        return line
    }
}

/** Splits [path] into subpaths of straight lines. A lone move is not a subpath. */
private fun flatten(path: KitePath, tol: Double): List<Polyline> {
    val out = ArrayList<Polyline>()
    val points = PointList()
    var drawn = false
    var x = 0.0
    var y = 0.0
    var startX = 0.0
    var startY = 0.0
    fun finish(closed: Boolean) {
        if (drawn && points.size > 0) out.add(points.take(closed)) else points.size = 0
        drawn = false
    }
    fun begin() {
        // A segment after a close starts a new subpath at the start of the closed one.
        if (points.size == 0) points.add(x, y)
        drawn = true
    }
    for (s in path.segments) when (s) {
        is KitePath.Segment.MoveTo -> {
            finish(closed = false)
            x = s.x; y = s.y
            startX = x; startY = y
            points.add(x, y)
        }
        is KitePath.Segment.LineTo -> {
            begin()
            x = s.x; y = s.y
            points.add(x, y)
        }
        is KitePath.Segment.CurveTo -> {
            begin()
            val dd = max(
                hypot(x - 2 * s.x1 + s.x2, y - 2 * s.y1 + s.y2),
                hypot(s.x1 - 2 * s.x2 + s.x3, s.y1 - 2 * s.y2 + s.y3),
            )
            // A cubic strays from its chords by at most 3/4 of dd over the square of the step count.
            val n = steps(sqrt(0.75 * dd / tol))
            for (i in 1..n) {
                val t = i.toDouble() / n
                val u = 1 - t
                val a = u * u * u
                val b = 3 * u * u * t
                val c = 3 * u * t * t
                val d = t * t * t
                points.add(a * x + b * s.x1 + c * s.x2 + d * s.x3, a * y + b * s.y1 + c * s.y2 + d * s.y3)
            }
            x = s.x3; y = s.y3
        }
        is KitePath.Segment.QuadTo -> {
            begin()
            val dd = hypot(x - 2 * s.x1 + s.x2, y - 2 * s.y1 + s.y2)
            val n = steps(sqrt(dd / (4 * tol)))
            for (i in 1..n) {
                val t = i.toDouble() / n
                val u = 1 - t
                points.add(u * u * x + 2 * u * t * s.x1 + t * t * s.x2, u * u * y + 2 * u * t * s.y1 + t * t * s.y2)
            }
            x = s.x2; y = s.y2
        }
        KitePath.Segment.Close -> {
            if (points.size > 0) drawn = true
            finish(closed = true)
            x = startX; y = startY
        }
    }
    finish(closed = false)
    return out
}

private fun steps(estimate: Double): Int =
    if (estimate.isFinite()) ceil(estimate).toInt().coerceIn(1, MAX_CURVE_STEPS) else MAX_CURVE_STEPS

/**
 * Walks [line] through [pattern] and strokes each dash as an open line. The pattern
 * has an even number of entries, so even entries are dashes and odd ones gaps.
 */
private fun dash(line: Polyline, pattern: List<Double>, phase: Double, out: OutlineBuilder) {
    val total = pattern.sum()
    var offset = phase % total
    if (offset < 0) offset += total
    var index = 0
    // Skip the entries the phase has passed. A dash of zero length at the start still draws.
    while (offset > pattern[index] || (offset == pattern[index] && pattern[index] > 0.0)) {
        offset -= pattern[index]
        index = (index + 1) % pattern.size
    }
    var left = pattern[index] - offset
    val piece = PointList()
    if (index % 2 == 0) piece.add(line.xs[0], line.ys[0])
    val count = if (line.closed) line.size else line.size - 1
    for (i in 0 until count) {
        val ax = line.xs[i]
        val ay = line.ys[i]
        val bx = line.xs[(i + 1) % line.size]
        val by = line.ys[(i + 1) % line.size]
        val length = hypot(bx - ax, by - ay)
        var along = 0.0
        while (length - along >= left) {
            along += left
            val px = ax + (bx - ax) * along / length
            val py = ay + (by - ay) * along / length
            if (index % 2 == 0) {
                piece.add(px, py)
                val dash = piece.take(closed = false)
                dash.dirX = (bx - ax) / length
                dash.dirY = (by - ay) / length
                out.dashPiece(dash)
            } else {
                piece.add(px, py)
            }
            index = (index + 1) % pattern.size
            left = pattern[index]
        }
        left -= length - along
        if (index % 2 == 0) piece.add(bx, by)
    }
    // A dash that starts exactly at the end of the line has nothing to draw.
    if (index % 2 == 0 && piece.size > 1) out.dashPiece(piece.take(closed = false))
}

/** Emits every piece of the outline counter-clockwise, so the nonzero rule fills their union. */
private class OutlineBuilder(val hw: Double, val cap: Int, val join: Int, val miterLimit: Double) {
    private val segments = ArrayList<KitePath.Segment>()

    fun build(): KitePath = KitePath(segments)

    /** A dash: a lone point gets the caps its direction allows, a longer one strokes as an open line. */
    fun dashPiece(line: Polyline) {
        if (line.size > 1) return polyline(line)
        when (cap) {
            1 -> disc(line.xs[0], line.ys[0])
            2 -> {
                val x = line.xs[0]
                val y = line.ys[0]
                val dx = line.dirX * hw
                val dy = line.dirY * hw
                polygon(x - dx - dy, y - dy + dx, x + dx - dy, y + dy + dx, x + dx + dy, y + dy - dx, x - dx + dy, y - dy - dx)
            }
        }
    }

    fun polyline(line: Polyline) {
        val n = line.size
        val count = if (line.closed) n else n - 1
        for (i in 0 until count) {
            val j = (i + 1) % n
            val (nx, ny) = normal(line.xs[i], line.ys[i], line.xs[j], line.ys[j])
            polygon(
                line.xs[i] + nx, line.ys[i] + ny, line.xs[j] + nx, line.ys[j] + ny,
                line.xs[j] - nx, line.ys[j] - ny, line.xs[i] - nx, line.ys[i] - ny,
            )
        }
        val first = if (line.closed) 0 else 1
        val last = if (line.closed) n - 1 else n - 2
        for (i in first..last) {
            val p = (i + n - 1) % n
            val q = (i + 1) % n
            joint(line.xs[p], line.ys[p], line.xs[i], line.ys[i], line.xs[q], line.ys[q])
        }
        if (!line.closed) {
            capAt(line.xs[0], line.ys[0], line.xs[1], line.ys[1])
            capAt(line.xs[n - 1], line.ys[n - 1], line.xs[n - 2], line.ys[n - 2])
        }
    }

    /** The left normal of the line from (ax, ay) to (bx, by), half a pen long. */
    private fun normal(ax: Double, ay: Double, bx: Double, by: Double): Pair<Double, Double> {
        val length = hypot(bx - ax, by - ay)
        return -(by - ay) / length * hw to (bx - ax) / length * hw
    }

    /** The cap at the end (x, y) of a line whose next point inward is (ix, iy). */
    private fun capAt(x: Double, y: Double, ix: Double, iy: Double) {
        when (cap) {
            1 -> disc(x, y)
            2 -> {
                val length = hypot(x - ix, y - iy)
                val dx = (x - ix) / length * hw
                val dy = (y - iy) / length * hw
                polygon(x - dy, y + dx, x - dy + dx, y + dx + dy, x + dy + dx, y - dx + dy, x + dy, y - dx)
            }
        }
    }

    /** The join at (x, y) between the line from (px, py) and the line on to (qx, qy). */
    private fun joint(px: Double, py: Double, x: Double, y: Double, qx: Double, qy: Double) {
        val l0 = hypot(x - px, y - py)
        val l1 = hypot(qx - x, qy - y)
        val d0x = (x - px) / l0
        val d0y = (y - py) / l0
        val d1x = (qx - x) / l1
        val d1y = (qy - y) / l1
        val cross = d0x * d1y - d0y * d1x
        val dot = d0x * d1x + d0y * d1y
        if (join == 1 && dot < 0.995) return disc(x, y)
        // The outer side is the one the line turns away from.
        val side = if (cross > 0) -1.0 else 1.0
        val ax = x - d0y * hw * side
        val ay = y + d0x * hw * side
        val bx = x - d1y * hw * side
        val by = y + d1x * hw * side
        val cosHalf = sqrt(max(0.0, (1 + dot) / 2))
        if (join == 0 && cosHalf > 0.0 && 1 / cosHalf <= miterLimit) {
            // The tip lies on the bisector of the two outer normals.
            val mx = (ax - x) + (bx - x)
            val my = (ay - y) + (by - y)
            val m = hypot(mx, my)
            if (m > 0.0) {
                val reach = hw / cosHalf
                return polygon(x, y, ax, ay, x + mx / m * reach, y + my / m * reach, bx, by)
            }
        }
        polygon(x, y, ax, ay, bx, by)
    }

    /** A circle of the pen's radius, as four cubic arcs. */
    fun disc(cx: Double, cy: Double) {
        val r = hw
        val k = 0.5522847498307936 * r
        segments.add(KitePath.Segment.MoveTo(cx + r, cy))
        segments.add(KitePath.Segment.CurveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r))
        segments.add(KitePath.Segment.CurveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy))
        segments.add(KitePath.Segment.CurveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r))
        segments.add(KitePath.Segment.CurveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy))
        segments.add(KitePath.Segment.Close)
    }

    /** A polygon from x, y pairs, turned counter-clockwise. One with no area adds nothing. */
    private fun polygon(vararg c: Double) {
        val n = c.size / 2
        var twice = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            twice += c[2 * i] * c[2 * j + 1] - c[2 * j] * c[2 * i + 1]
        }
        if (twice == 0.0 || !twice.isFinite()) return
        for (k in 0 until n) {
            val i = if (twice > 0) k else n - 1 - k
            val x = c[2 * i]
            val y = c[2 * i + 1]
            segments.add(if (k == 0) KitePath.Segment.MoveTo(x, y) else KitePath.Segment.LineTo(x, y))
        }
        segments.add(KitePath.Segment.Close)
    }
}
