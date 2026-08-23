package io.github.yuroyami.kitepdf.compose

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfAnnotation
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPage
import io.github.yuroyami.kitepdf.core.render.ReaderTheme
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * THE KitePDF viewer composable. It draws a [KiteDocument], so a PDF and an
 * EPUB go through the same code path, the same gestures and the same widgets.
 *
 * ```kotlin
 * // Simple: whole document, vertical continuous scroll.
 * KiteDocView(rememberKiteDocViewState(doc), Modifier.fillMaxSize())
 *
 * // Full control: horizontal pager, custom zoom, HUD overlay.
 * val state = rememberKiteDocViewState(doc)
 * KiteDocView(
 *     state = state,
 *     layout = KiteDocLayout.Paged(Orientation.Horizontal),
 *     zoomSpec = KiteZoomSpec(maxZoom = 6f),
 *     overlay = { s ->
 *         KiteNavigationControls(s, Modifier.align(Alignment.BottomCenter))
 *     },
 * )
 * // …and the same state drives widgets OUTSIDE the viewport too:
 * KitePageIndicator(state)
 * ```
 *
 * By default ([KiteRenderSpec.Rasterized]) pages are vector-rendered once into an
 * [ImageBitmap] per (page, size, zoom bucket) and then drawn as plain images, so
 * scrolling, panning and pinching never redraw the page itself.
 * Switch to [KiteRenderSpec.Vectorized] for resolution-independent, bitmap-free
 * drawing. See [KiteRenderSpec] for the per-mode knobs and [KiteZoomSpec] for
 * gestures.
 *
 * @param state the hoisted control surface. See [rememberKiteDocViewState].
 * @param layout continuous strip (any orientation), snap pager (any
 *   orientation) or a single fixed page. See [KiteDocLayout].
 * @param zoomSpec pinch/double-tap/pan behaviour and zoom bounds. Programmatic
 *   zoom through [KiteDocViewState.setZoom] honours the same bounds, so external
 *   controls (sliders, loupes) work with gestures fully disabled.
 * @param renderSpec how pages become pixels: [KiteRenderSpec.Rasterized]
 *   (bitmap-cached, with quality/memory/crisp-zoom/hairline knobs) or
 *   [KiteRenderSpec.Vectorized] (live vector draw). See [KiteRenderSpec].
 * @param colors page paper + viewport letterbox colours.
 * @param pageSpacing gap between pages (continuous gutter / pager spacing).
 * @param selectionEnabled whether the reader may select text at all. The
 *   default, true, is the long-press-to-select behaviour with draggable
 *   thumbs. False removes the gesture entirely: no long press selects, no wash
 *   is painted, no thumbs appear, and a selection already on screen is dropped.
 *   Turn it off for a document shown as a picture, a chart, a scan, a trace,
 *   where a text selection means nothing and a stray long press only gets in
 *   the way of panning.
 * @param userScrollEnabled gesture scrolling/swiping of the layout itself.
 *   Disable to drive paging exclusively through [KiteDocViewState] (nav buttons).
 * @param onPageRendered fires whenever a page finishes a FRESH rasterization,
 *   with the bitmap ready for export, e.g. via [encodeToPng]. Cache hits from
 *   the page-bitmap LRU do not re-fire it.
 * @param pagePlaceholder shown in a page's slot until its raster is ready.
 *   Defaults to a plain [KiteDocViewColors.pageBackground] box.
 * @param overlay HUD layer drawn over the viewport; receives [state] and a
 *   [BoxScope] for alignment. Widgets here float above the pages:
 *   [KiteNavigationControls], [KitePageIndicator], [KiteThumbnailStrip] or
 *   anything of your own.
 * @param onTap single-tap on the page, reported with the tap position. The tap
 *   does not consume pan/swipe, so it coexists with navigation. Typical use is
 *   toggling a HUD's visibility. Held back until the double-tap window lapses
 *   only when [KiteZoomSpec.doubleTapEnabled] is on. Taps that land on a link
 *   navigate (or go to [onLinkTap]) instead of reaching this callback.
 * @param onLinkTap fires when a tapped link carries something the viewer can't
 *   perform itself: a URL, a remote GoTo, a Launch. Return true after handling
 *   it (e.g. opening the URL in a browser); false lets the tap fall through to
 *   [onTap]. Internal go-to-page links (PDF destinations, EPUB internal hrefs)
 *   never reach this: the viewer scrolls to the target page directly. See
 *   [KiteLinkAction] for the payload; `link.uri` covers both formats.
 */
@Composable
public fun KiteDocView(
    state: KiteDocViewState,
    modifier: Modifier = Modifier,
    layout: KiteDocLayout = KiteDocLayout.Default,
    zoomSpec: KiteZoomSpec = KiteZoomSpec(),
    renderSpec: KiteRenderSpec = KiteRenderSpec.Default,
    colors: KiteDocViewColors = KiteDocViewColors(),
    pageSpacing: Dp = 8.dp,
    userScrollEnabled: Boolean = true,
    selectionEnabled: Boolean = true,
    onPageRendered: ((pageIndex: Int, image: ImageBitmap) -> Unit)? = null,
    pagePlaceholder: (@Composable (pageIndex: Int) -> Unit)? = null,
    overlay: (@Composable BoxScope.(KiteDocViewState) -> Unit)? = null,
    onTap: ((Offset) -> Unit)? = null,
    onLinkTap: ((KiteLinkAction) -> Boolean)? = null,
) {
    SideEffect {
        state.selectionEnabled = selectionEnabled
        state.zoomRange = zoomSpec.minZoom..zoomSpec.maxZoom
        state.panAxes = when (layout) {
            is KiteDocLayout.Continuous -> when (layout.orientation) {
                Orientation.Vertical -> KiteDocViewState.PanAxes.XOnly
                Orientation.Horizontal -> KiteDocViewState.PanAxes.YOnly
            }
            else -> KiteDocViewState.PanAxes.Both
        }
        if (state.zoom !in state.zoomRange) state.setZoom(state.zoom) // re-clamp on spec change
    }

    // Crisp zoom: the raster resolution follows the zoom level, but only after
    // the gesture settles, GPU-scaling the existing bitmap in between. Only the
    // rasterized path re-renders on settle; vector draws are resolution-free.
    val rerasterizeOnZoom = (renderSpec as? KiteRenderSpec.Rasterized)?.rerasterizeOnZoom == true
    val settledZoom by produceState(1f, state, rerasterizeOnZoom) {
        if (!rerasterizeOnZoom) {
            value = 1f
            return@produceState
        }
        snapshotFlow { state.zoom }.collectLatest { z ->
            delay(ZOOM_SETTLE_DEBOUNCE_MS)
            value = z
        }
    }

    // Route taps through link hit-testing first: a tap on a link
    // navigates (or defers to onLinkTap); anything else reaches user onTap.
    val tapScope = rememberCoroutineScope()
    val linkAwareTap: (Offset) -> Unit = { offset ->
        state.clearSelection() // tap anywhere dismisses an active selection
        if (!handleLinkTap(state, tapScope, onLinkTap, offset)) onTap?.invoke(offset)
    }

    Box(
        modifier
            .background(colors.viewportBackground)
            .clipToBounds()
            .onSizeChanged { state.viewportSize = it },
    ) {
        if (state.pageCount > 0) {
            when (layout) {
                is KiteDocLayout.Continuous -> ContinuousLayout(
                    state, layout, zoomSpec, renderSpec, colors, pageSpacing,
                    userScrollEnabled, settledZoom, onPageRendered, pagePlaceholder, linkAwareTap,
                )
                is KiteDocLayout.Paged -> PagedLayout(
                    state, layout, zoomSpec, renderSpec, colors, pageSpacing,
                    userScrollEnabled, settledZoom, onPageRendered, pagePlaceholder, linkAwareTap,
                )
                is KiteDocLayout.Spread -> SpreadLayout(
                    state, layout, zoomSpec, renderSpec, colors, pageSpacing,
                    userScrollEnabled, settledZoom, onPageRendered, pagePlaceholder, linkAwareTap,
                )
                is KiteDocLayout.SinglePage -> SinglePageLayout(
                    state, layout, zoomSpec, renderSpec, colors,
                    settledZoom, onPageRendered, pagePlaceholder, linkAwareTap,
                )
            }
        }
        overlay?.invoke(this, state)
    }
}

/**
 * Consumes a tap that lands on a link: PDF pages hit-test their Link
 * annotations (topmost drawn last, so scanned in reverse) in user space;
 * EPUB pages hit-test [EpubPage.links] in display space. In-document
 * targets animate to the target page; everything else is offered to
 * [onLinkTap]. Returns true when the tap was consumed.
 */
internal fun handleLinkTap(
    state: KiteDocViewState,
    scope: kotlinx.coroutines.CoroutineScope,
    onLinkTap: ((KiteLinkAction) -> Boolean)?,
    offset: Offset,
): Boolean {
    val hit = state.hitTest(offset) ?: return false
    when (val page = state.document.pages.getOrNull(hit.pageIndex)) {
        is PdfPage -> {
            val doc = state.document as? PdfDocument ?: return false
            for (ann in page.annotations.asReversed()) {
                if (ann.subtype != PdfAnnotation.Subtype.Link || ann.isHidden) continue
                val r = ann.rect
                if (hit.x < r.left || hit.x > r.right || hit.y < r.bottom || hit.y > r.top) continue
                val rawDest = ann.rawDestination
                    ?: (ann.action as? PdfAction.GoTo)?.destination
                val target = doc.resolveDestination(rawDest)?.pageIndex
                if (target != null) {
                    scope.launch { state.animateScrollToPage(target) }
                    return true
                }
                val action = ann.action
                    ?: ann.uri?.let { PdfAction.Uri(it, isMap = false, raw = io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap())) }
                    ?: return false
                return onLinkTap?.invoke(KiteLinkAction.Pdf(action)) == true
            }
            return false
        }
        is EpubPage -> {
            // hitTest maps through the EPUB flip, so hit.y is y-up; links are
            // y-down display rects. Flip back.
            val dy = page.displayHeight - hit.y
            for (link in page.links.asReversed()) {
                val r = link.rect
                if (hit.x < r.left || hit.x > r.right || dy < r.bottom || dy > r.top) continue
                if (SCHEME_REGEX.containsMatchIn(link.href)) {
                    return onLinkTap?.invoke(KiteLinkAction.Uri(link.href)) == true
                }
                val target = (state.document as? EpubDocument)?.pageOf(link.href) ?: return false
                scope.launch { state.animateScrollToPage(target) }
                return true
            }
            return false
        }
        else -> return false
    }
}

private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

/**
 * Convenience entry point: remembers its own state internally. Takes any
 * [KiteDocument], so a [PdfDocument] and an
 * [io.github.yuroyami.kitepdf.epub.EpubDocument] both go here.
 *
 * ```kotlin
 * KiteDocView(document = doc, modifier = Modifier.fillMaxSize())          // whole document
 * KiteDocView(document = doc, page = 0, modifier = Modifier.fillMaxWidth()) // one page
 * ```
 *
 * @param page index of the single page to show, or `null` (default) for the
 *   whole document as a continuous vertical scroll.
 * @param background colour painted behind page content. Ignored when [theme]
 *   is set (the theme owns the paper colour).
 * @param theme optional reading theme: [ReaderTheme.Dark] for night mode,
 *   [ReaderTheme.Sepia], or [ReaderTheme.Light]/null for the author's colours.
 *   Applied at render, so switching is instant (no re-layout).
 * @param selectionEnabled whether the reader may select text. See the
 *   state-based [KiteDocView] for what turning it off removes.
 */
@Composable
public fun KiteDocView(
    document: KiteDocument,
    modifier: Modifier = Modifier,
    page: Int? = null,
    background: Color = Color.White,
    theme: ReaderTheme? = null,
    pageSpacing: Dp = 8.dp,
    selectionEnabled: Boolean = true,
    onPageRendered: ((pageIndex: Int, image: ImageBitmap) -> Unit)? = null,
    onTap: ((Offset) -> Unit)? = null,
    onLinkTap: ((KiteLinkAction) -> Boolean)? = null,
) {
    require(page == null || page in 0 until document.pageCount) {
        "page $page is out of bounds (document has ${document.pageCount} page(s))"
    }
    KiteDocView(
        state = rememberKiteDocViewState(document),
        modifier = modifier,
        layout = if (page != null) KiteDocLayout.SinglePage(page) else KiteDocLayout.Continuous(),
        colors = KiteDocViewColors(pageBackground = background, theme = theme),
        pageSpacing = pageSpacing,
        selectionEnabled = selectionEnabled,
        onPageRendered = onPageRendered,
        onTap = onTap,
        onLinkTap = onLinkTap,
    )
}

/* ── continuous strip ─────────────────────────────────────────────────────── */

@Composable
private fun ContinuousLayout(
    state: KiteDocViewState,
    layout: KiteDocLayout.Continuous,
    zoomSpec: KiteZoomSpec,
    renderSpec: KiteRenderSpec,
    colors: KiteDocViewColors,
    pageSpacing: Dp,
    userScrollEnabled: Boolean,
    settledZoom: Float,
    onPageRendered: ((Int, ImageBitmap) -> Unit)?,
    pagePlaceholder: (@Composable (Int) -> Unit)?,
    onTap: ((Offset) -> Unit)?,
) {
    // Seed from currentPage, not pendingPage: this runs during composition,
    // but the outgoing layout only publishes its farewell position from
    // onDispose (the apply phase, strictly later), so pendingPage here is
    // always one layout switch stale. currentPage reads the still-attached
    // outgoing adapter live and falls back to pendingPage on first composition.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.currentPage.coerceIn(0, state.pageCount - 1),
    )
    DisposableEffect(state, listState) {
        val adapter = LazyListScrollAdapter(listState)
        state.adapter = adapter
        onDispose {
            state.pendingPage = adapter.currentPage
            if (state.adapter === adapter) state.adapter = null
        }
    }

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // Magnifier-style zoom: scale the whole strip around the viewport centre.
    // The scroll axis stays native (the list keeps scrolling while zoomed);
    // pan covers the cross axis only. Gestures sit OUTSIDE the layer so they
    // see untransformed viewport coordinates.
    Box(
        Modifier
            .fillMaxSize()
            .kiteTransformGestures(state, zoomSpec, scope, onTap)
            .kiteSelectionGestures(state, haptics)
            .graphicsLayer {
                scaleX = state.zoom
                scaleY = state.zoom
                translationX = state.panOffset.x
                translationY = state.panOffset.y
            },
    ) {
        val pageItem: @Composable androidx.compose.foundation.lazy.LazyItemScope.(Int) -> Unit = { index ->
            ContinuousPageItem(
                state = state,
                page = state.document.pages[index],
                pageIndex = index,
                orientation = layout.orientation,
                settledZoom = settledZoom,
                renderSpec = renderSpec,
                colors = colors,
                onPageRendered = onPageRendered,
                pagePlaceholder = pagePlaceholder,
            )
        }
        // The list is the untransformed-space anchor page slots measure their
        // hit-test geometry against (it sits inside the layer, so its
        // coordinates never see zoom/pan).
        val anchored = Modifier.fillMaxSize().onGloballyPositioned { state.contentCoordinates = it }
        // The strip's own scrolling yields to a text selection, the same way
        // the pan gesture does: a selection drag must not scroll the page out
        // from under itself, and the page has to stay put while the user acts
        // on the selected text.
        val listScrollEnabled = userScrollEnabled && !state.isSelectionActive
        when (layout.orientation) {
            Orientation.Vertical -> LazyColumn(
                modifier = anchored,
                state = listState,
                verticalArrangement = Arrangement.spacedBy(pageSpacing),
                userScrollEnabled = listScrollEnabled,
            ) {
                items(count = state.pageCount, key = { it }) { pageItem(it) }
            }
            Orientation.Horizontal -> LazyRow(
                modifier = anchored,
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(pageSpacing),
                userScrollEnabled = listScrollEnabled,
            ) {
                items(count = state.pageCount, key = { it }) { pageItem(it) }
            }
        }
    }
}

/** One page in the strip: fills the cross axis at its natural aspect ratio. */
@Composable
private fun androidx.compose.foundation.lazy.LazyItemScope.ContinuousPageItem(
    state: KiteDocViewState,
    page: KitePage,
    pageIndex: Int,
    orientation: Orientation,
    settledZoom: Float,
    renderSpec: KiteRenderSpec,
    colors: KiteDocViewColors,
    onPageRendered: ((Int, ImageBitmap) -> Unit)?,
    pagePlaceholder: (@Composable (Int) -> Unit)?,
) {
    val aspect = kitePageAspect(page)
    val sizing = when (orientation) {
        Orientation.Vertical -> Modifier.fillParentMaxWidth().aspectRatio(aspect)
        Orientation.Horizontal -> Modifier.fillParentMaxHeight().aspectRatio(aspect)
    }
    DisposableEffect(state, pageIndex) {
        onDispose { state.pageGeometry.remove(pageIndex) }
    }
    val reportGeometry = Modifier.onGloballyPositioned { coords ->
        val anchor = state.contentCoordinates?.takeIf { it.isAttached } ?: return@onGloballyPositioned
        state.pageGeometry[pageIndex] = anchor.localBoundingBoxOf(coords, clipBounds = false)
    }
    BoxWithConstraints(sizing.then(reportGeometry)) {
        val density = LocalDensity.current
        // fillParentMax* + aspectRatio normally give tight constraints; the
        // fallback covers unbounded hosts.
        val baseSize = when (orientation) {
            Orientation.Vertical -> {
                val w = if (constraints.hasBoundedWidth) constraints.maxWidth
                else with(density) { page.displayWidth.dp.roundToPx() }
                IntSize(w, (w / aspect).roundToInt().coerceAtLeast(1))
            }
            Orientation.Horizontal -> {
                val h = if (constraints.hasBoundedHeight) constraints.maxHeight
                else with(density) { page.displayHeight.dp.roundToPx() }
                IntSize((h * aspect).roundToInt().coerceAtLeast(1), h)
            }
        }
        val slot = Modifier.fillMaxSize()
            .highlightOverlay(state, page, pageIndex, colors)
        when (renderSpec) {
            is KiteRenderSpec.Rasterized -> KitePageRaster(
                page, pageIndex, baseSize, settledZoom, renderSpec, colors,
                onPageRendered, pagePlaceholder, slot,
                cache = state.bitmapCacheFor(renderSpec.cacheBudgetBytes),
            )
            is KiteRenderSpec.Vectorized -> KitePageVector(
                page, renderSpec, colors, slot,
            )
        }
    }
}

/* ── snap pager ───────────────────────────────────────────────────────────── */

@Composable
private fun PagedLayout(
    state: KiteDocViewState,
    layout: KiteDocLayout.Paged,
    zoomSpec: KiteZoomSpec,
    renderSpec: KiteRenderSpec,
    colors: KiteDocViewColors,
    pageSpacing: Dp,
    userScrollEnabled: Boolean,
    settledZoom: Float,
    onPageRendered: ((Int, ImageBitmap) -> Unit)?,
    pagePlaceholder: (@Composable (Int) -> Unit)?,
    onTap: ((Offset) -> Unit)?,
) {
    // currentPage, not pendingPage: see ContinuousLayout's seed comment.
    val pagerState = rememberPagerState(
        initialPage = state.currentPage.coerceIn(0, state.pageCount - 1),
    ) { state.pageCount }
    DisposableEffect(state, pagerState) {
        val adapter = PagerScrollAdapter(pagerState)
        state.adapter = adapter
        onDispose {
            state.pendingPage = adapter.currentPage
            if (state.adapter === adapter) state.adapter = null
        }
    }
    // Landing on another page recentres the pan (and, per spec, the zoom).
    LaunchedEffect(state, pagerState, zoomSpec.resetZoomOnPageChange) {
        snapshotFlow { pagerState.settledPage }.collect {
            state.panOffset = androidx.compose.ui.geometry.Offset.Zero
            if (zoomSpec.resetZoomOnPageChange) state.resetZoom()
        }
    }

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val pageContent: @Composable (Int) -> Unit = { index ->
        val isCurrent = index == pagerState.currentPage
        PageBox(
            page = state.document.pages[index],
            pageIndex = index,
            zoom = if (isCurrent) state.zoom else 1f,
            pan = if (isCurrent) state.panOffset else androidx.compose.ui.geometry.Offset.Zero,
            gestures = if (isCurrent) {
                Modifier.kiteTransformGestures(state, zoomSpec, scope, onTap).kiteSelectionGestures(state, haptics)
            } else Modifier,
            settledZoom = if (isCurrent) settledZoom else 1f,
            renderSpec = renderSpec,
            colors = colors,
            onPageRendered = onPageRendered,
            pagePlaceholder = pagePlaceholder,
            state = state,
            geometryInto = if (isCurrent) state else null,
        )
    }
    // While zoomed, the pager's own swipe is off so one-finger drags pan the
    // page; paging stays available through KiteDocViewState (nav widgets). An
    // active text selection takes the swipe away too, so the page cannot turn
    // under a selection drag.
    val pagerScrollEnabled = userScrollEnabled && !state.isZoomed && !state.isSelectionActive
    when (layout.orientation) {
        Orientation.Horizontal -> HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = pageSpacing,
            beyondViewportPageCount = layout.offscreenPages,
            userScrollEnabled = pagerScrollEnabled,
            reverseLayout = layout.reverseLayout,
        ) { pageContent(it) }
        Orientation.Vertical -> VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = pageSpacing,
            beyondViewportPageCount = layout.offscreenPages,
            userScrollEnabled = pagerScrollEnabled,
            reverseLayout = layout.reverseLayout,
        ) { pageContent(it) }
    }
}

/* ── single fixed page ────────────────────────────────────────────────────── */

@Composable
private fun SinglePageLayout(
    state: KiteDocViewState,
    layout: KiteDocLayout.SinglePage,
    zoomSpec: KiteZoomSpec,
    renderSpec: KiteRenderSpec,
    colors: KiteDocViewColors,
    settledZoom: Float,
    onPageRendered: ((Int, ImageBitmap) -> Unit)?,
    pagePlaceholder: (@Composable (Int) -> Unit)?,
    onTap: ((Offset) -> Unit)?,
) {
    require(layout.pageIndex in 0 until state.pageCount) {
        "page ${layout.pageIndex} is out of bounds (document has ${state.pageCount} page(s))"
    }
    DisposableEffect(state, layout.pageIndex) {
        val adapter = FixedPageAdapter(layout.pageIndex)
        state.adapter = adapter
        onDispose { if (state.adapter === adapter) state.adapter = null }
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    PageBox(
        page = state.document.pages[layout.pageIndex],
        pageIndex = layout.pageIndex,
        zoom = state.zoom,
        pan = state.panOffset,
        gestures = Modifier.kiteTransformGestures(state, zoomSpec, scope, onTap).kiteSelectionGestures(state, haptics),
        settledZoom = settledZoom,
        renderSpec = renderSpec,
        colors = colors,
        onPageRendered = onPageRendered,
        pagePlaceholder = pagePlaceholder,
        state = state,
        geometryInto = state,
    )
}

/* ── shared page slot (paged/single): letterbox fit + transform ───────────── */

@Composable
private fun PageBox(
    page: KitePage,
    pageIndex: Int,
    zoom: Float,
    pan: androidx.compose.ui.geometry.Offset,
    gestures: Modifier,
    settledZoom: Float,
    renderSpec: KiteRenderSpec,
    colors: KiteDocViewColors,
    onPageRendered: ((Int, ImageBitmap) -> Unit)?,
    pagePlaceholder: (@Composable (Int) -> Unit)?,
    /** The state whose search highlights this slot paints. */
    state: KiteDocViewState,
    /** The state to report hit-test geometry into (the on-screen slot only). */
    geometryInto: KiteDocViewState? = null,
) {
    if (geometryInto != null) {
        DisposableEffect(geometryInto, pageIndex) {
            onDispose { geometryInto.pageGeometry.remove(pageIndex) }
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(gestures)
            .graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = pan.x
                translationY = pan.y
            },
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val fit = fitWithin(constraints.maxWidth, constraints.maxHeight, kitePageAspect(page))
        if (geometryInto != null && fit != IntSize.Zero) {
            // Centered letterbox: the page rect in untransformed viewport
            // space follows directly from the constraints, no coordinates
            // walk needed (the layer above never affects it).
            val left = (constraints.maxWidth - fit.width) / 2f
            val top = (constraints.maxHeight - fit.height) / 2f
            val rect = Rect(left, top, left + fit.width, top + fit.height)
            SideEffect { geometryInto.pageGeometry[pageIndex] = rect }
        }
        if (fit != IntSize.Zero) {
            val dpSize = with(density) { DpSize(fit.width.toDp(), fit.height.toDp()) }
            val slot = Modifier.size(dpSize)
                .highlightOverlay(state, page, pageIndex, colors)
            when (renderSpec) {
                is KiteRenderSpec.Rasterized -> KitePageRaster(
                    page, pageIndex, fit, settledZoom, renderSpec, colors,
                    onPageRendered, pagePlaceholder, slot,
                    cache = state.bitmapCacheFor(renderSpec.cacheBudgetBytes),
                )
                is KiteRenderSpec.Vectorized -> KitePageVector(
                    page, renderSpec, colors, slot,
                )
            }
        }
    }
}

/* ── the raster slot: bitmap-once-per-bucket, placeholder while pending ───── */

/**
 * Draws [page] as a cached bitmap sized for [baseSize] (its on-screen px at
 * zoom 1) × the active raster scale. Re-rasterizes only when the bucket
 * (size, settled zoom, quality, colours) changes.
 */
@Composable
private fun KitePageRaster(
    page: KitePage,
    pageIndex: Int,
    baseSize: IntSize,
    settledZoom: Float,
    spec: KiteRenderSpec.Rasterized,
    colors: KiteDocViewColors,
    onPageRendered: ((Int, ImageBitmap) -> Unit)?,
    pagePlaceholder: (@Composable (Int) -> Unit)?,
    modifier: Modifier,
    /** The state-owned bitmap LRU; null renders uncached. */
    cache: PageBitmapCache? = null,
) {
    val rasterizer = rememberKitePageRasterizer()
    val onRendered by rememberUpdatedState(onPageRendered)

    val scale = spec.quality * settledZoom.coerceAtLeast(0.01f)
    val raster = fitWithin(
        (baseSize.width * scale).roundToInt(),
        (baseSize.height * scale).roundToInt(),
        kitePageAspect(page),
        spec.maxBitmapLongSide,
    )
    // Hairline compensation: the engine floors strokes at 1 *raster* px. When
    // the raster is larger than its final on-screen size (supersampling), that
    // floor must grow by the same ratio or sub-pixel strokes vanish in the
    // downscale. (Upscaling can only thicken them, so 1 is safe.)
    val visualWidth = baseSize.width * settledZoom
    val hairline = if (spec.preserveHairlines && visualWidth > 0f) {
        max(1f, raster.width / visualWidth)
    } else 1f

    val rastered by produceState<Pair<ImageBitmap, Boolean>?>(null, page, raster, colors.pageBackground, colors.theme, hairline, cache) {
        // Off the main thread: a 10-30ms page raster on the UI thread
        // janks scroll and pinch. The rasterizer serializes pages on its mutex
        // (TextMeasurer's cache is not thread-safe) but the main thread stays
        // free; the bitmap cache turns scroll-back into a lookup, and neighbour
        // prefetch (KiteDocLayout.Paged offscreenPages) hides first-render latency.
        // rasterizeCachedOrNull carries the mandatory failure guard: an
        // exception escaping produceState aborts the HOST APP.
        value = if (raster == IntSize.Zero) {
            null
        } else {
            rasterizer.rasterizeCachedOrNull(
                cache, page, raster.width, raster.height,
                colors.pageBackground, hairline, colors.theme, pageIndex,
            )
        }
    }
    val bitmap = rastered?.first

    // Fade the bitmap in once it lands instead of popping (and keep the previous
    // frame visible across a re-raster), so the placeholder→page hand-off and any
    // crisp-zoom refresh read as a smooth dissolve rather than a flash.
    // onPageRendered fires only on FRESH rasterization, never on cache hits.
    LaunchedEffect(rastered) {
        rastered?.let { (bmp, fresh) -> if (fresh) onRendered?.invoke(pageIndex, bmp) }
    }
    Crossfade(
        targetState = bitmap,
        animationSpec = tween(durationMillis = PAGE_FADE_MS),
        modifier = modifier,
        label = "pdf-page-raster",
    ) { bmp ->
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (pagePlaceholder != null) {
            pagePlaceholder(pageIndex)
        } else {
            Box(Modifier.fillMaxSize().background(colors.pageBackground))
        }
    }
}

/* ── the vector slot: live content-stream draw, no bitmap ─────────────────── */

/**
 * Draws [page] straight into a live [Canvas]
 * at the slot's layout resolution. No intermediate bitmap, so memory stays low
 * and quality is resolution-independent. Zoom/pan are applied by the enclosing
 * `graphicsLayer` (strip-level in continuous mode, per-page in paged/single),
 * so the draw lambda re-runs on recomposition, not on every gesture frame.
 *
 * `onPageRendered` is intentionally not honoured here: there is no [ImageBitmap]
 * to hand back. Use [KiteRenderSpec.Rasterized] (or [KitePageRasterizer] directly) if
 * you need the rendered bitmap.
 */
@Composable
private fun KitePageVector(
    page: KitePage,
    spec: KiteRenderSpec.Vectorized,
    colors: KiteDocViewColors,
    modifier: Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val theme = colors.theme
    Canvas(modifier) {
        drawRect(theme?.background?.let { Color(it.r.toFloat(), it.g.toFloat(), it.b.toFloat()) } ?: colors.pageBackground)
        val w = size.width
        val h = size.height
        val scale = if (page.displayWidth > 0.0) w / page.displayWidth else 0.0
        if (!scale.isFinite() || scale <= 0.0 || w <= 0f || h <= 0f) return@Canvas
        // displayToDeviceBase() maps page space onto a top-left, Y-down device box
        // (PDF folds in the display-box origin + /Rotate; EPUB its top-left flip).
        val deviceCtm = KiteMatrix.scaling(scale, scale).concat(page.displayToDeviceBase())
        val base = ComposeCanvas(this, textMeasurer, spec.hairlineWidthPx)
        page.renderTo(theme?.wrap(base) ?: base, deviceCtm)
    }
}

/**
 * Paints the overlay layer for [pageIndex] over the slot content: the
 * single-colour [KiteDocViewState.searchHighlights], then the individually coloured
 * [KiteDocViewState.highlights] with their optional margin markers, then the active
 * selection on top.
 *
 * Quads are display-space points; the slot shows the whole display box, so the
 * mapping is one uniform scale. It is the same math the vector path and
 * [KiteDocViewState.hitTest] use, inverted. Display rectangles keep y-min in
 * `bottom` (y grows downward), so `bottom` is the TOP edge.
 */
private fun Modifier.highlightOverlay(
    state: KiteDocViewState,
    page: KitePage,
    pageIndex: Int,
    colors: KiteDocViewColors,
): Modifier = drawWithContent {
    drawContent()
    if (page.displayWidth <= 0.0 || page.displayHeight <= 0.0) return@drawWithContent
    val sx = size.width / page.displayWidth.toFloat()
    val sy = size.height / page.displayHeight.toFloat()

    fun quad(q: io.github.yuroyami.kitepdf.core.KiteRectangle, color: Color) = drawRect(
        color = color,
        topLeft = Offset((q.left * sx).toFloat(), (q.bottom * sy).toFloat()),
        size = Size(((q.right - q.left) * sx).toFloat(), ((q.top - q.bottom) * sy).toFloat()),
    )

    for (hit in state.searchHighlights) {
        if (hit.pageIndex != pageIndex) continue
        for (q in hit.quads) quad(q, colors.searchHighlight)
    }
    for (highlight in state.highlights) {
        val hit = highlight.hit
        if (hit.pageIndex != pageIndex || hit.quads.isEmpty()) continue
        // Null colour means "behave exactly like searchHighlights", so wrapping
        // a plain hit in a KiteHighlight changes nothing on screen.
        val fill = highlight.color ?: colors.searchHighlight
        for (q in hit.quads) quad(q, fill)
        if (highlight.edgeMarker) {
            drawEdgeMarker(hit.quads, sx, sy, highlight.edgeMarkerColor ?: fill, highlight.edgeMarkerSide)
        }
    }
    state.selection?.takeIf { it.pageIndex == pageIndex }?.let { sel ->
        for (q in sel.quads) quad(q, colors.selectionHighlight)
        drawSelectionHandles(
            sel.quads, sx, sy, colors.selectionHandle,
            colors.selectionHandlePainter ?: KiteSelectionHandleDefaults.CaretAndDot,
        )
    }
}

/**
 * The two grab markers that bound the active selection, placed on the leading
 * edge of the first quad and the trailing edge of the last. The marker's look
 * comes from [painter] ([KiteDocViewColors.selectionHandlePainter], defaulting to
 * [KiteSelectionHandleDefaults.CaretAndDot]); this function owns only the
 * placement math.
 *
 * They are grab targets, not only indicators: a press within
 * [HandleGrabRadius] of either marker drags that end of the selection while the
 * other end stays anchored. The grab region is this placement, the boundary
 * line itself, no matter what [painter] draws around it, so a custom marker
 * cannot end up unreachable.
 */
private fun DrawScope.drawSelectionHandles(
    quads: List<io.github.yuroyami.kitepdf.core.KiteRectangle>,
    sx: Float,
    sy: Float,
    color: Color,
    painter: KiteSelectionHandlePainter,
) {
    val first = quads.firstOrNull() ?: return
    val last = quads.lastOrNull() ?: return

    // Display rectangles keep y-min in `bottom` (y grows downward): `bottom` is
    // the TOP edge on screen, the same convention as the quad fill above.
    with(painter) {
        drawHandle(
            edge = KiteSelectionHandleEdge.Start,
            x = (first.left * sx).toFloat(),
            top = (first.bottom * sy).toFloat(),
            bottom = (first.top * sy).toFloat(),
            color = color,
        )
        drawHandle(
            edge = KiteSelectionHandleEdge.End,
            x = (last.right * sx).toFloat(),
            top = (last.bottom * sy).toFloat(),
            bottom = (last.top * sy).toFloat(),
            color = color,
        )
    }
}

/**
 * The margin marker for one highlight: a rounded pill against the page's outer
 * edge, level with the highlighted text ([quads], display-space).
 *
 * Every dimension is a fraction of the rendered page width, so the marker keeps
 * its proportions on a thumbnail, on a phone and at deep zoom alike. Its left
 * edge is clamped past the rightmost quad, which keeps it in the margin instead
 * of over the words; on a page whose text runs right into that margin, leaving
 * no room, nothing is drawn rather than a marker across the glyphs.
 */
private fun DrawScope.drawEdgeMarker(
    quads: List<io.github.yuroyami.kitepdf.core.KiteRectangle>,
    sx: Float,
    sy: Float,
    color: Color,
    side: KiteMarkerSide,
) {
    var top = Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    var textRight = 0f
    var textLeft = Float.MAX_VALUE
    for (q in quads) {
        top = minOf(top, (q.bottom * sy).toFloat())
        bottom = maxOf(bottom, (q.top * sy).toFloat())
        textRight = maxOf(textRight, (q.right * sx).toFloat())
        textLeft = minOf(textLeft, (q.left * sx).toFloat())
    }
    if (!top.isFinite() || !bottom.isFinite()) return

    val width = size.width * EDGE_MARKER_WIDTH_FRACTION
    val gutter = width * EDGE_MARKER_GUTTER_RATIO
    // Same clamp on both sides, mirrored: the marker stays in its margin, and a
    // page whose text runs into that margin gets nothing rather than a pill
    // across the glyphs.
    val left: Float
    val right: Float
    when (side) {
        KiteMarkerSide.End -> {
            right = size.width - gutter
            left = maxOf(right - width, textRight + gutter)
        }
        KiteMarkerSide.Start -> {
            left = gutter
            right = minOf(left + width, textLeft - gutter)
        }
    }
    if (left >= right) return

    // A one-word highlight is only a few px tall in a thumbnail; floor the pill
    // so it stays a visible mark rather than a dash.
    val height = maxOf(bottom - top, width * EDGE_MARKER_MIN_HEIGHT_RATIO)
    val slack = (size.height - height).coerceAtLeast(0f)
    val y = ((top + bottom) / 2f - height / 2f).coerceIn(0f, slack)
    drawRoundRect(
        color = color,
        topLeft = Offset(left, y),
        size = Size(right - left, height),
        cornerRadius = CornerRadius((right - left) / 2f),
    )
}

/** Margin-marker width, as a fraction of the rendered page width. */
private const val EDGE_MARKER_WIDTH_FRACTION = 0.02f

/** Marker clearance from the page edge and from the text, in marker widths. */
private const val EDGE_MARKER_GUTTER_RATIO = 0.5f

/** Shortest a marker may be, in marker widths. */
private const val EDGE_MARKER_MIN_HEIGHT_RATIO = 3f

private const val ZOOM_SETTLE_DEBOUNCE_MS = 220L

/** Fade-in duration for a freshly rasterized page bitmap. */
private const val PAGE_FADE_MS = 160

/* ── spread pager: two pages per item, like an open book ───────────── */

@Composable
private fun SpreadLayout(
    state: KiteDocViewState,
    layout: KiteDocLayout.Spread,
    zoomSpec: KiteZoomSpec,
    renderSpec: KiteRenderSpec,
    colors: KiteDocViewColors,
    pageSpacing: Dp,
    userScrollEnabled: Boolean,
    settledZoom: Float,
    onPageRendered: ((Int, ImageBitmap) -> Unit)?,
    pagePlaceholder: (@Composable (Int) -> Unit)?,
    onTap: ((Offset) -> Unit)?,
) {
    val spreadCount = (state.pageCount + 1) / 2
    // currentPage, not pendingPage: see ContinuousLayout's seed comment.
    val pagerState = rememberPagerState(
        initialPage = (state.currentPage / 2).coerceIn(0, spreadCount - 1),
    ) { spreadCount }
    DisposableEffect(state, pagerState) {
        val adapter = SpreadScrollAdapter(pagerState)
        state.adapter = adapter
        onDispose {
            state.pendingPage = adapter.currentPage
            if (state.adapter === adapter) state.adapter = null
        }
    }
    LaunchedEffect(state, pagerState, zoomSpec.resetZoomOnPageChange) {
        snapshotFlow { pagerState.settledPage }.collect {
            state.panOffset = Offset.Zero
            if (zoomSpec.resetZoomOnPageChange) state.resetZoom()
        }
    }

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val pagerScrollEnabled = userScrollEnabled && !state.isZoomed && !state.isSelectionActive
    val spreadContent: @Composable (Int) -> Unit = { spread ->
        val isCurrent = spread == pagerState.currentPage
        SpreadBox(
            state = state,
            leftIndex = 2 * spread,
            rightIndex = (2 * spread + 1).takeIf { it < state.pageCount },
            reverseOrder = layout.reverseLayout,
            zoom = if (isCurrent) state.zoom else 1f,
            pan = if (isCurrent) state.panOffset else Offset.Zero,
            gestures = if (isCurrent) {
                Modifier.kiteTransformGestures(state, zoomSpec, scope, onTap).kiteSelectionGestures(state, haptics)
            } else Modifier,
            recordGeometry = isCurrent,
            settledZoom = if (isCurrent) settledZoom else 1f,
            renderSpec = renderSpec,
            colors = colors,
            onPageRendered = onPageRendered,
            pagePlaceholder = pagePlaceholder,
        )
    }
    when (layout.orientation) {
        Orientation.Horizontal -> HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = pageSpacing,
            beyondViewportPageCount = layout.offscreenPages,
            userScrollEnabled = pagerScrollEnabled,
            reverseLayout = layout.reverseLayout,
        ) { spreadContent(it) }
        Orientation.Vertical -> VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = pageSpacing,
            beyondViewportPageCount = layout.offscreenPages,
            userScrollEnabled = pagerScrollEnabled,
            reverseLayout = layout.reverseLayout,
        ) { spreadContent(it) }
    }
}

/**
 * One spread: reading-order pages [leftIndex] (2k) and [rightIndex] (2k+1,
 * null on an odd tail) letterboxed into the viewport halves. LTR shows 2k on
 * the left; [reverseOrder] (right-to-left books) shows 2k on the RIGHT. A
 * lone trailing page centres across the full width.
 */
@Composable
private fun SpreadBox(
    state: KiteDocViewState,
    leftIndex: Int,
    rightIndex: Int?,
    reverseOrder: Boolean,
    zoom: Float,
    pan: Offset,
    gestures: Modifier,
    recordGeometry: Boolean,
    settledZoom: Float,
    renderSpec: KiteRenderSpec,
    colors: KiteDocViewColors,
    onPageRendered: ((Int, ImageBitmap) -> Unit)?,
    pagePlaceholder: (@Composable (Int) -> Unit)?,
) {
    if (recordGeometry) {
        DisposableEffect(state, leftIndex, rightIndex) {
            onDispose {
                state.pageGeometry.remove(leftIndex)
                rightIndex?.let { state.pageGeometry.remove(it) }
            }
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(gestures)
            .graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = pan.x
                translationY = pan.y
            },
    ) {
        val density = LocalDensity.current
        val fullW = constraints.maxWidth
        val fullH = constraints.maxHeight

        @Composable
        fun slot(pageIndex: Int, regionLeft: Int, regionWidth: Int) {
            val page = state.document.pages[pageIndex]
            val fit = fitWithin(regionWidth, fullH, kitePageAspect(page))
            if (fit == IntSize.Zero) return
            val left = regionLeft + (regionWidth - fit.width) / 2f
            val top = (fullH - fit.height) / 2f
            if (recordGeometry) {
                val rect = Rect(left, top, left + fit.width, top + fit.height)
                SideEffect { state.pageGeometry[pageIndex] = rect }
            }
            val dpOffset = with(density) { DpSize(left.toInt().toDp(), top.toInt().toDp()) }
            val dpSize = with(density) { DpSize(fit.width.toDp(), fit.height.toDp()) }
            Box(
                Modifier
                    .padding(start = dpOffset.width, top = dpOffset.height)
                    .size(dpSize),
            ) {
                val slotModifier = Modifier.fillMaxSize()
                    .highlightOverlay(state, page, pageIndex, colors)
                when (renderSpec) {
                    is KiteRenderSpec.Rasterized -> KitePageRaster(
                        page, pageIndex, fit, settledZoom, renderSpec, colors,
                        onPageRendered, pagePlaceholder, slotModifier,
                        cache = state.bitmapCacheFor(renderSpec.cacheBudgetBytes),
                    )
                    is KiteRenderSpec.Vectorized -> KitePageVector(page, renderSpec, colors, slotModifier)
                }
            }
        }

        if (rightIndex == null) {
            slot(leftIndex, 0, fullW) // odd tail: centre alone
        } else {
            val firstVisual = if (reverseOrder) rightIndex else leftIndex
            val secondVisual = if (reverseOrder) leftIndex else rightIndex
            slot(firstVisual, 0, fullW / 2)
            slot(secondVisual, fullW / 2, fullW - fullW / 2)
        }
    }
}
