package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.epub.HtmlNode
import io.github.yuroyami.kitepdf.epub.elementParent
import io.github.yuroyami.kitepdf.epub.previousElementSibling

/** How a compound selector relates to the one on its left. */
internal enum class Combinator { DESCENDANT, CHILD, NEXT_SIBLING, SUBSEQUENT_SIBLING }

/** The generated-content pseudo-elements we synthesize (`::before` / `::after`). */
internal enum class PseudoSide { BEFORE, AFTER }

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

/**
 * A structural or link pseudo-class, matched for real against the DOM
 * (sibling/index queries walk [HtmlNode.Element.parent]). [Unknown] covers
 * every pseudo-class we do not implement plus the interaction states that can
 * never hold in a paginated renderer (`:visited`, `:hover`, `:focus`,
 * `:active`): per CSS invalid-selector behaviour they make the whole selector
 * never match, instead of the old always-match.
 */
internal sealed class PseudoClass {
    object FirstChild : PseudoClass()
    object LastChild : PseudoClass()
    object OnlyChild : PseudoClass()
    /** `:nth-child(An+B)`; `odd` = 2n+1, `even` = 2n, a bare integer = 0n+B. */
    class NthChild(val a: Int, val b: Int) : PseudoClass()
    object FirstOfType : PseudoClass()
    object LastOfType : PseudoClass()
    object Empty : PseudoClass()
    object Root : PseudoClass()
    /** `:link` = an `<a>` carrying an `href` (all links are unvisited here). */
    object Link : PseudoClass()
    /** `:not(<compound>)`; one compound argument, no nesting, no inner pseudos. */
    class Not(val inner: SimpleSelector) : PseudoClass()
    object Unknown : PseudoClass()

    fun matches(el: HtmlNode.Element): Boolean = when (this) {
        FirstChild -> el.previousElementSibling() == null
        LastChild -> nextElementSibling(el) == null
        OnlyChild -> el.previousElementSibling() == null && nextElementSibling(el) == null
        is NthChild -> {
            val i = elementIndex(el) // 1-based among element siblings
            if (a == 0) i == b else ((i - b) % a == 0 && (i - b) / a >= 0)
        }
        FirstOfType -> siblingElements(el).firstOrNull { it.tag == el.tag } === el
        LastOfType -> siblingElements(el).lastOrNull { it.tag == el.tag } === el
        // Whitespace-only text does not count as content (books indent markup).
        Empty -> el.children.none { c ->
            c is HtmlNode.Element || (c is HtmlNode.Text && c.text.isNotBlank())
        }
        Root -> el.elementParent() == null
        Link -> el.tag == "a" && "href" in el.attrs
        is Not -> !inner.matches(el)
        Unknown -> false
    }

    private companion object {
        fun siblingElements(el: HtmlNode.Element): List<HtmlNode.Element> =
            el.parent?.children?.filterIsInstance<HtmlNode.Element>() ?: listOf(el)

        fun elementIndex(el: HtmlNode.Element): Int {
            var i = 1
            for (c in el.parent?.children ?: return 1) {
                if (c === el) return i
                if (c is HtmlNode.Element) i++
            }
            return i
        }

        fun nextElementSibling(el: HtmlNode.Element): HtmlNode.Element? {
            val siblings = el.parent?.children ?: return null
            var seen = false
            for (c in siblings) {
                if (c === el) { seen = true; continue }
                if (seen && c is HtmlNode.Element) return c
            }
            return null
        }
    }
}

/** A compound selector: an optional type plus any #id, .class, [attr], :pseudo conditions. */
internal class SimpleSelector(
    val tag: String?, // null = universal
    val id: String?,
    val classes: List<String>,
    val attrs: List<AttrCond>,
    val pseudos: List<PseudoClass>,
    /** `::before`/`::after` on this compound, or null. */
    val pseudoElement: PseudoSide? = null,
    /** Any OTHER pseudo-element (`::first-line`, ...): the selector is dropped. */
    val unsupportedPseudoElement: Boolean = false,
) {
    /** Specificity contribution of the pseudo-classes (the `b` bucket). */
    val pseudoClassCount: Int get() = pseudos.size

    fun matches(el: HtmlNode.Element): Boolean {
        if (tag != null && tag != el.tag) return false
        if (id != null && el.attrs["id"] != id) return false
        if (classes.isNotEmpty()) {
            val cs = el.attrs["class"]?.split(' ', '\t', '\n', '\r')?.filter { it.isNotEmpty() } ?: emptyList()
            if (!classes.all { it in cs }) return false
        }
        if (!attrs.all { it.matches(el) }) return false
        return pseudos.all { it.matches(el) }
    }
}

/**
 * A complex selector: compound [parts] joined left-to-right by [combinators]
 * (size = parts-1). The rightmost part is the subject. Matching is right-to-left
 * through the DOM's parent/sibling pointers ([HtmlNode.Element.parent]), so
 * `>`, `+` and `~` all resolve against the real tree. A `::before`/`::after`
 * on the subject survives as [pseudoElement] (the cascade routes such rules to
 * generated content); any other pseudo-element makes [parse] return null.
 */
internal class Selector(
    val parts: List<SimpleSelector>,
    val combinators: List<Combinator>,
) {
    /** `::before`/`::after` on the subject compound, or null for a normal selector. */
    val pseudoElement: PseudoSide? get() = parts.last().pseudoElement

    /** CSS specificity (ids, classes+attrs+pseudo-classes, types+pseudo-elements). */
    val specificity: Int by lazy {
        var a = 0; var b = 0; var c = 0
        for (p in parts) {
            if (p.id != null) a++
            b += p.classes.size + p.attrs.size + p.pseudoClassCount
            if (p.tag != null) c++
            if (p.pseudoElement != null) c++ // pseudo-elements count as types
        }
        (a shl 16) or (b shl 8) or c.coerceAtMost(255)
    }

    /**
     * Whether this selector's subject is [el]. Matching walks the DOM via
     * parent/sibling pointers; [ancestors] is retained for source compatibility
     * with older call sites but is no longer consulted.
     */
    fun matches(el: HtmlNode.Element, ancestors: List<HtmlNode.Element> = emptyList()): Boolean =
        matchesFrom(parts.size - 1, el)

    private fun matchesFrom(partIdx: Int, el: HtmlNode.Element): Boolean {
        if (!parts[partIdx].matches(el)) return false
        if (partIdx == 0) return true
        return when (combinators[partIdx - 1]) {
            Combinator.CHILD ->
                el.elementParent()?.let { matchesFrom(partIdx - 1, it) } ?: false
            Combinator.DESCENDANT -> {
                var a = el.elementParent()
                while (a != null) {
                    if (matchesFrom(partIdx - 1, a)) return true
                    a = a.elementParent()
                }
                false
            }
            Combinator.NEXT_SIBLING ->
                el.previousElementSibling()?.let { matchesFrom(partIdx - 1, it) } ?: false
            Combinator.SUBSEQUENT_SIBLING -> {
                var s = el.previousElementSibling()
                while (s != null) {
                    if (matchesFrom(partIdx - 1, s)) return true
                    s = s.previousElementSibling()
                }
                false
            }
        }
    }

    companion object {
        /** Parse one complex selector; null if empty or it targets a pseudo-element. */
        fun parse(text: String): Selector? {
            val parts = ArrayList<SimpleSelector>()
            val combs = ArrayList<Combinator>()
            val sb = StringBuilder()
            var pending: Combinator? = null
            var bracket = 0
            var paren = 0

            fun flush(): Boolean {
                val t = sb.toString().trim(); sb.clear()
                if (t.isEmpty()) return true
                val simple = parseSimple(t)
                if (simple.unsupportedPseudoElement) return false // whole selector unusable
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
                    // A functional pseudo's argument (`:nth-child(2n+1)`, `:not(.x)`)
                    // may contain `+`, `~` and whitespace; none of those are
                    // combinators while inside the parentheses.
                    c == '(' -> { paren++; sb.append(c) }
                    c == ')' -> { paren--; sb.append(c) }
                    paren > 0 -> sb.append(c)
                    c == '>' -> { if (!flush()) return null; pending = Combinator.CHILD }
                    c == '+' -> { if (!flush()) return null; pending = Combinator.NEXT_SIBLING }
                    c == '~' -> { if (!flush()) return null; pending = Combinator.SUBSEQUENT_SIBLING }
                    // Whitespace just ends the current compound; flush() defaults the
                    // joint to DESCENDANT when no explicit combinator was seen.
                    c.isWhitespace() -> { if (sb.isNotBlank()) { if (!flush()) return null } }
                    else -> sb.append(c)
                }
                i++
            }
            if (!flush()) return null
            if (parts.isEmpty()) return null
            // A dangling explicit combinator (`p >`) is invalid CSS: drop the selector.
            if (pending != null) return null
            // ::before/::after may only sit on the subject (the last compound).
            for (k in 0 until parts.size - 1) if (parts[k].pseudoElement != null) return null
            return Selector(parts, combs)
        }

        private fun parseSimple(t: String): SimpleSelector {
            var tag: String? = null
            var id: String? = null
            val classes = ArrayList<String>()
            val attrs = ArrayList<AttrCond>()
            val pseudos = ArrayList<PseudoClass>()
            var pseudoElement: PseudoSide? = null
            var unsupportedPseudo = false
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
                        val doubleColon = i + 1 < n && t[i + 1] == ':'
                        if (doubleColon) i += 2 else i++
                        val j = readIdent(i)
                        val name = t.substring(i, j).lowercase()
                        i = j
                        // Capture a functional pseudo's (...) argument.
                        var arg: String? = null
                        if (i < n && t[i] == '(') {
                            var depth = 0
                            var k = i
                            while (k < n) {
                                if (t[k] == '(') depth++
                                if (t[k] == ')') { depth--; if (depth == 0) break }
                                k++
                            }
                            val close = if (k < n) k else n
                            arg = t.substring(i + 1, close.coerceAtMost(n))
                            i = if (k < n) k + 1 else n
                        }
                        when {
                            name == "before" || name == "after" -> {
                                val side = if (name == "before") PseudoSide.BEFORE else PseudoSide.AFTER
                                // Two different pseudo-elements on one compound: invalid.
                                if (pseudoElement != null && pseudoElement != side) unsupportedPseudo = true
                                pseudoElement = side
                            }
                            // Every other pseudo-ELEMENT (::first-line, ::marker, ...)
                            // drops the selector; single-colon unknown names remain
                            // pseudo-classes (which never match).
                            name == "first-line" || name == "first-letter" || doubleColon -> unsupportedPseudo = true
                            else -> pseudos.add(parsePseudoClass(name, arg))
                        }
                    }
                    else -> i++ // tolerate stray chars
                }
            }
            return SimpleSelector(tag, id, classes, attrs, pseudos, pseudoElement, unsupportedPseudo)
        }

        private fun parsePseudoClass(name: String, arg: String?): PseudoClass = when (name) {
            "first-child" -> PseudoClass.FirstChild
            "last-child" -> PseudoClass.LastChild
            "only-child" -> PseudoClass.OnlyChild
            "first-of-type" -> PseudoClass.FirstOfType
            "last-of-type" -> PseudoClass.LastOfType
            "empty" -> PseudoClass.Empty
            "root" -> PseudoClass.Root
            "link" -> PseudoClass.Link
            "nth-child" -> parseNth(arg)?.let { (a, b) -> PseudoClass.NthChild(a, b) } ?: PseudoClass.Unknown
            "not" -> {
                val inner = arg?.trim()?.takeIf { it.isNotEmpty() }?.let { parseSimple(it) }
                // One compound argument only: an inner pseudo-anything is out of scope.
                if (inner == null || inner.pseudoElement != null || inner.unsupportedPseudoElement ||
                    inner.pseudos.isNotEmpty()
                ) PseudoClass.Unknown
                else PseudoClass.Not(inner)
            }
            // Interaction states never hold in a paginated renderer.
            "visited", "hover", "focus", "active" -> PseudoClass.Unknown
            else -> PseudoClass.Unknown
        }

        /** `An+B` (plus `odd`/`even`/bare integer) → (A, B), or null when malformed. */
        private fun parseNth(arg: String?): Pair<Int, Int>? {
            val s = arg?.trim()?.lowercase()?.replace(" ", "") ?: return null
            if (s.isEmpty()) return null
            if (s == "odd") return 2 to 1
            if (s == "even") return 2 to 0
            if ('n' !in s) return s.toIntOrNull()?.let { 0 to it }
            val aPart = s.substringBefore('n')
            val bPart = s.substringAfter('n')
            val a = when (aPart) {
                "", "+" -> 1
                "-" -> -1
                else -> aPart.toIntOrNull() ?: return null
            }
            val b = if (bPart.isEmpty()) 0 else bPart.toIntOrNull() ?: return null
            return a to b
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
