package io.github.yuroyami.kitepdf

/**
 * Initial UI hint that tells a viewer which side panel to show when the
 * document opens (ISO 32000-1 §7.7.2 Table 28, `/PageMode`).
 *
 * The "Use*" naming preserves the spec's PDF-name values verbatim.
 */
enum class PageMode {
    /** No panel — page area only (default). */
    UseNone,

    /** Show the bookmarks (outlines) panel. */
    UseOutlines,

    /** Show the thumbnails panel. */
    UseThumbs,

    /** Full-screen mode, no menu bar / window controls / panel. */
    FullScreen,

    /** Show the optional-content (layers) panel — PDF 1.5. */
    UseOC,

    /** Show the attachments panel — PDF 1.6. */
    UseAttachments,

    /** A /PageMode value we don't recognise. */
    Other;

    companion object {
        fun fromName(name: String?): PageMode = when (name) {
            "UseNone", null -> UseNone
            "UseOutlines" -> UseOutlines
            "UseThumbs" -> UseThumbs
            "FullScreen" -> FullScreen
            "UseOC" -> UseOC
            "UseAttachments" -> UseAttachments
            else -> Other
        }
    }
}

/**
 * Initial page-layout hint that tells the viewer how to lay pages out in
 * the document area (ISO 32000-1 §7.7.2 Table 28, `/PageLayout`).
 */
enum class PageLayout {
    /** One page at a time (default). */
    SinglePage,

    /** Pages in a single scrollable column. */
    OneColumn,

    /** Two columns, odd-numbered pages on the left. */
    TwoColumnLeft,

    /** Two columns, odd-numbered pages on the right (RTL languages). */
    TwoColumnRight,

    /** Two pages side-by-side, odd on the left — PDF 1.5. */
    TwoPageLeft,

    /** Two pages side-by-side, odd on the right — PDF 1.5. */
    TwoPageRight,

    /** A /PageLayout value we don't recognise. */
    Other;

    companion object {
        fun fromName(name: String?): PageLayout = when (name) {
            "SinglePage", null -> SinglePage
            "OneColumn" -> OneColumn
            "TwoColumnLeft" -> TwoColumnLeft
            "TwoColumnRight" -> TwoColumnRight
            "TwoPageLeft" -> TwoPageLeft
            "TwoPageRight" -> TwoPageRight
            else -> Other
        }
    }
}
