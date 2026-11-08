package io.github.yuroyami.kitepdf.core.xml

/**
 * A parsed XML (or XHTML, or SVG) node tree. [KiteXml] gives a flat token
 * stream; a tree builder folds it into this so callers can reason about
 * nesting instead of a stack of open tags.
 *
 * An [Element] (tag + attributes + ordered children), a [Text] leaf, and a
 * [Comment], which only a parse that asks for comments makes, as a script
 * layer does. Processing instructions and the prologue never reach here,
 * [KiteXml] drops them. Tags and attribute names arrive lowercased with
 * their namespace prefix stripped, so `epub:type` reads as `type`.
 */
public sealed class KiteXmlNode {
    public class Element(
        public val tag: String,
        /**
         * The attributes, by name. A script layer replaces the map when a script sets or removes
         * one, on a tree of its own that no layout reads meanwhile (#41).
         */
        public var attrs: Map<String, String>,
        public val children: MutableList<KiteXmlNode> = ArrayList(),
    ) : KiteXmlNode() {
        /**
         * Enclosing element, set by the tree builder when the child is
         * appended, and null at the root. CSS selector matching needs it for
         * sibling combinators and the child-indexed pseudo-classes.
         */
        public var parent: Element? = null

        /** Where [indexInParent] last found this element: a hint that each use checks, never trusted. */
        private var siblingHint = -1

        /**
         * This element's index in its [parent]'s children, or -1 when it has none or is not among them.
         * The answer is remembered and checked on the next call, so a selector's sibling step costs
         * O(1) where a scan of the parent from its first child cost the element's position, and
         * `h1 ~ p` over n siblings cost n^3/6 (#990). A tree that changed since is scanned again.
         */
        public fun indexInParent(): Int {
            val siblings = parent?.children ?: return -1
            val hint = siblingHint
            if (hint in siblings.indices && siblings[hint] === this) return hint
            val found = siblings.indexOfFirst { it === this }
            siblingHint = found
            return found
        }
    }

    /** A text leaf. A script layer changes [text] on a tree of its own, as [Element.attrs] (#41). */
    public class Text(public var text: String) : KiteXmlNode()

    /**
     * A comment and its data, which a parse keeps only when asked, so a layout never sees one. A
     * script layer changes [text] on a tree of its own, as [Text.text] (#544).
     */
    public class Comment(public var text: String) : KiteXmlNode()
}
