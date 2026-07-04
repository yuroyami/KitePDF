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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.KitePage
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.render.Matrix as PdfMatrix
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

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
 *   only when [PdfZoomSpec.doubleTapEnabled] is on.
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
                    userScrollEnabled, settledZoom, onPageRendered, pagePlaceholder, onTap,
                )
                is PdfLayout.Paged -> PagedLayout(
                    state, layout, zoomSpec, renderSpec, colors, pageSpacing,
                    userScrollEnabled, settledZoom, onPageRendered, pagePlaceholder, onTap,
                )
                is PdfLayout.SinglePage -> SinglePageLayout(
                    state, layout, zoomSpec, renderSpec, colors,
                    settledZoom, onPageRendered, pagePlaceholder, onTap,
                )
            }
        }
        overlay?.invoke(this, state)
    }
}

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
            .graphicsLayer {
                scaleX = state.zoom
                scaleY = state.zoom
                translationX = state.panOffset.x
                translationY = state.panOffset.y
            },
    ) {
        val pageItem: @Composable androidx.compose.foundation.lazy.LazyItemScope.(Int) -> Unit = { index ->
            ContinuousPageItem(
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
        when (layout.orientation) {
            Orientation.Vertical -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(pageSpacing),
                userScrollEnabled = userScrollEnabled,
            ) {
                items(count = state.pageCount, key = { it }) { pageItem(it) }
            }
            Orientation.Horizontal -> LazyRow(
                modifier = Modifier.fillMaxSize(),
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
    BoxWithConstraints(sizing) {
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
        when (renderSpec) {
            is PdfRenderSpec.Rasterized -> PdfPageRaster(
                page, pageIndex, baseSize, settledZoom, renderSpec, colors,
                onPageRendered, pagePlaceholder, Modifier.fillMaxSize(),
            )
            is PdfRenderSpec.Vectorized -> PdfPageVector(
                page, renderSpec, colors, Modifier.fillMaxSize(),
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
                Modifier.pdfTransformGestures(state, zoomSpec, scope, onTap)
            } else Modifier,
            settledZoom = if (isCurrent) settledZoom else 1f,
            renderSpec = renderSpec,
            colors = colors,
            onPageRendered = onPageRendered,
            pagePlaceholder = pagePlaceholder,
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
        ) { pageContent(it) }
        Orientation.Vertical -> VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = pageSpacing,
            beyondViewportPageCount = layout.offscreenPages,
            userScrollEnabled = pagerScrollEnabled,
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
        gestures = Modifier.pdfTransformGestures(state, zoomSpec, scope, onTap),
        settledZoom = settledZoom,
        renderSpec = renderSpec,
        colors = colors,
        onPageRendered = onPageRendered,
        pagePlaceholder = pagePlaceholder,
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
) {
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
        if (fit != IntSize.Zero) {
            val dpSize = with(density) { DpSize(fit.width.toDp(), fit.height.toDp()) }
            when (renderSpec) {
                is PdfRenderSpec.Rasterized -> PdfPageRaster(
                    page, pageIndex, fit, settledZoom, renderSpec, colors,
                    onPageRendered, pagePlaceholder, Modifier.size(dpSize),
                )
                is PdfRenderSpec.Vectorized -> PdfPageVector(
                    page, renderSpec, colors, Modifier.size(dpSize),
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
        // Post-frame, main thread (Compose text measurement isn't thread-safe on
        // every platform). The jitter on a page turn is avoided not by moving
        // this off-thread but by prefetching neighbours (PdfLayout.Paged
        // offscreenPages) so the incoming page is already cached before the swipe
        // — this raster then only runs while idle, never mid-gesture.
        value = if (raster == IntSize.Zero) null
        else rasterizer.rasterize(page, raster.width, raster.height, colors.pageBackground, hairline, colors.theme)
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

private const val ZOOM_SETTLE_DEBOUNCE_MS = 220L

/** Fade-in duration for a freshly rasterized page bitmap. */
private const val PAGE_FADE_MS = 160
