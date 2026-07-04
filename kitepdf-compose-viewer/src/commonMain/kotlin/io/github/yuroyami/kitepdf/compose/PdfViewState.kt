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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.unit.IntSize
import io.github.yuroyami.kitepdf.KiteDocument
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.math.abs

/**
 * Remembers a [PdfViewState] for [document]. Hoist it to drive a [PdfView]
 * from anywhere — navigation buttons in your top bar, a zoom slider, a HUD
 * overlay — the state object is the single point of control.
 */
@Composable
fun rememberPdfViewState(document: PdfDocument, initialPage: Int = 0): PdfViewState =
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
class PdfViewState(
    val document: KiteDocument,
    initialPage: Int = 0,
) {
    val pageCount: Int get() = document.pageCount

    /** Current zoom factor. 1 = fit. Bounded by [PdfZoomSpec.minZoom]/[maxZoom]. */
    var zoom: Float by mutableFloatStateOf(1f)
        private set

    /** Pan translation in viewport px, applied after [zoom] around the viewport centre. */
    var panOffset: Offset by mutableStateOf(Offset.Zero)
        internal set

    /** True once zoomed in beyond the minimum (with a small epsilon). */
    val isZoomed: Boolean get() = zoom > zoomRange.start + EPSILON

    /**
     * The page the viewport currently rests on: the snapped page in paged
     * mode, the page nearest the viewport centre in continuous mode.
     */
    val currentPage: Int
        get() = adapter?.currentPage ?: pendingPage

    /* ── internal wiring (set by PdfView during composition) ─────────────── */

    internal var adapter: PdfScrollAdapter? by mutableStateOf(null)
    internal var pendingPage: Int = initialPage.coerceAtLeast(0)
    internal var zoomRange: ClosedFloatingPointRange<Float> by mutableStateOf(1f..8f)
    internal var viewportSize: IntSize by mutableStateOf(IntSize.Zero)

    /** Pan axes the active layout allows (continuous mode keeps its scroll axis native). */
    internal var panAxes: PanAxes = PanAxes.Both

    /* ── zoom ─────────────────────────────────────────────────────────────── */

    /**
     * Sets [zoom] immediately, clamped to the active [PdfZoomSpec] range.
     *
     * @param focal viewport-space point to keep visually stationary (e.g. the
     *   pinch centroid or double-tap position). Unspecified = viewport centre.
     */
    fun setZoom(zoom: Float, focal: Offset = Offset.Unspecified) {
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
    suspend fun animateZoomTo(
        target: Float,
        focal: Offset = Offset.Unspecified,
        animationSpec: AnimationSpec<Float> = spring(),
    ) {
        val clamped = target.coerceIn(zoomRange.start, zoomRange.endInclusive)
        animate(zoom, clamped, animationSpec = animationSpec) { value, _ -> setZoom(value, focal) }
    }

    /** Snaps back to the minimum zoom and recentres. */
    fun resetZoom() {
        zoom = zoomRange.start
        panOffset = Offset.Zero
    }

    /**
     * Pans by [delta] (viewport px), clamped to the zoomed content bounds.
     * Returns the portion actually consumed — the gesture layer hands the
     * remainder back to the underlying scroll container.
     */
    fun panBy(delta: Offset): Offset {
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

    /* ── navigation ───────────────────────────────────────────────────────── */

    /** Jumps to [page] (coerced into range) without animation. */
    suspend fun scrollToPage(page: Int) {
        val target = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        pendingPage = target
        adapter?.scrollToPage(target)
    }

    /** Animates to [page] (coerced into range). */
    suspend fun animateScrollToPage(page: Int) {
        val target = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        pendingPage = target
        adapter?.animateScrollToPage(target)
    }

    suspend fun nextPage() = animateScrollToPage(currentPage + 1)

    suspend fun previousPage() = animateScrollToPage(currentPage - 1)

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

/** Single-page mode: no scrolling at all. */
internal class FixedPageAdapter(private val pageIndex: Int) : PdfScrollAdapter {
    override val currentPage: Int get() = pageIndex
    override suspend fun scrollToPage(page: Int) = Unit
    override suspend fun animateScrollToPage(page: Int) = Unit
}
