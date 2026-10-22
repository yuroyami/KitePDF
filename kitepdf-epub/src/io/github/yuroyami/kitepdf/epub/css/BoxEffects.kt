package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor

/** One corner radius: points, or a fraction of the box's side for a percentage. */
internal class CssRadius(val value: Double, val percent: Boolean) {
    fun resolve(side: Double): Double = (if (percent) value * side else value).coerceAtLeast(0.0)

    companion object {
        val ZERO = CssRadius(0.0, false)
    }
}

/**
 * The radii of `border-radius`, horizontal and vertical, for the corners top-left, top-right,
 * bottom-right and bottom-left (CSS Backgrounds 3, 5.1, #28).
 */
internal class CornerRadii(val x: List<CssRadius>, val y: List<CssRadius>) {

    /** [corner]'s radii replaced, 0 top-left to 3 bottom-left. */
    fun with(corner: Int, rx: CssRadius, ry: CssRadius): CornerRadii =
        CornerRadii(x.toMutableList().also { it[corner] = rx }, y.toMutableList().also { it[corner] = ry })

    /**
     * The radii in points for a box of [w] by [h]: x and y of top-left, top-right, bottom-right
     * and bottom-left. Scaled down together when two corners on one side would overlap
     * (CSS Backgrounds 3, 5.5). Null when every radius is 0.
     */
    fun resolve(w: Double, h: Double): DoubleArray? {
        val r = DoubleArray(8) { i -> if (i % 2 == 0) x[i / 2].resolve(w) else y[i / 2].resolve(h) }
        if (r.all { it <= 0.0 }) return null
        var f = 1.0
        fun fit(side: Double, sum: Double) { if (sum > side && sum > 0.0) f = minOf(f, side / sum) }
        fit(w, r[0] + r[2])
        fit(h, r[3] + r[5])
        fit(w, r[4] + r[6])
        fit(h, r[7] + r[1])
        if (f < 1.0) for (i in r.indices) r[i] *= f
        return r
    }

    companion object {
        val ZERO = CornerRadii(List(4) { CssRadius.ZERO }, List(4) { CssRadius.ZERO })
    }
}

/**
 * One `box-shadow`: its offset, blur and spread in points, CSS y down, and its colour. A null
 * [color] is `currentColor` (CSS Backgrounds 3, 7.1, #28).
 */
internal class BoxShadow(
    val x: Double,
    val y: Double,
    val blur: Double,
    val spread: Double,
    val color: RgbColor?,
    val alpha: Double,
    val inset: Boolean,
)

/**
 * Adds a rectangle from ([left], [bottom]) to ([right], [top]) in a y-up space, with the corner
 * radii [r] as [CornerRadii.resolve] gives them, or square corners for null. Each corner is a
 * quarter ellipse, drawn as one cubic curve.
 */
internal fun KitePath.Builder.roundedRect(left: Double, bottom: Double, right: Double, top: Double, r: DoubleArray?) {
    if (r == null) {
        rectangle(left, bottom, right - left, top - bottom)
        return
    }
    // The control points sit this far along each tangent, which keeps a quarter circle round.
    val k = 0.5522847498
    // CSS corners are y down, so the top-left corner is at (left, top) here.
    val (tlx, tly, trx, try_) = listOf(r[0], r[1], r[2], r[3])
    val (brx, bry, blx, bly) = listOf(r[4], r[5], r[6], r[7])
    moveTo(left + tlx, top)
    lineTo(right - trx, top)
    if (trx > 0.0 && try_ > 0.0) curveTo(right - trx + trx * k, top, right, top - try_ + try_ * k, right, top - try_) else lineTo(right, top)
    lineTo(right, bottom + bry)
    if (brx > 0.0 && bry > 0.0) curveTo(right, bottom + bry - bry * k, right - brx + brx * k, bottom, right - brx, bottom) else lineTo(right, bottom)
    lineTo(left + blx, bottom)
    if (blx > 0.0 && bly > 0.0) curveTo(left + blx - blx * k, bottom, left, bottom + bly - bly * k, left, bottom + bly) else lineTo(left, bottom)
    lineTo(left, top - tly)
    if (tlx > 0.0 && tly > 0.0) curveTo(left, top - tly + tly * k, left + tlx - tlx * k, top, left + tlx, top) else lineTo(left, top)
    close()
}

/** [r] with every radius grown by [by], and none below 0: the corners of a larger or smaller box. */
internal fun grownRadii(r: DoubleArray?, by: Double): DoubleArray? =
    r?.let { DoubleArray(8) { i -> (it[i] + by).coerceAtLeast(0.0) } }

/** [r] with the horizontal radii less [dx] and the vertical ones less [dy], none below 0: the inner edge of a border. */
internal fun innerRadii(r: DoubleArray?, left: Double, top: Double, right: Double, bottom: Double): DoubleArray? = r?.let {
    doubleArrayOf(
        (it[0] - left).coerceAtLeast(0.0), (it[1] - top).coerceAtLeast(0.0),
        (it[2] - right).coerceAtLeast(0.0), (it[3] - top).coerceAtLeast(0.0),
        (it[4] - right).coerceAtLeast(0.0), (it[5] - bottom).coerceAtLeast(0.0),
        (it[6] - left).coerceAtLeast(0.0), (it[7] - bottom).coerceAtLeast(0.0),
    )
}
