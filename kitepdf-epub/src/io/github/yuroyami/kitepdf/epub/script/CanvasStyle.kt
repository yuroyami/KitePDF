package io.github.yuroyami.kitepdf.epub.script

import io.github.yuroyami.kitepdf.core.css.CssValues
import io.github.yuroyami.kitepdf.epub.cssNumber
import kotlin.math.roundToInt

/** A colour as a 2D context keeps it: eight bits for each channel and for alpha. */
internal data class CanvasColor(val r: Int, val g: Int, val b: Int, val a: Int) {

    val alpha: Double get() = a / 255.0

    /** `#rrggbb`, which SVG reads as the colour without its alpha. */
    fun hex(): String = "#" + hex2(r) + hex2(g) + hex2(b)

    /** How the context answers the colour: `#rrggbb` when opaque, else `rgba(r, g, b, a)` (HTML, 4.12.5.1.18). */
    fun serialize(): String = if (a == 255) hex() else "rgba($r, $g, $b, ${alphaText(a)})"

    companion object {
        val BLACK = CanvasColor(0, 0, 0, 255)
        val TRANSPARENT = CanvasColor(0, 0, 0, 0)

        /** A CSS colour, with `currentcolor` as [current], or null when [raw] is not one. */
        fun parse(raw: String, current: () -> CanvasColor): CanvasColor? {
            val s = raw.trim()
            if (s.equals("currentcolor", ignoreCase = true)) return current()
            if (s.equals("transparent", ignoreCase = true)) return TRANSPARENT
            val rgb = CssValues.color(s) ?: return null
            val alpha = CssValues.alpha(s) ?: 1.0
            return CanvasColor(byte(rgb.r), byte(rgb.g), byte(rgb.b), byte(alpha))
        }

        private fun byte(v: Double): Int = (v * 255.0).roundToInt().coerceIn(0, 255)

        private fun hex2(v: Int): String = v.toString(16).padStart(2, '0')

        /** Alpha with two decimals when they give back the same byte, else three, as CSS Color 4, 15.2 serializes it. */
        private fun alphaText(a: Int): String {
            val hundredths = (a / 255.0 * 100).roundToInt()
            if ((hundredths / 100.0 * 255).roundToInt() == a) return decimal(hundredths, 2)
            return decimal((a / 255.0 * 1000).roundToInt(), 3)
        }

        private fun decimal(n: Int, places: Int): String {
            val scale = if (places == 2) 100 else 1000
            if (n == 0) return "0"
            if (n == scale) return "1"
            return "0." + n.toString().padStart(places, '0').trimEnd('0')
        }
    }
}

/**
 * A font as the context's `font` attribute holds it: the CSS `font` shorthand with its sizes in
 * pixels and its line height dropped (HTML, 4.12.5.1.4).
 */
internal class CanvasFont(val italic: Boolean, val weight: Int, val smallCaps: Boolean, val sizePx: Double, val families: List<String>) {

    /** The family list as `font-family` reads it. */
    val familyList: String get() = families.joinToString(", ")

    /** How Chromium writes the font back: style, weight, caps, size and families. */
    fun serialize(): String = buildString {
        if (italic) append("italic ")
        when (weight) {
            400 -> {}
            700 -> append("bold ")
            else -> append(weight).append(' ')
        }
        if (smallCaps) append("small-caps ")
        append(cssNumber(sizePx)).append("px ")
        append(familyList)
    }

    companion object {
        val DEFAULT = CanvasFont(false, 400, false, 10.0, listOf("sans-serif"))

        private val STRETCH = setOf(
            "ultra-condensed", "extra-condensed", "condensed", "semi-condensed",
            "semi-expanded", "expanded", "extra-expanded", "ultra-expanded",
        )
        private val SYSTEM = setOf("caption", "icon", "menu", "message-box", "small-caption", "status-bar")
        private val WIDE = setOf("inherit", "initial", "unset", "default", "revert", "revert-layer")
        private val ABSOLUTE = mapOf(
            "xx-small" to 9.0, "x-small" to 10.0, "small" to 13.0, "medium" to 16.0,
            "large" to 18.0, "x-large" to 24.0, "xx-large" to 32.0, "xxx-large" to 48.0,
        )

        /**
         * Parses the `font` shorthand. [base] gives the font size of the canvas element, which `em`,
         * `%` and the relative keywords use, or 10 when the canvas is not in a document.
         */
        fun parse(raw: String, base: () -> Double): CanvasFont? {
            val basePx by lazy(base)
            val text = raw.trim()
            if (text.lowercase() in SYSTEM) return CanvasFont(false, 400, false, 16.0, listOf("Arial"))
            val words = split(text) ?: return null
            var i = 0
            var italic: Boolean? = null
            var weight: Int? = null
            var caps: Boolean? = null
            var stretch: Boolean? = null
            var size: Double? = null
            var prefix = 0
            while (i < words.size && size == null) {
                val w = words[i]
                val lower = w.lowercase()
                size = sizeOf(w) { basePx }
                if (size != null) { i++; break }
                if (prefix == 4) return null
                prefix++
                when {
                    lower == "normal" -> {}
                    lower == "italic" || lower == "oblique" -> { if (italic != null) return null; italic = true }
                    lower == "small-caps" -> { if (caps != null) return null; caps = true }
                    lower == "bold" || lower == "bolder" -> { if (weight != null) return null; weight = 700 }
                    lower == "lighter" -> { if (weight != null) return null; weight = 100 }
                    lower in STRETCH -> { if (stretch != null) return null; stretch = true }
                    else -> {
                        val n = w.toDoubleOrNull()?.takeIf { it >= 1 && it <= 1000 && !w.contains('e', true) } ?: return null
                        if (weight != null) return null
                        weight = n.roundToInt()
                    }
                }
                i++
            }
            if (size == null) return null
            // A line height after a slash is dropped.
            if (i < words.size && words[i].startsWith("/")) {
                val rest = words[i].drop(1)
                if (rest.isEmpty()) { i++; if (i >= words.size) return null }
                i++
            }
            if (i >= words.size) return null
            val families = familiesOf(words.subList(i, words.size).joinToString(" ")) ?: return null
            return CanvasFont(italic == true, weight ?: 400, caps == true, size, families)
        }

        /** The words of [text], with a quoted family kept whole and a slash split from the size before it. */
        private fun split(text: String): List<String>? {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var quote: Char? = null
            for (c in text) {
                when {
                    quote != null -> { cur.append(c); if (c == quote) quote = null }
                    c == '"' || c == '\'' -> { cur.append(c); quote = c }
                    c == '/' && out.isNotEmpty() || c == '/' && cur.isNotEmpty() -> {
                        if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                        cur.append(c)
                    }
                    c.isWhitespace() -> if (cur.isNotEmpty() && cur.toString() != "/") { out.add(cur.toString()); cur.clear() }
                    else -> cur.append(c)
                }
            }
            if (quote != null) return null
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out
        }

        /** A font size in pixels, or null when [w] is not one. */
        private fun sizeOf(w: String, base: () -> Double): Double? {
            val s = w.lowercase()
            val basePx by lazy(base)
            ABSOLUTE[s]?.let { return it }
            if (s == "larger") return basePx * 1.2
            if (s == "smaller") return basePx / 1.2
            if (s == "0") return 0.0
            val unit = s.takeLastWhile { it.isLetter() || it == '%' }
            val number = s.dropLast(unit.length)
            if (unit.isEmpty() || number.isEmpty()) return null
            if (!number.all { it.isDigit() || it == '.' || it == '-' || it == '+' || it == 'e' || it == 'E' }) return null
            val n = number.toDoubleOrNull() ?: return null
            if (n < 0 || !n.isFinite()) return null
            return when (unit) {
                "px" -> n
                "pt" -> n * 4.0 / 3.0
                "pc" -> n * 16.0
                "in" -> n * 96.0
                "cm" -> n * 96.0 / 2.54
                "mm" -> n * 96.0 / 25.4
                "q" -> n * 96.0 / 101.6
                "em" -> n * basePx
                "%" -> n * basePx / 100.0
                "rem" -> n * 16.0
                "ex", "ch" -> n * basePx / 2.0
                else -> null
            }
        }

        /** The family list, each name as Chromium writes it back, or null when one is not a family. */
        private fun familiesOf(text: String): List<String>? {
            val out = ArrayList<String>()
            for (part in splitCommas(text) ?: return null) {
                val p = part.trim()
                if (p.isEmpty()) return null
                if (p[0] == '"' || p[0] == '\'') {
                    if (p.length < 2 || p.last() != p[0]) return null
                    out.add("\"" + p.substring(1, p.length - 1) + "\"")
                    continue
                }
                val idents = p.split(' ', '\t', '\n').filter { it.isNotEmpty() }
                if (idents.isEmpty() || idents.any { !isIdent(it) }) return null
                if (idents.size == 1 && idents[0].lowercase() in WIDE) return null
                out.add(idents.joinToString(" "))
            }
            return out
        }

        private fun splitCommas(text: String): List<String>? {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var quote: Char? = null
            for (c in text) {
                when {
                    quote != null -> { cur.append(c); if (c == quote) quote = null }
                    c == '"' || c == '\'' -> { cur.append(c); quote = c }
                    c == ',' -> { out.add(cur.toString()); cur.clear() }
                    else -> cur.append(c)
                }
            }
            if (quote != null) return null
            out.add(cur.toString())
            return out
        }

        private fun isIdent(s: String): Boolean {
            val body = s.removePrefix("-")
            if (body.isEmpty() || body[0].isDigit() || body.startsWith("-") && body.length == 1) return false
            return s.all { it.isLetterOrDigit() || it == '-' || it == '_' || it.code > 127 }
        }
    }
}

/** The keyword attributes of the context and the values each accepts (HTML, 4.12.5.1). */
internal object CanvasKeywords {
    val LINE_CAP = setOf("butt", "round", "square")
    val LINE_JOIN = setOf("round", "bevel", "miter")
    val TEXT_ALIGN = setOf("start", "end", "left", "right", "center")
    val TEXT_BASELINE = setOf("top", "hanging", "middle", "alphabetic", "ideographic", "bottom")
    val DIRECTION = setOf("ltr", "rtl", "inherit")
    val SMOOTHING_QUALITY = setOf("low", "medium", "high")
    val FONT_KERNING = setOf("auto", "normal", "none")
    val FONT_STRETCH = setOf(
        "ultra-condensed", "extra-condensed", "condensed", "semi-condensed", "normal",
        "semi-expanded", "expanded", "extra-expanded", "ultra-expanded",
    )
    val FONT_VARIANT_CAPS = setOf("normal", "small-caps", "all-small-caps", "petite-caps", "all-petite-caps", "unicase", "titling-caps")
    val TEXT_RENDERING = setOf("auto", "optimizeSpeed", "optimizeLegibility", "geometricPrecision")

    /** `globalCompositeOperation`: the Porter-Duff operators and the blend modes (Compositing and Blending 1). */
    val COMPOSITE = setOf(
        "source-over", "source-in", "source-out", "source-atop", "destination-over", "destination-in",
        "destination-out", "destination-atop", "lighter", "copy", "xor",
        "multiply", "screen", "overlay", "darken", "lighten", "color-dodge", "color-burn", "hard-light",
        "soft-light", "difference", "exclusion", "hue", "saturation", "color", "luminosity",
    )

    private val LENGTH_UNITS = setOf("px", "pt", "pc", "in", "cm", "mm", "q", "em", "rem", "ex", "ch", "vw", "vh", "vmin", "vmax")

    /** Whether [s] is a CSS length, as `letterSpacing` and `wordSpacing` take. A bare number is not one, except zero. */
    fun isLength(s: String): Boolean {
        val t = s.trim().lowercase()
        if (t == "0") return true
        val unit = t.takeLastWhile { it.isLetter() }
        val number = t.dropLast(unit.length)
        return unit in LENGTH_UNITS && number.isNotEmpty() && number.toDoubleOrNull()?.isFinite() == true
    }

    private val FILTER_AMOUNT = setOf("brightness", "contrast", "grayscale", "invert", "opacity", "saturate", "sepia")

    /** Whether [s] is a CSS filter value: `none` or a list of filter functions (Filter Effects 1, 15). */
    fun isFilter(s: String): Boolean {
        val t = s.trim()
        if (t == "none") return true
        if (t.isEmpty()) return false
        var i = 0
        var count = 0
        while (i < t.length) {
            while (i < t.length && t[i].isWhitespace()) i++
            if (i >= t.length) break
            val open = t.indexOf('(', i)
            if (open < 0) return false
            val name = t.substring(i, open).lowercase()
            var depth = 0
            var close = open
            while (close < t.length) {
                if (t[close] == '(') depth++
                if (t[close] == ')') { depth--; if (depth == 0) break }
                close++
            }
            if (close >= t.length) return false
            val arg = t.substring(open + 1, close).trim()
            val ok = when {
                name == "url" -> arg.isNotEmpty()
                name == "blur" -> arg.isEmpty() || isLength(arg) && !arg.startsWith("-")
                name in FILTER_AMOUNT -> arg.isEmpty() || amount(arg)
                name == "hue-rotate" -> arg.isEmpty() || angle(arg)
                name == "drop-shadow" -> arg.isNotEmpty()
                else -> false
            }
            if (!ok) return false
            count++
            i = close + 1
        }
        return count > 0
    }

    private fun amount(arg: String): Boolean {
        val n = (if (arg.endsWith("%")) arg.dropLast(1) else arg).toDoubleOrNull() ?: return false
        return n >= 0 && n.isFinite()
    }

    private fun angle(arg: String): Boolean {
        if (arg == "0") return true
        val unit = arg.takeLastWhile { it.isLetter() }.lowercase()
        return unit in setOf("deg", "rad", "grad", "turn") && arg.dropLast(unit.length).toDoubleOrNull()?.isFinite() == true
    }
}
