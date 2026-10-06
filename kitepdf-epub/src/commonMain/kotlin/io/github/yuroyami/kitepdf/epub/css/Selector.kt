package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.text.Bidi
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

/** How a compound selector relates to the one on its left. */
internal enum class Combinator { DESCENDANT, CHILD, NEXT_SIBLING, SUBSEQUENT_SIBLING }

/** The generated-content pseudo-elements we synthesize (`::before` / `::after`). */
internal enum class PseudoSide { BEFORE, AFTER }

/**
 * What selector matching reads of a tree: the names and attributes of its elements, and how its
 * document compares them (HTML, 4.16.2). The layout's tree and a chapter's script DOM each give
 * their own, since only the second keeps the case and namespace of a name.
 */
internal interface SelectorTree {
    fun localName(el: KiteXmlNode.Element): String

    /** The namespace of [el], or null for none. */
    fun namespace(el: KiteXmlNode.Element): String?

    /**
     * Whether a selector compares the names of [el] and of its attributes ignoring ASCII case: an
     * HTML element in an HTML document (HTML, 4.16.2), and in Blink, which a book's scripts are
     * written against, an SVG or MathML element in one too.
     */
    fun namesIgnoreCase(el: KiteXmlNode.Element): Boolean

    /** Whether the document is an HTML document, where the values of some attributes of an HTML element compare ignoring case. */
    val htmlDocument: Boolean

    /** Whether `:empty` takes white space text as no content, as Selectors 4 has it and browsers do not. */
    val whitespaceIsEmpty: Boolean

    /** Whether [a] and [b] have the same local name and namespace, for the `-of-type` pseudo-classes. */
    fun sameType(a: KiteXmlNode.Element, b: KiteXmlNode.Element): Boolean

    /**
     * Whether [el] has an attribute of the local name [local] ([lower] in lowercase) in [namespace], which is
     * null for any and empty for none, whose value passes [test].
     */
    fun attribute(el: KiteXmlNode.Element, namespace: String?, local: String, lower: String, test: (String) -> Boolean): Boolean

    /** The value of the attribute of [el] named [name] in no namespace, or null. */
    fun attr(el: KiteXmlNode.Element, name: String): String?

    /** The form control state [key] of [el] that a script set, or null while the control follows its attributes (#552). */
    fun state(el: KiteXmlNode.Element, key: String): String? = el.attrs[FormStates.STATE + key]
}

/**
 * The layout's tree, whose tags and attribute names are lowercased with their prefix dropped: a
 * name compares ignoring case, and an element's namespace is SVG's or MathML's inside an `svg` or a
 * `math` element and HTML's elsewhere.
 */
internal object LayoutTree : SelectorTree {
    override fun localName(el: KiteXmlNode.Element): String = el.tag

    override fun namespace(el: KiteXmlNode.Element): String? {
        var e: KiteXmlNode.Element? = el
        while (e != null && !e.tag.startsWith('#')) {
            when (e.tag) {
                "svg" -> return SVG_NS
                "math" -> return MATHML_NS
                "foreignobject" -> if (e !== el) return XHTML_NS
            }
            e = e.parent
        }
        return XHTML_NS
    }

    override fun namesIgnoreCase(el: KiteXmlNode.Element): Boolean = true
    override val htmlDocument: Boolean get() = false
    override val whitespaceIsEmpty: Boolean get() = true
    override fun sameType(a: KiteXmlNode.Element, b: KiteXmlNode.Element): Boolean = a.tag == b.tag

    override fun attribute(el: KiteXmlNode.Element, namespace: String?, local: String, lower: String, test: (String) -> Boolean): Boolean =
        el.attrs[lower]?.let(test) ?: false

    override fun attr(el: KiteXmlNode.Element, name: String): String? = el.attrs[name]
}

internal const val XHTML_NS = "http://www.w3.org/1999/xhtml"
internal const val SVG_NS = "http://www.w3.org/2000/svg"
internal const val MATHML_NS = "http://www.w3.org/1998/Math/MathML"

/** What a match reads besides the element: the tree, and the element `:scope` names, or null when that is the root. */
internal class MatchContext(val tree: SelectorTree, val scope: KiteXmlNode.Element?)

/** A condition of a compound selector past its type: an id, a class, an attribute or a pseudo-class. */
internal sealed class Condition {
    abstract fun matches(el: KiteXmlNode.Element, cx: MatchContext): Boolean

    /** Its specificity, packed as [Specificity] does. */
    open val specificity: Int get() = Specificity.CLASS
}

internal class IdCondition(val id: String) : Condition() {
    override fun matches(el: KiteXmlNode.Element, cx: MatchContext): Boolean = cx.tree.attr(el, "id") == id
    override val specificity: Int get() = Specificity.ID
}

internal class ClassCondition(val name: String) : Condition() {
    override fun matches(el: KiteXmlNode.Element, cx: MatchContext): Boolean =
        cx.tree.attr(el, "class")?.let { hasToken(it, name, ignoreCase = false) } ?: false
}

/**
 * An attribute selector (Selectors 4, 6.3): the attribute's [namespace] (null for any, empty for
 * none), its [local] name, the operator [op] (`0` for presence, else the first character of `=`,
 * `~=`, `|=`, `^=`, `$=` or `*=`), the [value], and the `i` or `s` [flag] or `0`.
 */
internal class AttrCondition(val namespace: String?, val local: String, val op: Char, val value: String, val flag: Char) : Condition() {
    private val lower = asciiLower(local)
    private val lowerValue = asciiLower(value)

    override fun matches(el: KiteXmlNode.Element, cx: MatchContext): Boolean {
        val tree = cx.tree
        val ignoreCase = when (flag) {
            'i' -> true
            's' -> false
            else -> tree.htmlDocument && lower in CASE_INSENSITIVE_VALUES && tree.namespace(el) == XHTML_NS
        }
        return tree.attribute(el, namespace, local, lower) { test(it, ignoreCase) }
    }

    private fun test(actual: String, ignoreCase: Boolean): Boolean {
        val have = if (ignoreCase) asciiLower(actual) else actual
        val want = if (ignoreCase) lowerValue else value
        return when (op) {
            '0' -> true
            '=' -> have == want
            '~' -> want.isNotEmpty() && want.none(::isAsciiWhitespace) && hasToken(have, want, ignoreCase = false)
            '|' -> have == want || (have.startsWith(want) && have.length > want.length && have[want.length] == '-')
            '^' -> want.isNotEmpty() && have.startsWith(want)
            '$' -> want.isNotEmpty() && have.endsWith(want)
            '*' -> want.isNotEmpty() && have.contains(want)
            else -> false
        }
    }

    private companion object {
        /** The attributes whose values a selector compares ignoring ASCII case on an HTML element of an HTML document (HTML, 4.16.2). */
        val CASE_INSENSITIVE_VALUES = setOf(
            "accept", "accept-charset", "align", "alink", "axis", "bgcolor", "charset", "checked", "clear", "codetype", "color",
            "compact", "declare", "defer", "dir", "direction", "disabled", "enctype", "face", "frame", "hreflang", "http-equiv",
            "lang", "language", "link", "media", "method", "multiple", "nohref", "noresize", "noshade", "nowrap", "readonly", "rel",
            "rev", "rules", "scope", "scrolling", "selected", "shape", "target", "text", "type", "valign", "valuetype", "vlink",
        )
    }
}

/** The pseudo-classes that take no argument and that a match decides. */
internal enum class PseudoKind {
    ROOT, SCOPE, EMPTY, FIRST_CHILD, LAST_CHILD, ONLY_CHILD, FIRST_OF_TYPE, LAST_OF_TYPE, ONLY_OF_TYPE,
    ANY_LINK, NEVER, DEFINED, OPEN,
    CHECKED, DEFAULT, INDETERMINATE, DISABLED, ENABLED, REQUIRED, OPTIONAL, READ_ONLY, READ_WRITE,
    PLACEHOLDER_SHOWN, VALID, INVALID, IN_RANGE, OUT_OF_RANGE,
}

internal class PseudoCondition(val kind: PseudoKind) : Condition() {
    override fun matches(el: KiteXmlNode.Element, cx: MatchContext): Boolean {
        val tree = cx.tree
        return when (kind) {
            PseudoKind.ROOT -> isRoot(el)
            PseudoKind.SCOPE -> cx.scope?.let { it === el } ?: isRoot(el)
            PseudoKind.EMPTY -> el.children.none { c ->
                when (c) {
                    is KiteXmlNode.Element -> true
                    is KiteXmlNode.Text -> if (tree.whitespaceIsEmpty) c.text.isNotBlank() else c.text.isNotEmpty()
                    is KiteXmlNode.Comment -> false
                }
            }
            PseudoKind.FIRST_CHILD -> previousElement(el) == null
            PseudoKind.LAST_CHILD -> nextElement(el) == null
            PseudoKind.ONLY_CHILD -> previousElement(el) == null && nextElement(el) == null
            PseudoKind.FIRST_OF_TYPE -> noSiblingOfType(el, tree, before = true)
            PseudoKind.LAST_OF_TYPE -> noSiblingOfType(el, tree, before = false)
            PseudoKind.ONLY_OF_TYPE -> noSiblingOfType(el, tree, before = true) && noSiblingOfType(el, tree, before = false)
            PseudoKind.ANY_LINK -> isLink(el, tree)
            PseudoKind.NEVER -> false
            PseudoKind.DEFINED -> !(isCustomElementName(tree.localName(el)) && tree.namespace(el) == XHTML_NS)
            PseudoKind.OPEN -> html(el, tree).let { (it == "details" || it == "dialog") && tree.attr(el, "open") != null }
            else -> FormStates.matches(kind, el, tree)
        }
    }
}

/** A pseudo-class that is valid and never holds here, as `:hover` in a paginated book, or `:host` outside a shadow tree. */
internal object NeverCondition : Condition() {
    override fun matches(el: KiteXmlNode.Element, cx: MatchContext): Boolean = false
}

/**
 * `:nth-child`, `:nth-last-child`, `:nth-of-type` and `:nth-last-of-type` (Selectors 4, 14.4): the
 * element's place among its siblings, counted from the end when [last], among those of its own type
 * when [ofType] or those that [of] matches, is `An+B` for some n of 0 or more.
 */
internal class NthCondition(val a: Int, val b: Int, val last: Boolean, val ofType: Boolean, val of: List<Selector>?) : Condition() {
    override fun matches(el: KiteXmlNode.Element, cx: MatchContext): Boolean {
        if (of != null && of.none { it.matchFrom(el, cx) }) return false
        var position = 1L
        val siblings = el.parent?.children
        if (siblings != null) {
            val at = siblings.indexOfFirst { it === el }
            val range = if (last) (at + 1 until siblings.size) else (0 until at)
            for (k in range) {
                val s = siblings[k] as? KiteXmlNode.Element ?: continue
                val counts = when {
                    ofType -> cx.tree.sameType(s, el)
                    of != null -> of.any { it.matchFrom(s, cx) }
                    else -> true
                }
                if (counts) position++
            }
        }
        val n = position - b
        return if (a == 0) n == 0L else n % a == 0L && n / a >= 0
    }

    override val specificity: Int get() = Specificity.add(Specificity.CLASS, of?.let(Specificity::max) ?: 0)
}

/** `:is()`, `:where()` (when not [counts]) and `:-webkit-any()`: any of [list] matches. */
internal class IsCondition(val list: List<Selector>, val counts: Boolean) : Condition() {
    override fun matches(el: KiteXmlNode.Element, cx: MatchContext): Boolean = list.any { it.matchFrom(el, cx) }
    override val specificity: Int get() = if (counts) Specificity.max(list) else 0
}

internal class NotCondition(val list: List<Selector>) : Condition() {
    override fun matches(el: KiteXmlNode.Element, cx: MatchContext): Boolean = list.none { it.matchFrom(el, cx) }
    override val specificity: Int get() = Specificity.max(list)
}

/** `:has()` (Selectors 4, 4.5): one of the relative selectors of [list] matches an element relative to this one. */
internal class HasCondition(val list: List<Selector>) : Condition() {
    override fun matches(el: KiteXmlNode.Element, cx: MatchContext): Boolean = list.any { relative(it, el, cx) }
    override val specificity: Int get() = Specificity.max(list)

    private fun relative(sel: Selector, anchor: KiteXmlNode.Element, cx: MatchContext): Boolean {
        val siblingsOnly = sel.combinators.all { it == Combinator.NEXT_SIBLING || it == Combinator.SUBSEQUENT_SIBLING }
        fun tryAt(c: KiteXmlNode.Element) = sel.matchFrom(c, cx, anchor)
        fun subtree(root: KiteXmlNode.Element): Boolean {
            for (c in root.children) if (c is KiteXmlNode.Element && (tryAt(c) || subtree(c))) return true
            return false
        }
        return when (sel.leading) {
            Combinator.CHILD -> anchor.children.any { c ->
                c is KiteXmlNode.Element && (tryAt(c) || (!siblingsOnly && subtree(c)))
            }
            Combinator.NEXT_SIBLING, Combinator.SUBSEQUENT_SIBLING -> {
                var s = nextElement(anchor)
                while (s != null) {
                    if (tryAt(s) || (!siblingsOnly && subtree(s))) return true
                    if (sel.leading == Combinator.NEXT_SIBLING && siblingsOnly && sel.combinators.isEmpty()) return false
                    s = nextElement(s)
                }
                false
            }
            else -> subtree(anchor)
        }
    }
}

/** `:lang()` with its language [ranges], matched by the extended filtering of RFC 4647, 3.3.2 (Selectors 4, 7.2). */
internal class LangCondition(val ranges: List<String>) : Condition() {
    override fun matches(el: KiteXmlNode.Element, cx: MatchContext): Boolean {
        var e: KiteXmlNode.Element? = el
        while (e != null) {
            val lang = cx.tree.attr(e, "lang")
            if (lang != null) return lang.isNotEmpty() && ranges.any { extendedFilter(it, lang) }
            e = parentElement(e)
        }
        return false
    }

    private fun extendedFilter(range: String, tag: String): Boolean {
        val r = asciiLower(range).split('-')
        val t = asciiLower(tag).split('-')
        if (r[0] != "*" && r[0] != t[0]) return false
        var i = 1
        var j = 1
        while (i < r.size) {
            when {
                r[i] == "*" -> i++
                j >= t.size -> return false
                r[i] == t[j] -> { i++; j++ }
                t[j].length == 1 -> return false
                else -> j++
            }
        }
        return true
    }
}

/** `:dir()` with [rtl] for `rtl`, false for `ltr`, null for another value, which never matches (HTML, 3.2.6.4). */
internal class DirCondition(val rtl: Boolean?) : Condition() {
    override fun matches(el: KiteXmlNode.Element, cx: MatchContext): Boolean = rtl != null && directionality(el, cx.tree) == rtl

    private fun directionality(el: KiteXmlNode.Element, tree: SelectorTree): Boolean {
        var e = el
        while (true) {
            when (tree.attr(e, "dir")?.let(::asciiLower)) {
                "ltr" -> return false
                "rtl" -> return true
                "auto" -> return autoDirection(e, tree) ?: false
            }
            if (html(e, tree) == "bdi") return autoDirection(e, tree) ?: false
            e = parentElement(e) ?: return false
        }
    }

    /** The direction of the first strong character of [el]'s text, past the elements that set their own, or null. */
    private fun autoDirection(el: KiteXmlNode.Element, tree: SelectorTree): Boolean? {
        for (c in el.children) when (c) {
            is KiteXmlNode.Text -> {
                var k = 0
                while (k < c.text.length) {
                    val ch = c.text[k]
                    val cp = if (ch.isHighSurrogate() && k + 1 < c.text.length && c.text[k + 1].isLowSurrogate()) {
                        k++
                        0x10000 + ((ch.code - 0xD800) shl 10) + (c.text[k].code - 0xDC00)
                    } else ch.code
                    k++
                    when (Bidi.classify(cp)) {
                        Bidi.L -> return false
                        Bidi.R, Bidi.AL -> return true
                    }
                }
            }
            is KiteXmlNode.Element -> {
                val skip = html(c, tree) in AUTO_SKIPPED || tree.attr(c, "dir")?.let(::asciiLower) in VALID_DIRS
                if (!skip) autoDirection(c, tree)?.let { return it }
            }
            is KiteXmlNode.Comment -> {}
        }
        return null
    }

    private companion object {
        val AUTO_SKIPPED = setOf("bdi", "script", "style", "textarea")
        val VALID_DIRS = setOf("ltr", "rtl", "auto")
    }
}

/**
 * A compound selector: a type, which [tag] names as written or leaves out for `*`, in [namespace]
 * (null for any, empty for none), then its [conditions], and a pseudo-element.
 */
internal class SimpleSelector(
    val tag: String?,
    val namespace: String?,
    val conditions: List<Condition>,
    /** `::before`/`::after` on this compound, or null. */
    val pseudoElement: PseudoSide? = null,
    /** Another pseudo-element, which the layout does not draw and no element is, by name. */
    val otherPseudoElement: String? = null,
) {
    private val lowerTag = tag?.let(::asciiLower)

    val specificity: Int by lazy {
        var s = if (tag != null) Specificity.TYPE else 0
        for (c in conditions) s = Specificity.add(s, c.specificity)
        if (pseudoElement != null || otherPseudoElement != null) s = Specificity.add(s, Specificity.TYPE)
        s
    }

    fun matches(el: KiteXmlNode.Element, cx: MatchContext): Boolean {
        val tree = cx.tree
        if (tag != null) {
            val local = tree.localName(el)
            if (if (tree.namesIgnoreCase(el)) !asciiEquals(local, lowerTag!!) else local != tag) return false
        }
        for (c in conditions) if (!c.matches(el, cx)) return false
        return namespace == null || (tree.namespace(el) ?: "") == namespace
    }
}

/**
 * A complex selector: compound [parts] joined left to right by [combinators] (one fewer), whose last
 * part is the subject, and for a relative selector of `:has()`, the [leading] combinator that ties
 * its first part to the element `:has()` is on. Matching runs right to left through the tree's
 * parent and sibling links, failing a whole branch early as Blink's selector checker does.
 */
internal class Selector(
    val parts: List<SimpleSelector>,
    val combinators: List<Combinator>,
    val leading: Combinator? = null,
) {
    /** `::before`/`::after` on the subject compound, or null for a normal selector. */
    val pseudoElement: PseudoSide? get() = parts.last().pseudoElement

    /** Whether the subject has a pseudo-element other than `::before` and `::after`. */
    val otherPseudoElement: Boolean get() = parts.last().otherPseudoElement != null

    /** Specificity (Selectors 4, 17), packed as [Specificity] does. */
    val specificity: Int by lazy { parts.fold(0) { s, p -> Specificity.add(s, p.specificity) } }

    /**
     * Whether this selector's subject, or the element its `::before` or `::after` comes from, is
     * [el] in the layout's tree. [ancestors] is no longer read: matching walks the parent links.
     */
    @Suppress("UNUSED_PARAMETER")
    fun matches(el: KiteXmlNode.Element, ancestors: List<KiteXmlNode.Element> = emptyList()): Boolean =
        !otherPseudoElement && matchFrom(el, LAYOUT)

    /** Whether [el] is this selector's subject in [tree], with [scope] as `:scope`: never when the subject is a pseudo-element. */
    fun matchesElement(el: KiteXmlNode.Element, tree: SelectorTree, scope: KiteXmlNode.Element?): Boolean =
        pseudoElement == null && !otherPseudoElement && matchFrom(el, MatchContext(tree, scope))

    internal fun matchFrom(el: KiteXmlNode.Element, cx: MatchContext, anchor: KiteXmlNode.Element? = null): Boolean =
        match(parts.size - 1, el, cx, anchor) == MATCHES

    private fun match(index: Int, el: KiteXmlNode.Element, cx: MatchContext, anchor: KiteXmlNode.Element?): Int {
        if (!parts[index].matches(el, cx)) return FAILS_LOCALLY
        if (index == 0) {
            if (anchor == null || leading == null) return MATCHES
            return if (related(anchor, el, leading)) MATCHES else FAILS_LOCALLY
        }
        when (combinators[index - 1]) {
            Combinator.CHILD -> {
                val p = parentElement(el) ?: return FAILS_COMPLETELY
                return match(index - 1, p, cx, anchor)
            }
            Combinator.DESCENDANT -> {
                var a = parentElement(el)
                while (a != null) {
                    val r = match(index - 1, a, cx, anchor)
                    if (r == MATCHES || r == FAILS_COMPLETELY) return r
                    a = parentElement(a)
                }
                return FAILS_COMPLETELY
            }
            Combinator.NEXT_SIBLING -> {
                val s = previousElement(el) ?: return FAILS_ALL_SIBLINGS
                return match(index - 1, s, cx, anchor)
            }
            Combinator.SUBSEQUENT_SIBLING -> {
                var s = previousElement(el)
                while (s != null) {
                    val r = match(index - 1, s, cx, anchor)
                    if (r != FAILS_LOCALLY) return r
                    s = previousElement(s)
                }
                return FAILS_ALL_SIBLINGS
            }
        }
    }

    /** Whether [el] stands to [anchor] as [combinator] says. */
    private fun related(anchor: KiteXmlNode.Element, el: KiteXmlNode.Element, combinator: Combinator): Boolean = when (combinator) {
        Combinator.CHILD -> parentElement(el) === anchor
        Combinator.DESCENDANT -> generateSequence(parentElement(el), ::parentElement).any { it === anchor }
        Combinator.NEXT_SIBLING -> previousElement(el) === anchor
        Combinator.SUBSEQUENT_SIBLING -> generateSequence(previousElement(el), ::previousElement).any { it === anchor }
    }

    companion object {
        private const val MATCHES = 0
        private const val FAILS_LOCALLY = 1
        private const val FAILS_ALL_SIBLINGS = 2
        private const val FAILS_COMPLETELY = 3

        private val LAYOUT = MatchContext(LayoutTree, null)

        /** One complex selector, or null when [text] is not exactly one valid selector. */
        fun parse(text: String): Selector? = parseList(text)?.singleOrNull()

        /**
         * The selector list [text] (Selectors 4, 4.1), with the prefixes of [namespaces] and its default
         * namespace under the empty prefix, or null when it is not valid, which drops a whole style rule
         * and makes a script's query throw.
         */
        fun parseList(text: String, namespaces: Map<String, String> = emptyMap()): List<Selector>? =
            SelectorParser(text, namespaces).selectorList()
    }
}

/** A specificity packed as three bytes, ids, then classes, then types, each held at 255 (Selectors 4, 17). */
internal object Specificity {
    const val ID = 1 shl 16
    const val CLASS = 1 shl 8
    const val TYPE = 1

    fun add(x: Int, y: Int): Int {
        val a = minOf(255, (x shr 16) + (y shr 16))
        val b = minOf(255, ((x shr 8) and 255) + ((y shr 8) and 255))
        val c = minOf(255, (x and 255) + (y and 255))
        return (a shl 16) or (b shl 8) or c
    }

    fun max(list: List<Selector>): Int = list.maxOfOrNull { it.specificity } ?: 0
}

/** The parent of [el] that is an element: a document, a fragment or the parser's root is none. */
internal fun parentElement(el: KiteXmlNode.Element): KiteXmlNode.Element? = el.parent?.takeUnless { it.tag.startsWith('#') }

internal fun previousElement(el: KiteXmlNode.Element): KiteXmlNode.Element? {
    val siblings = el.parent?.children ?: return null
    var prev: KiteXmlNode.Element? = null
    for (c in siblings) {
        if (c === el) return prev
        if (c is KiteXmlNode.Element) prev = c
    }
    return null
}

internal fun nextElement(el: KiteXmlNode.Element): KiteXmlNode.Element? {
    val siblings = el.parent?.children ?: return null
    var seen = false
    for (c in siblings) {
        if (c === el) seen = true
        else if (seen && c is KiteXmlNode.Element) return c
    }
    return null
}

/** Whether [el] is the document element: the child of a document, not of a fragment or of no parent. */
private fun isRoot(el: KiteXmlNode.Element): Boolean = el.parent?.tag.let { it == "#root" || it == "#document" }

private fun noSiblingOfType(el: KiteXmlNode.Element, tree: SelectorTree, before: Boolean): Boolean {
    var s = if (before) previousElement(el) else nextElement(el)
    while (s != null) {
        if (tree.sameType(s, el)) return false
        s = if (before) previousElement(s) else nextElement(s)
    }
    return true
}

/** The local name of [el] when it is an HTML element, or null. */
internal fun html(el: KiteXmlNode.Element, tree: SelectorTree): String? {
    val local = tree.localName(el)
    return if (tree.namespace(el) == XHTML_NS) local else null
}

/** `a` and `area` with an `href`, and SVG's `a` with one (HTML, 4.16.3), all unvisited here. */
private fun isLink(el: KiteXmlNode.Element, tree: SelectorTree): Boolean {
    val local = tree.localName(el)
    if (local != "a" && local != "area") return false
    if (tree.attr(el, "href") == null) return false
    val ns = tree.namespace(el)
    return ns == XHTML_NS || (ns == SVG_NS && local == "a")
}

/** A valid custom element name of HTML, 4.13.2, in the form a parser keeps: a lowercase ASCII letter first and a hyphen. */
private fun isCustomElementName(name: String): Boolean =
    name.isNotEmpty() && name[0] in 'a'..'z' && '-' in name && name.none { it in 'A'..'Z' } && name !in RESERVED_NAMES

private val RESERVED_NAMES = setOf(
    "annotation-xml", "color-profile", "font-face", "font-face-src", "font-face-uri", "font-face-format", "font-face-name", "missing-glyph",
)

internal fun isAsciiWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\u000C' || c == '\r'

/** Whether the ASCII white space separated list [list] holds [token]. */
internal fun hasToken(list: String, token: String, ignoreCase: Boolean): Boolean {
    var i = 0
    val n = list.length
    while (i < n) {
        while (i < n && isAsciiWhitespace(list[i])) i++
        val start = i
        while (i < n && !isAsciiWhitespace(list[i])) i++
        if (i - start == token.length && i > start && list.regionMatches(start, token, 0, token.length, ignoreCase)) return true
    }
    return false
}
