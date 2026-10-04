package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.kiteWarn
import io.github.yuroyami.kitepdf.core.xml.KiteXml
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.core.xml.KiteXmlToken

/**
 * Folds the flat [KiteXml] token stream into an [KiteXmlNode] tree, recovering from
 * the tag soup real books ship: void elements that are never closed, and
 * optional end tags (`<p>`, `<li>`, `<dd>/<dt>`, table rows/cells) that the
 * markup relies on the parser to imply. Well-formed XHTML (the EPUB 3 norm) is a
 * subset of what this accepts -- explicit closes always win; the implied ones
 * only fire when an author left them out.
 */
internal object HtmlParser {

    /** Elements that never have children; a close tag for them is ignored. */
    private val VOID = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    )

    /** Starting any of these implies closing a still-open `<p>`. */
    private val CLOSES_P = setOf(
        "address", "article", "aside", "blockquote", "details", "div", "dl",
        "dd", "dt", "fieldset", "figcaption", "figure", "footer", "form",
        "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup", "hr", "main",
        "menu", "nav", "ol", "p", "pre", "section", "table", "ul",
    )

    private val LIST_ITEM = setOf("li")
    private val DEF_ITEM = setOf("dd", "dt")
    private val TABLE_ROW = setOf("tr")
    private val TABLE_CELL = setOf("td", "th")

    // A same-kind item's implied close must not reach across a nested container
    // (a new <li> inside a nested <ul> opens there; it does not close the outer <li>).
    private val LIST_CONTAINER = setOf("ul", "ol", "menu")
    private val DL_CONTAINER = setOf("dl")
    private val TABLE_SCOPE = setOf("table")
    private val ROW_SCOPE = setOf("table", "thead", "tbody", "tfoot")

    /** The layout keeps only rows, cells and columns under these, so another child would lose its text. */
    private val TABLE_PARTS = setOf("table", "thead", "tbody", "tfoot", "tr", "colgroup")

    /**
     * The deepest level of an element, with `html` at level 1. Layout recurses once for each level,
     * and in a debug build 51 nested grid containers overflow the 512 KB stack of a secondary
     * thread on Apple platforms (#450).
     */
    const val MAX_DEPTH = 32

    /**
     * Parse [xhtml] into a synthetic `#root` element holding the document. An element nested
     * deeper than [MAX_DEPTH] moves up to that level, or above a table part, and keeps its text.
     * Comments are dropped unless [keepComments] asks for them, as a script's DOM does (#544), and an
     * attribute keeps its name as written, prefix and case, for [keepNames], which a script's DOM names
     * as its document's parser would (#545).
     */
    fun parse(xhtml: String, keepComments: Boolean = false, keepNames: Boolean = false): KiteXmlNode.Element {
        val root = KiteXmlNode.Element("#root", emptyMap())
        val stack = OpenElements().apply { add(root) }
        var warned = false

        for (t in KiteXml.tokenize(xhtml, keepComments, keepNames)) when (t) {
            is KiteXmlToken.Open -> {
                implicitClose(stack, t.name)
                val el = KiteXmlNode.Element(t.name, t.attrs)
                // Past the limit, the parent is the last ancestor inside it, as in Blink and WebKit.
                var at = minOf(stack.lastIndex, MAX_DEPTH - 1)
                if (at < stack.lastIndex) {
                    while (stack[at].tag in TABLE_PARTS) at--
                    if (!warned) {
                        warned = true
                        kiteWarn { "epub: more than $MAX_DEPTH elements are nested, so deeper elements are moved up" }
                    }
                }
                el.parent = stack[at]
                stack[at].children.add(el)
                if (!t.selfClose && t.name !in VOID) stack.add(el)
            }
            is KiteXmlToken.Close -> {
                if (t.name in VOID) continue
                // Pop to the nearest matching open tag; tolerate mismatched nesting
                // by leaving the stack alone if no match is open.
                val idx = stack.lastIndexOf(t.name)
                if (idx >= 1) stack.popTo(idx)
            }
            is KiteXmlToken.Text -> stack.last().children.add(KiteXmlNode.Text(t.text))
            is KiteXmlToken.Comment -> stack.last().children.add(KiteXmlNode.Comment(t.text))
        }
        return root
    }

    /** Apply optional-end-tag rules before opening [opening]. */
    private fun implicitClose(stack: OpenElements, opening: String) {
        // Close the nearest still-open item of [itemTags], but stop (close nothing)
        // if a [barriers] container is reached first -- that means the new item
        // belongs to a nested list/table opened inside the outer item.
        fun closeItem(itemTags: Set<String>, barriers: Set<String>) {
            val item = stack.lastIndexOf(itemTags)
            if (item >= 1 && item > stack.lastIndexOf(barriers)) stack.popTo(item)
        }
        when {
            opening in LIST_ITEM -> closeItem(LIST_ITEM, LIST_CONTAINER)
            opening in DEF_ITEM -> closeItem(DEF_ITEM, DL_CONTAINER)
            opening in TABLE_ROW -> closeItem(TABLE_ROW, TABLE_SCOPE)
            opening in TABLE_CELL -> closeItem(TABLE_CELL, ROW_SCOPE)
        }
        if (opening in CLOSES_P) {
            val pIdx = stack.lastIndexOf("p")
            if (pIdx >= 1) stack.popTo(pIdx)
        }
    }

    /**
     * Index the nearest open occurrence of each tag instead of rescanning the stack (#452).
     * Each entry remembers the previous occurrence, so popping a mismatched or implied close
     * restores every affected tag. Each element is pushed and popped at most once.
     */
    private class OpenElements {
        private val elements = ArrayList<KiteXmlNode.Element>()
        private val previous = ArrayList<Int>()
        private val nearest = HashMap<String, Int>()

        val lastIndex: Int get() = elements.lastIndex

        operator fun get(index: Int): KiteXmlNode.Element = elements[index]
        fun last(): KiteXmlNode.Element = elements.last()
        fun lastIndexOf(tag: String): Int = nearest[tag] ?: -1

        fun lastIndexOf(tags: Set<String>): Int {
            var index = -1
            for (tag in tags) index = maxOf(index, lastIndexOf(tag))
            return index
        }

        fun add(element: KiteXmlNode.Element) {
            previous.add(nearest.put(element.tag, elements.size) ?: -1)
            elements.add(element)
        }

        /** Remove the matching element at [index] and every element opened after it. */
        fun popTo(index: Int) {
            while (elements.size > index) {
                val tag = elements.removeAt(elements.lastIndex).tag
                val before = previous.removeAt(previous.lastIndex)
                if (before < 0) nearest.remove(tag) else nearest[tag] = before
            }
        }
    }
}

/**
 * Parent for selector ANCESTOR walks: the synthetic `#root` wrapper is not a
 * real element, so combinators must not match against it (a top-level element
 * has no ancestor). Sibling/index queries, by contrast, DO use the raw
 * [KiteXmlNode.Element.parent] so the document element is its parent's
 * `:first-child`, matching browser behaviour.
 */
internal fun KiteXmlNode.Element.elementParent(): KiteXmlNode.Element? =
    parent?.takeIf { it.tag != "#root" }

/** Every text node under this element, in document order, joined as written. */
internal fun KiteXmlNode.Element.textContent(): String = buildString {
    fun rec(n: KiteXmlNode) { when (n) { is KiteXmlNode.Text -> append(n.text); is KiteXmlNode.Element -> n.children.forEach(::rec); is KiteXmlNode.Comment -> {} } }
    rec(this@textContent)
}

/** Nearest preceding sibling that is an element, or null. */
internal fun KiteXmlNode.Element.previousElementSibling(): KiteXmlNode.Element? {
    val siblings = parent?.children ?: return null
    var prev: KiteXmlNode.Element? = null
    for (c in siblings) {
        if (c === this) return prev
        if (c is KiteXmlNode.Element) prev = c
    }
    return null
}
