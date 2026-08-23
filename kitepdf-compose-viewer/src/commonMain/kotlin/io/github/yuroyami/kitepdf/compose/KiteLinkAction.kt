package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.PdfAction

/**
 * A tapped link the viewer cannot follow on its own, handed to
 * [KiteDocView]'s `onLinkTap`.
 *
 * In-document jumps never arrive here: the viewer scrolls to the target page
 * itself. What reaches you is everything else, and it differs by format, so
 * this type carries the format-native payload instead of flattening it:
 *
 *  - EPUB links are plain hrefs, so they arrive as [Uri].
 *  - PDF links carry a whole `/A` action dictionary (a URI, a remote GoTo, a
 *    Launch, JavaScript, a form submit), so they arrive as [Pdf] with the
 *    parsed [PdfAction] untouched.
 *
 * Opening web links needs no `when`, because both cases answer [uri]:
 *
 * ```kotlin
 * onLinkTap = { link -> link.uri?.let { openInBrowser(it); true } ?: false }
 * ```
 */
public sealed class KiteLinkAction {

    /** The external URL this link points at, or null when it is not a URL. */
    public open val uri: String? get() = null

    /** A plain external URL. EPUB hrefs with a scheme arrive as this. */
    public data class Uri(override val uri: String) : KiteLinkAction()

    /** A parsed PDF action the viewer does not perform itself. */
    public data class Pdf(val action: PdfAction) : KiteLinkAction() {
        override val uri: String? get() = (action as? PdfAction.Uri)?.uri
    }
}
