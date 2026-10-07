package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import io.github.yuroyami.kitepdf.core.render.ReaderTheme

/**
 * How [KiteDocView] lays its pages out and how the user moves between them.
 */
@Immutable
public sealed interface KiteDocLayout {

    /**
     * All pages in one continuous scrollable strip (lazy: offscreen pages are
     * not composed). Pages fill the cross axis at their natural aspect ratio,
     * or, with [fit] [KitePageFit.PAGE], shrink until the whole page shows.
     * Once the strip rests, [prefetchPages] pages on each side of the pages on
     * screen draw into the bitmap cache.
     *
     * Zoom in this mode is magnifier-style: the strip is scaled around the
     * viewport centre, pan across the strip is a clamped transform, and the
     * scroll axis keeps scrolling the (scaled) strip natively. At either end of
     * the strip, a drag past the end moves the zoomed page instead, so its first
     * and last lines come into view. The strip does not zoom out below fit.
     */
    @Immutable
    public data class Continuous(
        val orientation: Orientation = Orientation.Vertical,
        /** Scrollable edge clearance for host controls; pages still pass underneath them. */
        val contentPadding: PaddingValues = PaddingValues(0.dp),
        /**
         * How each page fits the viewport. [KitePageFit.WIDTH] fills the cross axis, so a page
         * longer than the viewport scrolls through it. [KitePageFit.PAGE] shrinks a page until
         * the whole of it shows at once, centred across the strip (#437).
         */
        val fit: KitePageFit = KitePageFit.WIDTH,
        /**
         * Pages drawn ahead on each side of the pages on screen once the strip rests, into the
         * bitmap cache, so a scroll to them finds their bitmap ready. 0 draws nothing ahead. The
         * strip draws them itself, because Compose's own prefetch runs on Android only (#437).
         */
        val prefetchPages: Int = 1,
    ) : KiteDocLayout {
        init {
            require(prefetchPages >= 0) { "prefetchPages must be >= 0 (was $prefetchPages)" }
        }
    }

    /**
     * One page at a time with snap paging (swipe, or drive programmatically
     * via [KiteDocViewState]). Each page is letterboxed to fit the viewport.
     *
     * @param offscreenPages pages kept composed (and rastered) on each side of
     *   the visible one. This trades memory for instant page turns. Defaults to 1 so
     *   the immediate neighbours are pre-rasterized while idle and a swipe never
     *   waits on a render; raise it to cover faster flinging, set 0 to minimise
     *   memory at the cost of a first-swipe render.
     */
    @Immutable
    public data class Paged(
        val orientation: Orientation = Orientation.Horizontal,
        val offscreenPages: Int = 1,
        /**
         * Reverses the paging direction so page N+1 sits visually LEFT of
         * page N (up in a vertical pager). Use this for right-to-left books (manga,
         * Arabic/Hebrew). Navigation stays logical: `nextPage()` is always
         * index +1. See [pagedFor] for automatic selection.
         */
        val reverseLayout: Boolean = false,
        /** How each page fits the viewport: whole, or across the width with the rest a pan away. */
        val fit: KitePageFit = KitePageFit.PAGE,
    ) : KiteDocLayout {
        init {
            require(offscreenPages >= 0) { "offscreenPages must be >= 0 (was $offscreenPages)" }
        }
    }

    /**
     * Two-page spreads: one snap-pager item shows two pages side by side, like
     * an open book. Pages pair in order, (0, 1), (2, 3) and so on, except where
     * the document declares otherwise (#37):
     *
     * - An EPUB chapter whose first page is `page-spread-left` or
     *   `page-spread-right` starts or ends a spread on that side, and one that is
     *   `rendition:page-spread-center` shows alone.
     * - An EPUB book or chapter with `rendition:spread` none shows its pages
     *   alone, and with landscape it pairs them only in a landscape viewport.
     * - A PDF whose `/PageLayout` is TwoPageRight or TwoColumnRight puts its
     *   first page on the right, so a left-to-right book shows it alone.
     *
     * A page without a partner centres alone. [reverseLayout] swaps both the
     * swipe direction and the in-spread visual order (the first page on the
     * right), for right-to-left books. Navigation stays logical (`nextPage()` =
     * index +1; the visible spread advances once the step leaves it). Meant for
     * fixed-layout content (PDF, pre-paginated EPUB); reflowable EPUB gains
     * nothing from it. Spreads pair the pages of the whole book, so a book shows
     * a chapter placeholder until every chapter is laid out.
     *
     * @property firstPageAlone shows the first page alone, as a printed book
     *   shows its cover, and pairs the pages after it.
     */
    @Immutable
    public data class Spread(
        val orientation: Orientation = Orientation.Horizontal,
        val offscreenPages: Int = 1,
        val reverseLayout: Boolean = false,
        val firstPageAlone: Boolean = false,
    ) : KiteDocLayout {
        init {
            require(offscreenPages >= 0) { "offscreenPages must be >= 0 (was $offscreenPages)" }
        }
    }

    /**
     * Exactly one fixed page, letterboxed to fit the viewport. [pageIndex] counts the pages of
     * the whole document, as [io.github.yuroyami.kitepdf.core.KiteDocument.pages] does. A
     * reflowable book that is still laying out shows a chapter placeholder until the chapters
     * before the page are laid out, and then the page. An index outside the document shows its
     * nearest page and logs a warning. Navigation does not turn a fixed page.
     */
    @Immutable
    public data class SinglePage(val pageIndex: Int) : KiteDocLayout

    public companion object {
        public val Default: KiteDocLayout = Continuous()

        /**
         * A horizontal pager following the document's page-progression
         * direction: right-to-left books ([io.github.yuroyami.kitepdf.core.KiteMetadata.rightToLeft])
         * get a reversed pager, everything else the plain one.
         */
        public fun pagedFor(document: io.github.yuroyami.kitepdf.core.KiteDocument): Paged =
            Paged(reverseLayout = document.metadata.rightToLeft)

        /**
         * The layout that [document] asks for. An EPUB book whose `rendition:flow` is
         * `scrolled-continuous` reads as one [Continuous] strip, and one that is `scrolled-doc`
         * reads in a [Paged] pager whose chapters each fit the width and scroll down (EPUB 3.3,
         * #505). [io.github.yuroyami.kitepdf.epub.EpubSettings.scrolled] false keeps such a book
         * in pages. A roll reads as a [Continuous] strip whose pages fill the width with no gap
         * between them (EPUB 3.4, #506). Every other document gets [pagedFor].
         */
        public fun forDocument(document: io.github.yuroyami.kitepdf.core.KiteDocument): KiteDocLayout {
            val book = document as? io.github.yuroyami.kitepdf.epub.EpubDocument ?: return pagedFor(document)
            if (book.epubMetadata.rendition.isRoll) return Continuous()
            if (book.settings.scrolled == false) return pagedFor(document)
            return when (book.epubMetadata.rendition.flow) {
                io.github.yuroyami.kitepdf.epub.EpubFlow.SCROLLED_CONTINUOUS -> Continuous()
                io.github.yuroyami.kitepdf.epub.EpubFlow.SCROLLED_DOC -> pagedFor(document).copy(fit = KitePageFit.WIDTH)
                else -> if (book.settings.scrolled == true) Continuous() else pagedFor(document)
            }
        }
    }
}

/** How a [KiteDocLayout.Paged] pager or a [KiteDocLayout.Continuous] strip fits each page into the viewport. */
public enum class KitePageFit {
    /**
     * The whole page shows. In a pager it is centred, with bands on the sides it does not fill.
     * In a strip each page shrinks until it fits the scroll axis too, centred across the strip,
     * so the reader sees one whole page at a time (#437).
     */
    PAGE,

    /**
     * The page fills the width. In a pager, a page taller than the viewport starts at its top,
     * and the reader drags or turns the wheel down through it; a drag sideways past its edge
     * turns it. A shorter page shows centred. This is how a scrolled EPUB chapter reads (#505).
     * In a strip, the pages fill the cross axis at their own aspect ratio, and the strip scrolls
     * through them.
     */
    WIDTH,
}

/**
 * Zoom & pan behaviour for [KiteDocView].
 *
 * [minZoom]/[maxZoom] bound *all* zoom changes, including programmatic ones
 * through [KiteDocViewState.setZoom], so an app driving zoom from its own slider
 * (gestures disabled) still declares its range here.
 *
 * @param pinchEnabled two-finger pinch zoom, Ctrl or Cmd with the wheel or a trackpad pinch,
 *   and Ctrl or Cmd with plus, minus and 0 on a keyboard.
 * @param doubleTapEnabled double-tap toggles between [minZoom] and [doubleTapZoom].
 * @param doubleTapZoom the zoom a double tap goes to. It must be finite and above 0, and a
 *   double tap clamps it into [minZoom]..[maxZoom], as every zoom change is clamped.
 * @param panEnabled one-finger pan while zoomed in. A quick release lets the pan go on and
 *   slow down, and in [KiteDocLayout.Paged] and [KiteDocLayout.Spread] a drag that goes on past
 *   the edge of the page turns it.
 * @param resetZoomOnPageChange in [KiteDocLayout.Paged] and [KiteDocLayout.Spread], snap
 *   zoom back to [minZoom] when the reader lands on another page or spread. The page a
 *   pager first shows is not a change, so a zoom set before the pager appears stays.
 *   Disable when zoom is driven externally and should persist across pages.
 */
@Immutable
public data class KiteZoomSpec(
    val pinchEnabled: Boolean = true,
    val doubleTapEnabled: Boolean = true,
    val panEnabled: Boolean = true,
    val minZoom: Float = 1f,
    val maxZoom: Float = 8f,
    val doubleTapZoom: Float = 2.5f,
    val resetZoomOnPageChange: Boolean = true,
) {
    init {
        require(minZoom > 0f) { "minZoom must be > 0 (was $minZoom)" }
        require(maxZoom >= minZoom) { "maxZoom ($maxZoom) must be >= minZoom ($minZoom)" }
        require(doubleTapZoom.isFinite() && doubleTapZoom > 0f) { "doubleTapZoom must be finite and > 0 (was $doubleTapZoom)" }
    }

    public companion object {
        /** No zoom at all: gestures off, range pinned to 1. */
        public val Disabled: KiteZoomSpec = KiteZoomSpec(
            pinchEnabled = false,
            doubleTapEnabled = false,
            panEnabled = false,
            minZoom = 1f,
            maxZoom = 1f,
        )
    }
}

/**
 * How [KiteDocView] turns pages into pixels. Pick a variant; each carries only the
 * knobs that actually apply to it, so there are no settings that silently do
 * nothing. Defaults to [Rasterized] via [KiteRenderSpec.Default].
 */
@Immutable
public sealed interface KiteRenderSpec {

    /**
     * Vector-render each page into a bitmap for its size and its settled zoom,
     * then draw that bitmap and GPU-transform it during gestures, so scrolling
     * and zoom never redraw the page itself. Heavy gesturing is cheap and
     * content-independent; the costs are one rasterization per page size and
     * per settled zoom, and softness when zoomed past the raster resolution
     * until the zoom settles and it re-rasterizes. Best for slow devices and
     * dense pages.
     *
     * @param quality supersampling multiplier over the on-screen pixel size.
     *   1 = rasterize exactly at display resolution (sharpest *and* cheapest,
     *   the default). >1 = oversample, e.g. for screenshots or print-ish export.
     *   <1 = undersample for cheap previews/thumbnails.
     * @param maxBitmapLongSide hard cap on the longest side of a page's whole
     *   bitmap, protecting memory on huge pages and deep zooms. The viewer's
     *   rasterizer raises its pixel ceiling to this value squared, so any side
     *   length allowed here also renders. A page that needs more pixels keeps
     *   the capped bitmap, and the part on screen draws over it in tiles at full
     *   resolution. On Android, stay at or below the GPU's texture limit, 4096 on
     *   many devices: a larger bitmap draws blank there.
     * @param rerasterizeOnZoom after a zoom settles, re-render at the zoomed
     *   resolution instead of upscaling the base raster. The zoom rounds up to a
     *   quarter of an octave (1, 1.19, 1.41, 1.68, 2 and so on), so pinches that
     *   settle close together share one raster. Past [maxBitmapLongSide], tiles of
     *   1,024 pixels draw the part on screen, so deep zoom and very tall pages stay
     *   sharp. Only the pages in view render at the zoom. Costs one rasterization
     *   per page in view and zoom step, and one per tile.
     * @param preserveHairlines scale the engine's stroke floors by the ratio of the
     *   raster to the screen, so a zero-width stroke stays one screen pixel and other
     *   sub-pixel strokes (ECG traces, fine table rules) keep their weight when the
     *   bitmap is downscaled.
     */
    @Immutable
    public data class Rasterized(
        val quality: Float = 1f,
        val maxBitmapLongSide: Int = 4096,
        val rerasterizeOnZoom: Boolean = true,
        val preserveHairlines: Boolean = true,
        /**
         * Byte budget of the per-[KiteDocViewState] page-bitmap LRU, so
         * scrolling back is a cache hit instead of a re-raster. 0 disables
         * caching (every slot re-rasterizes on recomposition, the pre-cache
         * behaviour).
         */
        val cacheBudgetBytes: Long = 96L * 1024 * 1024,
        /** Wraps each page paint pass; see [KiteCanvasDecorator] for cache and thread rules. */
        val canvasDecorator: KiteCanvasDecorator? = null,
    ) : KiteRenderSpec {
        init {
            require(quality > 0f) { "quality must be > 0 (was $quality)" }
            require(maxBitmapLongSide > 0) { "maxBitmapLongSide must be > 0" }
        }
    }

    /**
     * Draw each page into a live `Canvas`, transformed by zoom/pan via the same GPU
     * layer: no bitmap (lower memory). Vector content stays sharp at rest on every
     * platform. The page draws again when its size, the theme or this spec changes.
     * A change to the overlay, such as a search hit or a highlight, and each frame
     * of a pinch replay the drawing that the page recorded. Once a
     * zoom settles, the page draws again at it, so images keep the detail the zoom
     * shows and a hairline stays about one screen pixel wide (#418).
     * On Android the vector display list replays under the live transform, so it
     * stays crisp even mid-pinch; on Skia targets (iOS/desktop/web) the layer is
     * texture-cached, so deep in-gesture zoom softens until the draw re-runs.
     * Per-page draw cost scales with content complexity. Best for simple pages,
     * deep-zoom crispness, and low memory.
     *
     * The page is drawn inside the Compose draw pass, on the UI thread, and it is
     * parsed and painted again on every redraw, so a dense page can drop frames.
     * Unchanged images reuse their converted bitmaps across draws at the same sampling
     * size, within [imageCacheBudgetBytes]. The first conversion and cache misses still
     * run on the UI thread. A page of large scans, such as a comic, is better in
     * [Rasterized], which renders off the main thread.
     *
     * @param hairlineWidthPx the width in device pixels of a stroke whose line width
     *   is 0; 1 = the one device pixel of ISO 32000-1, 8.4.3.2. Other thin strokes
     *   (ECG traces, fine borders) widen to a fifth of it, as MuPDF draws them. There
     *   is no supersampling knob: vector output is already resolution-independent.
     */
    @Immutable
    public data class Vectorized(
        val hairlineWidthPx: Float = 1f,
        /** Wraps each live page paint pass on the UI thread. */
        val canvasDecorator: KiteCanvasDecorator? = null,
        /**
         * Byte budget of converted image bitmaps shared by the pages of one [KiteDocViewState].
         * Unchanged images at the same sampling size reuse these bitmaps across draws (#371).
         * 0 disables retention; an image larger than the budget is drawn without caching it.
         * This does not move the first conversion off the UI thread or hold a page for loading.
         */
        val imageCacheBudgetBytes: Long = 16L * 1024 * 1024,
    ) : KiteRenderSpec {
        /** Keeps the original constructor and its default arguments for existing callers. */
        public constructor(
            hairlineWidthPx: Float = 1f,
            canvasDecorator: KiteCanvasDecorator? = null,
        ) : this(hairlineWidthPx, canvasDecorator, 16L * 1024 * 1024)

        /** Keeps the original copy signature while preserving this spec's image-cache budget. */
        public fun copy(
            hairlineWidthPx: Float = this.hairlineWidthPx,
            canvasDecorator: KiteCanvasDecorator? = this.canvasDecorator,
        ): Vectorized = Vectorized(hairlineWidthPx, canvasDecorator, imageCacheBudgetBytes)

        init {
            require(hairlineWidthPx > 0f) { "hairlineWidthPx must be > 0 (was $hairlineWidthPx)" }
            require(imageCacheBudgetBytes >= 0L) { "imageCacheBudgetBytes must be >= 0 (was $imageCacheBudgetBytes)" }
        }
    }

    public companion object {
        /** Default: rasterized at display resolution. */
        public val Default: KiteRenderSpec = Rasterized()
    }
}

/**
 * Colours used by [KiteDocView].
 *
 * @param pageBackground painted behind page content. Most documents assume
 *   white paper and paint none themselves. When [theme] is set, the theme's paper
 *   colour replaces it everywhere the viewer paints a page: the page, its
 *   placeholder, a chapter gap and the thumbnails.
 * @param viewportBackground the letterbox/gutter colour around pages.
 * @param theme optional reading theme ([ReaderTheme.Dark]/[ReaderTheme.Sepia]/
 *   [ReaderTheme.Light]). When set, page content colours are remapped (text,
 *   borders, backgrounds, not images) and the paper uses the theme background.
 *   Reflowable EPUB especially benefits: night mode without re-laying-out.
 */
@Immutable
public data class KiteDocViewColors(
    val pageBackground: Color = Color.White,
    val viewportBackground: Color = Color.Transparent,
    val theme: ReaderTheme? = null,
    /** Fill for [KiteDocViewState.searchHighlights] quads, drawn over the page. */
    val searchHighlight: Color = Color(0x66FFEB3B),
    /** Fill for the active [KiteDocViewState.selection] quads. */
    val selectionHighlight: Color = Color(0x664285F4),
    /**
     * The caret-and-dot markers at the selection's two boundaries. Opaque on
     * purpose where [selectionHighlight] is translucent: the wash says "this
     * much is selected", the handles say exactly where that starts and ends,
     * and a see-through boundary marker would say neither.
     */
    val selectionHandle: Color = Color(0xFF4285F4),
    /**
     * How each selection boundary marker is drawn. Null uses the built-in
     * caret-and-dot ([KiteSelectionHandleDefaults.CaretAndDot]). The handles
     * are canvas vector drawing, not composables: they live inside the page's
     * draw pass so they scale, pan and zoom in lockstep with the words they
     * bound, which an overlay composable could only approximate.
     */
    val selectionHandlePainter: KiteSelectionHandlePainter? = null,
)

/** Which end of the selection a handle marks, in reading order. */
public enum class KiteSelectionHandleEdge { Start, End }

/**
 * Draws one selection boundary marker ("thumb") for [KiteDocView].
 *
 * Called once per edge inside the page's draw pass. [x] is the boundary's
 * horizontal position; [top]/[bottom] are the boundary line's vertical extent,
 * all in page-slot pixels. Size the marker against `bottom - top` (the line
 * height) so it scales with the text through thumbnails and deep zoom, like
 * [KiteSelectionHandleDefaults.CaretAndDot] does. A boundary in vertical text,
 * where a line is a column, goes to [drawColumnHandle] instead.
 *
 * Drawing does not move the grab target. A thumb is dragged by pressing near
 * the boundary line, not near the shape you paint, so a marker drawn far from
 * its boundary still answers to the boundary. Keep it close to the line and
 * the two agree.
 */
public fun interface KiteSelectionHandlePainter {
    public fun DrawScope.drawHandle(
        edge: KiteSelectionHandleEdge,
        x: Float,
        top: Float,
        bottom: Float,
        color: Color,
    )

    /**
     * Draws the marker for a boundary in vertical text, where a line is a column running down
     * the page: [y] is the boundary's position down the column, and [left]/[right] the column's
     * extent, in page-slot pixels. The default turns [drawHandle] a quarter turn clockwise, so a
     * marker drawn below a line sits to the left of a column.
     */
    public fun DrawScope.drawColumnHandle(
        edge: KiteSelectionHandleEdge,
        y: Float,
        left: Float,
        right: Float,
        color: Color,
    ) {
        val centre = (left + right) / 2f
        val half = (right - left) / 2f
        rotate(90f, pivot = androidx.compose.ui.geometry.Offset(centre, y)) {
            drawHandle(edge, centre, y - half, y + half, color)
        }
    }
}

public object KiteSelectionHandleDefaults {
    /**
     * The built-in marker: a caret spanning the boundary line's full height
     * with a grab dot beneath it, sized from the line height.
     */
    public val CaretAndDot: KiteSelectionHandlePainter = KiteSelectionHandlePainter { _, x, top, bottom, color ->
        val lineHeight = (bottom - top).coerceAtLeast(1f)
        val radius = (lineHeight * 0.28f).coerceIn(3f, 14f)
        val stroke = (radius * 0.5f).coerceAtLeast(1.5f)
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(x, top),
            end = androidx.compose.ui.geometry.Offset(x, bottom),
            strokeWidth = stroke,
        )
        drawCircle(
            color = color,
            radius = radius,
            center = androidx.compose.ui.geometry.Offset(x, bottom + radius),
        )
    }
}
