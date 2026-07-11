package io.github.yuroyami.kitepdf.core.render

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
public data class Matrix(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {

    /** PDF `cm` semantics: returns `other × this`. */
    public fun concat(other: Matrix): Matrix = Matrix(
        a = other.a * a + other.b * c,
        b = other.a * b + other.b * d,
        c = other.c * a + other.d * c,
        d = other.c * b + other.d * d,
        e = other.e * a + other.f * c + e,
        f = other.e * b + other.f * d + f,
    )

    public fun transformPoint(x: Double, y: Double): Pair<Double, Double> =
        (a * x + c * y + e) to (b * x + d * y + f)

    /** X-component of the unit vector after this transform — useful for scaled font sizes. */
    public fun scaleX(): Double = kotlin.math.sqrt(a * a + b * b)
    public fun scaleY(): Double = kotlin.math.sqrt(c * c + d * d)

    public fun translate(tx: Double, ty: Double): Matrix = translation(tx, ty).concat(this)
    public fun scale(sx: Double, sy: Double): Matrix = scaling(sx, sy).concat(this)

    /** Inverse transform, or null when the matrix is singular (det ≈ 0). */
    public fun invert(): Matrix? {
        val det = a * d - b * c
        if (kotlin.math.abs(det) < 1e-12) return null
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Matrix(ia, ib, ic, id, -(e * ia + f * ic), -(e * ib + f * id))
    }

    public companion object {
        public val IDENTITY: Matrix = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        public fun translation(tx: Double, ty: Double): Matrix = Matrix(1.0, 0.0, 0.0, 1.0, tx, ty)
        public fun scaling(sx: Double, sy: Double): Matrix = Matrix(sx, 0.0, 0.0, sy, 0.0, 0.0)
    }
}
