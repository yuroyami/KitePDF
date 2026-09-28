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

/**
 * What a link is for. A viewer can open a reference in place, such as a note in a popup,
 * instead of moving the reader away from the page.
 *
 * EPUB books mark their references in their markup. KitePDF reads no such marks from the
 * other formats yet, so their links are [LINK].
 */
public enum class KiteLinkKind {
    /** An ordinary link, or one whose document says nothing more. */
    LINK,

    /** A reference to a note, such as a footnote or an endnote. */
    NOTE_REFERENCE,

    /** A reference to a glossary entry. */
    GLOSSARY_REFERENCE,

    /** A reference to a bibliography entry. */
    BIBLIOGRAPHY_REFERENCE,
}
