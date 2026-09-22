package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.css.CssValues
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

/**
 * Embedded author styles, with presentation hints before rules and inline
 * declarations last (SVG 1.1, 6.4 and 6.6; CSS 2.2, 6.4, #89). The immutable
 * result is shared by concurrent renders, including referenced definitions.
 *
 * Selectors cover types, *, classes, IDs and their compounds. A selector list
 * containing unsupported syntax is ignored as a whole, never shortened into
 * a selector that could match a different element (CSS 2.2, 4.1.7 and 5.2).
 */
internal class SvgStyles(root: KiteXmlNode.Element) {
    private val values: Map<KiteXmlNode.Element, Map<String, String>>

    init {
        val elements = ArrayList<KiteXmlNode.Element>()
        val pending = ArrayList<KiteXmlNode.Element>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val el = pending.removeAt(pending.lastIndex)
            elements.add(el)
            for (i in el.children.indices.reversed()) {
                (el.children[i] as? KiteXmlNode.Element)?.let(pending::add)
            }
        }
        val rules = ArrayList<Rule>()
        for (el in elements) {
            if (el.tag != "style") continue
            val type = el.attrs["type"] ?: root.attrs["contentstyletype"] ?: "text/css"
            if (!type.trim().equals("text/css", ignoreCase = true)) continue
            val media = el.attrs["media"]?.trim().orEmpty()
            if (media.isNotEmpty() && media.split(',').none { it.trim().lowercase() in setOf("all", "screen") }) continue
            val text = el.children.filterIsInstance<KiteXmlNode.Text>().joinToString("") { it.text }
            rules.addAll(parseRules(text))
        }
        // Most SVGs only carry presentation attributes. Keep that common path
        // as direct map reads, without copying each element's attribute map.
        values = (if (rules.isEmpty()) elements.filter { "style" in it.attrs } else elements)
            .associateWith { cascade(it, rules) }
    }

    fun value(el: KiteXmlNode.Element, name: String): String? {
        val cascaded = values[el]
        return if (cascaded != null) cascaded[name] else el.attrs[name]?.trim()?.takeUnless { '!' in it }
    }

    private data class Specificity(val ids: Int, val classes: Int, val types: Int) : Comparable<Specificity> {
        override fun compareTo(other: Specificity): Int =
            compareValues(ids, other.ids).takeIf { it != 0 }
                ?: compareValues(classes, other.classes).takeIf { it != 0 }
                ?: compareValues(types, other.types)
    }

    private class Selector(val type: String?, val ids: List<String>, val classes: List<String>) {
        val specificity = Specificity(ids.size, classes.size, if (type == null) 0 else 1)
        fun matches(el: KiteXmlNode.Element, tokens: Set<String>): Boolean =
            (type == null || type.lowercase() == el.tag) && ids.all { it == el.attrs["id"] } && classes.all { it in tokens }
    }

    private class Declaration(val name: String, val value: String, val important: Boolean)
    private class Rule(val selectors: List<Selector>, val declarations: List<Declaration>)
    private class Winner(val declaration: Declaration, val inline: Boolean, val specificity: Specificity) {
        fun replaces(old: Winner): Boolean = when {
            declaration.important != old.declaration.important -> declaration.important
            inline != old.inline -> inline
            else -> specificity >= old.specificity // source order breaks a tie
        }
    }

    private fun cascade(el: KiteXmlNode.Element, rules: List<Rule>): Map<String, String> {
        val out = LinkedHashMap<String, Winner>()
        val zero = Specificity(0, 0, 0)
        // Geometry and other XML attributes remain attributes, not CSS properties.
        for ((name, value) in el.attrs) {
            if (name in PROPERTIES && '!' !in value) out[name] = Winner(Declaration(name, value.trim(), false), false, zero)
        }
        fun offer(declaration: Declaration, inline: Boolean, specificity: Specificity) {
            val next = Winner(declaration, inline, specificity)
            val old = out[declaration.name]
            if (old == null || next.replaces(old)) out[declaration.name] = next
        }
        val classes = el.attrs["class"].orEmpty().split(CSS_SPACE).filter { it.isNotEmpty() }.toSet()
        for (rule in rules) {
            val specificity = rule.selectors.filter { it.matches(el, classes) }.maxOfOrNull { it.specificity } ?: continue
            for (declaration in rule.declarations) offer(declaration, false, specificity)
        }
        for (declaration in declarations(el.attrs["style"].orEmpty())) offer(declaration, true, zero)
        return out.mapValues { it.value.declaration.value }
    }

    companion object {
        private val CSS_SPACE = Regex("[ \\t\\r\\n\\u000c]+")
        private val IMPORTANT = Regex("!\\s*important\\s*$", RegexOption.IGNORE_CASE)
        private val IDENT = Regex("-?[_a-zA-Z\\u0080-\\uffff][_a-zA-Z0-9\\u0080-\\uffff-]*")
        private val LENGTH = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?(?:px|pt|pc|in|cm|mm|q|em|ex|%)?", RegexOption.IGNORE_CASE)
        private val PROPERTIES = setOf(
            "color", "fill", "stroke", "stroke-width", "opacity", "fill-opacity", "stroke-opacity",
            "fill-rule", "display", "visibility", "transform", "clip-path", "font-size", "font-family",
            "font-weight", "font-style", "text-anchor", "stroke-dasharray", "stroke-dashoffset",
            "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stop-color", "stop-opacity",
        )

        /** The same declaration grammar for a standalone gradient parser. */
        fun inlineValue(el: KiteXmlNode.Element, name: String): String? {
            var selected: Declaration? = null
            for (declaration in declarations(el.attrs["style"].orEmpty())) {
                if (declaration.name != name) continue
                if (selected?.important != true || declaration.important) selected = declaration
            }
            return selected?.value ?: el.attrs[name]
        }

        private fun selector(raw: String): Selector? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            var at = 0
            fun ident(): String? {
                val match = IDENT.find(s, at)?.takeIf { it.range.first == at } ?: return null
                at = match.range.last + 1
                return match.value
            }
            val type = when (s[0]) {
                '*' -> { at++; null }
                '.', '#' -> null
                else -> ident() ?: return null
            }
            val ids = ArrayList<String>()
            val classes = ArrayList<String>()
            while (at < s.length) {
                val marker = s[at++]
                if (marker != '.' && marker != '#') return null
                val name = ident() ?: return null
                if (marker == '.') classes.add(name) else ids.add(name)
            }
            return Selector(type, ids, classes)
        }

        private fun parseRules(raw: String): List<Rule> {
            val s = withoutComments(raw)
            val out = ArrayList<Rule>()
            var at = 0
            while (at < s.length) {
                while (at < s.length && s[at].isWhitespace()) at++
                // CSS permits legacy HTML comment delimiters between rules.
                if (s.startsWith("<!--", at)) { at += 4; continue }
                if (s.startsWith("-->", at)) { at += 3; continue }
                val end = boundary(s, at, "{;}")
                if (end >= s.length) break
                if (s[end] != '{') { at = end + 1; continue }
                val close = closingBrace(s, end + 1)
                val prelude = s.substring(at, end).trim()
                // At-rules (including nested @media) are deliberately skipped.
                // Reading their inner text as ordinary rules would leak styles.
                if (!prelude.startsWith('@')) {
                    val selectors = prelude.split(',').map { selector(it) }
                    if (selectors.isNotEmpty() && selectors.all { it != null }) {
                        val declarations = declarations(s.substring(end + 1, close))
                        if (declarations.isNotEmpty()) out.add(Rule(selectors.filterNotNull(), declarations))
                    }
                }
                at = (close + 1).coerceAtMost(s.length)
            }
            return out
        }

        private fun declarations(raw: String): List<Declaration> {
            val s = withoutComments(raw)
            val out = ArrayList<Declaration>()
            var at = 0
            while (at < s.length) {
                val end = boundary(s, at, ";")
                val part = s.substring(at, end)
                val colon = part.indexOf(':')
                if (colon > 0) {
                    val name = part.substring(0, colon).trim().lowercase()
                    val rawValue = part.substring(colon + 1).trim()
                    val important = IMPORTANT.find(rawValue)
                    val value = (if (important == null) rawValue else rawValue.substring(0, important.range.first)).trim()
                    if (name in PROPERTIES && validValue(name, value) && boundary(value, 0, "{}!") == value.length) {
                        val normalized = when {
                            value.equals("currentcolor", ignoreCase = true) -> "currentColor"
                            name in setOf("display", "visibility", "fill-rule", "font-style", "font-weight", "text-anchor", "stroke-linecap", "stroke-linejoin") -> value.lowercase()
                            value.equals("none", ignoreCase = true) -> "none"
                            else -> value
                        }
                        out.add(Declaration(name, normalized, important != null))
                    }
                }
                at = end + 1
            }
            return out
        }

        /** Ignore unrecognised values before they can displace a valid fallback. */
        private fun validValue(name: String, value: String): Boolean {
            if (value.isEmpty()) return false
            val lower = value.lowercase()
            if (lower == "inherit") return true
            return when (name) {
                "color", "stop-color" -> lower == "currentcolor" || CssValues.color(value) != null
                "fill", "stroke" -> lower in setOf("none", "currentcolor") || CssValues.color(value) != null ||
                    (value.startsWith("url(") && value.indexOf(')') > 4)
                "fill-rule" -> lower in setOf("nonzero", "evenodd")
                "opacity", "fill-opacity", "stroke-opacity", "stop-opacity", "stroke-miterlimit" ->
                    value.toDoubleOrNull()?.isFinite() == true
                "stroke-width", "stroke-dashoffset", "font-size" -> LENGTH.matches(value)
                "stroke-linecap" -> lower in setOf("butt", "round", "square")
                "stroke-linejoin" -> lower in setOf("miter", "miter-clip", "arcs", "round", "bevel")
                "text-anchor" -> lower in setOf("start", "middle", "end")
                "visibility" -> lower in setOf("visible", "hidden", "collapse")
                "font-style" -> lower in setOf("normal", "italic", "oblique")
                "font-weight" -> lower in setOf("normal", "bold", "bolder", "lighter") || value.toIntOrNull()?.let { it in 100..900 } == true
                else -> true // Existing property parsers own their remaining value grammar.
            }
        }

        /** Find punctuation outside strings, escapes, functions and brackets. */
        private fun boundary(s: String, start: Int, delimiters: String): Int {
            var at = start
            var quote: Char? = null
            var parentheses = 0
            var brackets = 0
            while (at < s.length) {
                val c = s[at]
                if (c == '\\') { at += 2; continue }
                if (quote != null) {
                    if (c == quote) quote = null
                } else when {
                    c == '\'' || c == '"' -> quote = c
                    c == '(' -> parentheses++
                    c == ')' -> parentheses--
                    c == '[' -> brackets++
                    c == ']' -> brackets--
                    parentheses == 0 && brackets == 0 && c in delimiters -> return at
                }
                at++
            }
            return s.length
        }

        private fun closingBrace(s: String, start: Int): Int {
            var at = start
            var depth = 1
            while (at < s.length) {
                at = boundary(s, at, "{}")
                if (at == s.length) return at
                if (s[at] == '{') depth++ else depth--
                if (depth == 0) return at
                at++
            }
            return s.length
        }

        private fun withoutComments(s: String): String = buildString(s.length) {
            var at = 0
            var quote: Char? = null
            while (at < s.length) {
                val c = s[at]
                if (c == '\\' && at + 1 < s.length) {
                    append(c); append(s[at + 1]); at += 2; continue
                }
                if (quote == null && s.startsWith("/*", at)) {
                    val end = s.indexOf("*/", at + 2)
                    if (end < 0) break
                    append(' ') // Never join identifier fragments across a comment.
                    at = end + 2
                    continue
                }
                if (c == quote) quote = null else if (quote == null && (c == '\'' || c == '"')) quote = c
                append(c)
                at++
            }
        }
    }
}
