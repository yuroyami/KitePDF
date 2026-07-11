package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.render.RgbColor

/**
 * Value parsers shared by the cascade: CSS lengths → points, and CSS colours →
 * [RgbColor]. Kept declaration-value-agnostic (they take the raw token text) so
 * the cascade can interpret each property against the right reference (font size,
 * root size, containing-block width).
 *
 * Length model: 1 CSS px = 1/96 in, 1 pt = 1/72 in, so px → pt is ×0.75. `em` is
 * relative to the caller's [fontSizePt] (the parent's size when resolving
 * `font-size` itself, else the element's own), `rem` to [rootPt], `%` to
 * [refPt] (parent size for font-size, containing width for margins, etc.).
 */
internal object CssValues {

    /** Parse a CSS `<length>`/`<percentage>` to points, or null if not a length. */
    fun length(raw: String, fontSizePt: Double, rootPt: Double, refPt: Double): Double? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        fun numOf(suffix: String) = s.removeSuffix(suffix).trim().toDoubleOrNull()
        return when {
            s.endsWith("px") -> numOf("px")?.let { it * 0.75 }
            s.endsWith("pt") -> numOf("pt")
            s.endsWith("rem") -> numOf("rem")?.let { it * rootPt }
            s.endsWith("em") -> numOf("em")?.let { it * fontSizePt }
            s.endsWith("ex") -> numOf("ex")?.let { it * fontSizePt * 0.5 }
            s.endsWith("ch") -> numOf("ch")?.let { it * fontSizePt * 0.5 }
            s.endsWith("pc") -> numOf("pc")?.let { it * 12.0 }
            s.endsWith("in") -> numOf("in")?.let { it * 72.0 }
            s.endsWith("cm") -> numOf("cm")?.let { it * 28.3465 }
            s.endsWith("mm") -> numOf("mm")?.let { it * 2.83465 }
            s.endsWith("vw") -> numOf("vw")?.let { it / 100.0 * refPt }
            s.endsWith("vh") -> numOf("vh")?.let { it / 100.0 * refPt }
            s.endsWith("%") -> numOf("%")?.let { it / 100.0 * refPt }
            s == "0" -> 0.0
            else -> null // unitless non-zero is not a valid length here
        }
    }

    /** Absolute/relative `font-size` keywords → points. [mediumPt] is the base size. */
    fun fontSizeKeyword(raw: String, parentPt: Double, mediumPt: Double): Double? = when (raw.trim().lowercase()) {
        "xx-small" -> mediumPt * 0.6
        "x-small" -> mediumPt * 0.75
        "small" -> mediumPt * 0.89
        "medium" -> mediumPt
        "large" -> mediumPt * 1.2
        "x-large" -> mediumPt * 1.5
        "xx-large" -> mediumPt * 2.0
        "smaller" -> parentPt * 0.833
        "larger" -> parentPt * 1.2
        else -> null
    }

    /** Parse a CSS `<color>`; null for `transparent`, `inherit`, or unrecognised. */
    fun color(raw: String): RgbColor? {
        val s = raw.trim().lowercase()
        if (s.isEmpty() || s == "transparent" || s == "inherit" || s == "currentcolor" || s == "none") return null
        if (s.startsWith("#")) return hexColor(s.substring(1))
        if (s.startsWith("rgb")) return rgbFunc(s)
        return NAMED[s]
    }

    private fun hexColor(h: String): RgbColor? {
        fun c(v: Int) = v / 255.0
        return when (h.length) {
            3 -> {
                val r = hex(h[0]); val g = hex(h[1]); val b = hex(h[2])
                if (r < 0 || g < 0 || b < 0) null else RgbColor(c(r * 17), c(g * 17), c(b * 17))
            }
            4 -> hexColor(h.substring(0, 3)) // #rgba -> drop alpha
            6, 8 -> {
                val r = hex2(h, 0); val g = hex2(h, 2); val b = hex2(h, 4)
                if (r < 0 || g < 0 || b < 0) null else RgbColor(c(r), c(g), c(b))
            }
            else -> null
        }
    }

    private fun rgbFunc(s: String): RgbColor? {
        val open = s.indexOf('('); val close = s.indexOf(')')
        if (open < 0 || close < open) return null
        val parts = s.substring(open + 1, close).split(',', ' ', '/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        fun comp(p: String): Double? =
            if (p.endsWith("%")) p.dropLast(1).toDoubleOrNull()?.let { it / 100.0 }
            else p.toDoubleOrNull()?.let { it / 255.0 }
        val r = comp(parts[0]); val g = comp(parts[1]); val b = comp(parts[2])
        if (r == null || g == null || b == null) return null
        return RgbColor(r.coerceIn(0.0, 1.0), g.coerceIn(0.0, 1.0), b.coerceIn(0.0, 1.0))
    }

    private fun hex(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        else -> -1
    }

    private fun hex2(s: String, i: Int): Int {
        val hi = hex(s[i]); val lo = hex(s[i + 1])
        return if (hi < 0 || lo < 0) -1 else hi * 16 + lo
    }

    private fun rgb(r: Int, g: Int, b: Int) = RgbColor(r / 255.0, g / 255.0, b / 255.0)

    // A pragmatic subset of the CSS named colours (the ones books actually use).
    private val NAMED: Map<String, RgbColor> = mapOf(
        "black" to rgb(0, 0, 0), "white" to rgb(255, 255, 255),
        "red" to rgb(255, 0, 0), "green" to rgb(0, 128, 0), "blue" to rgb(0, 0, 255),
        "yellow" to rgb(255, 255, 0), "cyan" to rgb(0, 255, 255), "aqua" to rgb(0, 255, 255),
        "magenta" to rgb(255, 0, 255), "fuchsia" to rgb(255, 0, 255),
        "gray" to rgb(128, 128, 128), "grey" to rgb(128, 128, 128),
        "silver" to rgb(192, 192, 192), "lightgray" to rgb(211, 211, 211), "lightgrey" to rgb(211, 211, 211),
        "darkgray" to rgb(169, 169, 169), "darkgrey" to rgb(169, 169, 169), "dimgray" to rgb(105, 105, 105),
        "maroon" to rgb(128, 0, 0), "olive" to rgb(128, 128, 0), "lime" to rgb(0, 255, 0),
        "teal" to rgb(0, 128, 128), "navy" to rgb(0, 0, 128), "purple" to rgb(128, 0, 128),
        "orange" to rgb(255, 165, 0), "brown" to rgb(165, 42, 42), "pink" to rgb(255, 192, 203),
        "gold" to rgb(255, 215, 0), "indigo" to rgb(75, 0, 130), "violet" to rgb(238, 130, 238),
        "beige" to rgb(245, 245, 220), "ivory" to rgb(255, 255, 240), "khaki" to rgb(240, 230, 140),
        "crimson" to rgb(220, 20, 60), "coral" to rgb(255, 127, 80), "salmon" to rgb(250, 128, 114),
        "tan" to rgb(210, 180, 140), "tomato" to rgb(255, 99, 71), "orangered" to rgb(255, 69, 0),
        "darkred" to rgb(139, 0, 0), "darkgreen" to rgb(0, 100, 0), "darkblue" to rgb(0, 0, 139),
        "lightblue" to rgb(173, 216, 230), "lightgreen" to rgb(144, 238, 144), "lightyellow" to rgb(255, 255, 224),
        "steelblue" to rgb(70, 130, 180), "royalblue" to rgb(65, 105, 225), "slategray" to rgb(112, 128, 144),
        "whitesmoke" to rgb(245, 245, 245), "gainsboro" to rgb(220, 220, 220), "snow" to rgb(255, 250, 250),
    )
}
