package io.github.yuroyami.kitepdf.epub.script

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.epub.HtmlParser
import io.github.yuroyami.kitepdf.epub.TARGET_STATE
import io.github.yuroyami.kitepdf.epub.indicatedElement
import io.github.yuroyami.kitepdf.epub.resolveSwitches
import io.github.yuroyami.kitepdf.epub.css.FormStates
import io.github.yuroyami.kitepdf.epub.css.Selector
import io.github.yuroyami.kitepdf.epub.css.SelectorTree
import io.github.yuroyami.kitepdf.epub.css.asciiLower

/**
 * The live tree a chapter's scripts read and change (#41): a copy of the chapter's parse that
 * belongs to the scripts alone, so a layout on another thread never sees it change. Each node
 * has a number, which is how the script side names it, and [snapshot] hands the layout a copy
 * of the tree as it stands.
 *
 * An attribute map is never changed in place, only replaced, so a snapshot may share it.
 *
 * The tree has the comments of [commented], the chapter parsed again with them, which the layout's
 * tree drops, and a snapshot drops them again, so a layout never sees one (#544). [markup] is the
 * chapter's text: [commented] is parsed from it when not given, and the tree takes from it the
 * document type, processing instructions and CDATA sections that the layout's parser skips (#546).
 *
 * Each element's attributes are a list of [Attribute], with the namespace, prefix and local name
 * that the document's parser gives each, from the names [commented] keeps as written, and its map
 * is what the layout reads, keyed as the layout's parser keys it: by the local name of what the
 * markup writes, lowercased (#545).
 */
internal class ScriptDom(
    source: KiteXmlNode.Element,
    private val html: Boolean = false,
    commented: KiteXmlNode.Element? = null,
    markup: String? = null,
) {

    private val ids = HashMap<KiteXmlNode, Int>()
    private val nodes = ArrayList<KiteXmlNode>()

    /** The element each text node and comment sits in: neither keeps a parent of its own. */
    private val leafParents = HashMap<KiteXmlNode, KiteXmlNode.Element>()

    /** Elements made by a script and not yet in the tree, and every fragment, by number. */
    private val fragments = HashSet<KiteXmlNode.Element>()

    /** The documents a script made, each a tree of its own. */
    private val documents = HashSet<KiteXmlNode.Element>()

    /** The text nodes that are CDATA sections, which the DOM numbers 4 and the layout reads as text (#543). */
    private val cdataSections = HashSet<KiteXmlNode.Text>()

    /** The comment nodes that stand for a processing instruction, each with its target, which the layout drops as it drops a comment. */
    private val instructions = HashMap<KiteXmlNode.Comment, String>()

    /** The comment nodes that stand for a document type, which the layout drops as it drops a comment. */
    private val doctypes = HashMap<KiteXmlNode.Comment, XmlReader.Doctype>()

    /** The XML declaration of each document that a script parsed, which serializing the document writes first, as browsers do. */
    private val declarations = HashMap<KiteXmlNode.Element, String>()

    /** The HTML documents in quirks mode, which their document type, or its lack, set when they were parsed (HTML, 13.2.6.4.1). */
    private val quirksDocuments = HashSet<KiteXmlNode.Element>()

    /**
     * The namespace, prefix and local name of an element (DOM Standard, 4.9), which its tag does
     * not keep: the parser lowercases a name and drops its prefix (#541).
     */
    class Name(val namespace: String?, val prefix: String?, val localName: String)

    private val names = HashMap<KiteXmlNode.Element, Name>()

    /**
     * An attribute as the DOM has it (DOM Standard, 4.9.2): its [namespace], [prefix] and [localName],
     * with the case its markup or a script gave them, which the layout's map does not keep (#545).
     */
    class Attribute(val namespace: String?, val prefix: String?, val localName: String, val value: String) {
        val qualifiedName: String get() = if (prefix == null) localName else "$prefix:$localName"
    }

    /** The attributes of each element, in order, each list replaced and never changed. */
    private val attributeLists = HashMap<KiteXmlNode.Element, List<Attribute>>()

    /** The attributes of the elements of a parse as the markup writes them, until [nameTree] names them. */
    private val written = HashMap<KiteXmlNode.Element, Map<String, String>>()

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

    /** Called after a script set or removed an attribute in no namespace, with its local name (#501). */
    var attributeSet: ((KiteXmlNode.Element, String) -> Unit)? = null

    /**
     * What a canvas element shows, as an `<svg>` that a snapshot puts inside it in place of its
     * fallback content, or null for any other element (#501).
     */
    var canvasContent: ((KiteXmlNode.Element) -> KiteXmlNode.Element?)? = null

    init {
        val from = HashMap<KiteXmlNode.Element, KiteXmlNode.Element>()
        val to = HashMap<KiteXmlNode.Element, KiteXmlNode.Element>()
        val parsed = commented ?: markup?.let { HtmlParser.parse(it, keepComments = true, keepNames = true, html = html).also(::resolveSwitches) }
        root = copy(source, null, from, to, link = true, commented = parsed)
        fromLayout = from
        toLayout = to
        if (markup != null) readPrologue(markup)
        for (c in root.children) if (c is KiteXmlNode.Element) nameTree(c, root, emptyMap(), relayout = false)
        written.clear()
    }

    /**
     * Adds to the tree what the layout's parser skips (#546). A browser keeps no white space
     * beside the root element. An HTML chapter gets its document type, and with it its mode. An
     * XHTML chapter that is well-formed gets its XML declaration, document type, processing
     * instructions and CDATA sections, from the XML parser's tree where the two trees agree.
     */
    private fun readPrologue(markup: String) {
        for (c in root.children.filter { it is KiteXmlNode.Text && it.text.isBlank() }) {
            root.children.remove(c)
            leafParents.remove(c)
        }
        if (html) {
            val found = HtmlDoctype.read(markup)
            if (found.quirks) quirksDocuments += root
            found.doctype?.let { insertDoctype(root.children, it, found.commentsBefore) }
            for (c in root.children) if (c !is KiteXmlNode.Element) leafParents[c] = root
            return
        }
        val parsed = XmlReader.document(markup)
        if (parsed.error != null) return
        parsed.declaration?.let { declarations[root] = it }
        val pending = ArrayDeque<Pair<KiteXmlNode.Element, List<KiteXmlNode>>>()
        pending.addLast(root to parsed.nodes)
        while (pending.isNotEmpty()) {
            val (live, xml) = pending.removeLast()
            val liveElements = live.children.filterIsInstance<KiteXmlNode.Element>()
            val xmlElements = xml.filterIsInstance<KiteXmlNode.Element>()
            if (liveElements.size != xmlElements.size) continue
            if (liveElements.indices.any { !liveElements[it].tag.equals(parsed.names[xmlElements[it]]?.localName, ignoreCase = true) }) continue
            mergeLeaves(live, xml, parsed)
            for (k in liveElements.indices) pending.addLast(liveElements[k] to xmlElements[k].children)
        }
    }

    /** Puts a node for [doctype] into [children] after its first [commentsBefore] comments, before any element. */
    private fun insertDoctype(children: MutableList<KiteXmlNode>, doctype: XmlReader.Doctype, commentsBefore: Int) {
        val node = KiteXmlNode.Comment("")
        doctypes[node] = doctype
        var at = 0
        var comments = 0
        while (at < children.size && comments < commentsBefore && children[at] is KiteXmlNode.Comment) {
            at++
            comments++
        }
        children.add(at, node)
    }

    /**
     * Swaps each run of [live]'s texts and comments between two elements for the run of [xml] there,
     * when the two hold the same text and comments: the XML run splits that text at its CDATA
     * sections and processing instructions, and has the document type.
     */
    private fun mergeLeaves(live: KiteXmlNode.Element, xml: List<KiteXmlNode>, parsed: XmlReader.Result) {
        fun runs(nodes: List<KiteXmlNode>): List<List<KiteXmlNode>> {
            val out = arrayListOf(ArrayList<KiteXmlNode>())
            for (n in nodes) if (n is KiteXmlNode.Element) out.add(ArrayList()) else out.last().add(n)
            return out
        }
        fun plainComment(n: KiteXmlNode) = n is KiteXmlNode.Comment && n !in parsed.instructions && n !in parsed.doctypes
        val liveRuns = runs(live.children)
        val xmlRuns = runs(xml)
        val children = ArrayList<KiteXmlNode>()
        var element = 0
        val liveElements = live.children.filterIsInstance<KiteXmlNode.Element>()
        var changed = false
        for (r in liveRuns.indices) {
            val mine = liveRuns[r]
            val theirs = xmlRuns[r]
            val special = theirs.any { it in parsed.cdata || it in parsed.instructions || it in parsed.doctypes }
            val same = special &&
                mine.filterIsInstance<KiteXmlNode.Text>().joinToString("") { it.text } == theirs.filterIsInstance<KiteXmlNode.Text>().joinToString("") { it.text } &&
                mine.filterIsInstance<KiteXmlNode.Comment>().map { it.text } == theirs.filter(::plainComment).map { (it as KiteXmlNode.Comment).text }
            if (same) {
                changed = true
                for (n in mine) leafParents.remove(n)
                for (n in theirs) children += when (n) {
                    is KiteXmlNode.Text -> KiteXmlNode.Text(n.text).also { if (n in parsed.cdata) cdataSections += it }
                    else -> KiteXmlNode.Comment((n as KiteXmlNode.Comment).text).also { c ->
                        parsed.instructions[n]?.let { instructions[c] = it }
                        parsed.doctypes[n]?.let { doctypes[c] = it }
                    }
                }
            } else {
                children += mine
            }
            if (r < liveElements.size) children += liveElements[element++]
        }
        if (!changed) return
        live.children.clear()
        live.children += children
        for (c in children) if (c !is KiteXmlNode.Element) leafParents[c] = live
    }

    /** Whether [doc] is an HTML document in quirks mode, whose `compatMode` is `BackCompat`. */
    fun quirks(doc: KiteXmlNode.Element): Boolean = doc in quirksDocuments

    /** The name of [el]: the one it was made with, or else the one its place in the tree gives it. */
    fun nameOf(el: KiteXmlNode.Element): Name = names.getOrPut(el) { parsedName(el, el.parent) }

    /**
     * Names [el] and the elements under it, and their attributes as [written] has them, as the parser
     * names them, with [parent] above it and the namespace prefixes of [scope] declared. Each element
     * whose attributes are named takes the layout's map of them too when [relayout].
     */
    private fun nameTree(
        el: KiteXmlNode.Element,
        parent: KiteXmlNode.Element?,
        scope: Map<String, String>,
        relayout: Boolean,
        html: Boolean = this.html,
    ) {
        val raw = written.remove(el)
        var inScope = scope
        // An XML parser names an element by the declarations of its own attributes, and HTML's names
        // the attributes of an SVG or a MathML element by the element's namespace.
        if (raw != null && !html) {
            inScope = declared(raw, scope)
            attributeLists[el] = xmlAttributes(raw, inScope)
        }
        val name = parsedName(el, parent, html)
        names[el] = name
        if (raw != null && html) attributeLists[el] = htmlAttributes(raw, name.namespace)
        if (raw != null && relayout) el.attrs = layoutAttributes(attributes(el), el.attrs)
        for (c in el.children) if (c is KiteXmlNode.Element) nameTree(c, el, inScope, relayout, html)
    }

    /** [scope] with the prefixes that the `xmlns:` attributes of [raw] declare, and without those they undeclare. */
    private fun declared(raw: Map<String, String>, scope: Map<String, String>): Map<String, String> {
        if (raw.keys.none { it.startsWith("xmlns:") }) return scope
        val out = HashMap(scope)
        for ((name, value) in raw) if (isDeclaration(name)) {
            if (value.isEmpty()) out.remove(name.substring(6)) else out[name.substring(6)] = value
        }
        return out
    }

    /** The prefixes in scope at [context], as its `xmlns:` attributes and those of the elements above it declare them. */
    private fun scopeAt(context: KiteXmlNode.Element?): Map<String, String> {
        val chain = generateSequence(context) { it.parent }.takeWhile { !it.tag.startsWith('#') }.toList().asReversed()
        val out = HashMap<String, String>()
        for (e in chain) for (a in attributes(e)) if (a.namespace == XMLNS_NS && a.prefix == "xmlns") {
            if (a.value.isEmpty()) out.remove(a.localName) else out[a.localName] = a.value
        }
        return out
    }

    /**
     * The attributes of an element of an XML document whose markup writes [raw], with the prefixes of
     * [scope], as Namespaces in XML names them: its declarations first, in the namespace of `xmlns`,
     * as the parser hands them over apart, then the others in order, a prefixed one in the namespace
     * its prefix is declared for. A name a namespace-aware parser would refuse, of a prefix that is
     * not declared or of two colons, is salvaged as the whole local name of an attribute in no
     * namespace, and of two with one namespace and local name the first is kept.
     */
    private fun xmlAttributes(raw: Map<String, String>, scope: Map<String, String>): List<Attribute> {
        val out = ArrayList<Attribute>(raw.size)
        for ((name, value) in raw) when {
            name == "xmlns" -> out += Attribute(XMLNS_NS, null, name, value)
            isDeclaration(name) -> out += Attribute(XMLNS_NS, "xmlns", name.substring(6), value)
        }
        for ((name, value) in raw) {
            if (name == "xmlns" || isDeclaration(name)) continue
            val colon = name.indexOf(':')
            val prefix = name.takeIf { colon > 0 && colon < name.length - 1 && name.indexOf(':', colon + 1) < 0 }?.substring(0, colon)
            val namespace = if (prefix == "xml") XML_NS else prefix?.let(scope::get)
            val attribute = if (namespace == null) Attribute(null, null, name, value) else Attribute(namespace, prefix, name.substring(colon + 1), value)
            if (out.none { it.namespace == attribute.namespace && it.localName == attribute.localName }) out += attribute
        }
        return out
    }

    /**
     * The attributes of an element in [namespace] of an HTML document whose markup writes [raw], as
     * HTML's parser names them (13.2.5.33 and 13.2.6.3): lowercased, the first of two with one name
     * kept, and on an SVG or a MathML element with the case of SVG's and MathML's own names and the
     * namespaces of the `xlink:`, `xml:` and `xmlns` attributes.
     */
    private fun htmlAttributes(raw: Map<String, String>, namespace: String?): List<Attribute> {
        val out = ArrayList<Attribute>(raw.size)
        val seen = HashSet<String>()
        for ((written, value) in raw) {
            val name = asciiLower(written)
            if (!seen.add(name)) continue
            out += when (namespace) {
                SVG_NS -> foreignAttribute(SVG_ATTRIBUTE_CASE[name] ?: name, value)
                MATHML_NS -> foreignAttribute(if (name == "definitionurl") "definitionURL" else name, value)
                else -> Attribute(null, null, name, value)
            }
        }
        return out
    }

    /** An attribute [name] of an SVG or a MathML element of an HTML document, adjusted as HTML's parser adjusts a foreign attribute. */
    private fun foreignAttribute(name: String, value: String): Attribute = when (name) {
        "xlink:actuate", "xlink:arcrole", "xlink:href", "xlink:role", "xlink:show", "xlink:title", "xlink:type" ->
            Attribute(XLINK_NS, "xlink", name.substring(6), value)
        "xml:lang", "xml:space" -> Attribute(XML_NS, "xml", name.substring(4), value)
        "xmlns" -> Attribute(XMLNS_NS, null, name, value)
        "xmlns:xlink" -> Attribute(XMLNS_NS, "xmlns", "xlink", value)
        else -> Attribute(null, null, name, value)
    }

    /** Whether [name] declares a namespace prefix, as `xmlns:p` does. */
    private fun isDeclaration(name: String): Boolean = name.length > 6 && name.startsWith("xmlns:") && name.indexOf(':', 6) < 0

    /**
     * The attributes of [el], in order. An element no parse or script named them for has those of its
     * layout map, `xmlns` in its namespace and the others in none.
     */
    fun attributes(el: KiteXmlNode.Element): List<Attribute> = attributeLists.getOrPut(el) {
        el.attrs.filterKeys { !it.startsWith(FormStates.STATE) }
            .map { (name, value) -> if (name == "xmlns") Attribute(XMLNS_NS, null, name, value) else Attribute(null, null, name, value) }
    }

    /** The form control state [key] of [el] that a script set, or null while it follows the control's attributes (#552). */
    fun state(el: KiteXmlNode.Element, key: String): String? = el.attrs[FormStates.STATE + key]

    /**
     * Sets the form control state [key] of [el], or forgets it for null. The state sits in the layout's
     * map, where the page and the selectors read it, and stays out of the DOM's attributes (#552).
     */
    fun setState(el: KiteXmlNode.Element, key: String, value: String?, restyle: Boolean = true) {
        if (el.attrs[FormStates.STATE + key] == value) return
        el.attrs = LinkedHashMap(el.attrs).apply { if (value == null) remove(FormStates.STATE + key) else put(FormStates.STATE + key, value) }
        if (restyle) changed()
    }

    /** The element that the fragment of the document's URL indicated when it last changed, which `:target` matches (#550). */
    var target: KiteXmlNode.Element? = null
        private set

    /**
     * Makes the element that [fragment] indicates the target, as scrolling to a fragment does (HTML,
     * 7.4.6.4), or none for null. The layout sees the change only when [restyle], so that a chapter
     * whose style sheets have no `:target` is not laid out again.
     */
    fun retarget(fragment: String?, restyle: Boolean) {
        target?.let { setState(it, TARGET_STATE, null, restyle) }
        target = fragment?.let { f ->
            indicatedElement(root, f, { attr(it, "id") }) { el -> nameOf(el).takeIf { it.localName == "a" && it.namespace == XHTML_NS }?.let { attr(el, "name") } }
        }
        target?.let { setState(it, TARGET_STATE, "", restyle) }
    }

    /** The value of [el]'s attribute [localName] in [namespace], null for none, or null when it has none. */
    fun attr(el: KiteXmlNode.Element, localName: String, namespace: String? = null): String? =
        attributes(el).firstOrNull { it.namespace == namespace && it.localName == localName }?.value

    /** The first attribute of [el] whose qualified name is [qualifiedName], or null (DOM Standard, 4.9). */
    fun attrNamed(el: KiteXmlNode.Element, qualifiedName: String): Attribute? = attributes(el).firstOrNull { it.qualifiedName == qualifiedName }

    /**
     * Sets [el]'s attribute [localName] in [namespace] to [value]: one it has keeps its place, and its
     * prefix unless [replace] puts the attribute of [prefix] in its place, and a new one goes last with
     * [prefix] (DOM Standard, 4.9: set an attribute value, and set an attribute).
     */
    fun setAttr(
        el: KiteXmlNode.Element,
        localName: String,
        value: String,
        namespace: String? = null,
        prefix: String? = null,
        replace: Boolean = false,
    ) {
        val list = attributes(el)
        val at = list.indexOfFirst { it.namespace == namespace && it.localName == localName }
        val out = ArrayList(list)
        if (at >= 0) out[at] = Attribute(namespace, if (replace) prefix else list[at].prefix, localName, value)
        else out += Attribute(namespace, prefix, localName, value)
        setAttributes(el, out)
        if (namespace == null) attributeSet?.invoke(el, localName)
    }

    /** Takes [el]'s attribute [localName] in [namespace] off, if it has one. */
    fun removeAttr(el: KiteXmlNode.Element, localName: String, namespace: String? = null) {
        val list = attributes(el)
        val at = list.indexOfFirst { it.namespace == namespace && it.localName == localName }
        if (at < 0) return
        setAttributes(el, list.filterIndexed { i, _ -> i != at })
        if (namespace == null) attributeSet?.invoke(el, localName)
    }

    private fun setAttributes(el: KiteXmlNode.Element, list: List<Attribute>) {
        attributeLists[el] = list
        el.attrs = layoutAttributes(list, el.attrs)
        changed()
    }

    /**
     * [list] as the layout's parser would key it, by the local name of each qualified name, lowercased,
     * the last of two with one key winning, with the form control states of [old].
     */
    private fun layoutAttributes(list: List<Attribute>, old: Map<String, String>): Map<String, String> =
        LinkedHashMap<String, String>(list.size).apply {
            for (a in list) put(a.qualifiedName.substringAfterLast(':').lowercase(), a.value)
            // A form control's state outlives a change of its attributes (#552).
            for ((k, v) in old) if (k.startsWith(FormStates.STATE)) put(k, v)
        }

    /**
     * The name a parser gives [el] under [parent]. Its namespace is the one an `xmlns` attribute
     * declares, in an XML document; else SVG's for an `svg` element and MathML's for a `math`
     * one; else HTML's under a point where SVG or MathML holds HTML, as HTML's parser has it;
     * else its parent's, and HTML's at the top. A name in SVG takes the case of SVG's own names,
     * as HTML's parser gives it them, since the tag here is lowercased.
     */
    private fun parsedName(el: KiteXmlNode.Element, parent: KiteXmlNode.Element?, html: Boolean = this.html): Name {
        val outer = parent?.takeIf { it !== root && it.tag != FRAGMENT && it !in documents }?.let(::nameOf)
        val declared = if (html) null else attr(el, "xmlns", XMLNS_NS)
        val namespace = when {
            declared != null -> declared.ifEmpty { null }
            el.tag == "svg" -> SVG_NS
            el.tag == "math" -> MATHML_NS
            outer == null || parent == null -> XHTML_NS
            outer.namespace == SVG_NS && (parent.tag == "foreignobject" || (html && (parent.tag == "desc" || parent.tag == "title"))) -> XHTML_NS
            outer.namespace == MATHML_NS && parent.tag in MATHML_TEXT && el.tag != "mglyph" && el.tag != "malignmark" -> XHTML_NS
            outer.namespace == MATHML_NS && parent.tag == "annotation-xml" &&
                attr(parent, "encoding")?.lowercase().let { it == "text/html" || it == "application/xhtml+xml" } -> XHTML_NS
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

    /**
     * 9 for the document, 11 for a fragment, 1 for an element, 3 for text, 4 for a CDATA section, 7 for a
     * processing instruction, 8 for a comment and 10 for a document type, as the DOM numbers them.
     */
    fun kind(node: KiteXmlNode): Int = when {
        node === root || node in documents -> 9
        node is KiteXmlNode.Text -> if (node in cdataSections) 4 else 3
        node is KiteXmlNode.Comment -> if (node in instructions) 7 else if (node in doctypes) 10 else 8
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

    /** An element of the name [localName] in [namespace], with [prefix], whose tag is that name lowercased as the parser's are. */
    fun createElement(localName: String, namespace: String?, prefix: String?): KiteXmlNode.Element =
        KiteXmlNode.Element(localName.lowercase(), emptyMap()).also {
            fragments.add(it)
            names[it] = Name(namespace, prefix, localName)
        }

    /** A document of its own, which no tree holds, as `new Document()` makes one. */
    fun createDocument(): KiteXmlNode.Element = KiteXmlNode.Element(DOCUMENT, emptyMap()).also { documents.add(it) }

    /** A new document type node, which no tree holds yet. */
    fun createDoctype(name: String, publicId: String, systemId: String): KiteXmlNode.Comment =
        KiteXmlNode.Comment("").also { doctypes[it] = XmlReader.Doctype(name, publicId, systemId) }

    /** A new processing instruction of [target] with [data], which no tree holds yet. */
    fun createInstruction(target: String, data: String): KiteXmlNode.Comment = KiteXmlNode.Comment(data).also { instructions[it] = target }

    /** A new CDATA section of [data], which no tree holds yet. */
    fun createCdata(data: String): KiteXmlNode.Text = KiteXmlNode.Text(data).also { cdataSections += it }

    fun createFragment(): KiteXmlNode.Element = KiteXmlNode.Element(FRAGMENT, emptyMap()).also { fragments.add(it) }

    /**
     * Puts [child] into [parent] before [before], or at the end, taking it out of where it was.
     * A fragment gives up its children instead. Answers the DOM's error name for a move that
     * would make no tree, or null.
     */
    fun insert(parent: KiteXmlNode.Element, child: KiteXmlNode, before: KiteXmlNode?): String? {
        hierarchyError(parent, child, before, replacing = null)?.let { return it }
        if (child === before) return null
        place(parent, child, before)
        return null
    }

    /** Puts [child] into [parent] in place of [old], as `replaceChild` does. Answers the DOM's error name, or null. */
    fun replace(parent: KiteXmlNode.Element, child: KiteXmlNode, old: KiteXmlNode): String? {
        hierarchyError(parent, child, old, replacing = old)?.let { return it }
        if (child === old) return null
        place(parent, child, old)
        detach(old)
        return null
    }

    /** The error of `ensure pre-insert validity` that [insert] can run alone, for [child] into [parent] before [before], or null. */
    fun insertError(parent: KiteXmlNode.Element, child: KiteXmlNode, before: KiteXmlNode?): String? =
        hierarchyError(parent, child, before, replacing = null)

    /**
     * The DOM's error for putting [child] into [parent] before [before], or in place of [replacing]:
     * the checks of "ensure pre-insert validity" and "replace a child" (DOM Standard, 4.2.3). A
     * document holds no text, at most one document type and one element, and the document type
     * comes first.
     */
    private fun hierarchyError(parent: KiteXmlNode.Element, child: KiteXmlNode, before: KiteXmlNode?, replacing: KiteXmlNode?): String? {
        val error = "HierarchyRequestError"
        if (child === root || child in documents || (child is KiteXmlNode.Element && isAncestor(child, parent))) return error
        if (before != null && parentOf(before) !== parent) return "NotFoundError"
        val childKind = kind(child)
        if (kind(parent) != 9) return if (childKind == 10) error else null
        if (childKind == 3 || childKind == 4) return error
        val siblings = parent.children
        val at = if (before == null) siblings.size else siblings.indexOfFirst { it === before }
        val element = when (childKind) {
            1 -> true
            11 -> {
                val fragment = child as KiteXmlNode.Element
                val elements = fragment.children.count { it is KiteXmlNode.Element }
                if (elements > 1 || fragment.children.any { it is KiteXmlNode.Text }) return error
                elements == 1
            }
            else -> false
        }
        if (element) {
            if (siblings.any { it is KiteXmlNode.Element && it !== replacing }) return error
            val after = if (replacing != null) at + 1 else at
            if (siblings.subList(after, siblings.size).any { kind(it) == 10 }) return error
        }
        if (childKind == 10) {
            if (siblings.any { it !== replacing && kind(it) == 10 }) return error
            if (siblings.subList(0, at).any { it is KiteXmlNode.Element }) return error
        }
        return null
    }

    /** Puts [child] into [parent] before [before] once the move is known to be valid. */
    private fun place(parent: KiteXmlNode.Element, child: KiteXmlNode, before: KiteXmlNode?) {
        if (child is KiteXmlNode.Element && child.tag == FRAGMENT && child in fragments) {
            for (c in child.children.toList()) place(parent, c, before)
            return
        }
        detach(child)
        val at = if (before == null) parent.children.size else parent.children.indexOfFirst { it === before }
        parent.children.add(at, child)
        link(parent, child)
        changed()
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
        is KiteXmlNode.Text -> KiteXmlNode.Text(node.text).also { if (node in cdataSections) cdataSections += it }
        is KiteXmlNode.Comment -> KiteXmlNode.Comment(node.text).also { copy ->
            instructions[node]?.let { instructions[copy] = it }
            doctypes[node]?.let { doctypes[copy] = it }
        }
        is KiteXmlNode.Element -> {
            // A copy of the target is not the target (#550).
            val out = KiteXmlNode.Element(if (node === root) FRAGMENT else node.tag, node.attrs - (FormStates.STATE + TARGET_STATE))
            fragments.add(out)
            if (node !== root) {
                names[out] = nameOf(node)
                attributeLists[out] = attributes(node)
            }
            if (deep) for (c in node.children) append(out, clone(c, true))
            out
        }
    }

    /** The first element whose `id` is [id], in tree order. */
    fun byId(id: String): KiteXmlNode.Element? = find(root) { attr(it, "id") == id }

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

        /**
         * An HTML document compares a selector's name lowercased with the name of an HTML element's
         * attribute, as HTML has it (4.16.2), and with that of another element's ignoring case, as
         * Blink does; an XML document compares the two as they are.
         */
        override fun attribute(el: KiteXmlNode.Element, namespace: String?, local: String, lower: String, test: (String) -> Boolean): Boolean {
            val ofHtml = html && nameOf(el).namespace == XHTML_NS
            for (a in attributes(el)) {
                if (namespace != null && (a.namespace ?: "") != namespace) continue
                val same = when {
                    !html -> a.localName == local
                    ofHtml -> a.localName == lower
                    else -> asciiLower(a.localName) == lower
                }
                if (same && test(a.value)) return true
            }
            return false
        }

        override fun attr(el: KiteXmlNode.Element, name: String): String? = this@ScriptDom.attr(el, name)
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
            is KiteXmlNode.Comment -> when {
                // HTML 13.3: an instruction ends at its first >, and a document type gives its name alone.
                node in instructions -> out.append("<?").append(instructions.getValue(node)).append(' ').append(node.text).append('>')
                node in doctypes -> out.append("<!DOCTYPE ").append(doctypes.getValue(node).name).append('>')
                else -> out.append("<!--").append(node.text).append("-->")
            }
            is KiteXmlNode.Element -> {
                out.append('<').append(node.tag)
                for (a in attributes(node)) out.append(' ').append(serializedName(a)).append("=\"").append(escape(a.value, attribute = true)).append('"')
                out.append('>')
                if (node.tag in VOID) return
                for (c in node.children) serialize(c, out)
                out.append("</").append(node.tag).append('>')
            }
        }
    }

    /** The name HTML's serializer writes for [a] (13.3): its local name, with the prefix of the namespace HTML knows it in. */
    private fun serializedName(a: Attribute): String = when (a.namespace) {
        null -> a.localName
        XML_NS -> "xml:" + a.localName
        XMLNS_NS -> if (a.localName == "xmlns") "xmlns" else "xmlns:" + a.localName
        XLINK_NS -> "xlink:" + a.localName
        else -> a.qualifiedName
    }

    /** The target of [node] when it is a processing instruction, else null. */
    fun target(node: KiteXmlNode): String? = (node as? KiteXmlNode.Comment)?.let(instructions::get)

    /** The name and ids of [node] when it is a document type, else null. */
    fun doctype(node: KiteXmlNode): XmlReader.Doctype? = (node as? KiteXmlNode.Comment)?.let(doctypes::get)

    /**
     * A new document that holds what [markup] parses to, as `DOMParser` makes one (HTML, 8.5.1): with
     * HTML's parser, or else with the XML parser. A text that is not well-formed XML gives the
     * document that HTML names for it, a `parsererror` element in Mozilla's namespace, and the
     * error comes back beside it.
     */
    fun parseDocument(markup: String, xml: Boolean): Pair<KiteXmlNode.Element, XmlReader.Error?> {
        val doc = createDocument()
        if (!xml) {
            val found = HtmlDoctype.read(markup)
            if (found.quirks) quirksDocuments += doc
            val children = htmlDocument(markup).toMutableList()
            found.doctype?.let { insertDoctype(children, it, found.commentsBefore) }
            for (c in children) append(doc, c)
            return doc to null
        }
        val parsed = XmlReader.document(markup)
        val error = parsed.error
        if (error == null) {
            take(parsed)
            parsed.declaration?.let { declarations[doc] = it }
            for (c in parsed.nodes) append(doc, c)
            return doc to null
        }
        val root = KiteXmlNode.Element("parsererror", emptyMap())
        names[root] = Name(PARSER_ERROR_NS, null, "parsererror")
        attributeLists[root] = emptyList()
        append(root, KiteXmlNode.Text("XML Parsing Error: ${error.message}\nLine Number ${error.line}, Column ${error.column}"))
        append(doc, root)
        return doc to error
    }

    /** Takes over the names, attributes and node kinds of [parsed], and the parent of every text node and comment in it. */
    private fun take(parsed: XmlReader.Result) {
        names.putAll(parsed.names)
        attributeLists.putAll(parsed.attributes)
        cdataSections.addAll(parsed.cdata)
        instructions.putAll(parsed.instructions)
        doctypes.putAll(parsed.doctypes)
        for (c in parsed.nodes) registerTexts(c)
    }

    /** The children of a new HTML document for [markup]: what HTML's parser makes of it, `html`, `head` and `body` included (#547). */
    private fun htmlDocument(markup: String): List<KiteXmlNode> {
        val parsed = HtmlParser.parse(markup, keepComments = true, keepNames = true, html = true).children.toList()
        for (c in parsed) {
            if (c !is KiteXmlNode.Element) continue
            fixParents(c, null)
            recordWritten(c)
            nameTree(c, null, emptyMap(), relayout = true, html = true)
            registerTexts(c)
        }
        return parsed
    }

    /** Sets the parent of [el] and of every element under it, after a move that changed the children lists alone. */
    private fun fixParents(el: KiteXmlNode.Element, parent: KiteXmlNode.Element?) {
        el.parent = parent
        for (c in el.children) if (c is KiteXmlNode.Element) fixParents(c, el)
    }

    /**
     * [node] as XML, as `XMLSerializer` gives it (DOM Parsing and Serialization, 3.2.1), with the
     * namespace declarations each element needs to keep its namespace and prefix. Nothing here
     * refuses a node that cannot round-trip, as its "require well-formed" flag would.
     */
    fun xml(node: KiteXmlNode): String = buildString {
        XmlWriter(this).node(node, null, hashMapOf(XML_NS to mutableListOf("xml")))
    }

    /** The children of [el] as XML, as `innerHTML` gives them in an XML document: each as `XMLSerializer` would, in one run of prefixes (#548). */
    fun xmlChildren(el: KiteXmlNode.Element): String = buildString {
        val writer = XmlWriter(this)
        val prefixes = hashMapOf<String?, MutableList<String>>(XML_NS to mutableListOf("xml"))
        for (c in el.children) writer.node(c, null, prefixes)
    }

    /** The XML serialization algorithm, over this tree. [prefixes] is its namespace prefix map. */
    private inner class XmlWriter(val out: StringBuilder) {
        var nextPrefix = 1

        fun node(node: KiteXmlNode, context: String?, prefixes: MutableMap<String?, MutableList<String>>) {
            when (node) {
                is KiteXmlNode.Text -> if (node in cdataSections) out.append("<![CDATA[").append(node.text).append("]]>") else text(node.text)
                is KiteXmlNode.Comment -> when {
                    node in instructions -> out.append("<?").append(instructions.getValue(node)).append(' ').append(node.text).append("?>")
                    node in doctypes -> doctype(doctypes.getValue(node))
                    else -> out.append("<!--").append(node.text).append("-->")
                }
                is KiteXmlNode.Element ->
                    if (node === root || node.tag == FRAGMENT || node in documents) {
                        declarations[node]?.let(out::append)
                        for (c in node.children) node(c, context, prefixes)
                    }
                    else element(node, context, prefixes)
            }
        }

        private fun doctype(d: XmlReader.Doctype) {
            out.append("<!DOCTYPE ").append(d.name)
            if (d.publicId.isNotEmpty()) out.append(" PUBLIC \"").append(d.publicId).append('"')
            if (d.systemId.isNotEmpty() && d.publicId.isEmpty()) out.append(" SYSTEM")
            if (d.systemId.isNotEmpty()) out.append(" \"").append(d.systemId).append('"')
            out.append('>')
        }

        private fun element(el: KiteXmlNode.Element, context: String?, outer: MutableMap<String?, MutableList<String>>) {
            val name = nameOf(el)
            val ns = name.namespace
            val prefixes = HashMap<String?, MutableList<String>>(outer.size + 2).apply { for ((k, v) in outer) put(k, v.toMutableList()) }
            val local = HashMap<String, String>()
            // Record the namespace information: the declarations of the element's own attributes.
            var localDefault: String? = null
            var declaresDefault = false
            for (a in attributes(el)) if (a.namespace == XMLNS_NS) {
                if (a.prefix == null) {
                    declaresDefault = true
                    localDefault = a.value
                } else if (prefixes[a.value.ifEmpty { null }]?.contains(a.localName) != true) {
                    prefixes.getOrPut(a.value.ifEmpty { null }) { ArrayList() } += a.localName
                    local[a.localName] = a.value
                }
            }
            var inherited = context
            var ignoreDefault = false
            val qualified: String
            val declare = StringBuilder()
            if (inherited == ns) {
                if (declaresDefault) ignoreDefault = true
                qualified = if (ns == XML_NS) "xml:" + name.localName else name.localName
            } else {
                val preferred = preferredPrefix(prefixes, ns, name.prefix)
                when {
                    name.prefix == "xmlns" -> qualified = "xmlns:" + name.localName
                    preferred != null -> {
                        qualified = "$preferred:${name.localName}"
                        if (declaresDefault && localDefault != XML_NS) inherited = localDefault?.ifEmpty { null }
                    }
                    name.prefix != null -> {
                        val p = if (name.prefix in local) generatePrefix(prefixes, local, ns) else name.prefix.also { prefixes.getOrPut(ns) { ArrayList() } += it }
                        qualified = "$p:${name.localName}"
                        declare.append(" xmlns:").append(p).append("=\"").append(attributeValue(ns.orEmpty())).append('"')
                        if (declaresDefault) inherited = localDefault?.ifEmpty { null }
                    }
                    !declaresDefault || localDefault?.ifEmpty { null } != ns -> {
                        ignoreDefault = true
                        qualified = name.localName
                        inherited = ns
                        declare.append(" xmlns=\"").append(attributeValue(ns.orEmpty())).append('"')
                    }
                    else -> {
                        qualified = name.localName
                        inherited = ns
                    }
                }
            }
            out.append('<').append(qualified).append(declare)
            for (a in attributes(el)) {
                var prefix: String? = null
                if (a.namespace != null) {
                    prefix = preferredPrefix(prefixes, a.namespace, a.prefix)
                    if (a.namespace == XMLNS_NS) {
                        if (a.value == XML_NS || (a.prefix == null && ignoreDefault) || (a.prefix != null && local[a.localName] != a.value)) continue
                        if (a.prefix == "xmlns") prefix = "xmlns"
                    } else if (prefix == null) {
                        prefix = generatePrefix(prefixes, local, a.namespace)
                        out.append(" xmlns:").append(prefix).append("=\"").append(attributeValue(a.namespace)).append('"')
                    }
                }
                out.append(' ')
                if (prefix != null) out.append(prefix).append(':')
                out.append(a.localName).append("=\"").append(attributeValue(a.value)).append('"')
            }
            val children = el.children
            if (children.isEmpty() && (ns != XHTML_NS || name.localName in VOID)) {
                out.append(if (ns == XHTML_NS) " />" else "/>")
                return
            }
            out.append('>')
            for (c in children) node(c, inherited, prefixes)
            out.append("</").append(qualified).append('>')
        }

        /** The prefix [prefixes] holds for [ns]: [preferred] when it is one of them, else the last. */
        private fun preferredPrefix(prefixes: Map<String?, List<String>>, ns: String?, preferred: String?): String? {
            val candidates = prefixes[ns] ?: return null
            return candidates.firstOrNull { it == preferred } ?: candidates.lastOrNull()
        }

        private fun generatePrefix(prefixes: MutableMap<String?, MutableList<String>>, local: MutableMap<String, String>, ns: String?): String {
            val prefix = "ns${nextPrefix++}"
            local[prefix] = ns.orEmpty()
            prefixes.getOrPut(ns) { ArrayList() } += prefix
            return prefix
        }

        private fun text(t: String) {
            for (c in t) when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                else -> out.append(c)
            }
        }

        /** An attribute value, its white space other than the space written as references, as browsers do, so that a parse gives it back. */
        private fun attributeValue(v: String): String = buildString(v.length) {
            for (c in v) when (c) {
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\n' -> append("&#10;")
                '\t' -> append("&#9;")
                '\r' -> append("&#13;")
                else -> append(c)
            }
        }
    }

    /**
     * Parses [html] and puts what it holds in place of [el]'s children: as XML when [xml], for an
     * element of an XML document. Answers the DOM's error name for markup that is not well-formed
     * XML, and then changes nothing, or null.
     */
    fun setHtml(el: KiteXmlNode.Element, html: String, xml: Boolean = false): String? {
        val parsed = if (xml) parseXml(html, el).let { (nodes, error) -> error?.let { return it }; nodes } else parse(html, el)
        clearChildren(el)
        for (c in parsed) append(el, c)
        changed()
        return null
    }

    /**
     * Parses [html] and puts what it holds at [position] of [el], as `insertAdjacentHTML` names
     * it, as XML when [xml]. Answers the DOM's error name for a position that does not exist or
     * markup that is not well-formed XML, or null.
     */
    fun insertHtml(el: KiteXmlNode.Element, position: String, html: String, xml: Boolean = false): String? {
        val where = position.lowercase()
        val inside = where == "afterbegin" || where == "beforeend"
        if (!inside && where != "beforebegin" && where != "afterend") return "SyntaxError"
        // HTML, 8.5.4: markup beside an element needs a parent that is an element, not a document.
        val parent = el.parent
        if (!inside && (parent == null || parent === root || parent in documents)) return "NoModificationAllowedError"
        val context = if (inside) el else parent
        val parsed = if (xml) parseXml(html, context).let { (nodes, error) -> error?.let { return it }; nodes } else parse(html, context)
        when (where) {
            "beforebegin" -> for (c in parsed) insert(parent!!, c, el)
            "afterbegin" -> { val first = el.children.firstOrNull(); for (c in parsed) insert(el, c, first) }
            "beforeend" -> for (c in parsed) insert(el, c, null)
            "afterend" -> {
                val p = parent!!
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
        for (node in parse(html, parent, fragment = false)) {
            val next = parent.children.getOrNull(parent.children.indexOfFirst { it === at } + 1)
            insert(parent, node, next)
            at = node
        }
        return at
    }

    /**
     * The nodes [markup] holds as XML content under [context], with the namespaces in scope there,
     * and else the DOM's error name: `SyntaxError` for markup that is not well-formed, and
     * `NamespaceError` for an attribute in a prefix not declared (#548).
     */
    private fun parseXml(markup: String, context: KiteXmlNode.Element?): Pair<List<KiteXmlNode>, String?> {
        val (prefixes, default) = fragmentScope(context)
        val parsed = XmlReader.fragment(markup, prefixes, default)
        parsed.error?.let { return emptyList<KiteXmlNode>() to if (it.namespace) "NamespaceError" else "SyntaxError" }
        take(parsed)
        for (c in parsed.nodes) if (c is KiteXmlNode.Element) c.parent = null
        return parsed.nodes to null
    }

    /**
     * The prefixes and the default namespace that a fragment parsed in [context] starts with, as a
     * browser finds them: from the outermost element down to [context], each element's declarations
     * and then its own name's namespace and prefix (DOM Standard, 4.9, "locate a namespace"). A
     * fragment holds markup as the content of a `body` (HTML, 8.5.5).
     */
    private fun fragmentScope(context: KiteXmlNode.Element?): Pair<Map<String, String>, String?> {
        if (context != null && context.tag == FRAGMENT) return emptyMap<String, String>() to XHTML_NS
        val chain = generateSequence(context) { it.parent }.takeWhile { !it.tag.startsWith('#') }.toList().asReversed()
        val prefixes = HashMap<String, String>()
        var default: String? = null
        for (e in chain) {
            for (a in attributes(e)) if (a.namespace == XMLNS_NS) {
                if (a.prefix == null) default = a.value.ifEmpty { null } else prefixes[a.localName] = a.value
            }
            val name = nameOf(e)
            val ns = name.namespace ?: continue
            if (name.prefix == null) default = ns else prefixes[name.prefix] = ns
        }
        return prefixes to default
    }

    /**
     * The nodes [html] holds, their elements and attributes named as the parser names them under [context].
     * Under an `html` element of an HTML document, the parser starts before the head, so it makes a
     * head and a body (HTML, 13.4), as it does for a document (#547), unless the markup is no [fragment]
     * but more of the document, as `document.write` writes.
     */
    private fun parse(html: String, context: KiteXmlNode.Element?, fragment: Boolean = true): List<KiteXmlNode> {
        val underHtml = fragment && context != null && nameOf(context).let { it.namespace == XHTML_NS && it.localName == "html" }
        val parsed = if (underHtml) {
            val document = HtmlParser.parse("<html>$html", keepComments = true, keepNames = true, html = true)
            // A fragment ignores `</html>`, so a comment after it stays in the html element.
            document.children.flatMap { if (it is KiteXmlNode.Element) it.children else listOf(it) }
        } else {
            HtmlParser.parse(html, keepComments = true, keepNames = true).children.toList()
        }
        val scope = if (this.html) emptyMap() else scopeAt(context)
        for (c in parsed) {
            if (c is KiteXmlNode.Element) {
                c.parent = null
                recordWritten(c)
                nameTree(c, context, scope, relayout = true)
            }
            registerTexts(c)
        }
        return parsed
    }

    /** Records the attributes of [el] and of the elements under it as a parse that keeps their names writes them. */
    private fun recordWritten(el: KiteXmlNode.Element) {
        written[el] = el.attrs
        for (c in el.children) if (c is KiteXmlNode.Element) recordWritten(c)
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
        // A canvas shows its drawing, not its fallback content, where scripts run.
        if (!link) canvasContent?.invoke(el)?.let { svg ->
            out.attrs = out.attrs + (FormStates.STATE + CANVAS_STATE to "")
            svg.parent = out
            out.children.add(svg)
            return out
        }
        if (link && commented != null) written[out] = commented.attrs
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
        /** The state key that marks a canvas whose drawing a snapshot holds (#501). */
        const val CANVAS_STATE = "canvas"

        const val FRAGMENT = "#document-fragment"
        const val DOCUMENT = "#document"

        const val XHTML_NS = "http://www.w3.org/1999/xhtml"
        const val SVG_NS = "http://www.w3.org/2000/svg"
        const val MATHML_NS = "http://www.w3.org/1998/Math/MathML"
        const val XML_NS = "http://www.w3.org/XML/1998/namespace"
        const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"
        const val XLINK_NS = "http://www.w3.org/1999/xlink"

        /** The namespace of the element that stands for an XML parse error (HTML, 8.5.1). */
        const val PARSER_ERROR_NS = "http://www.mozilla.org/newlayout/xml/parsererror.xml"

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

        /** The names of SVG's attributes whose case HTML's parser restores, by their lowercased form (13.2.6.3). */
        private val SVG_ATTRIBUTE_CASE = listOf(
            "attributeName", "attributeType", "baseFrequency", "baseProfile", "calcMode", "clipPathUnits", "diffuseConstant",
            "edgeMode", "filterUnits", "glyphRef", "gradientTransform", "gradientUnits", "kernelMatrix", "kernelUnitLength",
            "keyPoints", "keySplines", "keyTimes", "lengthAdjust", "limitingConeAngle", "markerHeight", "markerUnits",
            "markerWidth", "maskContentUnits", "maskUnits", "numOctaves", "pathLength", "patternContentUnits", "patternTransform",
            "patternUnits", "pointsAtX", "pointsAtY", "pointsAtZ", "preserveAlpha", "preserveAspectRatio", "primitiveUnits", "refX",
            "refY", "repeatCount", "repeatDur", "requiredExtensions", "requiredFeatures", "specularConstant", "specularExponent",
            "spreadMethod", "startOffset", "stdDeviation", "stitchTiles", "surfaceScale", "systemLanguage", "tableValues", "targetX",
            "targetY", "textLength", "viewBox", "viewTarget", "xChannelSelector", "yChannelSelector", "zoomAndPan",
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
