package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteRectangle

/** What kind of element embeds a web document on a page. */
public enum class EpubEmbedKind {
    /** An `<iframe>`. Its box paints the document it names, when that document is in the book (#528). */
    FRAME,

    /**
     * An `<object>` of an HTML or XHTML type. Its box paints the document it names when that
     * document is in the book (#612), and else the element's fallback children.
     */
    OBJECT,
}

/**
 * An element that embeds a web document on an [EpubPage]: an inline frame, or an object whose
 * type is HTML or XHTML (#40). The page paints the embedded document in the box when it is a
 * document of the book (#528, #612), and else an object's fallback. An app can place a web view
 * of its own over [rect] for a URL outside the book, which the page does not show.
 *
 * Each box stays on one page, as an image does: one that does not fit what is left of a page
 * moves whole to the next (#41). Only an object taller than a page goes on from page to page.
 *
 * @property rect the box in display space, y down, with the smaller y in [KiteRectangle.bottom],
 *   as [EpubLink.rect] is. For an object that goes on to the next page, the part on this page.
 * @property href the zip path of the embedded document, as [EpubDocument.resource] reads it, or
 *   a URL with a scheme. Empty when the element names no document.
 * @property type the media type that the element or the manifest gives, or null.
 * @property id the element's own `id`, or null.
 * @property isWhole false for an object taller than a page, whose [rect] is the part on this
 *   page: a web view placed over it would show the top of the document on every page (#41).
 */
public class EpubEmbed internal constructor(
    public val rect: KiteRectangle,
    public val kind: EpubEmbedKind,
    public val href: String,
    public val type: String?,
    public val id: String?,
    public val isWhole: Boolean = true,
)

/** What a box of an embedding element keeps of the element. */
internal class EmbedInfo(val kind: EpubEmbedKind, val href: String, val type: String?, val id: String?)

/** The media types of the documents an object can embed as a web page. */
internal val EMBED_DOCUMENT_TYPES = setOf("text/html", "application/xhtml+xml")

/** The size of an embedding element that sets none, 300 by 150 CSS pixels as in a browser, in points. */
internal const val EMBED_DEFAULT_WIDTH_PT = 225.0
internal const val EMBED_DEFAULT_HEIGHT_PT = 112.5
