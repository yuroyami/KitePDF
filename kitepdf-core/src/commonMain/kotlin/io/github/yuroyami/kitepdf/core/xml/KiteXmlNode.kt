package io.github.yuroyami.kitepdf.core.xml

/**
 * A parsed XML (or XHTML, or SVG) node tree. [KiteXml] gives a flat token
 * stream; a tree builder folds it into this so callers can reason about
 * nesting instead of a stack of open tags.
 *
 * Only two node kinds: an [Element] (tag + attributes + ordered children) and a
 * [Text] leaf. Comments, processing instructions and the prologue never reach
 * here, [KiteXml] drops them. Tags and attribute names arrive lowercased with
 * their namespace prefix stripped, so `epub:type` reads as `type`.
 */
public sealed class KiteXmlNode {
    public class Element(
        public val tag: String,
        public val attrs: Map<String, String>,
        public val children: MutableList<KiteXmlNode> = ArrayList(),
    ) : KiteXmlNode() {
        /**
         * Enclosing element, set by the tree builder when the child is
         * appended, and null at the root. CSS selector matching needs it for
         * sibling combinators and the child-indexed pseudo-classes.
         */
        public var parent: Element? = null
    }

    public class Text(public val text: String) : KiteXmlNode()
}
