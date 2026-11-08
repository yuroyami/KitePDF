package io.github.yuroyami.kitepdf.epub.css

/**
 * Media Queries 4 for a reader that shows its pages on a screen: whether a media query list
 * matches a viewport of [widthPt] by [heightPt] (CSS Conditional Rules 3, 6; #841).
 *
 * `all` and `screen` match, and `print`, `speech` and every other type do not, such as
 * `amzn-kf8`. The features read are `width`, `height`, `aspect-ratio` and their `min-` and
 * `max-` forms, `orientation`, `prefers-color-scheme` (light), `color` and `monochrome`; an
 * unknown feature is false, and a list that does not parse matches nothing (Media Queries 4,
 * 3.2). An empty list matches. A height of 0 means none is known, so a height query is false.
 */
internal object MediaQueries {

    fun matches(list: String, widthPt: Double, heightPt: Double): Boolean {
        val text = list.trim().lowercase()
        if (text.isEmpty()) return true
        return splitTop(text, ',').any { query(it.trim(), widthPt, heightPt) }
    }

    private fun query(q: String, w: Double, h: Double): Boolean {
        if (q.isEmpty()) return false
        var rest = q
        var negate = false
        when {
            rest.startsWith("not ") -> { negate = true; rest = rest.removePrefix("not ").trim() }
            rest.startsWith("only ") -> rest = rest.removePrefix("only ").trim()
        }
        val parts = splitAnd(rest)
        if (parts.isEmpty()) return false
        var ok = true
        for ((i, part) in parts.withIndex()) {
            val p = part.trim()
            val value = when {
                p.startsWith("(") -> condition(p, w, h) ?: return false
                i == 0 -> when (p) {
                    "all", "screen" -> true
                    else -> false
                }
                else -> return false
            }
            ok = ok && value
        }
        return if (negate) !ok else ok
    }

    /** A parenthesized feature, `not (...)` inside, or null when it does not parse. */
    private fun condition(p: String, w: Double, h: Double): Boolean? {
        if (!p.startsWith("(") || !p.endsWith(")")) return null
        val inner = p.substring(1, p.length - 1).trim()
        if (inner.startsWith("(")) {
            // A nested condition: (a) and (b), (a) or (b), not (a).
            val ors = splitWord(inner, " or ")
            if (ors.size > 1) return ors.any { condition(it.trim(), w, h) ?: return null }
            val ands = splitAnd(inner)
            return ands.all { condition(it.trim(), w, h) ?: return null }
        }
        if (inner.startsWith("not ")) return condition(inner.removePrefix("not ").trim(), w, h)?.not()
        val colon = inner.indexOf(':')
        if (colon < 0) {
            return when (inner) {
                "color", "width", "orientation", "aspect-ratio", "prefers-color-scheme" -> true
                "height" -> h > 0.0
                "monochrome", "grid", "hover" -> false
                else -> false
            }
        }
        val name = inner.substring(0, colon).trim()
        val value = inner.substring(colon + 1).trim()
        return when (name) {
            "width", "min-width", "max-width" -> length(value)?.let { compare(name, w, it) } ?: false
            "height", "min-height", "max-height" -> if (h <= 0.0) false else length(value)?.let { compare(name, h, it) } ?: false
            "aspect-ratio", "min-aspect-ratio", "max-aspect-ratio" ->
                if (h <= 0.0) false else ratio(value)?.let { compare(name.replace("aspect-ratio", "x"), w / h, it) } ?: false
            "orientation" -> when (value) {
                "portrait" -> h <= 0.0 || h >= w
                "landscape" -> h > 0.0 && w > h
                else -> false
            }
            "prefers-color-scheme" -> value == "light"
            "color" -> value.toDoubleOrNull()?.let { it <= 8.0 } ?: false
            "min-color" -> value.toDoubleOrNull()?.let { it <= 8.0 } ?: false
            "monochrome", "max-monochrome" -> value.toDoubleOrNull() == 0.0
            else -> false
        }
    }

    private fun compare(name: String, actual: Double, target: Double): Boolean = when {
        name.startsWith("min-") -> actual >= target - EPSILON
        name.startsWith("max-") -> actual <= target + EPSILON
        else -> kotlin.math.abs(actual - target) <= EPSILON
    }

    /** A length in points: CSS px are 3/4 pt, and em and rem are the initial 16 px (Media Queries 4, 1.3). */
    private fun length(v: String): Double? {
        val m = UNIT.matchEntire(v) ?: return if (v == "0") 0.0 else null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2]) {
            "px" -> n * 0.75
            "pt" -> n
            "pc" -> n * 12.0
            "in" -> n * 72.0
            "cm" -> n * 72.0 / 2.54
            "mm" -> n * 72.0 / 25.4
            "q" -> n * 72.0 / 101.6
            "em", "rem" -> n * 12.0
            else -> null
        }
    }

    private fun ratio(v: String): Double? {
        val parts = v.split('/').map { it.trim().toDoubleOrNull() ?: return null }
        return when (parts.size) {
            1 -> parts[0]
            2 -> if (parts[1] == 0.0) null else parts[0] / parts[1]
            else -> null
        }
    }

    /** [text] split at [sep] outside parentheses. */
    private fun splitTop(text: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var from = 0
        for ((i, c) in text.withIndex()) {
            when (c) {
                '(' -> depth++
                ')' -> depth--
                sep -> if (depth == 0) { out.add(text.substring(from, i)); from = i + 1 }
            }
        }
        out.add(text.substring(from))
        return out
    }

    private fun splitAnd(text: String): List<String> = splitWord(text, " and ")

    /** [text] split at the word [sep], outside parentheses. */
    private fun splitWord(text: String, sep: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var from = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '(') depth++
            if (c == ')') depth--
            if (depth == 0 && text.startsWith(sep, i)) {
                out.add(text.substring(from, i)); i += sep.length; from = i; continue
            }
            i++
        }
        out.add(text.substring(from))
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val UNIT = Regex("(-?[0-9]*\\.?[0-9]+)([a-z]+)")
    private const val EPSILON = 1e-6
}
