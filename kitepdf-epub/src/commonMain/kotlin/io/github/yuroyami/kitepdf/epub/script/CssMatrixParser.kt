package io.github.yuroyami.kitepdf.epub.script

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Parses a CSS transform list into the 4 by 4 matrix that `new DOMMatrix(string)` and
 * `setMatrixValue` take (Geometry Interfaces 1, 6.1, #609). It reads every transform function of
 * CSS Transforms 1 and 2, with `calc()`, `min()`, `max()` and `clamp()`.
 *
 * The numbers follow Chromium where the standards leave them open. A length and a scale factor go
 * through a 32-bit float, as Chromium's style values do. An angle becomes degrees, and a rotation
 * by a multiple of 45 degrees is exact.
 */
internal object CssMatrixParser {

    /** What [parse] found. */
    sealed class Result {
        /** The matrix, as m11, m12, ... m44, and whether only 2D functions made it. */
        class Matrix(val m: DoubleArray, val is2D: Boolean) : Result()

        /** The list holds a length that needs an element to resolve, such as `em` or a percentage. */
        data object Relative : Result()

        data object Invalid : Result()
    }

    fun parse(text: String): Result = try {
        Reader(text).list()
    } catch (_: Unresolvable) {
        Result.Relative
    } catch (_: Bad) {
        Result.Invalid
    }

    private class Bad : RuntimeException()
    private class Unresolvable : RuntimeException()

    private enum class Kind { NUMBER, LENGTH, ANGLE, PERCENT }

    /** A value with its kind: px for a length, degrees for an angle, the number itself for a percentage. */
    private class Value(val kind: Kind, val v: Double, val relative: Boolean = false)

    private val ABSOLUTE = mapOf(
        "px" to 1.0, "in" to 96.0, "cm" to 96.0 / 2.54, "mm" to 96.0 / 25.4, "q" to 96.0 / 101.6, "pt" to 96.0 / 72.0, "pc" to 16.0,
    )
    private val RELATIVE = setOf(
        "em", "rem", "ex", "rex", "ch", "rch", "cap", "rcap", "ic", "ric", "lh", "rlh",
        "vw", "vh", "vi", "vb", "vmin", "vmax", "svw", "svh", "svi", "svb", "svmin", "svmax",
        "lvw", "lvh", "lvi", "lvb", "lvmin", "lvmax", "dvw", "dvh", "dvi", "dvb", "dvmin", "dvmax",
        "cqw", "cqh", "cqi", "cqb", "cqmin", "cqmax",
    )

    private class Reader(private val s: String) {
        private var i = 0

        private fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r' || s[i] == '\u000C')) i++
        }

        private fun peek(): Char? = s.getOrNull(i)

        private fun expect(c: Char) {
            ws()
            if (peek() != c) throw Bad()
            i++
        }

        private fun ident(): String {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '-' || s[i] == '_')) i++
            if (start == i || s[start].isDigit()) throw Bad()
            return s.substring(start, i).lowercase()
        }

        fun list(): Result {
            ws()
            if (i == s.length) return identity()
            val save = i
            if (ident() == "none") {
                ws()
                if (i == s.length) return identity()
                throw Bad()
            }
            i = save
            var m = IDENTITY
            var is2D = true
            while (true) {
                ws()
                if (i == s.length) break
                val name = ident()
                if (peek() != '(') throw Bad()
                i++
                val args = args()
                val (f, flat) = function(name, args)
                m = multiply(m, f)
                if (!flat) is2D = false
            }
            return Result.Matrix(m, is2D)
        }

        private fun identity() = Result.Matrix(IDENTITY.copyOf(), true)

        /** The comma-separated values up to the closing bracket. */
        private fun args(): List<Value> {
            val out = ArrayList<Value>()
            ws()
            if (peek() == ')') { i++; return out }
            while (true) {
                out += term(top = true)
                ws()
                when (peek()) {
                    ',' -> i++
                    ')' -> { i++; return out }
                    else -> throw Bad()
                }
            }
        }

        /** A dimension, a number, a percentage, a math function, or inside one of those a bracketed sum. */
        private fun term(top: Boolean = false): Value {
            ws()
            val c = peek() ?: throw Bad()
            if (c.isLetter()) {
                val name = ident()
                if (peek() != '(') throw Bad()
                i++
                return when (name) {
                    "calc" -> sum().also { expect(')') }
                    "min", "max" -> {
                        val values = ArrayList<Value>()
                        while (true) {
                            values += sum()
                            ws()
                            if (peek() == ',') { i++; continue }
                            expect(')')
                            break
                        }
                        values.reduce { a, b -> pick(a, b, name == "min") }
                    }
                    "clamp" -> {
                        val lo = sum(); expect(',')
                        val mid = sum(); expect(',')
                        val hi = sum(); expect(')')
                        pick(lo, pick(mid, hi, true), false)
                    }
                    else -> throw Bad()
                }
            }
            if (c == '(' && !top) {
                i++
                return sum().also { expect(')') }
            }
            return dimension()
        }

        private fun pick(a: Value, b: Value, min: Boolean): Value {
            if (a.kind != b.kind) throw Bad()
            val rel = a.relative || b.relative
            return Value(a.kind, if (min) minOf(a.v, b.v) else maxOf(a.v, b.v), rel)
        }

        private fun dimension(): Value {
            val start = i
            if (peek() == '+' || peek() == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            if (peek() == '.') { i++; while (i < s.length && s[i].isDigit()) i++ }
            if ((peek() == 'e' || peek() == 'E') && s.getOrNull(i + 1)?.let { it.isDigit() || ((it == '+' || it == '-') && s.getOrNull(i + 2)?.isDigit() == true) } == true) {
                i += 2
                while (i < s.length && s[i].isDigit()) i++
            }
            val number = s.substring(start, i).toDoubleOrNull() ?: throw Bad()
            if (s.substring(start, i).none { it.isDigit() }) throw Bad()
            if (peek() == '%') { i++; return Value(Kind.PERCENT, number) }
            if (peek()?.isLetter() != true) return Value(Kind.NUMBER, number)
            return when (val unit = ident()) {
                in ABSOLUTE -> Value(Kind.LENGTH, number * ABSOLUTE.getValue(unit))
                in RELATIVE -> Value(Kind.LENGTH, 0.0, relative = true)
                "deg" -> Value(Kind.ANGLE, number)
                "rad" -> Value(Kind.ANGLE, number * (180.0 / PI))
                "grad" -> Value(Kind.ANGLE, number * 0.9)
                "turn" -> Value(Kind.ANGLE, number * 360.0)
                else -> throw Bad()
            }
        }

        /** A sum of products, as calc() reads it; a + or a - needs white space on both sides. */
        private fun sum(): Value {
            var left = product()
            while (true) {
                val save = i
                val spaced = i < s.length && s[i].isWhitespace()
                ws()
                val op = peek()
                if (spaced && (op == '+' || op == '-') && s.getOrNull(i + 1)?.isWhitespace() == true) {
                    i++
                    val right = product()
                    left = add(left, right, if (op == '+') 1.0 else -1.0)
                } else {
                    i = save
                    return left
                }
            }
        }

        private fun add(a: Value, b: Value, sign: Double): Value {
            val rel = a.relative || b.relative
            return when {
                a.kind == b.kind -> Value(a.kind, a.v + sign * b.v, rel)
                // A percentage beside a length resolves against a box only an element has.
                (a.kind == Kind.LENGTH && b.kind == Kind.PERCENT) || (a.kind == Kind.PERCENT && b.kind == Kind.LENGTH) ->
                    Value(Kind.LENGTH, 0.0, relative = true)
                else -> throw Bad()
            }
        }

        private fun product(): Value {
            var left = term()
            while (true) {
                val save = i
                ws()
                val op = peek()
                if (op == '*' || op == '/') {
                    i++
                    val right = term()
                    left = if (op == '*') {
                        when {
                            right.kind == Kind.NUMBER -> Value(left.kind, left.v * right.v, left.relative)
                            left.kind == Kind.NUMBER -> Value(right.kind, left.v * right.v, right.relative)
                            else -> throw Bad()
                        }
                    } else {
                        if (right.kind != Kind.NUMBER) throw Bad()
                        Value(left.kind, left.v / right.v, left.relative)
                    }
                } else {
                    i = save
                    return left
                }
            }
        }
    }

    /** A length in px, through a float as Chromium keeps it; a zero number counts as a length. */
    private fun length(v: Value, percentOk: Boolean = true): Double = when {
        v.kind == Kind.LENGTH && v.relative -> throw Unresolvable()
        v.kind == Kind.LENGTH -> fround(v.v)
        v.kind == Kind.PERCENT && percentOk -> throw Unresolvable()
        v.kind == Kind.NUMBER && v.v == 0.0 -> 0.0
        else -> throw Bad()
    }

    private fun angle(v: Value): Double = when {
        v.kind == Kind.ANGLE -> v.v
        v.kind == Kind.NUMBER && v.v == 0.0 -> 0.0
        else -> throw Bad()
    }

    /** Rounds to the nearest float. Kotlin/JS keeps a Float as a double, so this goes through its bits. */
    private fun fround(v: Double): Double = Float.fromBits(v.toFloat().toBits()).toDouble()

    private fun number(v: Value): Double = if (v.kind == Kind.NUMBER) v.v else throw Bad()

    /** A scale factor: a number or a percentage, through a float. */
    private fun factor(v: Value): Double = when (v.kind) {
        Kind.NUMBER -> fround(v.v)
        Kind.PERCENT -> fround(v.v / 100.0)
        else -> throw Bad()
    }

    /** The matrix of one function and whether it is a 2D one. */
    private fun function(name: String, a: List<Value>): Pair<DoubleArray, Boolean> {
        fun count(vararg n: Int) { if (a.size !in n) throw Bad() }
        return when (name) {
            "matrix" -> {
                count(6)
                val n = a.map(::number)
                matrix2d(n[0], n[1], n[2], n[3], n[4], n[5]) to true
            }
            "matrix3d" -> {
                count(16)
                DoubleArray(16) { number(a[it]) } to false
            }
            "translate" -> { count(1, 2); translation(length(a[0]), if (a.size > 1) length(a[1]) else 0.0, 0.0) to true }
            "translatex" -> { count(1); translation(length(a[0]), 0.0, 0.0) to true }
            "translatey" -> { count(1); translation(0.0, length(a[0]), 0.0) to true }
            "translatez" -> { count(1); translation(0.0, 0.0, length(a[0], percentOk = false)) to false }
            "translate3d" -> { count(3); translation(length(a[0]), length(a[1]), length(a[2], percentOk = false)) to false }
            "scale" -> { count(1, 2); val x = factor(a[0]); scaling(x, if (a.size > 1) factor(a[1]) else x, 1.0) to true }
            "scalex" -> { count(1); scaling(factor(a[0]), 1.0, 1.0) to true }
            "scaley" -> { count(1); scaling(1.0, factor(a[0]), 1.0) to true }
            "scalez" -> { count(1); scaling(1.0, 1.0, factor(a[0])) to false }
            "scale3d" -> { count(3); scaling(factor(a[0]), factor(a[1]), factor(a[2])) to false }
            "rotate" -> { count(1); rotation(0.0, 0.0, 1.0, angle(a[0])) to true }
            "rotatez" -> { count(1); rotation(0.0, 0.0, 1.0, angle(a[0])) to true }
            "rotatex" -> { count(1); rotation(1.0, 0.0, 0.0, angle(a[0])) to false }
            "rotatey" -> { count(1); rotation(0.0, 1.0, 0.0, angle(a[0])) to false }
            "rotate3d" -> { count(4); rotation(number(a[0]), number(a[1]), number(a[2]), angle(a[3])) to false }
            "skew" -> { count(1, 2); skew(angle(a[0]), if (a.size > 1) angle(a[1]) else 0.0) to true }
            "skewx" -> { count(1); skew(angle(a[0]), 0.0) to true }
            "skewy" -> { count(1); skew(0.0, angle(a[0])) to true }
            "perspective" -> {
                count(1)
                val d = a[0]
                // A depth below 1px draws as 1px (CSS Transforms 2, 13.1).
                val depth = if (d.kind == Kind.NUMBER && d.v == 0.0) 0.0 else if (d.kind == Kind.LENGTH && !d.relative) d.v else if (d.kind == Kind.LENGTH) throw Unresolvable() else throw Bad()
                if (depth < 0) throw Bad()
                IDENTITY.copyOf().also { it[11] = -1.0 / maxOf(depth, 1.0) } to false
            }
            else -> throw Bad()
        }
    }

    val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    private fun matrix2d(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double) =
        doubleArrayOf(a, b, 0.0, 0.0, c, d, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, e, f, 0.0, 1.0)

    private fun translation(x: Double, y: Double, z: Double) = IDENTITY.copyOf().also { it[12] = x; it[13] = y; it[14] = z }

    private fun scaling(x: Double, y: Double, z: Double) = IDENTITY.copyOf().also { it[0] = x; it[5] = y; it[10] = z }

    private fun skew(ax: Double, ay: Double) = IDENTITY.copyOf().also {
        it[4] = tan(ax * PI / 180.0)
        it[1] = tan(ay * PI / 180.0)
    }

    /** The sine and cosine of [degrees], exact at each multiple of 45 degrees as in Chromium. */
    fun sinCos(degrees: Double): Pair<Double, Double> {
        if (degrees % 45.0 == 0.0) {
            val r = sqrt(0.5)
            return when ((((degrees / 45.0) % 8.0 + 8.0) % 8.0).toInt()) {
                0 -> 0.0 to 1.0
                1 -> r to r
                2 -> 1.0 to 0.0
                3 -> r to -r
                4 -> 0.0 to -1.0
                5 -> -r to -r
                6 -> -1.0 to 0.0
                else -> -r to r
            }
        }
        val rad = degrees * PI / 180.0
        return sin(rad) to cos(rad)
    }

    /** A rotation by [degrees] about the axis ([x], [y], [z]), as rotate3d() defines it. */
    fun rotation(x: Double, y: Double, z: Double, degrees: Double): DoubleArray {
        val length = sqrt(x * x + y * y + z * z)
        if (length == 0.0 || length.isNaN()) return IDENTITY.copyOf()
        val nx = x / length
        val ny = y / length
        val nz = z / length
        val out = IDENTITY.copyOf()
        val axis = when {
            nx == 1.0 && ny == 0.0 && nz == 0.0 -> 0
            nx == 0.0 && ny == 1.0 && nz == 0.0 -> 1
            nx == 0.0 && ny == 0.0 && nz == 1.0 -> 2
            else -> -1
        }
        if (axis >= 0) {
            val (s, c) = sinCos(degrees)
            when (axis) {
                0 -> { out[5] = c; out[6] = s; out[9] = -s; out[10] = c }
                1 -> { out[0] = c; out[2] = -s; out[8] = s; out[10] = c }
                else -> { out[0] = c; out[1] = s; out[4] = -s; out[5] = c }
            }
            return out
        }
        val r = degrees * PI / 180.0
        val s = sin(r)
        val c = cos(r)
        val k = 1.0 - c
        out[0] = nx * nx * k + c
        out[1] = nx * ny * k + nz * s
        out[2] = nz * nx * k - ny * s
        out[4] = nx * ny * k - nz * s
        out[5] = ny * ny * k + c
        out[6] = ny * nz * k + nx * s
        out[8] = nz * nx * k + ny * s
        out[9] = ny * nz * k - nx * s
        out[10] = nz * nz * k + c
        return out
    }

    /** [a] times [b], each column-major (m11, m12, ... m44): [b] applies first. */
    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var sum = 0.0
            for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
            out[col * 4 + row] = sum
        }
        return out
    }
}
