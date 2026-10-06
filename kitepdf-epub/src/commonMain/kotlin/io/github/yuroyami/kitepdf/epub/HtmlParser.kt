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
     * as its document's parser would (#545). For an [html] document, the tree has the `html`, `head`
     * and `body` elements that HTML's parser makes, as [placeDocument] places them (#547).
     */
    fun parse(xhtml: String, keepComments: Boolean = false, keepNames: Boolean = false, html: Boolean = false): KiteXmlNode.Element {
        val root = KiteXmlNode.Element("#root", emptyMap())
        val stack = OpenElements().apply { add(root) }
        var warned = false
        val tokens = KiteXml.tokenize(xhtml, keepComments, keepNames).let { if (html) placeDocument(it) else it }

        for (t in tokens) when (t) {
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

    /** The elements that belong in a head, which HTML's parser puts there before the body starts (13.2.6.4.4). */
    private val HEAD_ELEMENTS = setOf("base", "basefont", "bgsound", "link", "meta", "noframes", "noscript", "script", "style", "template", "title")

    private fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\u000C' || c == '\r'

    /**
     * [tokens] with the `html`, `head` and `body` elements where HTML's tree construction puts them,
     * in its insertion modes from "initial" to "after after body" (HTML, 13.2.6.4.1 to 13.2.6.4.7,
     * 13.2.6.4.19 and 13.2.6.4.22). The parser makes each one the markup leaves out. A later
     * `<html>` or `<body>` adds the attributes the first one lacks, and white space and comments go
     * where their mode puts them. What the body holds keeps its order.
     */
    private fun placeDocument(tokens: List<KiteXmlToken>): List<KiteXmlToken> {
        val before = ArrayList<KiteXmlToken>()
        val htmlAttrs = LinkedHashMap<String, String>()
        val beforeHead = ArrayList<KiteXmlToken>()
        val headAttrs = LinkedHashMap<String, String>()
        val head = ArrayList<KiteXmlToken>()
        val between = ArrayList<KiteXmlToken>()
        val bodyAttrs = LinkedHashMap<String, String>()
        val body = ArrayList<KiteXmlToken>()
        val afterBody = ArrayList<KiteXmlToken>()
        val after = ArrayList<KiteXmlToken>()
        // 0 before html, 1 before head, 2 in head, 3 after head, 4 in body, 5 after body, 6 after after body
        var mode = 0
        // The head element whose content goes into the head, and how deep in it the tokens are.
        var inside: String? = null
        var depth = 0
        // A text on each side of a tag that only merged its attributes is one text, as the parser appends to the last text node.
        var dropped = false
        fun merge(into: MutableMap<String, String>, attrs: Map<String, String>) {
            for ((k, v) in attrs) if (k !in into) into[k] = v
            dropped = true
        }
        fun toBody(token: KiteXmlToken) {
            val last = body.lastOrNull()
            if (dropped && token is KiteXmlToken.Text && last is KiteXmlToken.Text) body[body.lastIndex] = KiteXmlToken.Text(last.text + token.text)
            else body += token
            dropped = false
        }
        for (token in tokens) {
            if (inside != null) {
                head += token
                if (token is KiteXmlToken.Open && token.name == inside && !token.selfClose) depth++
                if (token is KiteXmlToken.Close && token.name == inside && --depth == 0) inside = null
                continue
            }
            var t: KiteXmlToken? = token
            while (t != null) {
                val now = t
                t = null
                if (now is KiteXmlToken.Text && mode != 4) {
                    // White space at the start of a text goes where the mode puts it, and the rest is content.
                    var n = 0
                    while (n < now.text.length && isSpace(now.text[n])) n++
                    if (n > 0) {
                        val space = KiteXmlToken.Text(now.text.substring(0, n))
                        when (mode) {
                            2 -> head += space
                            3 -> between += space
                            5, 6 -> toBody(space)
                        }
                    }
                    if (n == now.text.length) continue
                    if (n > 0) { t = KiteXmlToken.Text(now.text.substring(n)); continue }
                }
                when (mode) {
                    0 -> when {
                        now is KiteXmlToken.Comment -> before += now
                        now is KiteXmlToken.Open && now.name == "html" -> { merge(htmlAttrs, now.attrs); mode = 1 }
                        now is KiteXmlToken.Close && now.name !in setOf("head", "body", "html", "br") -> {}
                        else -> { mode = 1; t = now }
                    }
                    1 -> when {
                        now is KiteXmlToken.Comment -> beforeHead += now
                        now is KiteXmlToken.Open && now.name == "html" -> merge(htmlAttrs, now.attrs)
                        now is KiteXmlToken.Open && now.name == "head" -> { merge(headAttrs, now.attrs); mode = 2 }
                        now is KiteXmlToken.Close && now.name !in setOf("head", "body", "html", "br") -> {}
                        else -> { mode = 2; t = now }
                    }
                    2, 3 -> when {
                        now is KiteXmlToken.Comment -> if (mode == 2) head += now else between += now
                        now is KiteXmlToken.Open && now.name == "html" -> merge(htmlAttrs, now.attrs)
                        now is KiteXmlToken.Open && now.name == "head" -> {}
                        // After the head, HTML's parser puts the head elements but noscript back into it.
                        now is KiteXmlToken.Open && now.name in HEAD_ELEMENTS && (mode == 2 || now.name != "noscript") -> {
                            head += now
                            if (!now.selfClose && now.name !in VOID) { inside = now.name; depth = 1 }
                        }
                        now is KiteXmlToken.Close && now.name == "head" && mode == 2 -> mode = 3
                        now is KiteXmlToken.Open && now.name == "body" && mode == 3 -> { merge(bodyAttrs, now.attrs); mode = 4 }
                        now is KiteXmlToken.Close && now.name !in setOf("body", "html", "br") -> {}
                        else -> { mode = if (mode == 2) 3 else 4; t = now }
                    }
                    else -> when {
                        now is KiteXmlToken.Comment && mode != 4 -> if (mode == 5) afterBody += now else after += now
                        now is KiteXmlToken.Open && now.name == "html" -> merge(htmlAttrs, now.attrs)
                        now is KiteXmlToken.Open && now.name == "body" -> merge(bodyAttrs, now.attrs)
                        now is KiteXmlToken.Close && now.name == "body" -> { if (mode == 4) mode = 5; dropped = true }
                        now is KiteXmlToken.Close && now.name == "html" -> { mode = 6; dropped = true }
                        mode != 4 -> { mode = 4; t = now }
                        else -> toBody(now)
                    }
                }
            }
        }
        return before + KiteXmlToken.Open("html", htmlAttrs, false) + beforeHead + KiteXmlToken.Open("head", headAttrs, false) + head +
            KiteXmlToken.Close("head") + between + KiteXmlToken.Open("body", bodyAttrs, false) + body + KiteXmlToken.Close("body") +
            afterBody + KiteXmlToken.Close("html") + after
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
