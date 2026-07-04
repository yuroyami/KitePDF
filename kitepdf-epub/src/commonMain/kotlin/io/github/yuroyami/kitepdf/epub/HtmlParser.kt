package io.github.yuroyami.kitepdf.epub

/**
 * Folds the flat [MiniXml] token stream into an [HtmlNode] tree, recovering from
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
    fun parse(xhtml: String): HtmlNode.Element {
        val root = HtmlNode.Element("#root", emptyMap())
        val stack = ArrayList<HtmlNode.Element>().apply { add(root) }

        for (t in MiniXml.tokenize(xhtml)) when (t) {
            is XmlToken.Open -> {
                implicitClose(stack, t.name)
                val el = HtmlNode.Element(t.name, t.attrs)
                stack.last().children.add(el)
                if (!t.selfClose && t.name !in VOID) stack.add(el)
            }
            is XmlToken.Close -> {
                if (t.name in VOID) continue
                // Pop to the nearest matching open tag; tolerate mismatched nesting
                // by leaving the stack alone if no match is open.
                val idx = stack.indexOfLast { it.tag == t.name }
                if (idx >= 1) while (stack.size > idx) stack.removeAt(stack.lastIndex)
            }
            is XmlToken.Text -> stack.last().children.add(HtmlNode.Text(t.text))
        }
        return root
    }

    /** Apply optional-end-tag rules before opening [opening]. */
    private fun implicitClose(stack: ArrayList<HtmlNode.Element>, opening: String) {
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
