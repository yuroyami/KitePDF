package io.github.yuroyami.kitepdf.core.render

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns paths into the coverage of each pixel, for [KiteRasterCanvas]. Each pixel row is cut
 * into [SUBROWS] lines. On each line the span inside the path counts by its exact width, so a
 * pixel's coverage is exact across a row and has [SUBROWS] steps down it.
 */
internal class RasterScan(private val width: Int, private val height: Int) {

    private var x0 = DoubleArray(64)
    private var y0 = DoubleArray(64)
    private var x1 = DoubleArray(64)
    private var y1 = DoubleArray(64)
    private var dir = IntArray(64)
    private var count = 0

    private val cover = FloatArray(width + 2)
    private val runs = FloatArray(width + 2)
    private var crossX = DoubleArray(16)
    private var crossDir = IntArray(16)

    /** Adds the edges of [path] under [ctm], with curves cut into lines within [tolerance] pixels. */
    fun add(path: KitePath, ctm: KiteMatrix, tolerance: Double = 0.05) {
        var sx = 0.0; var sy = 0.0; var cx = 0.0; var cy = 0.0
        var open = false
        for (s in path.segments) when (s) {
            is KitePath.Segment.MoveTo -> {
                if (open) line(cx, cy, sx, sy)
                sx = ctm.transformX(s.x, s.y); sy = ctm.transformY(s.x, s.y)
                cx = sx; cy = sy; open = true
            }
            is KitePath.Segment.LineTo -> {
                val nx = ctm.transformX(s.x, s.y); val ny = ctm.transformY(s.x, s.y)
                line(cx, cy, nx, ny); cx = nx; cy = ny; open = true
            }
            is KitePath.Segment.QuadTo -> {
                val ax = ctm.transformX(s.x1, s.y1); val ay = ctm.transformY(s.x1, s.y1)
                val bx = ctm.transformX(s.x2, s.y2); val by = ctm.transformY(s.x2, s.y2)
                val n = steps(sqrt(hypot(cx - 2 * ax + bx, cy - 2 * ay + by) / (4 * tolerance)))
                var px = cx; var py = cy
                for (i in 1..n) {
                    val t = i.toDouble() / n; val u = 1 - t
                    val qx = u * u * cx + 2 * u * t * ax + t * t * bx
                    val qy = u * u * cy + 2 * u * t * ay + t * t * by
                    line(px, py, qx, qy); px = qx; py = qy
                }
                cx = bx; cy = by; open = true
            }
            is KitePath.Segment.CurveTo -> {
                val ax = ctm.transformX(s.x1, s.y1); val ay = ctm.transformY(s.x1, s.y1)
                val bx = ctm.transformX(s.x2, s.y2); val by = ctm.transformY(s.x2, s.y2)
                val ex = ctm.transformX(s.x3, s.y3); val ey = ctm.transformY(s.x3, s.y3)
                val dd = max(hypot(cx - 2 * ax + bx, cy - 2 * ay + by), hypot(ax - 2 * bx + ex, ay - 2 * by + ey))
                val n = steps(sqrt(0.75 * dd / tolerance))
                var px = cx; var py = cy
                for (i in 1..n) {
                    val t = i.toDouble() / n; val u = 1 - t
                    val a = u * u * u; val b = 3 * u * u * t; val c = 3 * u * t * t; val d = t * t * t
                    val qx = a * cx + b * ax + c * bx + d * ex
                    val qy = a * cy + b * ay + c * by + d * ey
                    line(px, py, qx, qy); px = qx; py = qy
                }
                cx = ex; cy = ey; open = true
            }
            KitePath.Segment.Close -> {
                line(cx, cy, sx, sy)
                cx = sx; cy = sy
            }
        }
        if (open) line(cx, cy, sx, sy)
    }

    private fun line(ax: Double, ay: Double, bx: Double, by: Double) {
        if (ay == by || !ax.isFinite() || !ay.isFinite() || !bx.isFinite() || !by.isFinite()) return
        if (count == x0.size) {
            val n = count * 2
            x0 = x0.copyOf(n); y0 = y0.copyOf(n); x1 = x1.copyOf(n); y1 = y1.copyOf(n); dir = dir.copyOf(n)
        }
        if (ay < by) {
            x0[count] = ax; y0[count] = ay; x1[count] = bx; y1[count] = by; dir[count] = 1
        } else {
            x0[count] = bx; y0[count] = by; x1[count] = ax; y1[count] = ay; dir[count] = -1
        }
        count++
    }

    /**
     * Calls [row] for each pixel row the edges cover inside the box from ([left], [top]) to
     * ([right], [bottom]), with the coverage of pixels [from] to [to] of that row in [coverage],
     * from 0 to 1. Then forgets the edges.
     */
    fun fill(
        evenOdd: Boolean, left: Int, top: Int, right: Int, bottom: Int,
        row: (y: Int, from: Int, to: Int, coverage: FloatArray) -> Unit,
    ) {
        val n = count
        count = 0
        if (n == 0 || left >= right || top >= bottom) return
        // The edges in order of their top, so a row walks only the edges it crosses.
        val order = (0 until n).sortedBy { y0[it] }.toIntArray()
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (i in 0 until n) { minY = min(minY, y0[i]); maxY = max(maxY, y1[i]) }
        val yStart = max(top, floor(minY).toInt())
        val yEnd = min(bottom, ceil(maxY).toInt())
        if (yStart >= yEnd) return
        val active = IntArray(n)
        var activeCount = 0
        var next = 0
        val lo = left.toDouble()
        val hi = right.toDouble()
        for (y in yStart until yEnd) {
            // Edges that reach into this row join, edges that end above it leave.
            while (next < n && y0[order[next]] < y + 1) active[activeCount++] = order[next++]
            var k = 0
            for (i in 0 until activeCount) if (y1[active[i]] > y) active[k++] = active[i]
            activeCount = k
            if (activeCount == 0) continue
            var from = Int.MAX_VALUE
            var to = Int.MIN_VALUE
            for (s in 0 until SUBROWS) {
                val sy = y + (s + 0.5) / SUBROWS
                var c = 0
                for (i in 0 until activeCount) {
                    val e = active[i]
                    if (sy < y0[e] || sy >= y1[e]) continue
                    if (c == crossX.size) { crossX = crossX.copyOf(c * 2); crossDir = crossDir.copyOf(c * 2) }
                    crossX[c] = x0[e] + (sy - y0[e]) * (x1[e] - x0[e]) / (y1[e] - y0[e])
                    crossDir[c] = dir[e]
                    c++
                }
                if (c < 2) continue
                sortCrossings(c)
                var winding = 0
                for (i in 0 until c - 1) {
                    winding += crossDir[i]
                    val inside = if (evenOdd) winding and 1 != 0 else winding != 0
                    if (!inside) continue
                    val a = max(crossX[i], lo)
                    val b = min(crossX[i + 1], hi)
                    if (b <= a) continue
                    span(a, b, 1f / SUBROWS)
                    from = min(from, floor(a).toInt())
                    to = max(to, min(right - 1, floor(b).toInt()))
                }
            }
            if (from > to) continue
            var run = 0f
            for (x in from..to) {
                run += runs[x]
                val v = cover[x] + run
                cover[x] = if (v > 1f) 1f else if (v < 0f) 0f else v
                runs[x] = 0f
            }
            runs[to + 1] = 0f
            row(y, from, to, cover)
            for (x in from..to) cover[x] = 0f
            cover[to + 1] = 0f
        }
    }

    /** Adds [w] times the part of each pixel that the span from [a] to [b] covers. */
    private fun span(a: Double, b: Double, w: Float) {
        val ia = floor(a).toInt()
        val ib = floor(b).toInt()
        if (ia == ib) {
            cover[ia] += ((b - a) * w).toFloat()
            return
        }
        cover[ia] += ((ia + 1 - a) * w).toFloat()
        // The whole pixels between go into a running sum, so a wide span costs two writes.
        runs[ia + 1] += w
        runs[ib] -= w
        if (ib < width) cover[ib] += ((b - ib) * w).toFloat()
    }

    private fun sortCrossings(c: Int) {
        for (i in 1 until c) {
            val x = crossX[i]
            val d = crossDir[i]
            var j = i - 1
            while (j >= 0 && crossX[j] > x) {
                crossX[j + 1] = crossX[j]; crossDir[j + 1] = crossDir[j]; j--
            }
            crossX[j + 1] = x; crossDir[j + 1] = d
        }
    }

    private fun steps(estimate: Double): Int =
        if (estimate.isFinite()) ceil(estimate).toInt().coerceIn(1, 1024) else 1024

    companion object {
        /** Lines a pixel row is cut into, so an edge across a row has this many steps of coverage. */
        const val SUBROWS = 16
    }
}
