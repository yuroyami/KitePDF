package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteCanvas

/**
 * A renderable page from any document handler: the format-neutral
 * `fz_page` equivalent. Both [io.github.yuroyami.kitepdf.PdfPage] and
 * [io.github.yuroyami.kitepdf.epub.EpubPage] implement it, so one viewer /
 * rasterizer path serves every format.
 *
 * All coordinates are in points (`1pt = 1/72in`). [displayToDeviceBase] hides
 * the per-format origin convention (PDF is y-up from bottom-left with page
 * rotation; EPUB is y-down from top-left) behind a single mapping onto a
 * top-left-origin, y-down device box `[0, displayWidth] x [0, displayHeight]`.
 */
public interface KitePage {

    /** On-screen page width in points, after any page rotation. */
    public val displayWidth: Double

    /** On-screen page height in points, after any page rotation. */
    public val displayHeight: Double

    /**
     * Maps unscaled display space onto a top-left-origin, y-down device box
     * `[0, displayWidth] x [0, displayHeight]`. Compose output scaling on top:
     *
     * ```kotlin
     * val ctm = KiteMatrix.scaling(scale, scale).concat(page.displayToDeviceBase())
     * page.renderTo(canvas, ctm)
     * ```
     */
    public fun displayToDeviceBase(): KiteMatrix

    /** Paints the page into [canvas] under [deviceCtm]. */
    public fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix = KiteMatrix.IDENTITY)

    /**
     * [renderTo] that stops early once [cancellation] reads true: the page returns with the
     * canvas closed and what it painted so far. A viewer passes it so a page the reader
     * scrolled past stops rendering. A format that renders its pages quickly ignores it.
     */
    public fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix, cancellation: KiteCancellation): Unit =
        renderTo(canvas, deviceCtm)

    /**
     * True when the page draws text in a host font, because the text has no outlines of its
     * own, and false when it draws none. Null when the page does not know before it draws. A
     * rasterizer that draws host-font text on one thread only goes there at once for a page
     * that says true, instead of drawing the page twice (#131).
     */
    public val drawsHostFontText: Boolean? get() = null

    /**
     * False when the content of this page is not in memory, so that drawing the page or reading
     * its text would lay its chapter out on the calling thread first. A document that drops
     * laid-out chapters to stay within a memory budget answers false for a page of a dropped
     * chapter. Reading it lays nothing out. True by default.
     */
    public val isContentLoaded: Boolean get() = true

    /**
     * Brings the content of this page back into memory: lays its chapter out again when a
     * memory budget dropped it. It blocks while it does that, so call it off the main thread.
     * Does nothing by default.
     */
    public fun loadContent() {}

    /**
     * Structured text for extraction / search / selection, in display space
     * (see [KiteStructuredText] for the coordinate convention), or `null`
     * when the handler does not expose it. Both handlers implement this:
     * EPUB pages natively, PDF pages by adapting their structured text.
     */
    public fun textContent(): KiteStructuredText? = null

    /**
     * This page's content in the order a reader that speaks would say it, each item with the
     * role its source gave it: the elements of an EPUB page, the structure of a tagged PDF page.
     * Empty when the page has no text or its format gives no order (#208).
     */
    public fun readingOrder(): List<KiteReadingItem> = emptyList()

    /**
     * The links of this page, each with its area and where it leads (#433). XPS and SVG pages
     * give theirs here. A PDF page gives its links with their actions as `PdfPage.annotations`,
     * and an EPUB page as `EpubPage.links`, so this list is empty for both.
     */
    public val hyperlinks: List<KiteLink> get() = emptyList()
}

/**
 * Format-neutral document metadata, for a viewer's title bar / info panel.
 * PDF fills it from `/Info` and XMP; EPUB from the OPF `dc:` elements.
 */
public data class KiteMetadata(
    val title: String? = null,
    val authors: List<String> = emptyList(),
    /** BCP-47 language tag when the document declares one. */
    val language: String? = null,
    /**
     * True when pages progress right-to-left (EPUB
     * `page-progression-direction="rtl"`, PDF `/ViewerPreferences /Direction
     * /R2L`). A paged viewer should put page N+1 visually LEFT of page N.
     */
    val rightToLeft: Boolean = false,
)

/**
 * One node of a format-neutral outline (PDF bookmarks / EPUB table of
 * contents), for a viewer's navigation panel.
 */
public class KiteOutlineItem(
    public val title: String,
    /**
     * Zero-based target page, or null when the destination is unresolvable OR
     * not laid out yet. Navigate by [target] instead when the document may
     * still be laying out.
     */
    public val pageIndex: Int?,
    public val children: List<KiteOutlineItem> = emptyList(),
    /**
     * Where this entry points, in a form that needs no layout to build and only
     * its own chapter to resolve. Null when the destination is unresolvable.
     */
    public val target: KiteBookmark? = null,
)

/**
 * A parsed document from any handler: the `fz_document` equivalent. Lets a
 * viewer treat a [io.github.yuroyami.kitepdf.PdfDocument] and an
 * [io.github.yuroyami.kitepdf.epub.EpubDocument] uniformly.
 *
 * A document is CHAPTERS of pages. A PDF is one chapter. A reflowable EPUB is
 * one chapter per spine item, and a chapter can be laid out without the ones
 * before it, so a reader opens at chapter 20 without paginating chapters 0 to
 * 19 first. Everything about chapters has a one-chapter default, so a handler
 * that does not need them implements nothing.
 *
 * Two ways to address a page:
 *
 *  - [KiteLocation] (chapter, page): always available for a ready chapter.
 *  - a global index into [pages]: only once every earlier chapter is ready.
 *
 * A viewer that wants to show a page before the whole document is laid out must
 * work in locations. [pageCount] and [pages] lay out everything.
 */
public interface KiteDocument {

    /**
     * Number of pages in the whole document.
     *
     * Lays out every chapter. For a large reflowable EPUB that is the expensive
     * call: prefer [chapterCount] with [pageCountIn], or [knownPageCount].
     */
    public val pageCount: Int

    /**
     * The pages, in reading order.
     *
     * Lays out every chapter, like [pageCount]. Use [page] with a
     * [KiteLocation] to reach one page without paying for the rest.
     */
    public val pages: List<KitePage>

    /** Title/authors/language; defaults empty so third-party implementors don't break. */
    public val metadata: KiteMetadata get() = KiteMetadata()

    /**
     * The navigation tree (PDF bookmarks / EPUB table of contents) with
     * destinations resolved to page indices; empty when the document has none.
     */
    public val outline: List<KiteOutlineItem> get() = emptyList()

    /* ── chapters ─────────────────────────────────────────────────────────── */

    /** How many chapters. One for a document that does not have them. */
    public val chapterCount: Int get() = 1

    /** True when [chapter] is laid out and its pages can be read. */
    public fun isChapterReady(chapter: Int): Boolean = true

    /**
     * Lays out [chapter] if it is not ready yet. The one expensive call.
     *
     * Blocks until the chapter is ready. Safe to call from any thread and from
     * several at once: callers for the same chapter wait for one layout rather
     * than repeating it. Out-of-range chapters are ignored.
     */
    public fun prepareChapter(chapter: Int) {}

    /**
     * [prepareChapter], with [checkpoint] called between two steps of the layout, so that a
     * caller on a thread that must keep drawing frames, as in a browser, can suspend there and
     * go on after a frame. A document that lays a chapter out in steps calls it; by default the
     * chapter is prepared at once. No lock is held across a checkpoint, so two callers for one
     * chapter may each lay it out, and the first to finish keeps its pages. A wrapper that
     * overrides [prepareChapter] should override this one too: `by` delegation sends it
     * straight to the wrapped document.
     */
    public suspend fun prepareChapter(chapter: Int, checkpoint: suspend () -> Unit): Unit = prepareChapter(chapter)

    /** Pages in [chapter]. Prepares it first. Zero for an empty chapter. */
    public fun pageCountIn(chapter: Int): Int = pageCount

    /**
     * The chapters on screen now. A document that drops laid-out chapters to stay within a
     * memory budget keeps these, so a tap, a long press or a draw on the screen does not wait
     * for a layout. A viewer calls it each time the chapters on screen change. Does nothing
     * by default.
     */
    public fun keepChapters(chapters: Set<Int>) {}

    /**
     * The page at [location]. Prepares its chapter first.
     *
     * The same location answers the same object for the life of the document,
     * and [pages] holds those same objects. Viewers key their caches on the
     * page object, so a handler that built a new one per call would defeat
     * every cache above it.
     */
    public fun page(location: KiteLocation): KitePage = pages[location.page]

    /** True once every chapter is laid out, so global page indices are final. */
    public val isComplete: Boolean get() = true

    /** Pages laid out so far. Equals [pageCount] once [isComplete]. */
    public val knownPageCount: Int get() = pageCount

    /**
     * The global index of [location], or null while an earlier chapter is not
     * ready yet and the index cannot be known.
     */
    public fun pageIndexOf(location: KiteLocation): Int? = location.page

    /**
     * The location of a global page index, or null when the index is out of
     * range or not reached yet.
     */
    public fun locationOf(pageIndex: Int): KiteLocation? =
        if (pageIndex in 0 until pageCount) KiteLocation(0, pageIndex) else null

    /** A re-flow-proof position for [location]. Prepares its chapter first. */
    public fun bookmarkOf(location: KiteLocation): KiteBookmark =
        KiteBookmark.Page(location.page)

    /**
     * Where [bookmark] sits in the current layout. Prepares that chapter, and
     * only that chapter. Clamps into range rather than failing.
     */
    public fun locate(bookmark: KiteBookmark): KiteLocation = when (bookmark) {
        is KiteBookmark.Page -> KiteLocation(0, bookmark.pageIndex)
        is KiteBookmark.Flow -> KiteLocation(0, 0)
    }
}
