package io.github.yuroyami.kitepdf.epub

/**
 * A minimal (X)HTML node tree. [MiniXml] gives a flat token stream; [HtmlParser]
 * folds it into this tree so the layout walker can reason about nesting (a run's
 * emphasis, a block's children, a list's items) instead of a stack of open tags.
 *
 * Only two node kinds: an [Element] (tag + attributes + ordered children) and a
 * [Text] leaf. Comments, PIs and the prologue never reach here -- MiniXml drops
 * them. Tags are lowercased and namespace-stripped upstream (`localName`).
 */
internal sealed class HtmlNode {
    class Element(
        val tag: String,
        val attrs: Map<String, String>,
        val children: MutableList<HtmlNode> = ArrayList(),
    ) : HtmlNode()

    class Text(val text: String) : HtmlNode()
}
