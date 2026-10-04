package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.css.CssValues
import io.github.yuroyami.kitepdf.core.kiteWarn

/**
 * A forgiving CSS parser for the EPUB subset. Strips comments, scans rules by
 * brace matching, splits grouped selectors and declarations, and expands the
 * shorthands that matter for book layout (`margin`, `padding`, `list-style`).
 * `@media`/`@supports` blocks are flattened (a reflow reader honours all media);
 * `@font-face`/`@page`/`@import`/`@charset` are skipped here; `@font-face` is
 * read separately by the font registry.
 * Malformed input is salvaged, never thrown on -- real EPUB CSS is messy.
 */
internal object CssParser {

    // Leave room for the caller on a 512 KB Apple thread, including debug builds (#451).
    const val MAX_NESTING = 32

    fun parse(text: String, origin: Origin): List<StyleRule> = parseAll(text, origin).rules

    fun parseAll(text: String, origin: Origin): ParsedCss {
        val css = stripComments(text)
        val rules = ArrayList<StyleRule>()
        val faces = ArrayList<FontFaceRule>()
        parseInto(css, 0, css.length, origin, rules, faces, 0, Sheet())
        return ParsedCss(rules, faces)
    }

    /**
     * What a style sheet declares for the rules after it: its namespace prefixes, with the default
     * namespace under the empty prefix, which only `@namespace` rules before any other rule but
     * `@charset` and `@import` declare (CSS Namespaces, 2).
     */
    private class Sheet {
        val namespaces = HashMap<String, String>()
        var ruled = false
    }

    private fun parseInto(css: String, start: Int, end: Int, origin: Origin, out: ArrayList<StyleRule>, faces: ArrayList<FontFaceRule>, nesting: Int, sheet: Sheet) {
        var i = start
        while (i < end) {
            while (i < end && css[i].isWhitespace()) i++
            // The `<!--` and `-->` that hide an old style element's text from browsers that knew no CSS (CSS Syntax 3, 5.4.1).
            if (css.startsWith("<!--", i)) { i += 4; continue }
            if (css.startsWith("-->", i)) { i += 3; continue }
            if (i >= end) break
            if (css[i] == '@') { i = handleAtRule(css, i, end, origin, out, faces, nesting, sheet); continue }
            val brace = css.indexOf('{', i)
            if (brace < 0 || brace >= end) break
            val prelude = css.substring(i, brace).trim()
            val close = matchBrace(css, brace, end)
            val body = css.substring(brace + 1, close)
            sheet.ruled = true
            // A selector list with one invalid selector drops the whole rule (Selectors 4, 3.1), and one the
            // layout draws nothing for, as `::first-line`, stays valid and leaves the rest of the list.
            val selectors = Selector.parseList(prelude, sheet.namespaces)?.filterNot { it.otherPseudoElement }.orEmpty()
            val decls = parseDeclarations(body)
            if (selectors.isNotEmpty() && decls.isNotEmpty()) out.add(StyleRule(selectors, decls, origin))
            i = close + 1
        }
    }

    private fun handleAtRule(css: String, at: Int, end: Int, origin: Origin, out: ArrayList<StyleRule>, faces: ArrayList<FontFaceRule>, nesting: Int, sheet: Sheet): Int {
        var j = at + 1
        while (j < end && (css[j].isLetterOrDigit() || css[j] == '-')) j++
        val keyword = css.substring(at + 1, j).lowercase()
        val brace = css.indexOf('{', at)
        val semi = css.indexOf(';', at)
        // No block (e.g. @import ...;): skip to the semicolon.
        if (brace < 0 || brace >= end || (semi in 0 until brace)) {
            val stop = if (semi < 0 || semi >= end) end else semi
            when (keyword) {
                "namespace" -> if (!sheet.ruled && nesting == 0) namespaceRule(css.substring(j, stop))?.let { (prefix, uri) -> sheet.namespaces[prefix] = uri }
                "charset", "import" -> {}
                else -> sheet.ruled = true
            }
            return if (stop >= end) end else stop + 1
        }
        sheet.ruled = true
        val close = matchBrace(css, brace, end)
        when (keyword) {
            "media", "supports" -> {
                if (nesting >= MAX_NESTING) {
                    kiteWarn { "epub: CSS blocks nested beyond $MAX_NESTING levels are skipped" }
                } else {
                    parseInto(css, brace + 1, close, origin, out, faces, nesting + 1, sheet) // flatten: always-matching
                }
            }
            "font-face" -> parseFontFace(css.substring(brace + 1, close))?.let { faces.add(it) }
            // @page / @keyframes / unknown: skip the whole block.
        }
        return close + 1
    }

    /** The prefix, empty for the default, and the namespace of an `@namespace` rule's [text], or null when it is not valid (CSS Namespaces, 3). */
    private fun namespaceRule(text: String): Pair<String, String>? {
        val tokens = CssTokenizer(text).tokens().filter { it.type != CssToken.WHITESPACE }
        var k = 0
        val prefix = if (tokens.getOrNull(0)?.type == CssToken.IDENT) { k = 1; tokens[0].value } else ""
        val t = tokens.getOrNull(k) ?: return null
        val uri = when {
            t.type == CssToken.STRING || t.type == CssToken.URL -> { k++; t.value }
            t.type == CssToken.FUNCTION && asciiEquals(t.value, "url") && tokens.getOrNull(k + 1)?.type == CssToken.STRING &&
                tokens.getOrNull(k + 2)?.type == CssToken.RIGHT_PAREN -> { k += 3; tokens[k - 2].value }
            else -> return null
        }
        return if (k == tokens.size) prefix to uri else null
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
            // Keep the supported decoration-line subset in one cascade slot,
            // so an author longhand can override the UA link shorthand. The
            // shorthand also sets the colour: an omitted one is currentcolor.
            "text-decoration" -> {
                emit("text-decoration-line", value)
                emit("text-decoration-color", splitWords(value).firstOrNull { CssValues.color(it) != null } ?: "currentcolor")
            }
            "font" -> expandFont(value, ::emit)
            else -> emit(prop, value)
        }
    }

    /**
     * Expand the `font:` shorthand subset:
     * `[style || variant || weight] size[/line-height] family[, ...]`.
     * System-font keywords (`caption`, `menu`, ...) are ignored. The size
     * token marks the boundary: everything after it is the family list.
     */
    private fun expandFont(value: String, emit: (String, String) -> Unit) {
        val words = splitWords(value)
        if (words.isEmpty()) return
        if (words.size == 1 && words[0].lowercase() in FONT_SYSTEM_KEYWORDS) return
        var sizeIdx = -1
        for ((i, w) in words.withIndex()) {
            val head = w.substringBefore('/')
            val low = head.lowercase()
            val looksLikeSize = low in FONT_SIZE_KEYWORDS ||
                (head.isNotEmpty() && (head[0].isDigit() || head[0] == '.') && head.any { it.isLetter() || it == '%' })
            if (looksLikeSize) { sizeIdx = i; break }
        }
        if (sizeIdx < 0) return // no size token: invalid shorthand, drop leniently
        for (i in 0 until sizeIdx) when (val low = words[i].lowercase()) {
            "italic", "oblique" -> emit("font-style", low)
            "small-caps" -> emit("font-variant", low)
            "bold", "bolder", "lighter" -> emit("font-weight", low)
            "normal" -> {} // could be any of the three; initial either way
            else -> words[i].toIntOrNull()?.let { emit("font-weight", words[i]) }
        }
        val sizeTok = words[sizeIdx]
        val size = sizeTok.substringBefore('/')
        emit("font-size", size)
        if ('/' in sizeTok) sizeTok.substringAfter('/').takeIf { it.isNotEmpty() }?.let { emit("line-height", it) }
        val family = words.drop(sizeIdx + 1).joinToString(" ")
        if (family.isNotEmpty()) emit("font-family", family)
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
    internal fun splitTopLevel(s: String, sep: Char): List<String> {
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
    private val FONT_SYSTEM_KEYWORDS = setOf("caption", "icon", "menu", "message-box", "small-caption", "status-bar")
    private val FONT_SIZE_KEYWORDS = setOf(
        "xx-small", "x-small", "small", "medium", "large", "x-large", "xx-large", "smaller", "larger",
    )
    private val BORDER_STYLES = setOf(
        "none", "hidden", "solid", "dotted", "dashed", "double", "groove", "ridge", "inset", "outset",
    )
    private val BORDER_WIDTH_KEYWORDS = setOf("thin", "medium", "thick")
}
