package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.css.CssValues
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round

/**
 * The value sanitization algorithm of each input type (HTML, 4.10.5.1), and the API value of a
 * textarea (HTML, 4.10.11), which a script's `value` and the form selectors both read (#605).
 */
internal object FormValues {

    /**
     * The sanitized form of [value] for an input of [type], whose other attributes [attr] gives. A
     * textarea, as type `textarea`, gets its API value, whose line breaks are LF.
     */
    fun sanitize(type: String, value: String, attr: (String) -> String?): String = when (type) {
        "text", "search", "tel", "password" -> stripNewlines(value)
        "url" -> trim(stripNewlines(value))
        "email" -> if (attr("multiple") != null) stripNewlines(value).split(',').joinToString(",") { trim(it) } else trim(stripNewlines(value))
        "number" -> if (isFloat(value)) value else ""
        "range" -> range(value, attr)
        // Chromium parses no colour with white space around it, though CSS would.
        "color" -> CssValues.color(value).takeIf { value == trim(value) }?.let { c -> "#" + listOf(c.r, c.g, c.b).joinToString("") { hex2(it) } } ?: "#000000"
        "date" -> if (date(value) != null) value else ""
        "month" -> if (month(value)) value else ""
        "week" -> if (week(value)) value else ""
        "time" -> if (time(value) != null) value else ""
        "datetime-local" -> dateTimeLocal(value).orEmpty()
        "textarea" -> value.replace("\r\n", "\n").replace('\r', '\n')
        else -> value
    }

    private fun stripNewlines(s: String) = if ('\r' in s || '\n' in s) s.filter { it != '\r' && it != '\n' } else s
    private fun trim(s: String) = s.trim { it == ' ' || it == '\t' || it == '\n' || it == '\u000C' || it == '\r' }
    private fun hex2(channel: Double) = round(channel.coerceIn(0.0, 1.0) * 255).toInt().toString(16).padStart(2, '0')

    private val FLOAT = Regex("-?(\\d+(\\.\\d+)?|\\.\\d+)([eE][+-]?\\d+)?")

    /** A valid floating-point number (HTML, 2.3.4.3), whose value is finite. */
    private fun isFloat(s: String) = FLOAT.matches(s) && s.toDouble().isFinite()
    private fun number(s: String?) = s?.takeIf(::isFloat)?.toDouble()

    /**
     * A range's value (HTML, 4.10.5.1.13): its default value when the value is no number, then
     * clamped to the minimum and the maximum, then the nearest allowed step, the larger on a tie.
     */
    private fun range(value: String, attr: (String) -> String?): String {
        val min = number(attr("min")) ?: 0.0
        val max = (number(attr("max")) ?: 100.0).coerceAtLeast(min)
        var v = number(value) ?: (min + (max - min) / 2)
        v = v.coerceIn(min, max)
        val stepAttr = attr("step")
        if (stepAttr == null || !stepAttr.equals("any", ignoreCase = true)) {
            val step = number(stepAttr)?.takeIf { it > 0 } ?: 1.0
            // The step base is the minimum attribute, else the value attribute, else zero.
            val base = number(attr("min")) ?: number(attr("value")) ?: 0.0
            val digits = maxOf(decimals(step), decimals(base))
            // The quotient is rounded first, so 0.35 over a step of 0.1 is 3.5 and not 3.4999999999999996.
            var n = floor(roundTo((v - base) / step, 9) + 0.5)
            if (base + n * step > max) n = floor((max - base) / step)
            if (base + n * step < min) n = ceil((min - base) / step)
            v = roundTo(base + n * step, digits)
        }
        return jsNumber(v)
    }

    private fun decimals(d: Double): Int {
        val s = jsNumber(d)
        if ('e' in s) return 15
        return s.substringAfter('.', "").length
    }

    private fun roundTo(d: Double, digits: Int): Double = if (digits >= 15) d else 10.0.pow(digits).let { round(d * it) / it }

    /** The number as JavaScript's `String(number)` writes it, for the values a range holds. */
    private fun jsNumber(d: Double): String {
        if (d == 0.0) return "0"
        if (d == floor(d) && abs(d) < 1e21) return d.toLong().toString()
        val s = d.toString()
        if ('E' !in s) return s
        val mantissa = s.substringBefore('E').removeSuffix(".0")
        val exp = s.substringAfter('E')
        return mantissa + "e" + (if (exp.startsWith('-')) exp else "+$exp")
    }

    private fun digits(s: String, from: Int, count: Int): Int? {
        if (from + count > s.length) return null
        var n = 0
        for (i in from until from + count) { val c = s[i]; if (c !in '0'..'9') return null; n = n * 10 + (c - '0') }
        return n
    }

    /** The year of a year component at the start of [s], four or more digits above zero, and where it ends. */
    private fun year(s: String): Pair<Int, Int>? {
        var end = 0
        while (end < s.length && s[end] in '0'..'9') end++
        if (end < 4 || end > 9) return null
        val y = s.substring(0, end).toInt()
        return if (y > 0) y to end else null
    }

    private fun leap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    private fun daysIn(y: Int, m: Int) = when (m) { 2 -> if (leap(y)) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }

    /** A valid date string (HTML, 2.3.5.2), as the length it takes, or null. */
    private fun date(s: String, whole: Boolean = true): Int? {
        val (y, e) = year(s) ?: return null
        if (s.getOrNull(e) != '-' || s.getOrNull(e + 3) != '-') return null
        val m = digits(s, e + 1, 2)?.takeIf { it in 1..12 } ?: return null
        digits(s, e + 4, 2)?.takeIf { it in 1..daysIn(y, m) } ?: return null
        return (e + 6).takeIf { !whole || it == s.length }
    }

    private fun month(s: String): Boolean {
        val (_, e) = year(s) ?: return false
        return s.length == e + 3 && s[e] == '-' && digits(s, e + 1, 2)?.let { it in 1..12 } == true
    }

    /** A valid week string (HTML, 2.3.5.8): week 53 exists in a year that begins on a Thursday, or a leap year that begins on a Wednesday. */
    private fun week(s: String): Boolean {
        val (y, e) = year(s) ?: return false
        if (s.length != e + 4 || s[e] != '-' || s[e + 1] != 'W') return false
        val w = digits(s, e + 2, 2) ?: return false
        val jan1 = dayOfWeek(y)
        val weeks = if (jan1 == 4 || (jan1 == 3 && leap(y))) 53 else 52
        return w in 1..weeks
    }

    /** The day of the week of January 1 of [y], 0 for Sunday. */
    private fun dayOfWeek(y: Int): Int {
        val p = y - 1
        return ((1 + 5 * (p % 4) + 4 * (p % 100) + 6 * (p % 400)) % 7)
    }

    private class Time(val hour: Int, val minute: Int, val second: Int, val fraction: String, val end: Int)

    /** A valid time string (HTML, 2.3.5.4) at [from], as its parts and the end, or null. */
    private fun time(s: String, from: Int = 0, whole: Boolean = true): Time? {
        val h = digits(s, from, 2)?.takeIf { it < 24 } ?: return null
        if (s.getOrNull(from + 2) != ':') return null
        val m = digits(s, from + 3, 2)?.takeIf { it < 60 } ?: return null
        var end = from + 5
        var sec = 0
        var fraction = ""
        if (s.getOrNull(end) == ':') {
            sec = digits(s, end + 1, 2)?.takeIf { it < 60 } ?: return null
            end += 3
            if (s.getOrNull(end) == '.') {
                var f = end + 1
                while (f < s.length && s[f] in '0'..'9') f++
                if (f - end - 1 !in 1..3) return null
                fraction = s.substring(end + 1, f)
                end = f
            }
        }
        return Time(h, m, sec, fraction, end).takeIf { !whole || end == s.length }
    }

    /** The normalized form of a valid local date and time string (HTML, 2.3.5.5), or null. */
    private fun dateTimeLocal(s: String): String? {
        val e = date(s, whole = false) ?: return null
        if (s.getOrNull(e) != 'T' && s.getOrNull(e) != ' ') return null
        val t = time(s, e + 1) ?: return null
        val fraction = t.fraction.trimEnd('0')
        val clock = buildString {
            append(two(t.hour)).append(':').append(two(t.minute))
            if (t.second != 0 || fraction.isNotEmpty()) append(':').append(two(t.second))
            if (fraction.isNotEmpty()) append('.').append(fraction)
        }
        return s.substring(0, e) + "T" + clock
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')
}
