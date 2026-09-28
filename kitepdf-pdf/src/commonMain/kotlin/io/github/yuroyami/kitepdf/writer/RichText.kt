package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.core.css.CssValues
import io.github.yuroyami.kitepdf.core.xml.KiteXml
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

/**
 * Reads a rich text value (`/RV`) and its default style (`/DS`) into paragraphs of styled runs
 * (ISO 32000-1, 12.7.3.4, #204). The value is XHTML: `body`, `p`, `span`, `b`, `i` and `br`, with
 * a `style` attribute of CSS declarations. Of those, the font family, size, weight and style,
 * the colour, `text-align` and an underline apply. A family maps to Helvetica, Times or Courier.
 */
internal object RichText {

    private data class Style(
        val family: Family,
        val bold: Boolean,
        val italic: Boolean,
        val size: Double,
        val color: String,
        val underline: Boolean,
        val align: Int,
    ) {
        fun font(): StandardFont = when (family) {
            Family.SANS -> if (bold && italic) StandardFont.HelveticaBoldOblique else if (bold) StandardFont.HelveticaBold else if (italic) StandardFont.HelveticaOblique else StandardFont.Helvetica
            Family.SERIF -> if (bold && italic) StandardFont.TimesBoldItalic else if (bold) StandardFont.TimesBold else if (italic) StandardFont.TimesItalic else StandardFont.TimesRoman
            Family.MONO -> if (bold && italic) StandardFont.CourierBoldOblique else if (bold) StandardFont.CourierBold else if (italic) StandardFont.CourierOblique else StandardFont.Courier
        }
    }

    private enum class Family { SANS, SERIF, MONO }

    /**
     * The paragraphs of [rv], styled first by [ds] and then by the value's own styles, over a
     * default text of [size] points in [color], a PDF colour operator. Null when [rv] holds no body.
     */
    fun paragraphs(rv: String, ds: String?, size: Double, color: String): List<FieldParagraph>? {
        val root = runCatching { KiteXml.parse(rv) }.getOrNull() ?: return null
        val body = root.elements().firstOrNull { it.tag == "body" }
            ?: root.elements().flatMap { it.elements() }.firstOrNull { it.tag == "body" }
            ?: return null
        var style = Style(Family.SANS, bold = false, italic = false, size = size, color = color, underline = false, align = 0)
        ds?.let { style = apply(style, it) }
        val out = ArrayList<FieldParagraph>()
        var runs = ArrayList<FieldRun>()
        var align = style.align
        fun endParagraph() {
            if (runs.isNotEmpty() || out.isEmpty()) out += FieldParagraph(runs, align)
            runs = ArrayList()
        }
        fun walk(el: KiteXmlNode.Element, parent: Style, depth: Int) {
            if (depth > MAX_DEPTH) return
            var s = parent
            when (el.tag) {
                "b", "strong" -> s = s.copy(bold = true)
                "i", "em" -> s = s.copy(italic = true)
                "u" -> s = s.copy(underline = true)
            }
            el.attrs["style"]?.let { s = apply(s, it) }
            val block = el.tag == "p" || el.tag == "div"
            if (block) {
                if (runs.isNotEmpty()) endParagraph()
                align = s.align
            }
            for (child in el.children) when (child) {
                is KiteXmlNode.Text -> {
                    // Runs of white space fold to one space, as in HTML.
                    val text = child.text.replace(WHITESPACE, " ")
                    if (text.isNotEmpty()) runs += FieldRun(text, s.font(), s.size, s.color, s.underline)
                }
                is KiteXmlNode.Element -> if (child.tag == "br") endParagraph().also { align = s.align } else walk(child, s, depth + 1)
                else -> {}
            }
            if (block) endParagraph()
        }
        walk(body, style, 0)
        if (runs.isNotEmpty()) endParagraph()
        // The first run of a paragraph starts at the margin, and the last ends there.
        return out.map { p ->
            val trimmed = p.runs.toMutableList()
            if (trimmed.isNotEmpty()) trimmed[0] = trimmed[0].copy(text = trimmed[0].text.trimStart())
            FieldParagraph(trimmed.filter { it.text.isNotEmpty() }, p.align)
        }
    }

    /** [s] with the CSS declarations of [css] applied. */
    private fun apply(s: Style, css: String): Style {
        var out = s
        for (declaration in css.split(';')) {
            val colon = declaration.indexOf(':')
            if (colon < 0) continue
            val name = declaration.substring(0, colon).trim().lowercase()
            val value = declaration.substring(colon + 1).trim()
            val lower = value.lowercase()
            when (name) {
                "font" -> out = font(out, value)
                "font-family" -> out = out.copy(family = family(value))
                "font-size" -> size(lower)?.let { out = out.copy(size = it) }
                "font-weight" -> out = out.copy(bold = lower == "bold" || lower == "bolder" || (lower.toIntOrNull() ?: 400) >= 600)
                "font-style" -> out = out.copy(italic = lower == "italic" || lower == "oblique")
                "color" -> CssValues.color(value)?.let { c -> out = out.copy(color = "${fmt(c.r)} ${fmt(c.g)} ${fmt(c.b)} rg") }
                "text-align" -> out = out.copy(align = when (lower) { "center" -> 1; "right", "end" -> 2; else -> 0 })
                "text-decoration" -> out = out.copy(underline = "underline" in lower)
            }
        }
        return out
    }

    /** The `font` shorthand: style and weight words, then the size, then the family. */
    private fun font(s: Style, value: String): Style {
        var out = s
        val words = value.trim().split(WHITESPACE)
        var i = 0
        while (i < words.size) {
            val w = words[i].lowercase()
            when {
                w == "italic" || w == "oblique" -> out = out.copy(italic = true)
                w == "bold" || w == "bolder" || (w.toIntOrNull() ?: 0) >= 600 -> out = out.copy(bold = true)
                w == "normal" || (w.toIntOrNull() ?: 0) in 1..599 -> {}
                else -> {
                    size(w.substringBefore('/'))?.let { out = out.copy(size = it) }
                    val rest = words.drop(i + 1).joinToString(" ")
                    if (rest.isNotBlank()) out = out.copy(family = family(rest))
                    return out
                }
            }
            i++
        }
        return out
    }

    private fun family(value: String): Family {
        val names = value.lowercase().split(',').map { it.trim().trim('\'', '"') }
        for (n in names) when {
            "courier" in n || n == "monospace" || "mono" in n -> return Family.MONO
            "times" in n || n == "serif" || "georgia" in n || "minion" in n -> return Family.SERIF
            "helvetica" in n || "arial" in n || n == "sans-serif" || "myriad" in n || "verdana" in n -> return Family.SANS
        }
        return Family.SANS
    }

    /** A font size in points: `12pt`, `16px` at 0.75 point a pixel, or a bare number of points. */
    private fun size(v: String): Double? {
        val t = v.trim().lowercase()
        val n = when {
            t.endsWith("pt") -> t.dropLast(2).toDoubleOrNull()
            t.endsWith("px") -> t.dropLast(2).toDoubleOrNull()?.times(0.75)
            else -> t.toDoubleOrNull()
        }
        return n?.takeIf { it > 0.0 && it.isFinite() }
    }

    private fun KiteXmlNode.Element.elements(): List<KiteXmlNode.Element> = children.filterIsInstance<KiteXmlNode.Element>()

    private fun fmt(d: Double): String = PdfObjectWriter.formatReal(d)

    private const val MAX_DEPTH = 64
    private val WHITESPACE = Regex("\\s+")
}
