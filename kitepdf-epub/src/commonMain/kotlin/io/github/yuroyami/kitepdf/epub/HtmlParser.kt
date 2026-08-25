package io.github.yuroyami.kitepdf.epub

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

    /** Parse [xhtml] into a synthetic `#root` element holding the document. */
    fun parse(xhtml: String): KiteXmlNode.Element {
        val root = KiteXmlNode.Element("#root", emptyMap())
        val stack = ArrayList<KiteXmlNode.Element>().apply { add(root) }

        for (t in KiteXml.tokenize(xhtml)) when (t) {
            is KiteXmlToken.Open -> {
                implicitClose(stack, t.name)
                val el = KiteXmlNode.Element(t.name, t.attrs)
                el.parent = stack.last()
                stack.last().children.add(el)
                if (!t.selfClose && t.name !in VOID) stack.add(el)
            }
            is KiteXmlToken.Close -> {
                if (t.name in VOID) continue
                // Pop to the nearest matching open tag; tolerate mismatched nesting
                // by leaving the stack alone if no match is open.
                val idx = stack.indexOfLast { it.tag == t.name }
                if (idx >= 1) while (stack.size > idx) stack.removeAt(stack.lastIndex)
            }
            is KiteXmlToken.Text -> stack.last().children.add(KiteXmlNode.Text(t.text))
        }
        return root
    }

    /** Apply optional-end-tag rules before opening [opening]. */
    private fun implicitClose(stack: ArrayList<KiteXmlNode.Element>, opening: String) {
        // Close the nearest still-open item of [itemTags], but stop (close nothing)
        // if a [barriers] container is reached first -- that means the new item
        // belongs to a nested list/table opened inside the outer item.
        fun closeItem(itemTags: Set<String>, barriers: Set<String>) {
            for (k in stack.indices.reversed()) {
                if (k < 1) return
                val tag = stack[k].tag
                if (tag in barriers) return
                if (tag in itemTags) { while (stack.size > k) stack.removeAt(stack.lastIndex); return }
            }
        }
        when {
            opening in LIST_ITEM -> closeItem(LIST_ITEM, LIST_CONTAINER)
            opening in DEF_ITEM -> closeItem(DEF_ITEM, DL_CONTAINER)
            opening in TABLE_ROW -> closeItem(TABLE_ROW, TABLE_SCOPE)
            opening in TABLE_CELL -> closeItem(TABLE_CELL, ROW_SCOPE)
        }
        if (opening in CLOSES_P) {
            val pIdx = stack.indexOfLast { it.tag == "p" }
            if (pIdx >= 1) while (stack.size > pIdx) stack.removeAt(stack.lastIndex)
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
