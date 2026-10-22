package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

import io.github.yuroyami.kitepdf.core.zip.ZipReader

/** One table-of-contents node. [spineIndex] is -1 when the target isn't in the spine. */
public class TocEntry internal constructor(
    public val label: String,
    /** Zip-absolute path of the target document, or null (a pure grouping label). */
    public val href: String?,
    public val spineIndex: Int,
    public val fragment: String?,
    public val children: List<TocEntry>,
)

/** A publication's navigation tree, from EPUB 3 `nav.xhtml` or EPUB 2 `toc.ncx`. */
public class TableOfContents internal constructor(public val entries: List<TocEntry>) {
    public val isEmpty: Boolean get() = entries.isEmpty()

    /** Depth-first flatten, useful for a flat outline list. */
    public fun flatten(): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        fun rec(e: TocEntry) { out.add(e); e.children.forEach(::rec) }
        entries.forEach(::rec)
        return out
    }
}

/**
 * Builds a [TableOfContents] from EPUB 3 `nav.xhtml` (the manifest item with the
 * `nav` property, its `<nav epub:type="toc">` list) or, failing that, EPUB 2
 * `toc.ncx` (`navMap`/`navPoint`). Both are parsed with the same [HtmlParser]
 * tree. Each entry's href is resolved to a spine index + fragment.
 */
internal object TocParser {

    fun parse(zip: ZipReader, opf: OpfPackage, spinePaths: List<String>, resolve: (String, String) -> String): TableOfContents {
        val index = HashMap<String, Int>()
        spinePaths.forEachIndexed { i, p -> if (p !in index) index[p] = i }

        opf.items.firstOrNull { it.hasProperty("nav") }?.let { nav ->
            val navPath = resolve(opf.baseDir, nav.href)
            zip.readText(navPath)?.let { xml ->
                parseNav(xml, navPath.substringBeforeLast('/', ""), index, resolve)?.let { return it }
            }
        }
        val ncx = opf.tocNcxId?.let { opf.itemsById[it] } ?: opf.items.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        ncx?.let {
            val ncxPath = resolve(opf.baseDir, it.href)
            zip.readText(ncxPath)?.let { xml -> return parseNcx(xml, ncxPath.substringBeforeLast('/', ""), index, resolve) }
        }
        return TableOfContents(emptyList())
    }

    // ---- EPUB 3 nav.xhtml ----------------------------------------------------

    private fun parseNav(xml: String, dir: String, index: Map<String, Int>, resolve: (String, String) -> String): TableOfContents? {
        val root = HtmlParser.parse(xml)
        val nav = findNavToc(root) ?: return null
        val ol = firstDescendantTag(nav, "ol") ?: return TableOfContents(emptyList())
        return TableOfContents(parseOl(ol, dir, index, resolve))
    }

    private fun parseOl(ol: KiteXmlNode.Element, dir: String, index: Map<String, Int>, resolve: (String, String) -> String): List<TocEntry> =
        ol.children.filterIsInstance<KiteXmlNode.Element>().filter { it.tag == "li" }.map { li ->
            val anchor = labelOf(li)
            val label = navLabel(anchor ?: li)
            val (spine, frag, path) = target(anchor?.attrs?.get("href"), dir, index, resolve)
            val childOl = li.children.filterIsInstance<KiteXmlNode.Element>().firstOrNull { it.tag == "ol" }
            TocEntry(label, path, spine, frag, childOl?.let { parseOl(it, dir, index, resolve) } ?: emptyList())
        }

    /**
     * The link or heading of an entry: its first `a` or `span`, looked for outside the list of its
     * children, so that a heading over a list does not take the label and link of its first child.
     */
    private fun labelOf(li: KiteXmlNode.Element): KiteXmlNode.Element? {
        for (c in li.children) if (c is KiteXmlNode.Element && c.tag != "ol") {
            if (c.tag == "a" || c.tag == "span") return c
            labelOf(c)?.let { return it }
        }
        return null
    }

    private fun findNavToc(root: KiteXmlNode.Element): KiteXmlNode.Element? {
        var firstNav: KiteXmlNode.Element? = null
        fun rec(e: KiteXmlNode.Element): KiteXmlNode.Element? {
            if (e.tag == "nav") {
                if (firstNav == null) firstNav = e
                if (e.attrs["type"] == "toc") return e
            }
            for (c in e.children) if (c is KiteXmlNode.Element) rec(c)?.let { return it }
            return null
        }
        return rec(root) ?: firstNav
    }

    // ---- EPUB 2 toc.ncx ------------------------------------------------------

    private fun parseNcx(xml: String, dir: String, index: Map<String, Int>, resolve: (String, String) -> String): TableOfContents {
        val root = HtmlParser.parse(xml)
        val navMap = firstDescendantTag(root, "navmap") ?: return TableOfContents(emptyList())
        return TableOfContents(navMap.children.filterIsInstance<KiteXmlNode.Element>().filter { it.tag == "navpoint" }.map { parseNavPoint(it, dir, index, resolve) })
    }

    private fun parseNavPoint(np: KiteXmlNode.Element, dir: String, index: Map<String, Int>, resolve: (String, String) -> String): TocEntry {
        val label = firstDescendantTag(np, "navlabel")?.let { navLabel(it) } ?: ""
        val src = np.children.filterIsInstance<KiteXmlNode.Element>().firstOrNull { it.tag == "content" }?.attrs?.get("src")
        val (spine, frag, path) = target(src, dir, index, resolve)
        val children = np.children.filterIsInstance<KiteXmlNode.Element>().filter { it.tag == "navpoint" }.map { parseNavPoint(it, dir, index, resolve) }
        return TocEntry(label, path, spine, frag, children)
    }

    // ---- shared --------------------------------------------------------------

    /**
     * The text label of a link or heading of the navigation: its text, with each element of non-text
     * content in it replaced by its text alternative, the `alt` before the `title` (EPUB Reading
     * Systems 3.3, 7), and its white space collapsed. A label with no text falls back on the `title`
     * of the link itself (#526).
     */
    private fun navLabel(el: KiteXmlNode.Element): String {
        val out = StringBuilder()
        fun walk(e: KiteXmlNode.Element) {
            for (c in e.children) when (c) {
                is KiteXmlNode.Text -> out.append(c.text)
                is KiteXmlNode.Comment -> {}
                is KiteXmlNode.Element -> when (c.tag) {
                    in NON_TEXT -> out.append(alternativeOf(c))
                    "ol" -> Unit // the entries under it have labels of their own
                    else -> walk(c)
                }
            }
        }
        walk(el)
        return metadataValue(out).ifEmpty { metadataValue(el.attrs["title"].orEmpty()) }
    }

    /** The text alternative of an element of non-text content, or an empty one. */
    private fun alternativeOf(el: KiteXmlNode.Element): String {
        fun given(name: String) = el.attrs[name]?.takeIf { metadataValue(it).isNotEmpty() }
        return given("alt") ?: given("alttext")
            ?: el.children.firstOrNull { it is KiteXmlNode.Element && it.tag == "title" }?.let { (it as KiteXmlNode.Element).textContent() }
            ?: given("aria-label") ?: given("title") ?: ""
    }

    /** Embedded content: images, an SVG or MathML island, media and frames. */
    private val NON_TEXT = setOf("img", "svg", "math", "picture", "video", "audio", "canvas", "object", "embed", "iframe")

    private data class Target(val spineIndex: Int, val fragment: String?, val path: String?)

    private fun target(href: String?, dir: String, index: Map<String, Int>, resolve: (String, String) -> String): Target {
        if (href.isNullOrBlank()) return Target(-1, null, null)
        val fragment = href.substringAfter('#', "").ifEmpty { null }
        val path = resolve(dir, href)
        return Target(index[path] ?: -1, fragment, path)
    }

    private fun firstDescendantTag(root: KiteXmlNode.Element, tag: String): KiteXmlNode.Element? {
        for (c in root.children) if (c is KiteXmlNode.Element) {
            if (c.tag == tag) return c
            firstDescendantTag(c, tag)?.let { return it }
        }
        return null
    }
}
