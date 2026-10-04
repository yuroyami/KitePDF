package io.github.yuroyami.kitepdf.epub.script

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.epub.HtmlParser
import io.github.yuroyami.kitepdf.epub.css.Selector
import io.github.yuroyami.kitepdf.epub.css.SelectorTree

/**
 * The live tree a chapter's scripts read and change (#41): a copy of the chapter's parse that
 * belongs to the scripts alone, so a layout on another thread never sees it change. Each node
 * has a number, which is how the script side names it, and [snapshot] hands the layout a copy
 * of the tree as it stands.
 *
 * An attribute map is never changed in place, only replaced, so a snapshot may share it.
 *
 * The tree has the comments of [commented], the chapter parsed again with them, which the layout's
 * tree drops, and a snapshot drops them again, so a layout never sees one (#544).
 */
internal class ScriptDom(
    source: KiteXmlNode.Element,
    private val html: Boolean = false,
    commented: KiteXmlNode.Element? = null,
) {

    private val ids = HashMap<KiteXmlNode, Int>()
    private val nodes = ArrayList<KiteXmlNode>()

    /** The element each text node and comment sits in: neither keeps a parent of its own. */
    private val leafParents = HashMap<KiteXmlNode, KiteXmlNode.Element>()

    /** Elements made by a script and not yet in the tree, and every fragment, by number. */
    private val fragments = HashSet<KiteXmlNode.Element>()

    /** The documents a script made, each a tree of its own. */
    private val documents = HashSet<KiteXmlNode.Element>()

    /**
     * The namespace, prefix and local name of an element (DOM Standard, 4.9), which its tag does
     * not keep: the parser lowercases a name and drops its prefix (#541).
     */
    class Name(val namespace: String?, val prefix: String?, val localName: String)

    private val names = HashMap<KiteXmlNode.Element, Name>()

    /** Each element of the layout's tree, to its element here. */
    var fromLayout: Map<KiteXmlNode.Element, KiteXmlNode.Element> = emptyMap()
        private set

    /** Each element here, to its element in the layout's tree. */
    var toLayout: Map<KiteXmlNode.Element, KiteXmlNode.Element> = emptyMap()
        private set

    /** The parser's `#root` element, which holds `<html>`: what a script calls the document. */
    val root: KiteXmlNode.Element

    /**
     * True once a script changed the tree since the last [snapshot]. The host sets it when the
     * layout shows another tree than this one, so the next snapshot replaces it (#498).
     */
    var dirty: Boolean = false

    /**
     * How many changes a script made to the tree, its attributes and its text: a live collection
     * of the script side reads its nodes again once this moved (#542).
     */
    var version: Int = 0
        private set

    private fun changed() {
        dirty = true
        version++
    }

    init {
        val from = HashMap<KiteXmlNode.Element, KiteXmlNode.Element>()
        val to = HashMap<KiteXmlNode.Element, KiteXmlNode.Element>()
        root = copy(source, null, from, to, link = true, commented = commented)
        fromLayout = from
        toLayout = to
        for (c in root.children) if (c is KiteXmlNode.Element) nameTree(c, root)
    }

    /** The name of [el]: the one it was made with, or else the one its place in the tree gives it. */
    fun nameOf(el: KiteXmlNode.Element): Name = names.getOrPut(el) { parsedName(el, el.parent) }

    /** Names [el] and the elements under it as the parser names them, with [parent] above it. */
    private fun nameTree(el: KiteXmlNode.Element, parent: KiteXmlNode.Element?) {
        names[el] = parsedName(el, parent)
        for (c in el.children) if (c is KiteXmlNode.Element) nameTree(c, el)
    }

    /**
     * The name a parser gives [el] under [parent]. Its namespace is the one an `xmlns` attribute
     * declares, in an XML document; else SVG's for an `svg` element and MathML's for a `math`
     * one; else HTML's under a point where SVG or MathML holds HTML, as HTML's parser has it;
     * else its parent's, and HTML's at the top. A name in SVG takes the case of SVG's own names,
     * as HTML's parser gives it them, since the tag here is lowercased.
     */
    private fun parsedName(el: KiteXmlNode.Element, parent: KiteXmlNode.Element?): Name {
        val outer = parent?.takeIf { it !== root && it.tag != FRAGMENT && it !in documents }?.let(::nameOf)
        val declared = if (html) null else el.attrs["xmlns"]
        val namespace = when {
            declared != null -> declared.ifEmpty { null }
            el.tag == "svg" -> SVG_NS
            el.tag == "math" -> MATHML_NS
            outer == null || parent == null -> XHTML_NS
            outer.namespace == SVG_NS && (parent.tag == "foreignobject" || (html && (parent.tag == "desc" || parent.tag == "title"))) -> XHTML_NS
            outer.namespace == MATHML_NS && parent.tag in MATHML_TEXT && el.tag != "mglyph" && el.tag != "malignmark" -> XHTML_NS
            outer.namespace == MATHML_NS && parent.tag == "annotation-xml" &&
                parent.attrs["encoding"]?.lowercase().let { it == "text/html" || it == "application/xhtml+xml" } -> XHTML_NS
            else -> outer.namespace
        }
        return Name(namespace, null, if (namespace == SVG_NS) SVG_TAG_CASE[el.tag] ?: el.tag else el.tag)
    }

    fun idOf(node: KiteXmlNode): Int = ids.getOrPut(node) { nodes.add(node); nodes.size - 1 }

    fun node(id: Int): KiteXmlNode? = nodes.getOrNull(id)

    fun element(id: Int): KiteXmlNode.Element? = node(id) as? KiteXmlNode.Element

    fun parentOf(node: KiteXmlNode): KiteXmlNode.Element? = when (node) {
        is KiteXmlNode.Element -> node.parent
        is KiteXmlNode.Text, is KiteXmlNode.Comment -> leafParents[node]
    }

    /** 9 for the document, 11 for a fragment, 1 for an element, 3 for text and 8 for a comment, as the DOM numbers them. */
    fun kind(node: KiteXmlNode): Int = when {
        node === root || node in documents -> 9
        node is KiteXmlNode.Text -> 3
        node is KiteXmlNode.Comment -> 8
        node in fragments && (node as KiteXmlNode.Element).tag == FRAGMENT -> 11
        else -> 1
    }

    /** Every text under [node], in order, or a text node's or a comment's own data. */
    fun textOf(node: KiteXmlNode): String = when (node) {
        is KiteXmlNode.Text -> node.text
        is KiteXmlNode.Comment -> node.text
        is KiteXmlNode.Element -> buildString { appendText(node, this) }
    }

    private fun appendText(el: KiteXmlNode.Element, out: StringBuilder) {
        for (c in el.children) when (c) {
            is KiteXmlNode.Text -> out.append(c.text)
            is KiteXmlNode.Comment -> {}
            is KiteXmlNode.Element -> appendText(c, out)
        }
    }

    fun setText(node: KiteXmlNode, text: String) {
        when (node) {
            is KiteXmlNode.Text -> node.text = text
            is KiteXmlNode.Comment -> node.text = text
            is KiteXmlNode.Element -> {
                clearChildren(node)
                if (text.isNotEmpty()) append(node, KiteXmlNode.Text(text))
            }
        }
        changed()
    }

    fun setAttr(el: KiteXmlNode.Element, name: String, value: String) {
        el.attrs = LinkedHashMap(el.attrs).apply { put(name, value) }
        changed()
    }

    fun removeAttr(el: KiteXmlNode.Element, name: String) {
        if (name !in el.attrs) return
        el.attrs = LinkedHashMap(el.attrs).apply { remove(name) }
        changed()
    }

    /** An element of the name [localName] in [namespace], with [prefix], whose tag is that name lowercased as the parser's are. */
    fun createElement(localName: String, namespace: String?, prefix: String?): KiteXmlNode.Element =
        KiteXmlNode.Element(localName.lowercase(), emptyMap()).also {
            fragments.add(it)
            names[it] = Name(namespace, prefix, localName)
        }

    /** A document of its own, which no tree holds, as `new Document()` makes one. */
    fun createDocument(): KiteXmlNode.Element = KiteXmlNode.Element(DOCUMENT, emptyMap()).also { documents.add(it) }

    fun createFragment(): KiteXmlNode.Element = KiteXmlNode.Element(FRAGMENT, emptyMap()).also { fragments.add(it) }

    /**
     * Puts [child] into [parent] before [before], or at the end, taking it out of where it was.
     * A fragment gives up its children instead. Answers the DOM's error name for a move that
     * would make no tree, or null.
     */
    fun insert(parent: KiteXmlNode.Element, child: KiteXmlNode, before: KiteXmlNode?): String? {
        if (before != null && parentOf(before) !== parent) return "NotFoundError"
        if (child === root || child in documents || (child is KiteXmlNode.Element && isAncestor(child, parent))) return "HierarchyRequestError"
        if (child === before) return null
        if (child is KiteXmlNode.Element && child.tag == FRAGMENT && child in fragments) {
            for (c in child.children.toList()) insert(parent, c, before)
            return null
        }
        detach(child)
        val at = if (before == null) parent.children.size else parent.children.indexOfFirst { it === before }
        parent.children.add(at, child)
        link(parent, child)
        changed()
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
            is KiteXmlNode.Text, is KiteXmlNode.Comment -> leafParents.remove(child)
        }
        changed()
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
            is KiteXmlNode.Text, is KiteXmlNode.Comment -> leafParents[child] = parent
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
        is KiteXmlNode.Comment -> KiteXmlNode.Comment(node.text)
        is KiteXmlNode.Element -> {
            val out = KiteXmlNode.Element(if (node === root) FRAGMENT else node.tag, node.attrs)
            fragments.add(out)
            if (node !== root) names[out] = nameOf(node)
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
     * not parse. Only the first, when not [all]. The selectors match in the whole tree, and `:scope`
     * is [scope], or the document element when [scope] is a document (DOM Standard, 4.2.6).
     */
    fun query(scope: KiteXmlNode.Element, selectors: String, all: Boolean): List<KiteXmlNode.Element>? {
        val parsed = Selector.parseList(selectors) ?: return null
        val scoping = scope.takeUnless { it.tag.startsWith('#') }
        val out = ArrayList<KiteXmlNode.Element>()
        fun walk(el: KiteXmlNode.Element): Boolean {
            for (c in el.children) if (c is KiteXmlNode.Element) {
                if (parsed.any { it.matchesElement(c, selectorTree, scoping) }) {
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

    /** Whether [selectors] match [el], with [el] as `:scope`, or null when the list does not parse. */
    fun matches(el: KiteXmlNode.Element, selectors: String): Boolean? =
        Selector.parseList(selectors)?.any { it.matchesElement(el, selectorTree, el) }

    /**
     * The nearest of [el] and the elements above it that [selectors] match, with [el] as `:scope`, as a
     * list of none or one, or null when the list does not parse (DOM Standard, 4.9).
     */
    fun closest(el: KiteXmlNode.Element, selectors: String): List<KiteXmlNode.Element>? {
        val parsed = Selector.parseList(selectors) ?: return null
        var e: KiteXmlNode.Element? = el
        while (e != null && !e.tag.startsWith('#')) {
            val at = e
            if (parsed.any { it.matchesElement(at, selectorTree, el) }) return listOf(at)
            e = e.parent
        }
        return emptyList()
    }

    /**
     * The tree as a selector reads it: names with the case and namespace their parse gave them, which an
     * HTML chapter compares ignoring case, as Blink does, and white space text as content, as browsers
     * have `:empty`.
     */
    private val selectorTree = object : SelectorTree {
        override fun localName(el: KiteXmlNode.Element): String = nameOf(el).localName
        override fun namespace(el: KiteXmlNode.Element): String? = nameOf(el).namespace
        override fun namesIgnoreCase(el: KiteXmlNode.Element): Boolean = html
        override val htmlDocument: Boolean get() = html
        override val whitespaceIsEmpty: Boolean get() = false

        override fun sameType(a: KiteXmlNode.Element, b: KiteXmlNode.Element): Boolean {
            val x = nameOf(a)
            val y = nameOf(b)
            return x.localName == y.localName && x.namespace == y.namespace
        }

        override fun attribute(el: KiteXmlNode.Element, namespace: String?, local: String, lower: String, test: (String) -> Boolean): Boolean =
            el.attrs[lower]?.let(test) ?: false

        override fun attr(el: KiteXmlNode.Element, name: String): String? = el.attrs[name]
    }

    /** [node] as HTML: its children only, or the element itself too when [outer]. */
    fun html(node: KiteXmlNode, outer: Boolean): String = buildString {
        if (outer || node !is KiteXmlNode.Element) serialize(node, this)
        else for (c in (node as KiteXmlNode.Element).children) serialize(c, this)
    }

    private fun serialize(node: KiteXmlNode, out: StringBuilder) {
        when (node) {
            is KiteXmlNode.Text -> {
                val raw = leafParents[node]?.tag in RAW_TEXT
                out.append(if (raw) node.text else escape(node.text, attribute = false))
            }
            is KiteXmlNode.Comment -> out.append("<!--").append(node.text).append("-->")
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
        for (c in parse(html, el)) append(el, c)
        changed()
    }

    /**
     * Parses [html] and puts what it holds at [position] of [el], as `insertAdjacentHTML` names
     * it. Answers the DOM's error name for a position that does not exist, or null.
     */
    fun insertHtml(el: KiteXmlNode.Element, position: String, html: String): String? {
        val inside = position.lowercase() == "afterbegin" || position.lowercase() == "beforeend"
        val parsed = parse(html, if (inside) el else el.parent)
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
        changed()
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
        for (node in parse(html, parent)) {
            val next = parent.children.getOrNull(parent.children.indexOfFirst { it === at } + 1)
            insert(parent, node, next)
            at = node
        }
        return at
    }

    /** The nodes [html] holds, their elements named as the parser names them under [context]. */
    private fun parse(html: String, context: KiteXmlNode.Element?): List<KiteXmlNode> {
        val parsed = HtmlParser.parse(html, keepComments = true).children.toList()
        for (c in parsed) {
            if (c is KiteXmlNode.Element) {
                c.parent = null
                nameTree(c, context)
            }
            registerTexts(c)
        }
        return parsed
    }

    /** Records the parent of every text node and comment under [node], which the parser does not keep. */
    private fun registerTexts(node: KiteXmlNode) {
        if (node !is KiteXmlNode.Element) return
        for (c in node.children) {
            if (c is KiteXmlNode.Element) registerTexts(c) else leafParents[c] = node
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
     * snapshot; [to] maps the other direction. A snapshot has no comments, and the live tree has
     * those of [commented], [el] as parsed with them, where its children less its comments are
     * [el]'s, one for one, as two parses of one text give them.
     */
    private fun copy(
        el: KiteXmlNode.Element,
        parent: KiteXmlNode.Element?,
        from: HashMap<KiteXmlNode.Element, KiteXmlNode.Element>,
        to: HashMap<KiteXmlNode.Element, KiteXmlNode.Element>,
        link: Boolean,
        commented: KiteXmlNode.Element? = null,
    ): KiteXmlNode.Element {
        val out = KiteXmlNode.Element(el.tag, el.attrs)
        out.parent = parent
        if (link) { from[el] = out; to[out] = el } else { from[out] = el; to[el] = out }
        fun add(child: KiteXmlNode) {
            out.children.add(child)
            if (link && child !is KiteXmlNode.Element) leafParents[child] = out
        }
        val shape = commented?.children?.takeIf { sameExceptComments(it, el.children) }
        if (shape == null) {
            for (c in el.children) when (c) {
                is KiteXmlNode.Element -> add(copy(c, out, from, to, link))
                is KiteXmlNode.Text -> add(KiteXmlNode.Text(c.text))
                is KiteXmlNode.Comment -> if (link) add(KiteXmlNode.Comment(c.text))
            }
        } else {
            var next = 0
            for (c in shape) when (c) {
                is KiteXmlNode.Comment -> add(KiteXmlNode.Comment(c.text))
                is KiteXmlNode.Element -> add(copy(el.children[next++] as KiteXmlNode.Element, out, from, to, link, c))
                is KiteXmlNode.Text -> add(KiteXmlNode.Text((el.children[next++] as KiteXmlNode.Text).text))
            }
        }
        return out
    }

    /** Whether [commented] is [plain] with comments among it: the same elements by tag and texts, in order. */
    private fun sameExceptComments(commented: List<KiteXmlNode>, plain: List<KiteXmlNode>): Boolean {
        var next = 0
        for (c in commented) {
            if (c is KiteXmlNode.Comment) continue
            val p = plain.getOrNull(next++) ?: return false
            val same = when (c) {
                is KiteXmlNode.Element -> p is KiteXmlNode.Element && p.tag == c.tag
                is KiteXmlNode.Text -> p is KiteXmlNode.Text && p.text == c.text
                is KiteXmlNode.Comment -> false
            }
            if (!same) return false
        }
        return next == plain.size
    }

    companion object {
        const val FRAGMENT = "#document-fragment"
        const val DOCUMENT = "#document"

        const val XHTML_NS = "http://www.w3.org/1999/xhtml"
        const val SVG_NS = "http://www.w3.org/2000/svg"
        const val MATHML_NS = "http://www.w3.org/1998/Math/MathML"

        /** The MathML elements whose content is HTML's, its text integration points. */
        private val MATHML_TEXT = setOf("mi", "mo", "mn", "ms", "mtext")

        /** The names of SVG whose case HTML's parser restores, by their lowercased form. */
        private val SVG_TAG_CASE = listOf(
            "altGlyph", "altGlyphDef", "altGlyphItem", "animateColor", "animateMotion", "animateTransform", "clipPath", "feBlend",
            "feColorMatrix", "feComponentTransfer", "feComposite", "feConvolveMatrix", "feDiffuseLighting", "feDisplacementMap",
            "feDistantLight", "feDropShadow", "feFlood", "feFuncA", "feFuncB", "feFuncG", "feFuncR", "feGaussianBlur", "feImage",
            "feMerge", "feMergeNode", "feMorphology", "feOffset", "fePointLight", "feSpecularLighting", "feSpotLight", "feTile",
            "feTurbulence", "foreignObject", "glyphRef", "linearGradient", "radialGradient", "textPath",
        ).associateBy { it.lowercase() }

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
