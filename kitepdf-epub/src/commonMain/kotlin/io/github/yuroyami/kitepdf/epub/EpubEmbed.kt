package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteRectangle

/** What kind of element embeds a web document on a page. */
public enum class EpubEmbedKind {
    /** An `<iframe>`. Its box paints nothing. */
    FRAME,

    /** An `<object>` of an HTML or XHTML type. Its box paints the element's fallback children. */
    OBJECT,
}

/**
 * An element that embeds a web document on an [EpubPage]: an inline frame, or an object whose
 * type is HTML or XHTML (#40). This library runs no script, so the page keeps the box empty, or
 * paints an object's fallback there, and an app can place a web view over [rect].
 *
 * @property rect the box in display space, y down, with the smaller y in [KiteRectangle.bottom],
 *   as [EpubLink.rect] is. For an object that goes on to the next page, the part on this page.
 * @property href the zip path of the embedded document, as [EpubDocument.resource] reads it, or
 *   a URL with a scheme. Empty when the element names no document.
 * @property type the media type that the element or the manifest gives, or null.
 * @property id the element's own `id`, or null.
 */
public class EpubEmbed internal constructor(
    public val rect: KiteRectangle,
    public val kind: EpubEmbedKind,
    public val href: String,
    public val type: String?,
    public val id: String?,
)

/** What a box of an embedding element keeps of the element. */
internal class EmbedInfo(val kind: EpubEmbedKind, val href: String, val type: String?, val id: String?)

/** The media types of the documents an object can embed as a web page. */
internal val EMBED_DOCUMENT_TYPES = setOf("text/html", "application/xhtml+xml")

/** The size of an embedding element that sets none, 300 by 150 CSS pixels as in a browser, in points. */
internal const val EMBED_DEFAULT_WIDTH_PT = 225.0
internal const val EMBED_DEFAULT_HEIGHT_PT = 112.5
