package io.github.yuroyami.kitepdf.core.css

import io.github.yuroyami.kitepdf.core.render.RgbColor

/**
 * CSS value parsers: lengths to points, and colours to [RgbColor]. Shared by
 * the EPUB cascade and the SVG renderer, which read the same syntax.
 *
 * They take the raw token text and no context, so each caller interprets a
 * value against the right reference (font size, root size, containing width).
 *
 * Length model: 1 CSS px = 1/96 in, 1 pt = 1/72 in, so px → pt is ×0.75. `em` is
 * relative to the caller's [fontSizePt] (the parent's size when resolving
 * `font-size` itself, else the element's own), `rem` to [rootPt], `%` to
 * [refPt] (parent size for font-size, containing width for margins, etc.).
 */
public object CssValues {

    /** Parse a CSS `<length>`/`<percentage>` to points, or null if not a length. */
    public fun length(raw: String, fontSizePt: Double, rootPt: Double, refPt: Double): Double? {
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
    public fun fontSizeKeyword(raw: String, parentPt: Double, mediumPt: Double): Double? = when (raw.trim().lowercase()) {
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

    /**
     * The RGB part of a CSS `<color>` ([alpha] reads the rest), in sRGB: hex, a named colour, or
     * `rgb()`, `hsl()`, `hwb()`, `lab()`, `lch()`, `oklab()`, `oklch()` or `color()` of CSS Color 4,
     * clipped to sRGB (#606). Null for `transparent`, `inherit`, `currentcolor`, or unrecognised.
     */
    public fun color(raw: String): RgbColor? {
        if (raw.trim().equals("transparent", ignoreCase = true)) return null
        return CssColors.parse(raw)?.rgb
    }

    /**
     * The alpha of a CSS `<color>`, from 0 (transparent) to 1 (opaque). [color] reads
     * only the RGB part, so a painter that honours transparency asks for both.
     *
     * CSS Color 4 makes alpha part of the colour: `transparent` is fully transparent
     * (6.3), each function takes it after a slash, or `rgba()` and `hsla()` as a fourth
     * value (4.2, 5.1), and the four- and eight-digit hex forms carry it in their last
     * digits (5.2). Every other colour is opaque. Null when [raw] is not a colour.
     */
    public fun alpha(raw: String): Double? = CssColors.parse(raw)?.alpha
}
