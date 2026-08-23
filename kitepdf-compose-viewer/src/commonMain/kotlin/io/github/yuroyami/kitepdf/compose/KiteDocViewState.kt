package io.github.yuroyami.kitepdf.compose

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntSize
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteSearchHit
import io.github.yuroyami.kitepdf.core.KiteStructuredText
import kotlin.math.abs

/**
 * Remembers a [KiteDocViewState] for [document]. Hoist it to drive a
 * [KiteDocView] from anywhere: navigation buttons in your top bar, a zoom
 * slider, a HUD overlay. The state object is the single point of control.
 *
 * Takes any [KiteDocument], so a [io.github.yuroyami.kitepdf.PdfDocument] and
 * an [io.github.yuroyami.kitepdf.epub.EpubDocument] both go here; one viewer
 * path serves both formats.
 */
@Composable
public fun rememberKiteDocViewState(document: KiteDocument, initialPage: Int = 0): KiteDocViewState =
    remember(document) { KiteDocViewState(document, initialPage) }

/**
 * Observable state + control surface of a [KiteDocView].
 *
 * Everything a navigation/zoom widget needs lives here, so widgets are just
 * composables that take a [KiteDocViewState]. Place them inside the viewport
 * (via [KiteDocView]'s `overlay` slot), next to it, or anywhere else in your tree.
 *
 * Reads ([currentPage], [zoom], [panOffset]…) are snapshot-state backed and
 * recompose their readers automatically. Navigation suspends until finished;
 * calls made before the state is attached to a composed [KiteDocView] are
 * remembered and applied on attach.
 */
@Stable
public class KiteDocViewState(
    public val document: KiteDocument,
    initialPage: Int = 0,
) {
    public val pageCount: Int get() = document.pageCount

    /** Current zoom factor. 1 = fit. Bounded by [KiteZoomSpec.minZoom]/[maxZoom]. */
    public var zoom: Float by mutableFloatStateOf(1f)
        private set

    /** Pan translation in viewport px, applied after [zoom] around the viewport centre. */
    public var panOffset: Offset by mutableStateOf(Offset.Zero)
        internal set

    /** True once zoomed in beyond the minimum (with a small epsilon). */
    public val isZoomed: Boolean get() = zoom > zoomRange.start + EPSILON

    /**
     * Search hits to paint as translucent quads over their pages (colour:
     * [KiteDocViewColors.searchHighlight]). Feed it from `PdfDocument.search`,
     * `EpubDocument.search` or `KiteStructuredText.search` (quads are display-space, as both produce);
     * clear it by assigning an empty list.
     *
     * Every hit here paints in the same colour. For marks that each carry their
     * own colour, or that want a marker in the page margin, use [highlights]
     * instead. Both channels paint, [searchHighlights] first.
     */
    public var searchHighlights: List<KiteSearchHit> by mutableStateOf(emptyList())

    /**
     * App-owned highlights, each with its own fill colour and its own optional
     * margin marker. Painted over the page after [searchHighlights], in list
     * order, so later entries win where they overlap. Clear by assigning an
     * empty list.
     *
     * ```kotlin
     * state.highlights = notes.map { note ->
     *     KiteHighlight(
     *         hit = KiteSearchHit(note.pageIndex, note.quads, note.text),
     *         color = note.category.tint,
     *         edgeMarker = true,
     *     )
     * }
     * ```
     *
     * See [KiteHighlight] for the per-entry knobs.
     */
    public var highlights: List<KiteHighlight> by mutableStateOf(emptyList())

    /**
     * The page the viewport currently rests on: the snapped page in paged
     * mode, the page nearest the viewport centre in continuous mode.
     */
    public val currentPage: Int
        get() = adapter?.currentPage ?: pendingPage

    /* ── internal wiring (set by KiteDocView during composition) ─────────────── */

    internal var adapter: KiteScrollAdapter? by mutableStateOf(null)
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
     * The page-bitmap LRU: outlives individual page composables, dies
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
     * Sets [zoom] immediately, clamped to the active [KiteZoomSpec] range.
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
     * Returns the portion actually consumed. The gesture layer hands the
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

    /* ── text selection ────────────────────────────────────────────── */

    /**
     * The active text selection, or null. Set by the long-press-drag gesture;
     * observe via snapshot reads or [onSelectionChange]. The viewer never
     * touches the clipboard itself. Read [KiteTextSelection.text] and copy in
     * the app (see the sample's selection actions).
     */
    public var selection: KiteTextSelection? by mutableStateOf(null)
        private set

    /**
     * True while text selection owns the pointer, and while the selection it
     * produced is still on screen.
     *
     * [KiteDocView] yields to it: one-finger pan and the list/pager's own scrolling
     * are both suppressed while this is set, so a drag that began as a
     * selection never slides the page out from under the finger, and the page
     * stays put afterwards while the user acts on the selected text.
     * Two-finger pinch zoom is unaffected.
     *
     * It goes true the instant the long press fires, which is BEFORE
     * [selection] exists (the hit test and the text extraction still have to
     * run), and it stays true after the finger lifts. [clearSelection] turns it
     * off, and so does a long press that never anchored anything (an empty page
     * region), which releases the lock when that drag ends.
     */
    public var isSelectionActive: Boolean by mutableStateOf(false)
        private set

    /**
     * True only while the finger is still down on the long-press drag that is
     * building the selection; false the moment it lifts.
     *
     * This is the flag a selection menu should gate on. [isSelectionActive]
     * deliberately stays true after the finger lifts (it keeps the page from
     * drifting while the user acts on the words), so it cannot distinguish
     * "still choosing" from "chosen". A popup shown while this is true covers
     * the very words the finger is trying to reach; [KiteSelectionMenu] waits
     * for it to drop.
     */
    public var selectionInProgress: Boolean by mutableStateOf(false)
        private set

    /** Fires on every selection change, including clearing (null). */
    public var onSelectionChange: ((KiteTextSelection?) -> Unit)? = null

    private var selectionAllowed by mutableStateOf(true)

    /**
     * Whether text selection is offered at all, set from [KiteDocView]'s
     * `selectionEnabled`.
     *
     * Off is a hard off: the long-press gesture is not attached in the first
     * place, and the entry points below refuse as well, so nothing can produce
     * a selection. Switching it off drops a selection already on screen, which
     * also hands back the pan and scroll locks that selection was holding.
     */
    internal var selectionEnabled: Boolean
        get() = selectionAllowed
        set(value) {
            if (selectionAllowed == value) return
            selectionAllowed = value
            if (!value) clearSelection()
        }

    /** The fixed anchor (page, flattened char index) of an active drag. */
    private var selectionAnchor: Pair<Int, Int>? = null

    public fun clearSelection() {
        selectionAnchor = null
        isSelectionActive = false
        selectionInProgress = false
        if (selection != null) {
            selection = null
            onSelectionChange?.invoke(null)
        }
    }

    /** Long-press: anchor the selection at the char under [viewportOffset]. */
    internal fun beginSelection(viewportOffset: Offset) {
        if (!selectionEnabled) return
        selectionAnchor = null
        // Claim the gesture up front. The hit test below can fail, and even a
        // successful one only produces `selection` a few statements later; pan
        // has to be off for the whole drag, not from whenever the model catches
        // up. `endSelectionGesture` hands the lock back if nothing anchored.
        isSelectionActive = true
        selectionInProgress = true
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

    /**
     * The long-press drag ended or was cancelled. A gesture that selected
     * something keeps [isSelectionActive] until [clearSelection]: the page must
     * not drift while the user reaches for a copy button. A gesture that
     * anchored nothing (long press on a margin, or on a page with no text
     * layer) gives pan and scrolling straight back.
     */
    internal fun endSelectionGesture() {
        selectionInProgress = false
        if (selection == null) isSelectionActive = false
    }

    /**
     * A finished selection's [edge] thumb was grabbed: that end now follows the
     * finger and the OTHER end becomes the fixed anchor.
     *
     * From here the drag is the long-press drag, character for character:
     * [extendSelection] keeps running the hit test, and because it orders the
     * two indices, hauling one thumb past the other swaps the ends instead of
     * collapsing the selection. A no-op when nothing is selected.
     */
    internal fun beginHandleDrag(edge: KiteSelectionHandleEdge) {
        if (!selectionEnabled) return
        val sel = selection ?: return
        selectionAnchor = sel.pageIndex to if (edge == KiteSelectionHandleEdge.Start) sel.end else sel.start
        isSelectionActive = true
        selectionInProgress = true
    }

    /**
     * Where the [edge] thumb of the active selection sits, in viewport pixels,
     * or null when nothing is selected (or the page has no geometry yet).
     *
     * The point is on the boundary line itself, at mid-height, rather than on
     * whatever a [KiteSelectionHandlePainter] drew around it. That keeps the
     * grab target the same for every painter, and it doubles as the text
     * position a drag maps back to, so grabbing a thumb never nudges the
     * selection by itself.
     */
    internal fun handlePoint(edge: KiteSelectionHandleEdge): Offset? {
        val sel = selection ?: return null
        val quad = (if (edge == KiteSelectionHandleEdge.Start) sel.quads.firstOrNull() else sel.quads.lastOrNull())
            ?: return null
        val x = if (edge == KiteSelectionHandleEdge.Start) quad.left else quad.right
        return displayToViewport(sel.pageIndex, x, (quad.bottom + quad.top) / 2.0)
    }

    /**
     * Which thumb a press at [viewportOffset] grabs, or null for a press that
     * misses both by more than [radiusPx]. The nearer thumb wins a tie, which
     * matters on a one-word selection where the two overlap.
     */
    internal fun handleAt(viewportOffset: Offset, radiusPx: Float): KiteSelectionHandleEdge? {
        var best: KiteSelectionHandleEdge? = null
        var bestDistance = radiusPx
        for (edge in KiteSelectionHandleEdge.entries) {
            val point = handlePoint(edge) ?: continue
            val distance = (point - viewportOffset).getDistance()
            if (distance <= bestDistance) {
                bestDistance = distance
                best = edge
            }
        }
        return best
    }

    private fun applySelection(text: KiteStructuredText, page: Int, start: Int, end: Int) {
        val sel = KiteTextSelection(
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
     * [hitTestDisplay] run backwards: a display-space point on [pageIndex] to
     * the viewport pixel it is painted at. Null when that page has no laid-out
     * geometry.
     */
    internal fun displayToViewport(pageIndex: Int, x: Double, y: Double): Offset? {
        if (viewportSize == IntSize.Zero || zoom <= 0f) return null
        val rect = pageGeometry[pageIndex] ?: return null
        val page = document.pages.getOrNull(pageIndex) ?: return null
        if (rect.width <= 0f || rect.height <= 0f || page.displayWidth <= 0.0 || page.displayHeight <= 0.0) return null
        val content = Offset(
            rect.left + (x / page.displayWidth).toFloat() * rect.width,
            rect.top + (y / page.displayHeight).toFloat() * rect.height,
        )
        val centre = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        return centre + (content - centre) * zoom + panOffset
    }

    /**
     * Like [hitTest] but stops in DISPLAY space (y-down points, the space
     * [io.github.yuroyami.kitepdf.core.KiteStructuredText] geometry lives in).
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
     * centre, then translates by [panOffset], the same math [setZoom]'s focal
     * logic composes), locates the page slot from the geometry the layout
     * reported, then maps display-space points through the inverse of
     * [KitePage.displayToDeviceBase] into page space: PDF pages get user
     * space (y-up, rotation unfolded), EPUB pages their document space.
     */
    public fun hitTest(viewportOffset: Offset): KitePageHit? {
        val (index, devX, devY) = hitTestDisplay(viewportOffset) ?: return null
        val page = document.pages.getOrNull(index) ?: return null
        val inv = page.displayToDeviceBase().invert() ?: return null
        val (x, y) = inv.transformPoint(devX, devY)
        return KitePageHit(index, x, y)
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
 * A [KiteDocViewState.hitTest] result: the page under a viewport point and the
 * point in that page's own space (PDF: user space, y-up from the display
 * box's bottom-left with rotation unfolded; EPUB: the page's document space).
 */
public data class KitePageHit(
    val pageIndex: Int,
    val x: Double,
    val y: Double,
)

/**
 * A finalized or in-progress text selection on one page (cross-page
 * selection is out of scope). [start]/[end] are INCLUSIVE flattened char
 * indices into the page's [io.github.yuroyami.kitepdf.core.KiteStructuredText]
 * reading order; [text] carries `\n`/`\n\n` line/block separators exactly
 * like the extraction text; [quads] are display-space, one per line touched.
 */
public data class KiteTextSelection(
    val pageIndex: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val quads: List<io.github.yuroyami.kitepdf.core.KiteRectangle>,
)

/**
 * One entry of [KiteDocViewState.highlights]: where to paint ([hit]) plus how to
 * paint it.
 *
 * [KiteSearchHit] stays a pure text-search result, with no idea colours exist;
 * this wraps one with the viewer's paint choices. Build the hit yourself from
 * quads you already hold, or take it straight out of `PdfDocument.search`,
 * `EpubDocument.search` or `KiteStructuredText.search`.
 *
 * @param hit the page index and the display-space quads to cover.
 * @param color fill for those quads. Null (the default) paints
 *   [KiteDocViewColors.searchHighlight], exactly what [KiteDocViewState.searchHighlights]
 *   does, so wrapping a plain hit changes nothing on screen.
 * @param edgeMarker also paint a small rounded marker in one page margin,
 *   level with this highlight. It tells a reader a note lives on this
 *   page without them having to find the highlighted words. Every dimension is
 *   a fraction of the rendered page width, so it keeps its proportions in a
 *   thumbnail and at deep zoom alike, and its inner edge is clamped past the
 *   highlighted quads so it never paints over the words. On a page whose text
 *   reaches into that margin, leaving no room, nothing is drawn.
 * @param edgeMarkerColor fill for that marker. Null (the default) falls back to
 *   [color], and then to [KiteDocViewColors.searchHighlight]. A marker usually
 *   wants a stronger, opaque colour than the translucent fill next to it.
 * @param edgeMarkerSide which margin carries the marker. [KiteMarkerSide.End]
 *   is the pre-0.5.1 behaviour and stays the default.
 */
@Immutable
public data class KiteHighlight(
    val hit: KiteSearchHit,
    val color: Color? = null,
    val edgeMarker: Boolean = false,
    val edgeMarkerColor: Color? = null,
    val edgeMarkerSide: KiteMarkerSide = KiteMarkerSide.End,
)

/**
 * Which page margin an edge marker is painted in.
 *
 * Named Start/End rather than Left/Right deliberately: these are the page's
 * margins in reading order, and a host that lays out RTL books can map its own
 * notion of "outer margin" onto them without this enum lying about geometry.
 * In the viewer's display space Start is the left margin and End is the right.
 */
public enum class KiteMarkerSide { Start, End }

/* ── scroll adapters: one state API over LazyList and Pager backends ──────── */

internal interface KiteScrollAdapter {
    val currentPage: Int
    suspend fun scrollToPage(page: Int)
    suspend fun animateScrollToPage(page: Int)
}

/** Continuous mode: "current" = the visible item whose centre is nearest the viewport centre. */
internal class LazyListScrollAdapter(private val listState: LazyListState) : KiteScrollAdapter {
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

internal class PagerScrollAdapter(private val pagerState: PagerState) : KiteScrollAdapter {
    override val currentPage: Int get() = pagerState.currentPage
    override suspend fun scrollToPage(page: Int) = pagerState.scrollToPage(page)
    override suspend fun animateScrollToPage(page: Int) = pagerState.animateScrollToPage(page)
}

/**
 * Spread mode: pager items are page PAIRS. Logical page indices stay the
 * public currency ([KiteDocViewState.scrollToPage] etc.); this adapter maps them
 * to spread items ("current" reports the spread's first page in reading
 * order), so nextPage()/previousPage() remain plain index +1/-1 and the
 * visible spread advances every second step.
 */
internal class SpreadScrollAdapter(private val pagerState: PagerState) : KiteScrollAdapter {
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
internal class FixedPageAdapter(private val pageIndex: Int) : KiteScrollAdapter {
    override val currentPage: Int get() = pageIndex
    override suspend fun scrollToPage(page: Int) = Unit
    override suspend fun animateScrollToPage(page: Int) = Unit
}
