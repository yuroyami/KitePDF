package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How a gradient continues past its ends: the `spreadMethod` of an SVG gradient
 * (SVG 1.1, 13.2.2 and 13.2.3) and the `SpreadMethod` of an XPS gradient brush. A PDF
 * shading has no such attribute: its /Extend flags can only pad.
 */
public enum class KiteGradientSpread {
    /** The end colours continue outward. */
    Pad,

    /** The gradient starts again past each end. */
    Repeat,

    /** The gradient runs back the other way past each end, then forward again. */
    Reflect,
}

/** The most periods that [spreadOver] builds. Canvases sample a gradient at 256 stops, 8 per period. */
public const val MAX_SPREAD_PERIODS: Int = 32

/**
 * This axial or radial shading continued past its ends by [spread], over a region
 * whose bounds in shading space are [region]. The result is an ordinary shading
 * that every canvas paints: its geometry grows to cover the region, and a stitching
 * function (ISO 32000-1, 7.10.4) repeats or mirrors the colours once per period.
 *
 * The result has at most [MAX_SPREAD_PERIODS] periods, the ones nearest the
 * shading's own. Past them, the shading's /Extend flags apply.
 *
 * Returns this shading unchanged for [KiteGradientSpread.Pad], for other shading
 * types, when the region needs nothing past the shading's own range, and for a
 * radial shading whose circles do not grow outward from a start inside the end
 * circle: only that shape gives each point one position along the gradient.
 */
public fun KiteShading.spreadOver(spread: KiteGradientSpread, region: KiteRectangle): KiteShading {
    if (spread == KiteGradientSpread.Pad) return this
    val reflect = spread == KiteGradientSpread.Reflect
    val xs = doubleArrayOf(region.left, region.right, region.right, region.left)
    val ys = doubleArrayOf(region.bottom, region.bottom, region.top, region.top)
    return when (this) {
        is KiteShading.Axial -> {
            val c = coords
            val dx = c[2] - c[0]
            val dy = c[3] - c[1]
            val length2 = dx * dx + dy * dy
            if (!(length2 > 0.0) || !length2.isFinite()) return this
            // The position along the axis is linear, so the corners hold its extremes.
            var lo = 0.0
            var hi = 1.0
            for (i in 0 until 4) {
                val s = ((xs[i] - c[0]) * dx + (ys[i] - c[1]) * dy) / length2
                if (!s.isFinite()) return this
                lo = min(lo, s)
                hi = max(hi, s)
            }
            val (from, to) = periods(lo, hi) ?: return this
            copy(
                coords = doubleArrayOf(c[0] + from * dx, c[1] + from * dy, c[0] + to * dx, c[1] + to * dy),
                domain = doubleArrayOf(from, to),
                function = periodic(function, domain, from, to, reflect),
            )
        }
        is KiteShading.Radial -> {
            val c = coords
            val dcx = c[3] - c[0]
            val dcy = c[4] - c[1]
            val dr = c[5] - c[2]
            val a = dcx * dcx + dcy * dcy - dr * dr
            if (!(dr > 0.0) || !(a < 0.0) || c[2] < 0.0) return this
            // Circle s has its centre at c0 + s (c1 - c0) and radius r0 + s (r1 - r0). Each circle
            // holds the one before, so the largest position over the region is at a corner.
            var hi = 1.0
            for (i in 0 until 4) {
                val qx = xs[i] - c[0]
                val qy = ys[i] - c[1]
                val b = qx * dcx + qy * dcy + c[2] * dr
                val d = b * b - a * (qx * qx + qy * qy - c[2] * c[2])
                if (!(d >= 0.0)) continue
                val s = (b - sqrt(d)) / a
                if (!s.isFinite()) return this
                hi = max(hi, s)
            }
            // Below position 0, the circles shrink to a point where the radius reaches 0.
            val (from, to) = periods(if (c[2] > 0.0) -c[2] / dr else 0.0, hi) ?: return this
            copy(
                coords = doubleArrayOf(
                    c[0] + from * dcx, c[1] + from * dcy, max(0.0, c[2] + from * dr),
                    c[0] + to * dcx, c[1] + to * dcy, c[2] + to * dr,
                ),
                domain = doubleArrayOf(from, to),
                function = periodic(function, domain, from, to, reflect),
            )
        }
        else -> this
    }
}

/**
 * The positions from [lo] to [hi], cut to [MAX_SPREAD_PERIODS] periods nearest the
 * shading's own range 0..1. Null when the range adds nothing past 0..1.
 */
private fun periods(lo: Double, hi: Double): Pair<Double, Double>? {
    if (lo >= 0.0 && hi <= 1.0) return null
    var from = min(lo, 0.0)
    var to = max(hi, 1.0)
    if (to - from > MAX_SPREAD_PERIODS) {
        val budget = MAX_SPREAD_PERIODS - 1.0
        val below = -from
        val above = to - 1.0
        val keepBelow = min(below, max(budget / 2, budget - above))
        from = -keepBelow
        to = 1.0 + min(above, budget - keepBelow)
    }
    return from to to
}

/**
 * [function] repeated, or mirrored with [reflect], once per unit of position from
 * [from] to [to]. The period from k to k + 1 maps onto the whole of [domain].
 */
private fun periodic(function: KiteFunction, domain: DoubleArray, from: Double, to: Double, reflect: Boolean): KiteFunction {
    val d0 = domain[0]
    val d1 = domain[1]
    val subs = ArrayList<KiteFunction>()
    val bounds = ArrayList<Double>()
    val encode = ArrayList<Double>()
    var k = floor(from)
    var lo = from
    while (lo < to) {
        val hi = min(k + 1.0, to)
        val mirrored = reflect && (k.toLong() and 1L) == 1L
        fun at(s: Double) = d0 + (if (mirrored) k + 1.0 - s else s - k) * (d1 - d0)
        subs += function
        encode += at(lo)
        encode += at(hi)
        if (hi < to) bounds += hi
        lo = hi
        k += 1.0
    }
    return KiteFunction.Type3(doubleArrayOf(from, to), null, subs, bounds.toDoubleArray(), encode.toDoubleArray())
}
