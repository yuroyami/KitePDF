package com.yuroyami.kitepdf.render

/**
 * 2D affine transformation matrix as PDF uses it (ISO 32000-1 §8.3.4).
 *
 * PDF carries matrices as a flat 6-element row vector `[a b c d e f]` meaning
 *
 *     ┌ a  b  0 ┐
 *     │ c  d  0 │
 *     └ e  f  1 ┘
 *
 * Applied to a point `(x, y, 1)` to give `(x·a + y·c + e, x·b + y·d + f, 1)`.
 *
 * The `cm` content-stream operator multiplies "new = operand × current",
 * so the new origin lands at the operand's translation rather than the
 * current one — see [concat].
 *
 * Immutable: every operation returns a fresh Matrix. The page renderer keeps
 * a single state per gsave level and re-assigns rather than mutating.
 */
data class Matrix(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {

    /** PDF `cm` semantics: returns `other × this`. */
    fun concat(other: Matrix): Matrix = Matrix(
        a = other.a * a + other.b * c,
        b = other.a * b + other.b * d,
        c = other.c * a + other.d * c,
        d = other.c * b + other.d * d,
        e = other.e * a + other.f * c + e,
        f = other.e * b + other.f * d + f,
    )

    fun transformPoint(x: Double, y: Double): Pair<Double, Double> =
        (a * x + c * y + e) to (b * x + d * y + f)

    /** X-component of the unit vector after this transform — useful for scaled font sizes. */
    fun scaleX(): Double = kotlin.math.sqrt(a * a + b * b)
    fun scaleY(): Double = kotlin.math.sqrt(c * c + d * d)

    fun translate(tx: Double, ty: Double): Matrix = translation(tx, ty).concat(this)
    fun scale(sx: Double, sy: Double): Matrix = scaling(sx, sy).concat(this)

    companion object {
        val IDENTITY = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        fun translation(tx: Double, ty: Double) = Matrix(1.0, 0.0, 0.0, 1.0, tx, ty)
        fun scaling(sx: Double, sy: Double) = Matrix(sx, 0.0, 0.0, sy, 0.0, 0.0)
    }
}
