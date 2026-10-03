package io.github.yuroyami.kitepdf.epub.script

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.epub.HtmlParser
import io.github.yuroyami.kitepdf.epub.css.Selector

/**
 * The live tree a chapter's scripts read and change (#41): a copy of the chapter's parse that
 * belongs to the scripts alone, so a layout on another thread never sees it change. Each node
 * has a number, which is how the script side names it, and [snapshot] hands the layout a copy
 * of the tree as it stands.
 *
 * An attribute map is never changed in place, only replaced, so a snapshot may share it.
 */
internal class ScriptDom(source: KiteXmlNode.Element) {

    private val ids = HashMap<KiteXmlNode, Int>()
    private val nodes = ArrayList<KiteXmlNode>()

    /** The element each text node sits in: a text node keeps no parent of its own. */
    private val textParents = HashMap<KiteXmlNode.Text, KiteXmlNode.Element>()

    /** Elements made by a script and not yet in the tree, and every fragment, by number. */
    private val fragments = HashSet<KiteXmlNode.Element>()

    /** Each element of the layout's tree, to its element here. */
    var fromLayout: Map<KiteXmlNode.Element, KiteXmlNode.Element> = emptyMap()
        private set

    /** Each element here, to its element in the layout's tree. */
    var toLayout: Map<KiteXmlNode.Element, KiteXmlNode.Element> = emptyMap()
        private set

    /** The parser's `#root` element, which holds `<html>`: what a script calls the document. */
    val root: KiteXmlNode.Element

    /** True once a script changed the tree since the last [snapshot]. */
    var dirty: Boolean = false
        private set

    init {
        val from = HashMap<KiteXmlNode.Element, KiteXmlNode.Element>()
        val to = HashMap<KiteXmlNode.Element, KiteXmlNode.Element>()
        root = copy(source, null, from, to, link = true)
        fromLayout = from
        toLayout = to
    }

    fun idOf(node: KiteXmlNode): Int = ids.getOrPut(node) { nodes.add(node); nodes.size - 1 }

    fun node(id: Int): KiteXmlNode? = nodes.getOrNull(id)

    fun element(id: Int): KiteXmlNode.Element? = node(id) as? KiteXmlNode.Element

    fun parentOf(node: KiteXmlNode): KiteXmlNode.Element? = when (node) {
        is KiteXmlNode.Element -> node.parent
        is KiteXmlNode.Text -> textParents[node]
    }

    /** 9 for the document, 11 for a fragment, 1 for an element and 3 for text, as the DOM numbers them. */
    fun kind(node: KiteXmlNode): Int = when {
        node === root -> 9
        node is KiteXmlNode.Text -> 3
        node in fragments && (node as KiteXmlNode.Element).tag == FRAGMENT -> 11
        else -> 1
    }

    /** Every text under [node], in order. */
    fun textOf(node: KiteXmlNode): String = when (node) {
        is KiteXmlNode.Text -> node.text
        is KiteXmlNode.Element -> buildString { appendText(node, this) }
    }

    private fun appendText(el: KiteXmlNode.Element, out: StringBuilder) {
        for (c in el.children) when (c) {
            is KiteXmlNode.Text -> out.append(c.text)
            is KiteXmlNode.Element -> appendText(c, out)
        }
    }

    fun setText(node: KiteXmlNode, text: String) {
        when (node) {
            is KiteXmlNode.Text -> node.text = text
            is KiteXmlNode.Element -> {
                clearChildren(node)
                if (text.isNotEmpty()) append(node, KiteXmlNode.Text(text))
            }
        }
        dirty = true
    }

    fun setAttr(el: KiteXmlNode.Element, name: String, value: String) {
        el.attrs = LinkedHashMap(el.attrs).apply { put(name, value) }
        dirty = true
    }

    fun removeAttr(el: KiteXmlNode.Element, name: String) {
        if (name !in el.attrs) return
        el.attrs = LinkedHashMap(el.attrs).apply { remove(name) }
        dirty = true
    }

    fun createElement(tag: String): KiteXmlNode.Element = KiteXmlNode.Element(tag, emptyMap()).also { fragments.add(it) }

    fun createFragment(): KiteXmlNode.Element = KiteXmlNode.Element(FRAGMENT, emptyMap()).also { fragments.add(it) }

    /**
     * Puts [child] into [parent] before [before], or at the end, taking it out of where it was.
     * A fragment gives up its children instead. Answers the DOM's error name for a move that
     * would make no tree, or null.
     */
    fun insert(parent: KiteXmlNode.Element, child: KiteXmlNode, before: KiteXmlNode?): String? {
        if (before != null && parentOf(before) !== parent) return "NotFoundError"
        if (child === root || (child is KiteXmlNode.Element && isAncestor(child, parent))) return "HierarchyRequestError"
        if (child === before) return null
        if (child is KiteXmlNode.Element && child.tag == FRAGMENT && child in fragments) {
            for (c in child.children.toList()) insert(parent, c, before)
            return null
        }
        detach(child)
        val at = if (before == null) parent.children.size else parent.children.indexOfFirst { it === before }
        parent.children.add(at, child)
        link(parent, child)
        dirty = true
        return null
    }

    /** Takes [child] out of its parent. Answers the DOM's error name when [parent] is not its parent. */
    fun remove(parent: KiteXmlNode.Element?, child: KiteXmlNode): String? {
        if (parent != null && parentOf(child) !== parent) return "NotFoundError"
        detach(child)
        return null
    }

    private fun detach(child: KiteXmlNode) {
        val old = parentOf(child) ?: return
        old.children.removeAll { it === child }
        when (child) {
            is KiteXmlNode.Element -> child.parent = null
            is KiteXmlNode.Text -> textParents.remove(child)
        }
        dirty = true
    }

    private fun clearChildren(el: KiteXmlNode.Element) {
        for (c in el.children.toList()) detach(c)
    }

    private fun append(parent: KiteXmlNode.Element, child: KiteXmlNode) {
        parent.children.add(child)
        link(parent, child)
    }

    private fun link(parent: KiteXmlNode.Element, child: KiteXmlNode) {
        when (child) {
            is KiteXmlNode.Element -> {
                child.parent = parent
                fragments.remove(child)
            }
            is KiteXmlNode.Text -> textParents[child] = parent
        }
    }

    private fun isAncestor(candidate: KiteXmlNode.Element, of: KiteXmlNode.Element): Boolean {
        var at: KiteXmlNode.Element? = of
        while (at != null) {
            if (at === candidate) return true
            at = at.parent
        }
        return false
    }

    /** True when [node] is in the document, not in a fragment or on its own. */
    fun isConnected(node: KiteXmlNode): Boolean {
        var at: KiteXmlNode.Element? = parentOf(node) ?: return node === root
        while (at != null) {
            if (at === root) return true
            at = at.parent
        }
        return false
    }

    /** A copy of [node], with its subtree when [deep], on its own. */
    fun clone(node: KiteXmlNode, deep: Boolean): KiteXmlNode = when (node) {
        is KiteXmlNode.Text -> KiteXmlNode.Text(node.text)
        is KiteXmlNode.Element -> {
            val out = KiteXmlNode.Element(if (node === root) FRAGMENT else node.tag, node.attrs)
            fragments.add(out)
            if (deep) for (c in node.children) append(out, clone(c, true))
            out
        }
    }

    /** The first element whose `id` is [id], in tree order. */
    fun byId(id: String): KiteXmlNode.Element? = find(root) { it.attrs["id"] == id }

    private fun find(el: KiteXmlNode.Element, predicate: (KiteXmlNode.Element) -> Boolean): KiteXmlNode.Element? {
        for (c in el.children) if (c is KiteXmlNode.Element) {
            if (predicate(c)) return c
            find(c, predicate)?.let { return it }
        }
        return null
    }

    /**
     * The elements under [scope] that [selectors] match, in tree order, or null when the list does
     * not parse. Only the first, when not [all].
     */
    fun query(scope: KiteXmlNode.Element, selectors: String, all: Boolean): List<KiteXmlNode.Element>? {
        val parsed = parseSelectors(selectors) ?: return null
        val out = ArrayList<KiteXmlNode.Element>()
        fun walk(el: KiteXmlNode.Element): Boolean {
            for (c in el.children) if (c is KiteXmlNode.Element) {
                if (parsed.any { it.matches(c) }) {
                    out.add(c)
                    if (!all) return true
                }
                if (walk(c)) return true
            }
            return false
        }
        walk(scope)
        return out
    }

    /** Whether [selectors] match [el], or null when the list does not parse. */
    fun matches(el: KiteXmlNode.Element, selectors: String): Boolean? = parseSelectors(selectors)?.any { it.matches(el) }

    private fun parseSelectors(text: String): List<Selector>? {
        val parts = splitSelectors(text)
        if (parts.isEmpty()) return null
        return parts.map { Selector.parse(it.trim()) ?: return null }
    }

    /** [text] cut at its top-level commas: one inside brackets, parentheses or quotes stays. */
    private fun splitSelectors(text: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var quote = 0.toChar()
        var start = 0
        for (i in text.indices) {
            val c = text[i]
            when {
                quote != 0.toChar() -> if (c == quote) quote = 0.toChar()
                c == '"' || c == '\'' -> quote = c
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> depth--
                c == ',' && depth == 0 -> { out.add(text.substring(start, i)); start = i + 1 }
            }
        }
        out.add(text.substring(start))
        return out.filter { it.isNotBlank() }.takeIf { it.size == out.size }.orEmpty()
    }

    /** [node] as HTML: its children only, or the element itself too when [outer]. */
    fun html(node: KiteXmlNode, outer: Boolean): String = buildString {
        if (outer || node is KiteXmlNode.Text) serialize(node, this)
        else for (c in (node as KiteXmlNode.Element).children) serialize(c, this)
    }

    private fun serialize(node: KiteXmlNode, out: StringBuilder) {
        when (node) {
            is KiteXmlNode.Text -> {
                val raw = textParents[node]?.tag in RAW_TEXT
                out.append(if (raw) node.text else escape(node.text, attribute = false))
            }
            is KiteXmlNode.Element -> {
                out.append('<').append(node.tag)
                for ((k, v) in node.attrs) out.append(' ').append(k).append("=\"").append(escape(v, attribute = true)).append('"')
                out.append('>')
                if (node.tag in VOID) return
                for (c in node.children) serialize(c, out)
                out.append("</").append(node.tag).append('>')
            }
        }
    }

    /** Parses [html] and puts what it holds in place of [el]'s children. */
    fun setHtml(el: KiteXmlNode.Element, html: String) {
        clearChildren(el)
        for (c in parse(html)) append(el, c)
        dirty = true
    }

    /**
     * Parses [html] and puts what it holds at [position] of [el], as `insertAdjacentHTML` names
     * it. Answers the DOM's error name for a position that does not exist, or null.
     */
    fun insertHtml(el: KiteXmlNode.Element, position: String, html: String): String? {
        val parsed = parse(html)
        when (position.lowercase()) {
            "beforebegin" -> { val p = el.parent ?: return "NoModificationAllowedError"; for (c in parsed) insert(p, c, el) }
            "afterbegin" -> { val first = el.children.firstOrNull(); for (c in parsed) insert(el, c, first) }
            "beforeend" -> for (c in parsed) insert(el, c, null)
            "afterend" -> {
                val p = el.parent ?: return "NoModificationAllowedError"
                val next = p.children.getOrNull(p.children.indexOfFirst { it === el } + 1)
                for (c in parsed) insert(p, c, next)
            }
            else -> return "SyntaxError"
        }
        dirty = true
        return null
    }

    /**
     * Parses [html] and puts what it holds after [cursor], as `document.write` does while its
     * script runs, and answers the last node it put there, where the next write goes; null when
     * [cursor] is not in a tree.
     */
    fun writeAfter(cursor: KiteXmlNode, html: String): KiteXmlNode? {
        val parent = parentOf(cursor) ?: return null
        var at = cursor
        for (node in parse(html)) {
            val next = parent.children.getOrNull(parent.children.indexOfFirst { it === at } + 1)
            insert(parent, node, next)
            at = node
        }
        return at
    }

    private fun parse(html: String): List<KiteXmlNode> {
        val parsed = HtmlParser.parse(html).children.toList()
        for (c in parsed) {
            if (c is KiteXmlNode.Element) c.parent = null
            registerTexts(c)
        }
        return parsed
    }

    /** Records the parent of every text node under [node], which the parser does not keep. */
    private fun registerTexts(node: KiteXmlNode) {
        if (node !is KiteXmlNode.Element) return
        for (c in node.children) {
            if (c is KiteXmlNode.Text) textParents[c] = node else registerTexts(c)
        }
    }

    /**
     * A copy of the tree for the layout, which from now on owns it, and remembers which element
     * of it is which element here.
     */
    fun snapshot(): KiteXmlNode.Element {
        val from = HashMap<KiteXmlNode.Element, KiteXmlNode.Element>()
        val to = HashMap<KiteXmlNode.Element, KiteXmlNode.Element>()
        val out = copy(root, null, from, to, link = false)
        fromLayout = from
        toLayout = to
        dirty = false
        return out
    }

    /**
     * A copy of [el] and its subtree under [parent]. [from] maps each new element to its original
     * when [link] makes the copy the live tree, and the other way round when the copy is a
     * snapshot; [to] maps the other direction.
     */
    private fun copy(
        el: KiteXmlNode.Element,
        parent: KiteXmlNode.Element?,
        from: HashMap<KiteXmlNode.Element, KiteXmlNode.Element>,
        to: HashMap<KiteXmlNode.Element, KiteXmlNode.Element>,
        link: Boolean,
    ): KiteXmlNode.Element {
        val out = KiteXmlNode.Element(el.tag, el.attrs)
        out.parent = parent
        if (link) { from[el] = out; to[out] = el } else { from[out] = el; to[el] = out }
        for (c in el.children) {
            val child = when (c) {
                is KiteXmlNode.Element -> copy(c, out, from, to, link)
                is KiteXmlNode.Text -> KiteXmlNode.Text(c.text).also { if (link) textParents[it] = out }
            }
            out.children.add(child)
        }
        return out
    }

    companion object {
        const val FRAGMENT = "#document-fragment"

        private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
        private val RAW_TEXT = setOf("script", "style")

        private fun escape(text: String, attribute: Boolean): String = buildString(text.length) {
            for (c in text) when (c) {
                '&' -> append("&amp;")
                '<' -> if (attribute) append(c) else append("&lt;")
                '>' -> if (attribute) append(c) else append("&gt;")
                '"' -> if (attribute) append("&quot;") else append(c)
                ' ' -> append("&nbsp;")
                else -> append(c)
            }
        }
    }
}
