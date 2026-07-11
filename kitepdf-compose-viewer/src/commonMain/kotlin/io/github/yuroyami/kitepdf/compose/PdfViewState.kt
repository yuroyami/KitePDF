package io.github.yuroyami.kitepdf.compose

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntSize
import io.github.yuroyami.kitepdf.KiteDocument
import io.github.yuroyami.kitepdf.KiteSearchHit
import io.github.yuroyami.kitepdf.KiteStructuredText
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.math.abs

/**
 * Remembers a [PdfViewState] for [document]. Hoist it to drive a [PdfView]
 * from anywhere — navigation buttons in your top bar, a zoom slider, a HUD
 * overlay — the state object is the single point of control.
 */
@Composable
public fun rememberPdfViewState(document: PdfDocument, initialPage: Int = 0): PdfViewState =
    remember(document) { PdfViewState(document, initialPage) }

/**
 * Observable state + control surface of a [PdfView].
 *
 * Everything a navigation/zoom widget needs lives here, so widgets are just
 * composables that take a [PdfViewState] — place them inside the viewport
 * (via [PdfView]'s `overlay` slot), next to it, or anywhere else in your tree.
 *
 * Reads ([currentPage], [zoom], [panOffset]…) are snapshot-state backed and
 * recompose their readers automatically. Navigation suspends until finished;
 * calls made before the state is attached to a composed [PdfView] are
 * remembered and applied on attach.
 */
@Stable
public class PdfViewState(
    public val document: KiteDocument,
    initialPage: Int = 0,
) {
    public val pageCount: Int get() = document.pageCount

    /** Current zoom factor. 1 = fit. Bounded by [PdfZoomSpec.minZoom]/[maxZoom]. */
    public var zoom: Float by mutableFloatStateOf(1f)
        private set

    /** Pan translation in viewport px, applied after [zoom] around the viewport centre. */
    public var panOffset: Offset by mutableStateOf(Offset.Zero)
        internal set

    /** True once zoomed in beyond the minimum (with a small epsilon). */
    public val isZoomed: Boolean get() = zoom > zoomRange.start + EPSILON

    /**
     * Search hits to paint as translucent quads over their pages (colour:
     * [PdfViewColors.searchHighlight]). Feed it from `PdfDocument.search` /
     * `KiteStructuredText.search` (quads are display-space, as both produce);
     * clear it by assigning an empty list.
     */
    public var searchHighlights: List<KiteSearchHit> by mutableStateOf(emptyList())

    /**
     * The page the viewport currently rests on: the snapped page in paged
     * mode, the page nearest the viewport centre in continuous mode.
     */
    public val currentPage: Int
        get() = adapter?.currentPage ?: pendingPage

    /* ── internal wiring (set by PdfView during composition) ─────────────── */

    internal var adapter: PdfScrollAdapter? by mutableStateOf(null)
    internal var pendingPage: Int = initialPage.coerceAtLeast(0)
    internal var zoomRange: ClosedFloatingPointRange<Float> by mutableStateOf(1f..8f)
    internal var viewportSize: IntSize by mutableStateOf(IntSize.Zero)

    /**
     * Per-page on-screen geometry in UNTRANSFORMED viewport space (before the
     * zoom/pan `graphicsLayer`): page slots report their rects during layout
     * and remove them on dispose. [hitTest] inverts the layer transform onto
     * this space, so the map never needs to update on zoom/pan alone.
     */
    internal val pageGeometry = mutableStateMapOf<Int, Rect>()

    /**
     * The viewport-filling content node INSIDE the zoom/pan layer, the anchor
     * page slots measure their rects against (continuous mode; paged/single
     * slots compute their letterbox rect directly from constraints).
     */
    internal var contentCoordinates: LayoutCoordinates? = null

    /** Pan axes the active layout allows (continuous mode keeps its scroll axis native). */
    internal var panAxes: PanAxes = PanAxes.Both

    /**
     * The page-bitmap LRU (T-15): outlives individual page composables, dies
     * with the state. Recreated when the render spec's budget changes.
     */
    private var bitmapCache: PageBitmapCache? = null
    private var bitmapCacheBudget = -1L

    internal fun bitmapCacheFor(budgetBytes: Long): PageBitmapCache? {
        if (budgetBytes <= 0L) return null
        if (bitmapCacheBudget != budgetBytes) {
            bitmapCache = PageBitmapCache(budgetBytes)
            bitmapCacheBudget = budgetBytes
        }
        return bitmapCache
    }

    /* ── zoom ─────────────────────────────────────────────────────────────── */

    /**
     * Sets [zoom] immediately, clamped to the active [PdfZoomSpec] range.
     *
     * @param focal viewport-space point to keep visually stationary (e.g. the
     *   pinch centroid or double-tap position). Unspecified = viewport centre.
     */
    public fun setZoom(zoom: Float, focal: Offset = Offset.Unspecified) {
        val new = zoom.coerceIn(zoomRange.start, zoomRange.endInclusive)
        val old = this.zoom
        if (new == old) return
        panOffset = if (focal.isSpecified && viewportSize != IntSize.Zero) {
            // Keep the focal point stationary: screen = centre + (content-centre)·zoom + pan
            val centre = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
            val f = focal - centre
            clampPan((panOffset - f) * (new / old) + f, new)
        } else {
            clampPan(panOffset, new)
        }
        this.zoom = new
    }

    /** Animates zoom to [target] (clamped), keeping [focal] stationary throughout. */
    public suspend fun animateZoomTo(
        target: Float,
        focal: Offset = Offset.Unspecified,
        animationSpec: AnimationSpec<Float> = spring(),
    ) {
        val clamped = target.coerceIn(zoomRange.start, zoomRange.endInclusive)
        animate(zoom, clamped, animationSpec = animationSpec) { value, _ -> setZoom(value, focal) }
    }

    /** Snaps back to the minimum zoom and recentres. */
    public fun resetZoom() {
        zoom = zoomRange.start
        panOffset = Offset.Zero
    }

    /**
     * Pans by [delta] (viewport px), clamped to the zoomed content bounds.
     * Returns the portion actually consumed — the gesture layer hands the
     * remainder back to the underlying scroll container.
     */
    public fun panBy(delta: Offset): Offset {
        val allowed = Offset(
            if (panAxes.x) delta.x else 0f,
            if (panAxes.y) delta.y else 0f,
        )
        val old = panOffset
        panOffset = clampPan(old + allowed, zoom)
        return panOffset - old
    }

    internal fun clampPan(offset: Offset, zoom: Float): Offset {
        val maxX = ((viewportSize.width * (zoom - 1f)) / 2f).coerceAtLeast(0f)
        val maxY = ((viewportSize.height * (zoom - 1f)) / 2f).coerceAtLeast(0f)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    /* ── text selection (T-80) ────────────────────────────────────────────── */

    /**
     * The active text selection, or null. Set by the long-press-drag gesture;
     * observe via snapshot reads or [onSelectionChange]. The viewer never
     * touches the clipboard itself — read [TextSelection.text] and copy in
     * the app (see the sample's selection actions).
     */
    public var selection: TextSelection? by mutableStateOf(null)
        private set

    /** Fires on every selection change, including clearing (null). */
    public var onSelectionChange: ((TextSelection?) -> Unit)? = null

    /** The fixed anchor (page, flattened char index) of an active drag. */
    private var selectionAnchor: Pair<Int, Int>? = null

    public fun clearSelection() {
        selectionAnchor = null
        if (selection != null) {
            selection = null
            onSelectionChange?.invoke(null)
        }
    }

    /** Long-press: anchor the selection at the char under [viewportOffset]. */
    internal fun beginSelection(viewportOffset: Offset) {
        selectionAnchor = null
        val (pageIndex, x, y) = hitTestDisplay(viewportOffset) ?: return
        val text = document.pages.getOrNull(pageIndex)?.textContent() ?: return
        val idx = text.charIndexAt(x, y) ?: return
        selectionAnchor = pageIndex to idx
        applySelection(text, pageIndex, idx, idx)
    }

    /**
     * Drag: extend from the anchor to the char under [viewportOffset].
     * Both ends stay on the anchor page (cross-page selection is out of
     * scope); points past the page or off any line keep the last state.
     */
    internal fun extendSelection(viewportOffset: Offset) {
        val (page, anchor) = selectionAnchor ?: return
        val (pageIndex, x, y) = hitTestDisplay(viewportOffset) ?: return
        if (pageIndex != page) return
        val text = document.pages.getOrNull(page)?.textContent() ?: return
        val idx = text.charIndexAt(x, y) ?: return
        applySelection(text, page, minOf(anchor, idx), maxOf(anchor, idx))
    }

    private fun applySelection(text: KiteStructuredText, page: Int, start: Int, end: Int) {
        val sel = TextSelection(
            pageIndex = page,
            start = start,
            end = end,
            text = text.textRange(start, end),
            quads = text.quadsFor(start, end),
        )
        if (sel.start == selection?.start && sel.end == selection?.end && sel.pageIndex == selection?.pageIndex) return
        selection = sel
        onSelectionChange?.invoke(sel)
    }

    /* ── hit testing ──────────────────────────────────────────────────────── */

    /**
     * Like [hitTest] but stops in DISPLAY space (y-down points, the space
     * [io.github.yuroyami.kitepdf.KiteStructuredText] geometry lives in).
     */
    internal fun hitTestDisplay(viewportOffset: Offset): Triple<Int, Double, Double>? {
        if (viewportSize == IntSize.Zero || zoom <= 0f) return null
        val centre = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        val content = centre + (viewportOffset - centre - panOffset) / zoom
        for ((index, rect) in pageGeometry) {
            if (rect.width <= 0f || rect.height <= 0f || !rect.contains(content)) continue
            val page = document.pages.getOrNull(index) ?: continue
            return Triple(
                index,
                (content.x - rect.left) / rect.width * page.displayWidth,
                (content.y - rect.top) / rect.height * page.displayHeight,
            )
        }
        return null
    }

    /**
     * Maps a viewport point (the space gesture callbacks like `onTap` report
     * in) to the page under it, or null when it lands on background/spacing.
     *
     * Inverts the zoom/pan layer first (the layer scales around the viewport
     * centre, then translates by [panOffset] — the same math [setZoom]'s focal
     * logic composes), locates the page slot from the geometry the layout
     * reported, then maps display-space points through the inverse of
     * [KitePage.displayToDeviceBase] into page space: PDF pages get user
     * space (y-up, rotation unfolded), EPUB pages their document space.
     */
    public fun hitTest(viewportOffset: Offset): PageHit? {
        val (index, devX, devY) = hitTestDisplay(viewportOffset) ?: return null
        val page = document.pages.getOrNull(index) ?: return null
        val inv = page.displayToDeviceBase().invert() ?: return null
        val (x, y) = inv.transformPoint(devX, devY)
        return PageHit(index, x, y)
    }

    /* ── navigation ───────────────────────────────────────────────────────── */

    /** Jumps to [page] (coerced into range) without animation. */
    public suspend fun scrollToPage(page: Int) {
        val target = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        pendingPage = target
        adapter?.scrollToPage(target)
    }

    /** Animates to [page] (coerced into range). */
    public suspend fun animateScrollToPage(page: Int) {
        val target = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        pendingPage = target
        adapter?.animateScrollToPage(target)
    }

    public suspend fun nextPage(): Unit = animateScrollToPage(currentPage + 1)

    public suspend fun previousPage(): Unit = animateScrollToPage(currentPage - 1)

    internal data class PanAxes(val x: Boolean, val y: Boolean) {
        companion object {
            val Both = PanAxes(x = true, y = true)
            val XOnly = PanAxes(x = true, y = false)
            val YOnly = PanAxes(x = false, y = true)
        }
    }

    private companion object {
        const val EPSILON = 0.001f
    }
}

/**
 * A [PdfViewState.hitTest] result: the page under a viewport point and the
 * point in that page's own space (PDF: user space, y-up from the display
 * box's bottom-left with rotation unfolded; EPUB: the page's document space).
 */
public data class PageHit(
    val pageIndex: Int,
    val x: Double,
    val y: Double,
)

/**
 * A finalized or in-progress text selection on one page (cross-page
 * selection is out of scope). [start]/[end] are INCLUSIVE flattened char
 * indices into the page's [io.github.yuroyami.kitepdf.KiteStructuredText]
 * reading order; [text] carries `\n`/`\n\n` line/block separators exactly
 * like the extraction text; [quads] are display-space, one per line touched.
 */
public data class TextSelection(
    val pageIndex: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val quads: List<io.github.yuroyami.kitepdf.Rectangle>,
)

/* ── scroll adapters: one state API over LazyList and Pager backends ──────── */

internal interface PdfScrollAdapter {
    val currentPage: Int
    suspend fun scrollToPage(page: Int)
    suspend fun animateScrollToPage(page: Int)
}

/** Continuous mode: "current" = the visible item whose centre is nearest the viewport centre. */
internal class LazyListScrollAdapter(private val listState: LazyListState) : PdfScrollAdapter {
    override val currentPage: Int
        get() {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) return listState.firstVisibleItemIndex
            val viewportCentre = (info.viewportStartOffset + info.viewportEndOffset) / 2
            return visible.minByOrNull { abs((it.offset + it.size / 2) - viewportCentre) }?.index
                ?: listState.firstVisibleItemIndex
        }

    override suspend fun scrollToPage(page: Int) = listState.scrollToItem(page)
    override suspend fun animateScrollToPage(page: Int) = listState.animateScrollToItem(page)
}

internal class PagerScrollAdapter(private val pagerState: PagerState) : PdfScrollAdapter {
    override val currentPage: Int get() = pagerState.currentPage
    override suspend fun scrollToPage(page: Int) = pagerState.scrollToPage(page)
    override suspend fun animateScrollToPage(page: Int) = pagerState.animateScrollToPage(page)
}

/**
 * Spread mode: pager items are page PAIRS. Logical page indices stay the
 * public currency ([PdfViewState.scrollToPage] etc.); this adapter maps them
 * to spread items ("current" reports the spread's first page in reading
 * order), so nextPage()/previousPage() remain plain index +1/-1 and the
 * visible spread advances every second step.
 */
internal class SpreadScrollAdapter(private val pagerState: PagerState) : PdfScrollAdapter {
    /**
     * The last logically-requested page. Within one spread, +1 must actually
     * advance (0 -> 1 stays on spread 0, the next +1 reaches spread 1), so
     * the adapter remembers it; a user swipe onto another spread supersedes
     * it and "current" snaps back to that spread's first page.
     */
    private var logical = pagerState.currentPage * 2

    override val currentPage: Int
        get() = if (logical / 2 == pagerState.currentPage) logical else pagerState.currentPage * 2

    override suspend fun scrollToPage(page: Int) {
        logical = page
        pagerState.scrollToPage(page / 2)
    }

    override suspend fun animateScrollToPage(page: Int) {
        logical = page
        pagerState.animateScrollToPage(page / 2)
    }
}

/** Single-page mode: no scrolling at all. */
internal class FixedPageAdapter(private val pageIndex: Int) : PdfScrollAdapter {
    override val currentPage: Int get() = pageIndex
    override suspend fun scrollToPage(page: Int) = Unit
    override suspend fun animateScrollToPage(page: Int) = Unit
}
