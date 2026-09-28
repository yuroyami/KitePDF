package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteLink
import io.github.yuroyami.kitepdf.core.KiteLinkKind
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.epub.EpubLink
import io.github.yuroyami.kitepdf.epub.EpubLinkKind

/**
 * A link that the reader tapped, handed to [KiteDocView]'s `onLinkTap` before the viewer acts
 * on it. Every format gives the same facts, so most hosts need no `when`:
 *
 *  - [uri]: the address outside the document, such as `https://example.com`, or null.
 *  - [target]: the place inside the document, or null.
 *  - [kind]: what the link is for, such as a reference to a note.
 *  - [pageIndex] and [rect]: where the link sits, for example to place a popup next to it with
 *    [KiteDocViewState.displayRectToViewport].
 *
 * Each subclass keeps its format's own link for what these leave out. [Pdf] holds the parsed
 * [PdfAction]: a go-to, a URI, a Launch, JavaScript, a form submit. [Epub] holds the [EpubLink],
 * whose href `EpubDocument.linkTarget` reads. [Plain] holds the [KiteLink] of an XPS or SVG page.
 *
 * A document can name any scheme, `file:`, `intent:` and `javascript:` included, so open only
 * the ones you trust:
 *
 * ```kotlin
 * onLinkTap = { link ->
 *     val uri = link.uri
 *     if (uri != null && (uri.startsWith("https://") || uri.startsWith("http://"))) {
 *         openInBrowser(uri)
 *         true
 *     } else {
 *         false // the viewer follows a link inside the document
 *     }
 * }
 * ```
 */
public sealed class KiteLinkAction(
    /** True when the viewer follows this link on its own if the host returns false. */
    internal val followedByViewer: Boolean,
) {

    /** The index of the page that holds the link. */
    public abstract val pageIndex: Int

    /**
     * The link's area on its page, in display space: points from the page's top-left corner,
     * y down, with the smaller y in [KiteRectangle.bottom].
     */
    public abstract val rect: KiteRectangle

    /** The address outside the document that the link leads to, or null for a link inside it. */
    public abstract val uri: String?

    /** The place inside the document that the link leads to, or null for a link that leads out of it. */
    public abstract val target: KiteBookmark?

    /**
     * What the link is for. EPUB books mark their references to notes, glossary entries and
     * bibliography entries. KitePDF reads no such marks from the other formats yet, so their
     * links are [KiteLinkKind.LINK].
     */
    public open val kind: KiteLinkKind get() = KiteLinkKind.LINK

    /**
     * A PDF link, or the action of a form widget that the viewer does not perform itself, with
     * its parsed [action] untouched.
     */
    public class Pdf internal constructor(
        public val action: PdfAction,
        override val pageIndex: Int,
        override val rect: KiteRectangle,
        override val target: KiteBookmark?,
        followedByViewer: Boolean = false,
    ) : KiteLinkAction(followedByViewer) {
        override val uri: String? get() = (action as? PdfAction.Uri)?.uri

        override fun toString(): String = "KiteLinkAction.Pdf($action, page $pageIndex)"
    }

    /** An EPUB link. `EpubDocument.linkTarget` reads the element that its [EpubLink.href] points at. */
    public class Epub internal constructor(
        public val link: EpubLink,
        override val pageIndex: Int,
        override val target: KiteBookmark?,
        followedByViewer: Boolean = false,
    ) : KiteLinkAction(followedByViewer) {
        override val rect: KiteRectangle get() = link.rect
        override val uri: String? get() = link.href.takeIf { hasScheme(it) }
        override val kind: KiteLinkKind get() = when (link.kind) {
            EpubLinkKind.LINK -> KiteLinkKind.LINK
            EpubLinkKind.NOTE_REFERENCE -> KiteLinkKind.NOTE_REFERENCE
            EpubLinkKind.GLOSSARY_REFERENCE -> KiteLinkKind.GLOSSARY_REFERENCE
            EpubLinkKind.BIBLIOGRAPHY_REFERENCE -> KiteLinkKind.BIBLIOGRAPHY_REFERENCE
        }

        override fun toString(): String = "KiteLinkAction.Epub(${link.href}, $kind, page $pageIndex)"
    }

    /** A link of a page that gives plain links, such as an XPS or an SVG page. */
    public class Plain internal constructor(
        public val link: KiteLink,
        override val pageIndex: Int,
        followedByViewer: Boolean = false,
    ) : KiteLinkAction(followedByViewer) {
        override val rect: KiteRectangle get() = link.rect
        override val uri: String? get() = link.uri
        override val target: KiteBookmark? get() = link.target

        override fun toString(): String = "KiteLinkAction.Plain(${uri ?: target}, page $pageIndex)"
    }
}

/** True for an href with a scheme, such as `https:` or `mailto:`: a link out of the book. */
internal fun hasScheme(href: String): Boolean = SCHEME_REGEX.containsMatchIn(href)

private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
