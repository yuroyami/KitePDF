package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteLink
import io.github.yuroyami.kitepdf.core.KiteRectangle

/**
 * What one walk of a page finds for its links (#433): each element with a
 * `FixedPage.NavigateUri`, with the box of what it draws, and the box of each element with a
 * `Name`, both in display space.
 */
internal class XpsLinkPass {
    val links = ArrayList<Pair<String, KiteRectangle>>()
    val names = HashMap<String, KiteRectangle>()
}

/**
 * Where the links of an XPS package lead (#433). A link with a scheme leaves the document. A
 * relative link names a page part, a fixed document, or an element by its `Name`, which the
 * `LinkTarget` entries of each page reference list with the page it is on.
 */
internal class XpsLinkIndex {
    private val pageOfPart = HashMap<String, Int>()
    private val documentOfPage = HashMap<Int, String>()
    private val firstPageOfDocument = HashMap<String, Int>()
    private val targetsOfDocument = HashMap<String, HashMap<String, Int>>()

    /** The pages of the package, which a link reads the named element of. */
    var pages: List<XpsPage> = emptyList()

    fun addPage(document: String, part: String, index: Int, names: List<String>) {
        val doc = document.lowercase()
        pageOfPart.getOrPut(part.lowercase()) { index }
        documentOfPage[index] = doc
        firstPageOfDocument.getOrPut(doc) { index }
        val targets = targetsOfDocument.getOrPut(doc) { HashMap() }
        for (name in names) targets.getOrPut(name) { index }
    }

    /** The link that [uri], written on the page [fromPart], makes over [rect], or null when it leads nowhere. */
    fun link(fromPart: String, uri: String, rect: KiteRectangle): KiteLink? {
        val raw = uri.trim()
        if (raw.isEmpty()) return null
        if (SCHEME.containsMatchIn(raw)) return KiteLink(rect, uri = raw)
        val name = raw.substringAfter('#', "").takeIf { it.isNotEmpty() }
        val path = raw.substringBefore('#')
        val page: Int = if (path.isEmpty()) {
            // A bare name is looked for in the document of the page first.
            val from = pageOfPart[fromPart.lowercase()]?.let(documentOfPage::get)
            name ?: return null
            from?.let { targetsOfDocument[it]?.get(name) } ?: anyTarget(name) ?: return null
        } else {
            val part = resolvePart(fromPart, path)?.lowercase() ?: return null
            pageOfPart[part]
                ?: name?.let { targetsOfDocument[part]?.get(it) ?: anyTarget(it) }
                ?: firstPageOfDocument[part]
                ?: return null
        }
        return KiteLink(
            rect,
            target = KiteBookmark.Page(page),
            targetY = name?.let { { pages.getOrNull(page)?.namedBox(it)?.bottom } },
        )
    }

    private fun anyTarget(name: String): Int? = targetsOfDocument.values.firstNotNullOfOrNull { it[name] }

    private companion object {
        val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    }
}
