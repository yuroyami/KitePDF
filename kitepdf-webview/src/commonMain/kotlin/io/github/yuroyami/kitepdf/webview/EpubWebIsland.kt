package io.github.yuroyami.kitepdf.webview

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.epub.EpubEmbedKind
import io.github.yuroyami.kitepdf.epub.EpubLayout
import io.github.yuroyami.kitepdf.epub.EpubPage

/** What an [EpubWebIsland] shows. */
public enum class EpubWebIslandKind {
    /** The document of an `<iframe>`, over the box the page keeps empty for it. */
    FRAME,

    /** The document of an `<object>` of an HTML or XHTML type, over the box of its fallback. */
    OBJECT,

    /** A whole scripted fixed-layout page: its own document, over the page. */
    PAGE,
}

/**
 * A region of an EPUB page that a web engine shows, because this library runs no script (#41):
 * the box of an embedded document, or a whole scripted fixed-layout page. The rest of the page
 * stays on the library's own rendering.
 *
 * @property rect the region in the page's display space, y down, with the smaller y in
 *   [KiteRectangle.bottom], as `KitePageOverlayScope.displayRect` takes it.
 * @property href the zip path of the document, as `EpubDocument.resource` reads it, or an
 *   `https` URL when the island was asked for with remote documents allowed.
 * @property kind what the island shows.
 * @property contentWidth the width the document lays out in, in CSS pixels: the box of an
 *   embedded document, or the viewport of a fixed-layout page. The web view scales it to [rect].
 * @property contentHeight the height the document lays out in, in CSS pixels.
 */
public class EpubWebIsland internal constructor(
    public val rect: KiteRectangle,
    public val href: String,
    public val kind: EpubWebIslandKind,
    public val contentWidth: Double,
    public val contentHeight: Double,
) {
    override fun toString(): String = "EpubWebIsland($kind, $href, $rect)"
}

/** CSS pixels in a point: a page lays out 96 CSS pixels to its 72 points of an inch. */
internal const val CSS_PX_PER_PT: Double = 4.0 / 3.0

/**
 * The regions of this page that a web engine shows (#41), in document order.
 *
 * A scripted fixed-layout page is one island over the whole page, which loads the page's own
 * document, and its embedded documents run inside it. On any other page, each inline frame and
 * each HTML object with a document in the book is an island over its box. An object taller than
 * a page goes on from page to page, so it keeps its fallback instead. A document outside the
 * book is an island only with [allowRemote], and only over `https`: an island loads nothing from
 * the network otherwise.
 *
 * This reads the page's layout, so call it where the page's content is loaded, as
 * [EpubPage.isContentLoaded] says, or off the main thread.
 */
public fun EpubPage.webIslands(allowRemote: Boolean = false): List<EpubWebIsland> {
    val book = document
    val rendition = book.renditionOf(chapter)
    if (rendition.layout == EpubLayout.PRE_PAGINATED && book.isScripted(chapter)) {
        return listOf(
            EpubWebIsland(
                KiteRectangle(0.0, 0.0, displayWidth, displayHeight),
                book.chapterPath(chapter),
                EpubWebIslandKind.PAGE,
                displayWidth * CSS_PX_PER_PT,
                displayHeight * CSS_PX_PER_PT,
            ),
        )
    }
    return embeds.mapNotNull { embed ->
        if (!embed.isWhole || embed.href.isEmpty()) return@mapNotNull null
        val href = embed.href
        val inBook = !hasScheme(href)
        if (inBook && book.resource(href) == null) return@mapNotNull null
        if (!inBook && !(allowRemote && href.startsWith("https://", ignoreCase = true))) return@mapNotNull null
        val rect = embed.rect
        EpubWebIsland(
            rect, href,
            if (embed.kind == EpubEmbedKind.FRAME) EpubWebIslandKind.FRAME else EpubWebIslandKind.OBJECT,
            (rect.right - rect.left) * CSS_PX_PER_PT,
            (rect.top - rect.bottom) * CSS_PX_PER_PT,
        )
    }
}

/** True for an href with a scheme, such as `https:` or `javascript:`: not a path in the book. */
internal fun hasScheme(href: String): Boolean = SCHEME.containsMatchIn(href)

private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
