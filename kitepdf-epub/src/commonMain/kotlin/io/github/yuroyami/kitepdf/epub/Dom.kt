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
    ) : HtmlNode() {
        /**
         * Enclosing element, set by [HtmlParser] when the child is appended;
         * `null` only for the synthetic `#root`. Selector matching needs it for
         * sibling combinators and the child-indexed pseudo-classes.
         */
        var parent: Element? = null
    }

    class Text(val text: String) : HtmlNode()
}

/**
 * Parent for selector ANCESTOR walks: the synthetic `#root` wrapper is not a
 * real element, so combinators must not match against it (a top-level element
 * has no ancestor). Sibling/index queries, by contrast, DO use the raw
 * [HtmlNode.Element.parent] so the document element is its parent's
 * `:first-child`, matching browser behaviour.
 */
internal fun HtmlNode.Element.elementParent(): HtmlNode.Element? =
    parent?.takeIf { it.tag != "#root" }

/** Nearest preceding sibling that is an element, or null. */
internal fun HtmlNode.Element.previousElementSibling(): HtmlNode.Element? {
    val siblings = parent?.children ?: return null
    var prev: HtmlNode.Element? = null
    for (c in siblings) {
        if (c === this) return prev
        if (c is HtmlNode.Element) prev = c
    }
    return null
}
