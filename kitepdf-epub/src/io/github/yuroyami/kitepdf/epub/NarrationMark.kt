package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

/**
 * The element of a book that a reading aloud marks with the book's active class, and whether its
 * chapter's root holds the playback-active class (#525). [activeAdded] and [playbackAdded] say
 * which class the mark added, so taking the mark off leaves a class the document had itself.
 */
internal data class NarrationMark(
    val chapter: Int,
    val id: String,
    val playing: Boolean,
    val activeAdded: Boolean = false,
    val playbackAdded: Boolean = false,
)

/** A copy of [root], a layout tree, that the caller can change while the layout reads [root] on another thread. */
internal fun copyLayoutTree(root: KiteXmlNode.Element): KiteXmlNode.Element {
    fun copy(el: KiteXmlNode.Element, parent: KiteXmlNode.Element?): KiteXmlNode.Element {
        val out = KiteXmlNode.Element(el.tag, el.attrs)
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
    return copy(root, null)
}

/** The first element in tree order under [root], [root] included, whose `id` is [id]. */
internal fun elementWithId(root: KiteXmlNode.Element, id: String): KiteXmlNode.Element? {
    if (root.attrs["id"] == id) return root
    for (c in root.children) if (c is KiteXmlNode.Element) elementWithId(c, id)?.let { return it }
    return null
}

/** Adds the class [name] to [el], and answers whether [el] did not have it already. */
internal fun addClass(el: KiteXmlNode.Element, name: String): Boolean {
    val list = el.attrs["class"]
    if (list != null && name in list.split(' ', '\t', '\n', '\r', '\u000C')) return false
    el.attrs = el.attrs + ("class" to if (list.isNullOrBlank()) name else "$list $name")
    return true
}

/** Takes the class [name] off [el]. */
internal fun removeClass(el: KiteXmlNode.Element, name: String) {
    val list = el.attrs["class"] ?: return
    val rest = list.split(' ', '\t', '\n', '\r', '\u000C').filter { it.isNotEmpty() && it != name }
    el.attrs = if (rest.isEmpty()) el.attrs - "class" else el.attrs + ("class" to rest.joinToString(" "))
}
