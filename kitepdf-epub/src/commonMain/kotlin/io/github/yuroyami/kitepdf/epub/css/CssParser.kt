package io.github.yuroyami.kitepdf.epub.css

/**
 * A forgiving CSS parser for the EPUB subset. Strips comments, scans rules by
 * brace matching, splits grouped selectors and declarations, and expands the
 * shorthands that matter for book layout (`margin`, `padding`, `list-style`).
 * `@media`/`@supports` blocks are flattened (a reflow reader honours all media);
 * `@font-face`/`@page`/`@import`/`@charset` are skipped (font-face is Phase 4).
 * Malformed input is salvaged, never thrown on -- real EPUB CSS is messy.
 */
internal object CssParser {

    fun parse(text: String, origin: Origin): List<StyleRule> = parseAll(text, origin).rules

    fun parseAll(text: String, origin: Origin): ParsedCss {
        val css = stripComments(text)
        val rules = ArrayList<StyleRule>()
        val faces = ArrayList<FontFaceRule>()
        parseInto(css, 0, css.length, origin, rules, faces)
        return ParsedCss(rules, faces)
    }

    private fun parseInto(css: String, start: Int, end: Int, origin: Origin, out: ArrayList<StyleRule>, faces: ArrayList<FontFaceRule>) {
        var i = start
        while (i < end) {
            while (i < end && css[i].isWhitespace()) i++
            if (i >= end) break
            if (css[i] == '@') { i = handleAtRule(css, i, end, origin, out, faces); continue }
            val brace = css.indexOf('{', i)
            if (brace < 0 || brace >= end) break
            val prelude = css.substring(i, brace).trim()
            val close = matchBrace(css, brace, end)
            val body = css.substring(brace + 1, close)
            val selectors = prelude.split(',').mapNotNull { Selector.parse(it.trim()) }
            val decls = parseDeclarations(body)
            if (selectors.isNotEmpty() && decls.isNotEmpty()) out.add(StyleRule(selectors, decls, origin))
            i = close + 1
        }
    }

    private fun handleAtRule(css: String, at: Int, end: Int, origin: Origin, out: ArrayList<StyleRule>, faces: ArrayList<FontFaceRule>): Int {
        var j = at + 1
        while (j < end && (css[j].isLetterOrDigit() || css[j] == '-')) j++
        val keyword = css.substring(at + 1, j).lowercase()
        val brace = css.indexOf('{', at)
        val semi = css.indexOf(';', at)
        // No block (e.g. @import ...;): skip to the semicolon.
        if (brace < 0 || brace >= end || (semi in 0 until brace)) return if (semi < 0 || semi >= end) end else semi + 1
        val close = matchBrace(css, brace, end)
        when (keyword) {
            "media", "supports" -> parseInto(css, brace + 1, close, origin, out, faces) // flatten: always-matching
            "font-face" -> parseFontFace(css.substring(brace + 1, close))?.let { faces.add(it) }
            // @page / @keyframes / unknown: skip the whole block.
        }
        return close + 1
    }

    private fun parseFontFace(body: String): FontFaceRule? {
        var family = ""
        val urls = ArrayList<String>()
        var bold = false
        var italic = false
        for (d in parseDeclarations(body)) when (d.property) {
            "font-family" -> family = d.value.trim().trim('"', '\'').lowercase()
            "src" -> urls.addAll(parseSrcUrls(d.value))
            "font-weight" -> bold = d.value.trim().lowercase().let { it == "bold" || it == "bolder" || (it.toIntOrNull()?.let { n -> n >= 600 } == true) }
            "font-style" -> italic = d.value.trim().lowercase().let { it == "italic" || it == "oblique" }
        }
        return if (family.isEmpty() || urls.isEmpty()) null else FontFaceRule(family, urls, bold, italic)
    }

    /** Extract the `url(...)` targets from a `src:` value, in order. */
    private fun parseSrcUrls(value: String): List<String> {
        val out = ArrayList<String>()
        for (part in splitTopLevel(value, ',')) {
            val idx = part.indexOf("url(")
            if (idx < 0) continue
            val close = part.indexOf(')', idx)
            if (close > idx) out.add(part.substring(idx + 4, close).trim().trim('"', '\''))
        }
        return out
    }

    /** Index of the `}` matching the `{` at [openIdx] (or [end] if unbalanced). */
    private fun matchBrace(css: String, openIdx: Int, end: Int): Int {
        var depth = 0
        var i = openIdx
        while (i < end) {
            when (css[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return end
    }

    private fun parseDeclarations(body: String): List<Declaration> {
        val out = ArrayList<Declaration>()
        for (chunk in splitTopLevel(body, ';')) {
            val c = chunk.trim()
            if (c.isEmpty()) continue
            val colon = c.indexOf(':')
            if (colon <= 0) continue
            val prop = c.substring(0, colon).trim().lowercase()
            var value = c.substring(colon + 1).trim()
            var important = false
            val bang = value.lowercase().lastIndexOf("!important")
            if (bang >= 0) { important = true; value = value.substring(0, bang).trim() }
            if (prop.isEmpty() || value.isEmpty()) continue
            expandShorthand(prop, value, important, out)
        }
        return out
    }

    private fun expandShorthand(prop: String, value: String, important: Boolean, out: ArrayList<Declaration>) {
        fun emit(p: String, v: String) = out.add(Declaration(p, v, important))
        when (prop) {
            "margin", "padding" -> {
                val v = splitWords(value)
                if (v.isEmpty()) return
                val (top, right, bottom, left) = fourSides(v)
                emit("$prop-top", top); emit("$prop-right", right); emit("$prop-bottom", bottom); emit("$prop-left", left)
            }
            "border" -> emitBorder(SIDES, value, ::emit)
            "border-top" -> emitBorder(listOf("top"), value, ::emit)
            "border-right" -> emitBorder(listOf("right"), value, ::emit)
            "border-bottom" -> emitBorder(listOf("bottom"), value, ::emit)
            "border-left" -> emitBorder(listOf("left"), value, ::emit)
            "border-width", "border-style", "border-color" -> {
                val which = prop.substringAfter('-') // width|style|color
                val v = splitWords(value); if (v.isEmpty()) return
                val (top, right, bottom, left) = fourSides(v)
                emit("border-top-$which", top); emit("border-right-$which", right)
                emit("border-bottom-$which", bottom); emit("border-left-$which", left)
            }
            "list-style" -> {
                val kw = splitWords(value).firstOrNull { it.lowercase() in LIST_TYPES }
                if (kw != null) emit("list-style-type", kw.lowercase())
                emit(prop, value)
            }
            else -> emit(prop, value)
        }
    }

    /** Expand a `border[-side]: <width> <style> <color>` shorthand for [sides]. */
    private fun emitBorder(sides: List<String>, value: String, emit: (String, String) -> Unit) {
        var width: String? = null; var style: String? = null; var color: String? = null
        for (tok in splitWords(value)) {
            val low = tok.lowercase()
            when {
                low in BORDER_STYLES -> style = low
                low in BORDER_WIDTH_KEYWORDS || tok.first().isDigit() || tok.startsWith(".") || tok.startsWith("-") -> width = tok
                else -> color = tok
            }
        }
        for (s in sides) {
            width?.let { emit("border-$s-width", it) }
            style?.let { emit("border-$s-style", it) }
            color?.let { emit("border-$s-color", it) }
        }
    }

    /** Split on whitespace, keeping `func(a, b)` tokens (e.g. `rgb(0, 0, 0)`) whole. */
    private fun splitWords(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        for (c in s) when {
            c == '(' -> { depth++; sb.append(c) }
            c == ')' -> { if (depth > 0) depth--; sb.append(c) }
            c.isWhitespace() && depth == 0 -> { if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() } }
            else -> sb.append(c)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private data class Sides(val top: String, val right: String, val bottom: String, val left: String)

    private fun fourSides(v: List<String>): Sides = when (v.size) {
        1 -> Sides(v[0], v[0], v[0], v[0])
        2 -> Sides(v[0], v[1], v[0], v[1])
        3 -> Sides(v[0], v[1], v[2], v[1])
        else -> Sides(v[0], v[1], v[2], v[3])
    }

    /** Split on [sep] at top level (ignoring separators inside `()` or quotes). */
    private fun splitTopLevel(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        var quote = ' '
        for (c in s) {
            when {
                quote != ' ' -> { sb.append(c); if (c == quote) quote = ' ' }
                c == '"' || c == '\'' -> { quote = c; sb.append(c) }
                c == '(' -> { depth++; sb.append(c) }
                c == ')' -> { if (depth > 0) depth--; sb.append(c) }
                c == sep && depth == 0 -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun stripComments(css: String): String {
        if ("/*" !in css) return css
        val sb = StringBuilder(css.length)
        var i = 0
        while (i < css.length) {
            if (i + 1 < css.length && css[i] == '/' && css[i + 1] == '*') {
                val endC = css.indexOf("*/", i + 2)
                i = if (endC < 0) css.length else endC + 2
            } else {
                sb.append(css[i]); i++
            }
        }
        return sb.toString()
    }

    private val LIST_TYPES = setOf(
        "disc", "circle", "square", "decimal", "decimal-leading-zero",
        "lower-roman", "upper-roman", "lower-alpha", "upper-alpha",
        "lower-latin", "upper-latin", "none",
    )

    private val SIDES = listOf("top", "right", "bottom", "left")
    private val BORDER_STYLES = setOf(
        "none", "hidden", "solid", "dotted", "dashed", "double", "groove", "ridge", "inset", "outset",
    )
    private val BORDER_WIDTH_KEYWORDS = setOf("thin", "medium", "thick")
}
