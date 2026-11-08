package io.github.yuroyami.kitepdf.core.css

/**
 * The CSS math functions `calc()`, `min()`, `max()` and `clamp()` over lengths, percentages and
 * numbers, with `+`, `-`, `*`, `/`, brackets and nesting (CSS Values and Units 4, 10; #930).
 *
 * A length or a percentage resolves to points through [leaf] as it is read, so `calc(100% - 2em)`
 * gives points against the same percentage basis as `%` alone. A sum of a length and a number, a
 * product of two lengths, a division by a length or by zero, or a result that is a number where a
 * length is wanted makes the value invalid, and [evaluate] answers null.
 */
internal class CssMath private constructor(private val s: String, private val leaf: (String) -> Double?) {

    /** A value: points when [length], else a plain number. */
    private class Value(val amount: Double, val length: Boolean)

    private var p = 0
    private var depth = 0

    private fun fail(): Nothing = throw IllegalArgumentException()

    private fun skipSpace() { while (p < s.length && s[p].isWhitespace()) p++ }

    /** sum := product (('+' | '-') product)*, where + and - need white space on both sides. */
    private fun sum(): Value {
        var v = product()
        while (true) {
            val save = p
            skipSpace()
            if (p >= s.length || (s[p] != '+' && s[p] != '-') || p == save) { p = save; return v }
            val op = s[p++]
            if (p >= s.length || !s[p].isWhitespace()) fail()
            skipSpace()
            val r = product()
            if (r.length != v.length) fail()
            v = Value(if (op == '+') v.amount + r.amount else v.amount - r.amount, v.length)
        }
    }

    /** product := term (('*' | '/') term)* */
    private fun product(): Value {
        var v = term()
        while (true) {
            val save = p
            skipSpace()
            if (p >= s.length || (s[p] != '*' && s[p] != '/')) { p = save; return v }
            val op = s[p++]
            skipSpace()
            val r = term()
            v = if (op == '*') {
                if (v.length && r.length) fail()
                Value(v.amount * r.amount, v.length || r.length)
            } else {
                if (r.length || r.amount == 0.0) fail()
                Value(v.amount / r.amount, v.length)
            }
        }
    }

    /** term := '(' sum ')' | function '(' args ')' | number | dimension | percentage */
    private fun term(): Value {
        skipSpace()
        if (p >= s.length || ++depth > MAX_DEPTH) fail()
        try {
            return termAt()
        } finally {
            depth--
        }
    }

    private fun termAt(): Value {
        if (s[p] == '(') {
            p++
            val v = sum()
            skipSpace()
            if (p >= s.length || s[p] != ')') fail()
            p++
            return v
        }
        val name = FUNCTIONS.firstOrNull { s.startsWith(it, p) }
        if (name != null) {
            p += name.length
            val args = ArrayList<Value>()
            while (true) {
                skipSpace()
                args += sum()
                skipSpace()
                if (p >= s.length) fail()
                if (s[p] == ',') { p++; continue }
                if (s[p] == ')') { p++; break }
                fail()
            }
            if (args.any { it.length != args[0].length }) fail()
            val length = args[0].length
            return when (name) {
                "calc(" -> if (args.size == 1) args[0] else fail()
                "min(" -> Value(args.minOf { it.amount }, length)
                "max(" -> Value(args.maxOf { it.amount }, length)
                else -> {
                    if (args.size != 3) fail()
                    Value(maxOf(args[0].amount, minOf(args[1].amount, args[2].amount)), length)
                }
            }
        }
        val start = p
        if (p < s.length && (s[p] == '+' || s[p] == '-')) p++
        while (p < s.length && (s[p].isDigit() || s[p] == '.')) p++
        if (p < s.length && (s[p] == 'e') && p + 1 < s.length && (s[p + 1].isDigit() || s[p + 1] == '-' || s[p + 1] == '+')) {
            p += 2
            while (p < s.length && s[p].isDigit()) p++
        }
        val numberEnd = p
        if (numberEnd == start) fail()
        while (p < s.length && (s[p].isLetter() || s[p] == '%')) p++
        val token = s.substring(start, p)
        if (p == numberEnd) return Value(token.toDoubleOrNull() ?: fail(), false)
        return Value(leaf(token) ?: fail(), true)
    }

    companion object {
        private val FUNCTIONS = listOf("calc(", "min(", "max(", "clamp(")

        /** True when [raw] is a math function that [evaluate] reads. */
        fun isMath(raw: String): Boolean = FUNCTIONS.any { raw.startsWith(it) }

        /**
         * The length in points that the math function [raw], lower case and trimmed, gives, each
         * length or percentage in it read by [leaf]; null when it is invalid or a plain number.
         */
        fun evaluate(raw: String, leaf: (String) -> Double?): Double? = valueOf(raw, leaf)?.takeIf { it.length }?.amount

        /** The plain number that the math function [raw] gives, or null when it is invalid or a length. */
        fun evaluateNumber(raw: String): Double? = valueOf(raw) { null }?.takeIf { !it.length }?.amount

        private fun valueOf(raw: String, leaf: (String) -> Double?): Value? {
            if (raw.length > MAX_LENGTH) return null
            return try {
                val math = CssMath(raw, leaf)
                val v = math.term()
                math.skipSpace()
                if (math.p != raw.length || !v.amount.isFinite()) null else v
            } catch (invalid: IllegalArgumentException) {
                null
            }
        }

        /** A longer value is a broken file, not a style. */
        private const val MAX_LENGTH = 4096

        /** How deep brackets and functions nest before the value is refused, far below any stack's reach. */
        private const val MAX_DEPTH = 32
    }
}
