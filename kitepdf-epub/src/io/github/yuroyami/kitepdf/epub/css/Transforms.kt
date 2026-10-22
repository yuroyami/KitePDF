package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** One two-dimensional `transform` function (CSS Transforms 1, 13, #28). Lengths are in points, angles in degrees. */
internal sealed class CssTransform {
    class Translate(val x: CssOffset, val y: CssOffset) : CssTransform()
    class Scale(val x: Double, val y: Double) : CssTransform()
    class Rotate(val degrees: Double) : CssTransform()
    class Skew(val x: Double, val y: Double) : CssTransform()
    class Matrix(val m: KiteMatrix) : CssTransform()

    /** This function as a matrix, CSS y down, for a box of [w] by [h] that percentages refer to. */
    fun matrix(w: Double, h: Double): KiteMatrix = when (this) {
        is Translate -> KiteMatrix(1.0, 0.0, 0.0, 1.0, x.resolve(w), y.resolve(h))
        is Scale -> KiteMatrix(x, 0.0, 0.0, y, 0.0, 0.0)
        is Rotate -> {
            // Clockwise on screen, where y runs down.
            val r = degrees * PI / 180.0
            KiteMatrix(cos(r), sin(r), -sin(r), cos(r), 0.0, 0.0)
        }
        is Skew -> KiteMatrix(1.0, tan(y * PI / 180.0), tan(x * PI / 180.0), 1.0, 0.0, 0.0)
        is Matrix -> m
    }
}

/**
 * The matrix of [functions] with its origin at ([ox], [oy]), in the space of the box, y down: the
 * point p goes to origin + f1(f2(... (p - origin))), since the functions apply right to left.
 */
internal fun transformMatrix(functions: List<CssTransform>, w: Double, h: Double, ox: Double, oy: Double): KiteMatrix {
    var m = KiteMatrix.IDENTITY
    for (f in functions) m = m.concat(f.matrix(w, h))
    return KiteMatrix.translation(ox, oy).concat(m).concat(KiteMatrix.translation(-ox, -oy))
}

/** Reads `transform` and `transform-origin`. Lengths go through [length], which knows the element's font. */
internal class TransformParser(private val length: (String) -> Double?) {

    /** A `transform` list, an empty list for `none`, or null when it does not read and the value is dropped. */
    fun transform(value: String): List<CssTransform>? {
        val v = value.trim()
        if (v.equals("none", ignoreCase = true)) return emptyList()
        val out = ArrayList<CssTransform>()
        var i = 0
        while (i < v.length) {
            if (v[i].isWhitespace()) { i++; continue }
            val open = v.indexOf('(', i)
            val close = v.indexOf(')', open + 1)
            if (open < 0 || close < 0) return null
            val name = v.substring(i, open).trim().lowercase()
            val args = v.substring(open + 1, close).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            out += function(name, args) ?: return null
            i = close + 1
        }
        return out
    }

    private fun function(name: String, args: List<String>): CssTransform? {
        fun offset(s: String): CssOffset? =
            if (s.endsWith('%')) s.dropLast(1).trim().toDoubleOrNull()?.let { CssOffset(it / 100.0, true) }
            else length(s)?.let { CssOffset(it, false) }
        fun number(s: String) = if (s.endsWith('%')) s.dropLast(1).trim().toDoubleOrNull()?.div(100.0) else s.toDoubleOrNull()
        return when (name) {
            "translate" -> if (args.size in 1..2) CssTransform.Translate(offset(args[0]) ?: return null, args.getOrNull(1)?.let { offset(it) ?: return null } ?: CssOffset.ZERO) else null
            "translatex" -> args.singleOrNull()?.let { offset(it) }?.let { CssTransform.Translate(it, CssOffset.ZERO) }
            "translatey" -> args.singleOrNull()?.let { offset(it) }?.let { CssTransform.Translate(CssOffset.ZERO, it) }
            "scale" -> if (args.size in 1..2) {
                val x = number(args[0]) ?: return null
                CssTransform.Scale(x, args.getOrNull(1)?.let { number(it) ?: return null } ?: x)
            } else null
            "scalex" -> args.singleOrNull()?.let { number(it) }?.let { CssTransform.Scale(it, 1.0) }
            "scaley" -> args.singleOrNull()?.let { number(it) }?.let { CssTransform.Scale(1.0, it) }
            "rotate" -> args.singleOrNull()?.let { angle(it) }?.let { CssTransform.Rotate(it) }
            "skew" -> if (args.size in 1..2) CssTransform.Skew(angle(args[0]) ?: return null, args.getOrNull(1)?.let { angle(it) ?: return null } ?: 0.0) else null
            "skewx" -> args.singleOrNull()?.let { angle(it) }?.let { CssTransform.Skew(it, 0.0) }
            "skewy" -> args.singleOrNull()?.let { angle(it) }?.let { CssTransform.Skew(0.0, it) }
            "matrix" -> {
                val n = args.map { it.toDoubleOrNull() ?: return null }
                // The translation of matrix() is in CSS pixels.
                if (n.size == 6) CssTransform.Matrix(KiteMatrix(n[0], n[1], n[2], n[3], n[4] * 0.75, n[5] * 0.75)) else null
            }
            else -> null
        }
    }

    /** An angle in degrees: `deg`, `grad`, `rad` or `turn`, or a bare 0. */
    private fun angle(token: String): Double? {
        val t = token.trim().lowercase()
        fun num(suffix: String) = t.removeSuffix(suffix).trim().toDoubleOrNull()
        return when {
            t.endsWith("deg") -> num("deg")
            t.endsWith("grad") -> num("grad")?.times(0.9)
            t.endsWith("rad") -> num("rad")?.times(180.0 / PI)
            t.endsWith("turn") -> num("turn")?.times(360.0)
            t == "0" -> 0.0
            else -> null
        }
    }

    /** `transform-origin` with one or two values, keywords or offsets, as x then y. */
    fun origin(value: String, background: BackgroundParser): Pair<CssOffset, CssOffset>? =
        background.position(value.trim().split(' ').filter { it.isNotEmpty() }.take(2).joinToString(" "))
}
