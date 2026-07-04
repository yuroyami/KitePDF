package io.github.yuroyami.kitepdf.epub

/** One table-of-contents node. [spineIndex] is -1 when the target isn't in the spine. */
class TocEntry internal constructor(
    val label: String,
    /** Zip-absolute path of the target document, or null (a pure grouping label). */
    val href: String?,
    val spineIndex: Int,
    val fragment: String?,
    val children: List<TocEntry>,
)

/** A publication's navigation tree, from EPUB 3 `nav.xhtml` or EPUB 2 `toc.ncx`. */
class TableOfContents internal constructor(val entries: List<TocEntry>) {
    val isEmpty: Boolean get() = entries.isEmpty()

    /** Depth-first flatten, useful for a flat outline list. */
    fun flatten(): List<TocEntry> {
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

    private fun parseOl(ol: HtmlNode.Element, dir: String, index: Map<String, Int>, resolve: (String, String) -> String): List<TocEntry> =
        ol.children.filterIsInstance<HtmlNode.Element>().filter { it.tag == "li" }.map { li ->
            val anchor = firstDescendantTag(li, "a") ?: firstDescendantTag(li, "span")
            val label = textOf(anchor ?: li).trim()
            val (spine, frag, path) = target(anchor?.attrs?.get("href"), dir, index, resolve)
            val childOl = li.children.filterIsInstance<HtmlNode.Element>().firstOrNull { it.tag == "ol" }
            TocEntry(label, path, spine, frag, childOl?.let { parseOl(it, dir, index, resolve) } ?: emptyList())
        }

    private fun findNavToc(root: HtmlNode.Element): HtmlNode.Element? {
        var firstNav: HtmlNode.Element? = null
        fun rec(e: HtmlNode.Element): HtmlNode.Element? {
            if (e.tag == "nav") {
                if (firstNav == null) firstNav = e
                if (e.attrs["type"] == "toc") return e
            }
            for (c in e.children) if (c is HtmlNode.Element) rec(c)?.let { return it }
            return null
        }
        return rec(root) ?: firstNav
    }

    // ---- EPUB 2 toc.ncx ------------------------------------------------------

    private fun parseNcx(xml: String, dir: String, index: Map<String, Int>, resolve: (String, String) -> String): TableOfContents {
        val root = HtmlParser.parse(xml)
        val navMap = firstDescendantTag(root, "navmap") ?: return TableOfContents(emptyList())
        return TableOfContents(navMap.children.filterIsInstance<HtmlNode.Element>().filter { it.tag == "navpoint" }.map { parseNavPoint(it, dir, index, resolve) })
    }

    private fun parseNavPoint(np: HtmlNode.Element, dir: String, index: Map<String, Int>, resolve: (String, String) -> String): TocEntry {
        val label = firstDescendantTag(np, "navlabel")?.let { textOf(it).trim() } ?: ""
        val src = np.children.filterIsInstance<HtmlNode.Element>().firstOrNull { it.tag == "content" }?.attrs?.get("src")
        val (spine, frag, path) = target(src, dir, index, resolve)
        val children = np.children.filterIsInstance<HtmlNode.Element>().filter { it.tag == "navpoint" }.map { parseNavPoint(it, dir, index, resolve) }
        return TocEntry(label, path, spine, frag, children)
    }

    // ---- shared --------------------------------------------------------------

    private data class Target(val spineIndex: Int, val fragment: String?, val path: String?)

    private fun target(href: String?, dir: String, index: Map<String, Int>, resolve: (String, String) -> String): Target {
        if (href.isNullOrBlank()) return Target(-1, null, null)
        val fragment = href.substringAfter('#', "").ifEmpty { null }
        val path = resolve(dir, href)
        return Target(index[path] ?: -1, fragment, path)
    }

    private fun firstDescendantTag(root: HtmlNode.Element, tag: String): HtmlNode.Element? {
        for (c in root.children) if (c is HtmlNode.Element) {
            if (c.tag == tag) return c
            firstDescendantTag(c, tag)?.let { return it }
        }
        return null
    }

    private fun textOf(el: HtmlNode.Element): String {
        val sb = StringBuilder()
        fun rec(n: HtmlNode) { when (n) { is HtmlNode.Text -> sb.append(n.text); is HtmlNode.Element -> n.children.forEach(::rec) } }
        rec(el)
        return sb.toString()
    }
}
