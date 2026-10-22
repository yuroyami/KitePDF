package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.epub.css.FormStates
import io.github.yuroyami.kitepdf.epub.script.WhatwgUrl

/** The state key that marks the element `:target` matches, in an element's layout attributes (#550). */
internal const val TARGET_STATE = "target"

/**
 * [fragment] as the URL parser keeps it, without its `#`: percent-encoded where the fragment
 * percent-encode set says, as `location.hash` gives it (#550).
 */
internal fun normalizedFragment(fragment: String): String = WhatwgUrl.parse("epub://book/#$fragment")?.fragment ?: fragment

/**
 * The element that [fragment] indicates in the tree under [root], or null for none and for the top
 * of the document (HTML, 7.4.6.4, the indicated part, #550). That is the first element in tree order
 * whose ID is the fragment, else the first HTML `a` whose name is. The fragment is tried as written,
 * then percent-decoded as UTF-8.
 */
internal fun indicatedElement(
    root: KiteXmlNode.Element,
    fragment: String,
    id: (KiteXmlNode.Element) -> String?,
    anchorName: (KiteXmlNode.Element) -> String?,
): KiteXmlNode.Element? {
    if (fragment.isEmpty()) return null
    potentialIndicated(root, fragment, id, anchorName)?.let { return it }
    val decoded = WhatwgUrl.percentDecode(fragment).decodeToString()
    return potentialIndicated(root, decoded, id, anchorName)
}

private fun potentialIndicated(
    root: KiteXmlNode.Element,
    fragment: String,
    id: (KiteXmlNode.Element) -> String?,
    anchorName: (KiteXmlNode.Element) -> String?,
): KiteXmlNode.Element? = first(root) { id(it) == fragment } ?: first(root) { anchorName(it) == fragment }

private fun first(el: KiteXmlNode.Element, test: (KiteXmlNode.Element) -> Boolean): KiteXmlNode.Element? {
    for (c in el.children) if (c is KiteXmlNode.Element) {
        if (test(c)) return c
        first(c, test)?.let { return it }
    }
    return null
}

/**
 * A copy of [root], a layout tree, whose target is the element [fragment] indicates, and no other
 * (#550). The layout may be reading [root] on another thread, so it stays as it is.
 */
internal fun withTarget(root: KiteXmlNode.Element, fragment: String): KiteXmlNode.Element {
    val key = FormStates.STATE + TARGET_STATE
    fun copy(el: KiteXmlNode.Element, parent: KiteXmlNode.Element?): KiteXmlNode.Element {
        val out = KiteXmlNode.Element(el.tag, if (key in el.attrs) el.attrs - key else el.attrs)
        out.parent = parent
        for (c in el.children) out.children.add(
            when (c) {
                is KiteXmlNode.Element -> copy(c, out)
                is KiteXmlNode.Text -> KiteXmlNode.Text(c.text)
                is KiteXmlNode.Comment -> KiteXmlNode.Comment(c.text)
            },
        )
        return out
    }
    val out = copy(root, null)
    // The layout's tags have no prefix, so an `a` inside an `svg` is SVG's, which names no anchor.
    fun inSvg(el: KiteXmlNode.Element): Boolean = generateSequence(el.parent) { it.parent }.any { it.tag == "svg" }
    indicatedElement(out, fragment, { it.attrs["id"] }, { if (it.tag == "a" && !inSvg(it)) it.attrs["name"] else null })
        ?.let { it.attrs = it.attrs + (key to "") }
    return out
}
