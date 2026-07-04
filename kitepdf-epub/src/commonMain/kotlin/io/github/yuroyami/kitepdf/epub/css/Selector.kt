package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.epub.HtmlNode

/** How a compound selector relates to the one on its left. */
internal enum class Combinator { DESCENDANT, CHILD }

/** An attribute condition: `[a]`, `[a=v]`, `[a~=v]`, `[a|=v]`, `[a^=v]`, `[a$=v]`, `[a*=v]`. */
internal class AttrCond(val name: String, val op: String?, val value: String?) {
    fun matches(el: HtmlNode.Element): Boolean {
        val actual = el.attrs[name] ?: return false
        val v = value ?: return true // presence
        return when (op) {
            "=" -> actual == v
            "~=" -> actual.split(' ', '\t', '\n').any { it == v }
            "|=" -> actual == v || actual.startsWith("$v-")
            "^=" -> actual.startsWith(v)
            "$=" -> actual.endsWith(v)
            "*=" -> actual.contains(v)
            else -> actual == v
        }
    }
}

/** A compound selector: an optional type plus any #id, .class, [attr] conditions. */
internal class SimpleSelector(
    val tag: String?, // null = universal
    val id: String?,
    val classes: List<String>,
    val attrs: List<AttrCond>,
    val pseudoClassCount: Int,
    val pseudoElement: Boolean,
) {
    fun matches(el: HtmlNode.Element): Boolean {
        if (tag != null && tag != el.tag) return false
        if (id != null && el.attrs["id"] != id) return false
        if (classes.isNotEmpty()) {
            val cs = el.attrs["class"]?.split(' ', '\t', '\n', '\r')?.filter { it.isNotEmpty() } ?: emptyList()
            if (!classes.all { it in cs }) return false
        }
        return attrs.all { it.matches(el) }
    }
}

/**
 * A complex selector: compound [parts] joined left-to-right by [combinators]
 * (size = parts-1). The rightmost part is the subject. Matching is right-to-left
 * against an element and its ancestor chain. Pseudo-elements (`::before`,
 * `:after`) make [parse] return null -- we can't synthesize their content, so
 * those rules simply don't apply.
 */
internal class Selector(
    val parts: List<SimpleSelector>,
    val combinators: List<Combinator>,
) {
    /** CSS specificity (ids, classes+attrs+pseudo-classes, type names). */
    val specificity: Int by lazy {
        var a = 0; var b = 0; var c = 0
        for (p in parts) {
            if (p.id != null) a++
            b += p.classes.size + p.attrs.size + p.pseudoClassCount
            if (p.tag != null) c++
        }
        (a shl 16) or (b shl 8) or c.coerceAtMost(255)
    }

    /** [ancestors]: immediate parent first, then outward to the root. */
    fun matches(el: HtmlNode.Element, ancestors: List<HtmlNode.Element>): Boolean {
        if (!parts.last().matches(el)) return false
        var chainIdx = 0 // position in ancestors we may still consume
        var partIdx = parts.size - 2
        while (partIdx >= 0) {
            val combinator = combinators[partIdx]
            val part = parts[partIdx]
            when (combinator) {
                Combinator.CHILD -> {
                    if (chainIdx >= ancestors.size || !part.matches(ancestors[chainIdx])) return false
                    chainIdx++
                }
                Combinator.DESCENDANT -> {
                    var found = false
                    while (chainIdx < ancestors.size) {
                        if (part.matches(ancestors[chainIdx])) { chainIdx++; found = true; break }
                        chainIdx++
                    }
                    if (!found) return false
                }
            }
            partIdx--
        }
        return true
    }

    companion object {
        /** Parse one complex selector; null if empty or it targets a pseudo-element. */
        fun parse(text: String): Selector? {
            val parts = ArrayList<SimpleSelector>()
            val combs = ArrayList<Combinator>()
            val sb = StringBuilder()
            var pending: Combinator? = null
            var bracket = 0

            fun flush(): Boolean {
                val t = sb.toString().trim(); sb.clear()
                if (t.isEmpty()) return true
                val simple = parseSimple(t)
                if (simple.pseudoElement) return false // whole selector unusable
                if (parts.isNotEmpty()) combs.add(pending ?: Combinator.DESCENDANT)
                parts.add(simple); pending = null
                return true
            }

            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '[' -> { bracket++; sb.append(c) }
                    c == ']' -> { bracket--; sb.append(c) }
                    bracket > 0 -> sb.append(c)
                    c == '>' -> { if (!flush()) return null; pending = Combinator.CHILD }
                    c.isWhitespace() -> { if (sb.isNotBlank()) { if (!flush()) return null; if (pending == null) pending = Combinator.DESCENDANT } }
                    else -> sb.append(c)
                }
                i++
            }
            if (!flush()) return null
            if (parts.isEmpty()) return null
            return Selector(parts, combs)
        }

        private fun parseSimple(t: String): SimpleSelector {
            var tag: String? = null
            var id: String? = null
            val classes = ArrayList<String>()
            val attrs = ArrayList<AttrCond>()
            var pseudoClasses = 0
            var pseudoElement = false
            var i = 0
            val n = t.length

            fun readIdent(from: Int): Int {
                var j = from
                while (j < n && (t[j].isLetterOrDigit() || t[j] == '-' || t[j] == '_')) j++
                return j
            }

            // Optional leading type or universal.
            if (i < n && (t[i].isLetter() || t[i] == '*')) {
                if (t[i] == '*') { i++ } else { val j = readIdent(i); tag = t.substring(i, j).lowercase(); i = j }
            }
            while (i < n) {
                when (t[i]) {
                    '.' -> { val j = readIdent(i + 1); if (j > i + 1) classes.add(t.substring(i + 1, j)); i = j }
                    '#' -> { val j = readIdent(i + 1); if (j > i + 1) id = t.substring(i + 1, j); i = j }
                    '[' -> {
                        val close = t.indexOf(']', i)
                        val end = if (close < 0) n else close
                        parseAttr(t.substring(i + 1, end))?.let { attrs.add(it) }
                        i = if (close < 0) n else close + 1
                    }
                    ':' -> {
                        if (i + 1 < n && t[i + 1] == ':') { pseudoElement = true; i += 2 } else i++
                        val j = readIdent(i)
                        val name = t.substring(i, j).lowercase()
                        if (name == "before" || name == "after" || name == "first-line" || name == "first-letter") pseudoElement = true
                        else pseudoClasses++
                        i = j
                        // skip a functional pseudo's (...) argument
                        if (i < n && t[i] == '(') { val cl = t.indexOf(')', i); i = if (cl < 0) n else cl + 1 }
                    }
                    else -> i++ // tolerate stray chars
                }
            }
            return SimpleSelector(tag, id, classes, attrs, pseudoClasses, pseudoElement)
        }

        private fun parseAttr(body: String): AttrCond? {
            val ops = listOf("~=", "|=", "^=", "$=", "*=", "=")
            for (op in ops) {
                val idx = body.indexOf(op)
                if (idx > 0) {
                    val name = body.substring(0, idx).trim().lowercase()
                    var value = body.substring(idx + op.length).trim()
                    if (value.length >= 2 && (value.first() == '"' || value.first() == '\'') && value.last() == value.first()) {
                        value = value.substring(1, value.length - 1)
                    }
                    return if (name.isEmpty()) null else AttrCond(name, op, value)
                }
            }
            val name = body.trim().lowercase()
            return if (name.isEmpty()) null else AttrCond(name, null, null)
        }
    }
}
