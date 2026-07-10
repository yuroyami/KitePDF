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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.KitePage
import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfAnnotation
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPage
import io.github.yuroyami.kitepdf.render.Matrix as PdfMatrix
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * THE KitePDF viewer composable.
 *
 * ```kotlin
 * // Simple: whole document, vertical continuous scroll.
 * PdfView(rememberPdfViewState(doc), Modifier.fillMaxSize())
 *
 * // Full control: horizontal pager, custom zoom, HUD overlay.
 * val state = rememberPdfViewState(doc)
 * PdfView(
 *     state = state,
 *     layout = PdfLayout.Paged(Orientation.Horizontal),
 *     zoomSpec = PdfZoomSpec(maxZoom = 6f),
 *     overlay = { s ->
 *         PdfNavigationControls(s, Modifier.align(Alignment.BottomCenter))
 *     },
 * )
 * // …and the same state drives widgets OUTSIDE the viewport too:
 * PdfPageIndicator(state)
 * ```
 *
 * By default ([PdfRenderSpec.Rasterized]) pages are vector-rendered once into an
 * [ImageBitmap] per (page, size, zoom bucket) and then drawn as plain images —
 * scrolling, panning and pinching never re-execute the PDF content stream.
 * Switch to [PdfRenderSpec.Vectorized] for resolution-independent, bitmap-free
 * drawing. See [PdfRenderSpec] for the per-mode knobs and [PdfZoomSpec] for
 * gestures.
 *
 * @param state the hoisted control surface — see [rememberPdfViewState].
 * @param layout continuous strip (any orientation), snap pager (any
 *   orientation) or a single fixed page. See [PdfLayout].
 * @param zoomSpec pinch/double-tap/pan behaviour and zoom bounds. Programmatic
 *   zoom through [PdfViewState.setZoom] honours the same bounds, so external
 *   controls (sliders, loupes) work with gestures fully disabled.
 * @param renderSpec how pages become pixels — [PdfRenderSpec.Rasterized]
 *   (bitmap-cached, with quality/memory/crisp-zoom/hairline knobs) or
 *   [PdfRenderSpec.Vectorized] (live vector draw). See [PdfRenderSpec].
 * @param colors page paper + viewport letterbox colours.
 * @param pageSpacing gap between pages (continuous gutter / pager spacing).
 * @param userScrollEnabled gesture scrolling/swiping of the layout itself.
 *   Disable to drive paging exclusively through [PdfViewState] (nav buttons).
 * @param onPageRendered fires whenever a page finishes rasterizing, with the
 *   bitmap ready for export — e.g. via [encodeToPng].
 * @param pagePlaceholder shown in a page's slot until its raster is ready.
 *   Defaults to a plain [PdfViewColors.pageBackground] box.
 * @param overlay HUD layer drawn over the viewport; receives [state] and a
 *   [BoxScope] for alignment. Widgets here float above the pages —
 *   [PdfNavigationControls], [PdfPageIndicator], [PdfThumbnailStrip] or
 *   anything of your own.
 * @param onTap single-tap on the page, reported with the tap position. The tap
 *   does not consume pan/swipe, so it coexists with navigation — typical use is
 *   toggling a HUD's visibility. Held back until the double-tap window lapses
 *   only when [PdfZoomSpec.doubleTapEnabled] is on. Taps that land on a link
 *   navigate (or go to [onLinkTap]) instead of reaching this callback.
 * @param onLinkTap fires when a tapped link carries an action the viewer can't
 *   perform itself — a URI, a remote GoTo, a Launch. Return true after handling
 *   it (e.g. opening the URL in a browser); false lets the tap fall through to
 *   [onTap]. Internal go-to-page links (PDF destinations, EPUB internal hrefs)
 *   never reach this: the viewer scrolls to the target page directly.
 */
@Composable
fun PdfView(
    state: PdfViewState,
    modifier: Modifier = Modifier,
    layout: PdfLayout = PdfLayout.Default,
    zoomSpec: PdfZoomSpec = PdfZoomSpec(),
    renderSpec: PdfRenderSpec = PdfRenderSpec.Default,
    colors: PdfViewColors = PdfViewColors(),
    pageSpacing: Dp = 8.dp,
    userScrollEnabled: Boolean = true,
    onPageRendered: ((pageIndex: Int, image: ImageBitmap) -> Unit)? = null,
    pagePlaceholder: (@Composable (pageIndex: Int) -> Unit)? = null,
    overlay: (@Composable BoxScope.(PdfViewState) -> Unit)? = null,
    onTap: ((Offset) -> Unit)? = null,
    onLinkTap: ((PdfAction) -> Boolean)? = null,
) {
    SideEffect {
        state.zoomRange = zoomSpec.minZoom..zoomSpec.maxZoom
        state.panAxes = when (layout) {
            is PdfLayout.Continuous -> when (layout.orientation) {
                Orientation.Vertical -> PdfViewState.PanAxes.XOnly
                Orientation.Horizontal -> PdfViewState.PanAxes.YOnly
            }
            else -> PdfViewState.PanAxes.Both
        }
        if (state.zoom !in state.zoomRange) state.setZoom(state.zoom) // re-clamp on spec change
    }

    // Crisp zoom: the raster resolution follows the zoom level, but only after
    // the gesture settles — GPU-scaling the existing bitmap in between. Only the
    // rasterized path re-renders on settle; vector draws are resolution-free.
    val rerasterizeOnZoom = (renderSpec as? PdfRenderSpec.Rasterized)?.rerasterizeOnZoom == true
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

    // Route taps through link hit-testing first (T-32/T-82): a tap on a link
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
                is PdfLayout.Continuous -> ContinuousLayout(
                    state, layout, zoomSpec, renderSpec, colors, pageSpacing,
                    userScrollEnabled, settledZoom, onPageRendered, pagePlaceholder, linkAwareTap,
                )
                is PdfLayout.Paged -> PagedLayout(
                    state, layout, zoomSpec, renderSpec, colors, pageSpacing,
                    userScrollEnabled, settledZoom, onPageRendered, pagePlaceholder, linkAwareTap,
                )
                is PdfLayout.Spread -> SpreadLayout(
                    state, layout, zoomSpec, renderSpec, colors, pageSpacing,
                    userScrollEnabled, settledZoom, onPageRendered, pagePlaceholder, linkAwareTap,
                )
                is PdfLayout.SinglePage -> SinglePageLayout(
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
    state: PdfViewState,
    scope: kotlinx.coroutines.CoroutineScope,
    onLinkTap: ((PdfAction) -> Boolean)?,
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
                    ?: ann.uri?.let { PdfAction.Uri(it, isMap = false, raw = io.github.yuroyami.kitepdf.parser.PdfDictionary(emptyMap())) }
                    ?: return false
                return onLinkTap?.invoke(action) == true
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
                    return onLinkTap?.invoke(PdfAction.Uri(link.href, isMap = false, raw = io.github.yuroyami.kitepdf.parser.PdfDictionary(emptyMap()))) == true
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
 * Convenience entry point: remembers its own state internally.
 *
 * ```kotlin
 * PdfView(document = doc, modifier = Modifier.fillMaxSize())          // whole document
 * PdfView(document = doc, page = 0, modifier = Modifier.fillMaxWidth()) // one page
 * ```
 *
 * @param page index of the single page to show, or `null` (default) for the
 *   whole document as a continuous vertical scroll.
 * @param background colour painted behind page content.
 */
@Composable
fun PdfView(
    document: PdfDocument,
    modifier: Modifier = Modifier,
    page: Int? = null,
    background: Color = Color.White,
    pageSpacing: Dp = 8.dp,
    onPageRendered: ((pageIndex: Int, image: ImageBitmap) -> Unit)? = null,
) {
    require(page == null || page in 0 until document.pageCount) {
        "page $page is out of bounds (document has ${document.pageCount} page(s))"
    }
    PdfView(
        state = rememberPdfViewState(document),
        modifier = modifier,
        layout = if (page != null) PdfLayout.SinglePage(page) else PdfLayout.Continuous(),
        colors = PdfViewColors(pageBackground = background),
        pageSpacing = pageSpacing,
        onPageRendered = onPageRendered,
    )
}

/* ── continuous strip ─────────────────────────────────────────────────────── */

@Composable
private fun ContinuousLayout(
    state: PdfViewState,
    layout: PdfLayout.Continuous,
    zoomSpec: PdfZoomSpec,
    renderSpec: PdfRenderSpec,
    colors: PdfViewColors,
    pageSpacing: Dp,
    userScrollEnabled: Boolean,
    settledZoom: Float,
    onPageRendered: ((Int, ImageBitmap) -> Unit)?,
    pagePlaceholder: (@Composable (Int) -> Unit)?,
    onTap: ((Offset) -> Unit)?,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.pendingPage.coerceIn(0, state.pageCount - 1),
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
    // Magnifier-style zoom: scale the whole strip around the viewport centre.
    // The scroll axis stays native (the list keeps scrolling while zoomed);
    // pan covers the cross axis only. Gestures sit OUTSIDE the layer so they
    // see untransformed viewport coordinates.
    Box(
        Modifier
            .fillMaxSize()
            .pdfTransformGestures(state, zoomSpec, scope, onTap)
            .pdfSelectionGestures(state)
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
        when (layout.orientation) {
            Orientation.Vertical -> LazyColumn(
                modifier = anchored,
                state = listState,
                verticalArrangement = Arrangement.spacedBy(pageSpacing),
                userScrollEnabled = userScrollEnabled,
            ) {
                items(count = state.pageCount, key = { it }) { pageItem(it) }
            }
            Orientation.Horizontal -> LazyRow(
                modifier = anchored,
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(pageSpacing),
                userScrollEnabled = userScrollEnabled,
            ) {
                items(count = state.pageCount, key = { it }) { pageItem(it) }
            }
        }
    }
}

/** One page in the strip: fills the cross axis at its natural aspect ratio. */
@Composable
private fun androidx.compose.foundation.lazy.LazyItemScope.ContinuousPageItem(
    state: PdfViewState,
    page: KitePage,
    pageIndex: Int,
    orientation: Orientation,
    settledZoom: Float,
    renderSpec: PdfRenderSpec,
    colors: PdfViewColors,
    onPageRendered: ((Int, ImageBitmap) -> Unit)?,
    pagePlaceholder: (@Composable (Int) -> Unit)?,
) {
    val aspect = pdfPageAspect(page)
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
            .searchHighlightOverlay(state, page, pageIndex, colors)
        when (renderSpec) {
            is PdfRenderSpec.Rasterized -> PdfPageRaster(
                page, pageIndex, baseSize, settledZoom, renderSpec, colors,
                onPageRendered, pagePlaceholder, slot,
            )
            is PdfRenderSpec.Vectorized -> PdfPageVector(
                page, renderSpec, colors, slot,
            )
        }
    }
}

/* ── snap pager ───────────────────────────────────────────────────────────── */

@Composable
private fun PagedLayout(
    state: PdfViewState,
    layout: PdfLayout.Paged,
    zoomSpec: PdfZoomSpec,
    renderSpec: PdfRenderSpec,
    colors: PdfViewColors,
    pageSpacing: Dp,
    userScrollEnabled: Boolean,
    settledZoom: Float,
    onPageRendered: ((Int, ImageBitmap) -> Unit)?,
    pagePlaceholder: (@Composable (Int) -> Unit)?,
    onTap: ((Offset) -> Unit)?,
) {
    val pagerState = rememberPagerState(
        initialPage = state.pendingPage.coerceIn(0, state.pageCount - 1),
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
    val pageContent: @Composable (Int) -> Unit = { index ->
        val isCurrent = index == pagerState.currentPage
        PageBox(
            page = state.document.pages[index],
            pageIndex = index,
            zoom = if (isCurrent) state.zoom else 1f,
            pan = if (isCurrent) state.panOffset else androidx.compose.ui.geometry.Offset.Zero,
            gestures = if (isCurrent) {
                Modifier.pdfTransformGestures(state, zoomSpec, scope, onTap).pdfSelectionGestures(state)
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
    // page; paging stays available through PdfViewState (nav widgets).
    val pagerScrollEnabled = userScrollEnabled && !state.isZoomed
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
    state: PdfViewState,
    layout: PdfLayout.SinglePage,
    zoomSpec: PdfZoomSpec,
    renderSpec: PdfRenderSpec,
    colors: PdfViewColors,
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
    PageBox(
        page = state.document.pages[layout.pageIndex],
        pageIndex = layout.pageIndex,
        zoom = state.zoom,
        pan = state.panOffset,
        gestures = Modifier.pdfTransformGestures(state, zoomSpec, scope, onTap).pdfSelectionGestures(state),
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
    renderSpec: PdfRenderSpec,
    colors: PdfViewColors,
    onPageRendered: ((Int, ImageBitmap) -> Unit)?,
    pagePlaceholder: (@Composable (Int) -> Unit)?,
    /** The state whose search highlights this slot paints. */
    state: PdfViewState,
    /** The state to report hit-test geometry into (the on-screen slot only). */
    geometryInto: PdfViewState? = null,
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
        val fit = fitWithin(constraints.maxWidth, constraints.maxHeight, pdfPageAspect(page))
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
                .searchHighlightOverlay(state, page, pageIndex, colors)
            when (renderSpec) {
                is PdfRenderSpec.Rasterized -> PdfPageRaster(
                    page, pageIndex, fit, settledZoom, renderSpec, colors,
                    onPageRendered, pagePlaceholder, slot,
                )
                is PdfRenderSpec.Vectorized -> PdfPageVector(
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
private fun PdfPageRaster(
    page: KitePage,
    pageIndex: Int,
    baseSize: IntSize,
    settledZoom: Float,
    spec: PdfRenderSpec.Rasterized,
    colors: PdfViewColors,
    onPageRendered: ((Int, ImageBitmap) -> Unit)?,
    pagePlaceholder: (@Composable (Int) -> Unit)?,
    modifier: Modifier,
) {
    val rasterizer = rememberPdfRasterizer()
    val onRendered by rememberUpdatedState(onPageRendered)

    val scale = spec.quality * settledZoom.coerceAtLeast(0.01f)
    val raster = fitWithin(
        (baseSize.width * scale).roundToInt(),
        (baseSize.height * scale).roundToInt(),
        pdfPageAspect(page),
        spec.maxBitmapLongSide,
    )
    // Hairline compensation: the engine floors strokes at 1 *raster* px. When
    // the raster is larger than its final on-screen size (supersampling), that
    // floor must grow by the same ratio or sub-pixel strokes vanish in the
    // downscale. (Upscaling can only thicken them — safe to leave at 1.)
    val visualWidth = baseSize.width * settledZoom
    val hairline = if (spec.preserveHairlines && visualWidth > 0f) {
        max(1f, raster.width / visualWidth)
    } else 1f

    val bitmap by produceState<ImageBitmap?>(null, page, raster, colors.pageBackground, colors.theme, hairline) {
        // Off the main thread (T-14): a 10-30ms page raster on the UI thread
        // janks scroll and pinch. rasterizeOffMain serializes pages on the
        // rasterizer's mutex (TextMeasurer's cache is not thread-safe) but the
        // main thread stays free; neighbour prefetch (PdfLayout.Paged
        // offscreenPages) still hides the latency across page turns.
        value = if (raster == IntSize.Zero) null
        else rasterizer.rasterizeOffMain(page, raster.width, raster.height, colors.pageBackground, hairline, colors.theme)
    }

    // Fade the bitmap in once it lands instead of popping (and keep the previous
    // frame visible across a re-raster), so the placeholder→page hand-off and any
    // crisp-zoom refresh read as a smooth dissolve rather than a flash.
    LaunchedEffect(bitmap) { bitmap?.let { onRendered?.invoke(pageIndex, it) } }
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
 * Draws [page] by re-executing its content stream straight into a live [Canvas]
 * at the slot's layout resolution. No intermediate bitmap, so memory stays low
 * and quality is resolution-independent. Zoom/pan are applied by the enclosing
 * `graphicsLayer` (strip-level in continuous mode, per-page in paged/single),
 * so the draw lambda re-runs on recomposition — not on every gesture frame.
 *
 * `onPageRendered` is intentionally not honoured here: there is no [ImageBitmap]
 * to hand back. Use [PdfRenderSpec.Rasterized] (or [PdfRasterizer] directly) if
 * you need the rendered bitmap.
 */
@Composable
private fun PdfPageVector(
    page: KitePage,
    spec: PdfRenderSpec.Vectorized,
    colors: PdfViewColors,
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
        val deviceCtm = PdfMatrix.scaling(scale, scale).concat(page.displayToDeviceBase())
        val base = ComposeCanvas(this, textMeasurer, spec.hairlineWidthPx)
        page.renderTo(theme?.wrap(base) ?: base, deviceCtm)
    }
}

/**
 * Paints [PdfViewState.searchHighlights] quads for [pageIndex] over the slot
 * content (T-33). Quads are display-space points; the slot shows the whole
 * display box, so the mapping is one uniform scale — the same math the
 * vector path and [PdfViewState.hitTest] use, inverted. Display rectangles
 * keep y-min in `bottom` (y grows downward), so `bottom` is the TOP edge.
 */
private fun Modifier.searchHighlightOverlay(
    state: PdfViewState,
    page: KitePage,
    pageIndex: Int,
    colors: PdfViewColors,
): Modifier = drawWithContent {
    drawContent()
    if (page.displayWidth <= 0.0 || page.displayHeight <= 0.0) return@drawWithContent
    val sx = size.width / page.displayWidth.toFloat()
    val sy = size.height / page.displayHeight.toFloat()

    fun quad(q: io.github.yuroyami.kitepdf.Rectangle, color: Color) = drawRect(
        color = color,
        topLeft = Offset((q.left * sx).toFloat(), (q.bottom * sy).toFloat()),
        size = Size(((q.right - q.left) * sx).toFloat(), ((q.top - q.bottom) * sy).toFloat()),
    )

    for (hit in state.searchHighlights) {
        if (hit.pageIndex != pageIndex) continue
        for (q in hit.quads) quad(q, colors.searchHighlight)
    }
    state.selection?.takeIf { it.pageIndex == pageIndex }?.let { sel ->
        for (q in sel.quads) quad(q, colors.selectionHighlight)
    }
}

private const val ZOOM_SETTLE_DEBOUNCE_MS = 220L

/** Fade-in duration for a freshly rasterized page bitmap. */
private const val PAGE_FADE_MS = 160

/* ── spread pager: two pages per item, like an open book (T-85) ───────────── */

@Composable
private fun SpreadLayout(
    state: PdfViewState,
    layout: PdfLayout.Spread,
    zoomSpec: PdfZoomSpec,
    renderSpec: PdfRenderSpec,
    colors: PdfViewColors,
    pageSpacing: Dp,
    userScrollEnabled: Boolean,
    settledZoom: Float,
    onPageRendered: ((Int, ImageBitmap) -> Unit)?,
    pagePlaceholder: (@Composable (Int) -> Unit)?,
    onTap: ((Offset) -> Unit)?,
) {
    val spreadCount = (state.pageCount + 1) / 2
    val pagerState = rememberPagerState(
        initialPage = (state.pendingPage / 2).coerceIn(0, spreadCount - 1),
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
    val pagerScrollEnabled = userScrollEnabled && !state.isZoomed
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
                Modifier.pdfTransformGestures(state, zoomSpec, scope, onTap).pdfSelectionGestures(state)
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
    state: PdfViewState,
    leftIndex: Int,
    rightIndex: Int?,
    reverseOrder: Boolean,
    zoom: Float,
    pan: Offset,
    gestures: Modifier,
    recordGeometry: Boolean,
    settledZoom: Float,
    renderSpec: PdfRenderSpec,
    colors: PdfViewColors,
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
            val fit = fitWithin(regionWidth, fullH, pdfPageAspect(page))
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
                    .searchHighlightOverlay(state, page, pageIndex, colors)
                when (renderSpec) {
                    is PdfRenderSpec.Rasterized -> PdfPageRaster(
                        page, pageIndex, fit, settledZoom, renderSpec, colors,
                        onPageRendered, pagePlaceholder, slotModifier,
                    )
                    is PdfRenderSpec.Vectorized -> PdfPageVector(page, renderSpec, colors, slotModifier)
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
