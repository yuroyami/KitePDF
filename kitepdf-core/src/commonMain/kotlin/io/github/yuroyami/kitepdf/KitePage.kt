package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.PdfCanvas

/**
 * A renderable page from any document handler — the format-neutral
 * `fz_page` equivalent. Both [io.github.yuroyami.kitepdf.PdfPage] and
 * [io.github.yuroyami.kitepdf.epub.EpubPage] implement it, so one viewer /
 * rasterizer path serves every format.
 *
 * All coordinates are in points (`1pt = 1/72in`). [displayToDeviceBase] hides
 * the per-format origin convention (PDF is y-up from bottom-left with page
 * rotation; EPUB is y-down from top-left) behind a single mapping onto a
 * top-left-origin, y-down device box `[0, displayWidth] x [0, displayHeight]`.
 */
interface KitePage {

    /** On-screen page width in points, after any page rotation. */
    val displayWidth: Double

    /** On-screen page height in points, after any page rotation. */
    val displayHeight: Double

    /**
     * Maps unscaled display space onto a top-left-origin, y-down device box
     * `[0, displayWidth] x [0, displayHeight]`. Compose output scaling on top:
     *
     * ```kotlin
     * val ctm = Matrix.scaling(scale, scale).concat(page.displayToDeviceBase())
     * page.renderTo(canvas, ctm)
     * ```
     */
    fun displayToDeviceBase(): Matrix

    /** Paints the page into [canvas] under [deviceCtm]. */
    fun renderTo(canvas: PdfCanvas, deviceCtm: Matrix = Matrix.IDENTITY)

    /**
     * Structured text for extraction / search / selection, in display space
     * (see [KiteStructuredText] for the coordinate convention), or `null`
     * when the handler does not expose it. EPUB pages implement this;
     * the PDF adapter (bridging [io.github.yuroyami.kitepdf.text] extraction)
     * is future work.
     */
    fun textContent(): KiteStructuredText? = null
}

/**
 * Format-neutral document metadata, for a viewer's title bar / info panel.
 * PDF fills it from `/Info` and XMP; EPUB from the OPF `dc:` elements.
 */
data class KiteMetadata(
    val title: String? = null,
    val authors: List<String> = emptyList(),
    /** BCP-47 language tag when the document declares one. */
    val language: String? = null,
)

/**
 * One node of a format-neutral outline (PDF bookmarks / EPUB table of
 * contents), for a viewer's navigation panel.
 */
class KiteOutlineItem(
    val title: String,
    /** Zero-based target page, or null when the destination is unresolvable. */
    val pageIndex: Int?,
    val children: List<KiteOutlineItem> = emptyList(),
)

/**
 * A parsed document from any handler — the `fz_document` equivalent. Lets a
 * viewer treat a [io.github.yuroyami.kitepdf.PdfDocument] and an
 * [io.github.yuroyami.kitepdf.epub.EpubDocument] uniformly.
 */
interface KiteDocument {

    /** Number of pages. */
    val pageCount: Int

    /** The pages, in reading order. */
    val pages: List<KitePage>

    /** Title/authors/language; defaults empty so third-party implementors don't break. */
    val metadata: KiteMetadata get() = KiteMetadata()

    /**
     * The navigation tree (PDF bookmarks / EPUB table of contents) with
     * destinations resolved to page indices; empty when the document has none.
     */
    val outline: List<KiteOutlineItem> get() = emptyList()
}
