package io.github.yuroyami.kitepdf.core

/**
 * A link on a page: the area that a tap follows, and where the link leads (#433).
 *
 * XPS and SVG pages give their links as [KitePage.hyperlinks]. A PDF link carries an action and
 * an EPUB link carries a kind, so those formats give their links as `PdfPage.annotations` and
 * `EpubPage.links`.
 *
 * @property rect the area, in display space: points from the top-left corner of the page, y
 *   down, with the smaller y in [KiteRectangle.bottom].
 * @property uri the address outside the document, such as `https://example.com`, or null for a
 *   link inside the document.
 * @property target the place inside the document, or null for a link that leads out of it.
 */
public class KiteLink(
    public val rect: KiteRectangle,
    public val uri: String? = null,
    public val target: KiteBookmark? = null,
    targetY: (() -> Double?)? = null,
) {
    /**
     * The height on the target page, in display space, that the link brings to the top of the
     * view, or null for the top of the page. The format finds it on first use, because that can
     * mean reading the target page.
     */
    public val targetY: Double? by lazy { targetY?.invoke() }

    override fun toString(): String = "KiteLink(${uri ?: target}, $rect)"
}
