package io.github.yuroyami.kitepdf.compose

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import io.github.yuroyami.kitepdf.epub.EpubLink
import io.github.yuroyami.kitepdf.epub.EpubLinkKind
import io.github.yuroyami.kitepdf.epub.EpubPage
import io.github.yuroyami.kitepdf.core.render.KITE_DEFAULT_MAX_RASTER_PIXELS
import io.github.yuroyami.kitepdf.core.render.ReaderTheme
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
 * By default ([KiteRenderSpec.Rasterized]) pages are vector-rendered into an
 * [ImageBitmap] per page, size and settled zoom and then drawn as plain images, so
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
 * @param onPageRendered fires whenever a page finishes a FRESH screen raster:
 *   once per page and bitmap size, so again at each settled zoom when crisp zoom
 *   is on. It is the bitmap on screen, not an export: it leaves out the form
 *   widgets when a form layer draws them (a `scripts` handler on a PDF), and it
 *   never fires in [KiteRenderSpec.Vectorized] mode (#431). Cache hits from the
 *   page-bitmap LRU do not re-fire it. To export a page, use
 *   [KitePageRasterizer.rasterize] with the form state.
 * @param pagePlaceholder shown in a page's slot until its raster is ready.
 *   Defaults to a plain [KiteDocViewColors.pageBackground] box.
 * @param chapterPlaceholder shown in the slot a chapter holds while it is still
 *   being laid out. Only reflowable EPUB reaches this: a PDF is never mid-layout.
 *   [KiteDocLayout.Spread] pairs pages across the whole book, so it shows the
 *   placeholder until every chapter is laid out. Defaults to an empty
 *   page-coloured box.
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
 *   never reach this: the viewer scrolls to the target page directly. An EPUB
 *   reference to a note goes to `onEpubReferenceTap` first. See
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
    chapterPlaceholder: (@Composable (chapter: Int) -> Unit)? = null,
    overlay: (@Composable BoxScope.(KiteDocViewState) -> Unit)? = null,
    onTap: ((Offset) -> Unit)? = null,
    onLinkTap: ((KiteLinkAction) -> Boolean)? = null,
    /**
     * Runs the document's own scripts, when the host wants them run. `kitepdf-javascript`
     * provides one; without it nothing in a document runs, which is the default.
     *
     * With a handler the viewer fires the document and page triggers, draws the form from its
     * live values, sends a tap on a widget to it, and pumps the timers a script set.
     */
    scripts: io.github.yuroyami.kitepdf.PdfScriptHandler? = null,
    /** Called before links for a saved highlight. Return true to consume the tap. */
    onHighlightTap: ((KiteHighlight) -> Boolean)? = null,
    /**
     * Called before the viewer follows an internal EPUB link whose [EpubLink.kind] marks it
     * as a reference to a note, a glossary entry or a bibliography entry. Return true to
     * consume the tap, for example after you show [EpubDocument.linkTarget] in a popup.
     * Return false, or pass null, and the viewer scrolls to the target.
     */
    onEpubReferenceTap: ((EpubLink) -> Boolean)? = null,
) {
    val scriptScope = rememberCoroutineScope()
    val scriptLane = remember { newScriptLane() }
    SideEffect {
        state.scripts = scripts
        state.scriptScope = scriptScope
        state.scriptLane = scriptLane
        // The thumbnail strip draws pages as the viewer does: with its theme and its decorator (#419).
        state.viewerTheme = colors.theme
        state.viewerDecorator = when (renderSpec) {
            is KiteRenderSpec.Rasterized -> renderSpec.canvasDecorator
            is KiteRenderSpec.Vectorized -> renderSpec.canvasDecorator
        }
        state.selectionEnabled = selectionEnabled
        // Vectorized mode keeps no page bitmaps, so it lets go of the ones a raster mode kept (#395).
        if (renderSpec !is KiteRenderSpec.Rasterized) state.bitmapCacheFor(0L)
        // A continuous strip does not zoom out below fit: it would shrink into a band (#398).
        val floor = if (layout is KiteDocLayout.Continuous) maxOf(1f, zoomSpec.minZoom) else zoomSpec.minZoom
        state.zoomRange = floor..maxOf(floor, zoomSpec.maxZoom)
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
    // A Vectorized page draws again at each settled zoom too, so its images and hairlines follow
    // the pixels on screen (#418).
    val tracksZoom = rerasterizeOnZoom || renderSpec is KiteRenderSpec.Vectorized
    val settledZoom by produceState(1f, state, tracksZoom) {
        if (!tracksZoom) {
            value = 1f
            return@produceState
        }
        snapshotFlow { state.zoom }.collectLatest { z ->
            delay(ZOOM_SETTLE_DEBOUNCE_MS)
            backOnComposeThread()
            value = z
        }
    }

    // A host may navigate from any thread: its calls come to this one (#429).
    LaunchedEffect(state) { state.attachViewerThread() }
    // The saved position, in an effect of its own, so a drag or a failure while it resolves
    // cannot stop the loader below (#344).
    LaunchedEffect(state, state.document) { state.openSavedPosition() }
    // Lay the rest of the book out behind the reader. One loader for as long as this state is
    // shown: it follows the reader by itself, so it is not keyed on a value read here (#343).
    LaunchedEffect(state, state.document) { state.loadChapters() }
    // Which side the reader reached a placeholder from, so its chapter lands on the right page (#348).
    // It follows the item, not the slot number: a placeholder that becomes a page keeps its slot.
    LaunchedEffect(state) { snapshotFlow { state.readerItem() }.collect { state.noteReaderItem(it) } }

    // The document's own scripts: its open action once, then each page's as the reader
    // reaches it, and the timers a script set, pumped a frame at a time.
    LaunchedEffect(scripts, state.document) {
        val handler = scripts ?: return@LaunchedEffect
        // A document's own scripts may run for a long time before they show anything, so they
        // run on the script lane and the reader keeps scrolling meanwhile. They run once for each
        // handler, however often this view leaves and comes back (#365).
        withContext(scriptLane) {
            if (state.openedScripts !== handler) {
                state.openedScripts = handler
                scriptCall("documentOpened", Unit) { handler.documentOpened() }
                state.scriptsRan()
            }
        }
        // The page scripts watch the reader with snapshots, which only the composition's thread takes (#443).
        backOnComposeThread()
        // Then each page's open and close scripts, as the reader lands on pages. They take a
        // page's index in the document, which only a PDF has (#366).
        if (state.document is PdfDocument) state.runPageScripts(handler, scriptLane)
    }
    // A field that has the caret when the view leaves commits what the reader typed and lets go
    // of the caret, even when its input never took the focus (#365).
    DisposableEffect(state) { onDispose { state.blurFocusedField() } }
    KiteScriptTimers(state, scripts, scriptLane)
    KiteFormRevision(state, scripts)
    KiteFormInput(state, scripts, scriptLane)

    // Keep callbacks fresh without restarting pointer input during a press or a selection.
    val currentHighlightTap by rememberUpdatedState(onHighlightTap)
    val currentLinkTap by rememberUpdatedState(onLinkTap)
    val currentReferenceTap by rememberUpdatedState(onEpubReferenceTap)
    val currentTap by rememberUpdatedState(onTap)
    val currentScripts by rememberUpdatedState(scripts)
    val tapScope = rememberCoroutineScope()
    val linkAwareTap: (Offset) -> Unit = remember(state, tapScope) {
        { offset ->
            state.clearSelection()
            if (!handleWidgetTap(state, currentScripts, offset, tapScope, currentLinkTap)) {
                state.blurFocusedField()
                val highlight = state.highlightAt(offset)
                val consumed = highlight != null && currentHighlightTap?.invoke(highlight) == true
                if (!consumed && !handleLinkTap(state, tapScope, currentLinkTap, offset, currentReferenceTap)) {
                    currentTap?.invoke(offset)
                }
            }
        }
    }

    Box(
        modifier
            .background(colors.viewportBackground)
            .clipToBounds()
            .onSizeChanged { state.viewportSize = it },
    ) {
        if (state.itemCount > 0) {
            // A new state gets a new layout, containers and all, so nothing of the old document's
            // strip reaches the new one's pages (#346).
            key(state) {
                when (layout) {
                    is KiteDocLayout.Continuous -> ContinuousLayout(
                        state, layout, zoomSpec, renderSpec, colors, pageSpacing,
                        userScrollEnabled, settledZoom, onPageRendered, pagePlaceholder,
                        chapterPlaceholder, linkAwareTap,
                    )
                    is KiteDocLayout.Paged -> PagedLayout(
                        state, layout, zoomSpec, renderSpec, colors, pageSpacing,
                        userScrollEnabled, settledZoom, onPageRendered, pagePlaceholder,
                        chapterPlaceholder, linkAwareTap,
                    )
                    is KiteDocLayout.Spread -> SpreadLayout(
                        state, layout, zoomSpec, renderSpec, colors, pageSpacing,
                        userScrollEnabled, settledZoom, onPageRendered, pagePlaceholder,
                        chapterPlaceholder, linkAwareTap,
                    )
                    is KiteDocLayout.SinglePage -> SinglePageLayout(
                        state, layout, zoomSpec, renderSpec, colors,
                        settledZoom, onPageRendered, pagePlaceholder, chapterPlaceholder, linkAwareTap,
                    )
                }
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
 * [onLinkTap]. An EPUB reference to a note, a glossary entry or a
 * bibliography entry goes to [onEpubReferenceTap] before the viewer
 * follows it. Returns true when the tap was consumed.
 */
internal fun handleLinkTap(
    state: KiteDocViewState,
    scope: kotlinx.coroutines.CoroutineScope,
    onLinkTap: ((KiteLinkAction) -> Boolean)?,
    offset: Offset,
    onEpubReferenceTap: ((EpubLink) -> Boolean)? = null,
): Boolean = try {
    linkTap(state, scope, onLinkTap, offset, onEpubReferenceTap)
} catch (failure: Throwable) {
    // A page whose links cannot be read acts as a page without links (#334).
    io.github.yuroyami.kitepdf.core.kiteWarn { "tap: the links of a page cannot be read: ${failure.message}" }
    false
}

private fun linkTap(
    state: KiteDocViewState,
    scope: kotlinx.coroutines.CoroutineScope,
    onLinkTap: ((KiteLinkAction) -> Boolean)?,
    offset: Offset,
    onEpubReferenceTap: ((EpubLink) -> Boolean)?,
): Boolean {
    val hit = state.hitTest(offset) ?: return false
    when (val page = state.pageAt(hit.pageIndex)) {
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
                // The host may show a note or an entry in place instead (#277).
                if (link.kind != EpubLinkKind.LINK && onEpubReferenceTap?.invoke(link) == true) return true
                // A bookmark needs no layout to build, and following it lays out
                // the target chapter alone rather than the whole book.
                val target = (state.document as? EpubDocument)?.bookmarkOf(link.href) ?: return false
                scope.launch { state.scrollTo(target, animate = true) }
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
 * @param page index of the single page to show, counted over the whole
 *   document, or `null` (default) for the whole document as a continuous
 *   vertical scroll. A book that is still laying out shows a placeholder until
 *   the chapters before the page are laid out, and an index outside the
 *   document shows its nearest page, as [KiteDocLayout.SinglePage] does.
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
    // No check against pageCount here: for a book it lays out every chapter on this thread (#351).
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
    chapterPlaceholder: (@Composable (chapter: Int) -> Unit)?,
    onTap: ((Offset) -> Unit)?,
) {
    // The state owns the position: the layout is keyed on the state, a strip on the other axis
    // gets a new list too, each list is seeded from the state, and none saves anything of its own
    // (#345, #346, #352). The seed reads the position live: the outgoing list parks its own only
    // from onDispose, which runs after this composition.
    val orientation = layout.orientation
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val listState = remember(orientation) {
        val padding = layout.contentPadding
        val crossPx = with(density) {
            if (orientation == Orientation.Vertical) {
                state.viewportSize.width - (padding.calculateStartPadding(direction) + padding.calculateEndPadding(direction)).roundToPx()
            } else {
                state.viewportSize.height - (padding.calculateTopPadding() + padding.calculateBottomPadding()).roundToPx()
            }
        }
        val (index, offset) = state.stripSeed(orientation, crossPx)
        LazyListState(index, offset)
    }
    DisposableEffect(state, listState) {
        // A strip on the other axis starts unpanned: the old pan lies on the axis this one scrolls.
        if (state.stripOrientation.let { it != null && it != orientation }) state.panOffset = Offset.Zero
        state.stripOrientation = orientation
        val adapter = LazyListScrollAdapter(listState)
        state.adapter = adapter
        onDispose {
            state.park(adapter.currentPage, adapter.leadingPage, adapter.scrollOffsetPx, adapter.leadingSlotLength ?: 0)
            if (state.adapter === adapter) state.adapter = null
        }
    }
    LaunchedEffect(state, listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) state.onUserDrag() }
    }

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val stripEnds = remember(state) { StripEndsPan(state) }
    // Magnifier-style zoom: scale the whole strip around the viewport centre.
    // The scroll axis stays native (the list keeps scrolling while zoomed);
    // pan covers the cross axis, and the scroll axis at the strip's ends only.
    // Gestures sit OUTSIDE the layer so they see untransformed viewport coordinates.
    Box(
        Modifier
            .fillMaxSize()
            .kiteTransformGestures(state, zoomSpec, scope, onTap)
            .kiteSelectionGestures(state, haptics)
            .nestedScroll(stripEnds)
            .graphicsLayer {
                scaleX = state.zoom
                scaleY = state.zoom
                translationX = state.panOffset.x
                translationY = state.panOffset.y
            },
    ) {
        val pageItem: @Composable androidx.compose.foundation.lazy.LazyItemScope.(Int) -> Unit = { index ->
            val page = state.pageAt(index)
            if (page == null) {
                ChapterGapSlot(state, index, layout.orientation, colors, chapterPlaceholder, inStrip = true)
            } else {
                ContinuousPageItem(
                    state = state,
                    page = page,
                    pageIndex = index,
                    orientation = layout.orientation,
                    settledZoom = settledZoom,
                    renderSpec = renderSpec,
                    colors = colors,
                    onPageRendered = onPageRendered,
                    pagePlaceholder = pagePlaceholder,
                )
            }
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
                contentPadding = layout.contentPadding,
            ) {
                items(count = state.itemCount, key = { state.items[it].key }) { pageItem(it) }
            }
            Orientation.Horizontal -> LazyRow(
                modifier = anchored,
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(pageSpacing),
                userScrollEnabled = listScrollEnabled,
                contentPadding = layout.contentPadding,
            ) {
                items(count = state.itemCount, key = { state.items[it].key }) { pageItem(it) }
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
    val slotDensity = LocalDensity.current
    val sizing = Modifier.stripSlot(orientation, aspect) {
        with(slotDensity) {
            (if (orientation == Orientation.Vertical) page.displayWidth else page.displayHeight).dp.roundToPx()
        }
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
        PageSlotContent(
            state, page, pageIndex, baseSize, settledZoom, renderSpec, colors,
            onPageRendered, pagePlaceholder, Modifier.fillMaxSize(),
        )
    }
}

/**
 * One page drawn into its slot, with the live form and the highlights over it. Every layout
 * draws its pages through here, so each one shows what a script or the reader changed in the
 * form, in both render modes (#358). With a form layer the page itself leaves the file's
 * widgets out, so a cleared or hidden field does not show its old appearance underneath.
 */
@Composable
private fun PageSlotContent(
    state: KiteDocViewState,
    page: KitePage,
    pageIndex: Int,
    baseSize: IntSize,
    settledZoom: Float,
    renderSpec: KiteRenderSpec,
    colors: KiteDocViewColors,
    onPageRendered: ((Int, ImageBitmap) -> Unit)?,
    pagePlaceholder: (@Composable (Int) -> Unit)?,
    modifier: Modifier,
) {
    val drawsForm = state.scripts != null && page is PdfPage
    // The page's text, built off the main thread once the reader rests on the page, so a long
    // press on a dense page does not build it on the main thread (#380).
    if (state.selectionEnabled) {
        LaunchedEffect(state, page, pageIndex) {
            snapshotFlow { state.currentPage == pageIndex && state.adapter?.isScrollInProgress != true }.first { it }
            state.prepareText(page)
        }
    }
    val formTextMeasurer = rememberTextMeasurer()
    val formFailure = remember(page) { DrawFailure() }
    val slot = modifier
        .kiteFormLayer(
            page, state.scripts, formTextMeasurer,
            // The form layer draws at screen resolution, so a hairline is one screen pixel unless the spec says else.
            (renderSpec as? KiteRenderSpec.Vectorized)?.hairlineWidthPx ?: 1f,
            state.formRevision, formFailure, colors.theme,
        )
        .highlightOverlay(state, page, pageIndex, colors)
    when (renderSpec) {
        is KiteRenderSpec.Rasterized -> KitePageRaster(
            page, pageIndex, baseSize, settledZoom, renderSpec, colors,
            onPageRendered, pagePlaceholder, slot,
            cache = state.bitmapCacheFor(renderSpec.cacheBudgetBytes),
            drawsFormLayer = drawsForm,
            state = state,
        )
        is KiteRenderSpec.Vectorized -> KitePageVector(page, renderSpec, colors, slot, skipWidgets = drawsForm, magnification = settledZoom)
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
    chapterPlaceholder: (@Composable (chapter: Int) -> Unit)?,
    onTap: ((Offset) -> Unit)?,
) {
    // Seeded from the state and saving nothing of its own; see ContinuousLayout's seed comment.
    val pagerState = remember {
        PagerState(currentPage = state.currentPage.coerceIn(0, (state.itemCount - 1).coerceAtLeast(0))) { state.itemCount }
    }
    val pagerAdapter = remember(pagerState) { PagerScrollAdapter(pagerState) }
    DisposableEffect(state, pagerAdapter) {
        state.adapter = pagerAdapter
        onDispose {
            state.park(pagerAdapter.currentPage)
            if (state.adapter === pagerAdapter) state.adapter = null
        }
    }
    LaunchedEffect(state, pagerAdapter) {
        pagerState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    pagerAdapter.dragging = true
                    state.onUserDrag()
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> pagerAdapter.dragging = false
            }
        }
    }
    // Landing on another page recentres the pan (and, per spec, the zoom).
    // Compared by location, not raw index: a keyed remeasure or a correction
    // moves the index while the reader stays on the same content, and that
    // must not cost them their zoom.
    LaunchedEffect(state, pagerState, zoomSpec.resetZoomOnPageChange) {
        // The page the pager appears on is not a change of page, so a zoom the host set or
        // restored before it appeared stays (#403).
        var last: io.github.yuroyami.kitepdf.core.KiteLocation? = state.anchorAt(pagerState.settledPage)
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            val location = state.anchorAt(settled)
            if (location == last) return@collect
            last = location
            state.panOffset = androidx.compose.ui.geometry.Offset.Zero
            if (zoomSpec.resetZoomOnPageChange) state.resetZoom()
        }
    }

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val pageContent: @Composable (Int) -> Unit = { index ->
        val isCurrent = index == pagerState.currentPage
        val page = state.pageAt(index)
        if (page == null) {
            // The same taps and zoom gestures as a page, so a host's onTap still works here (#409).
            ChapterGapSlot(
                state, index, Orientation.Vertical, colors, chapterPlaceholder, letterboxed = true,
                gestures = if (isCurrent) Modifier.kiteTransformGestures(state, zoomSpec, scope, onTap) else Modifier,
                zoom = if (isCurrent) state.zoom else 1f,
                pan = if (isCurrent) state.panOffset else androidx.compose.ui.geometry.Offset.Zero,
            )
        } else PageBox(
            page = page,
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
    // While the zoomed page overflows the viewport, the pager's own swipe is off so
    // one-finger drags pan the page; paging stays available through
    // KiteDocViewState (nav widgets). An
    // active text selection takes the swipe away too, so the page cannot turn
    // under a selection drag.
    val pagerScrollEnabled = userScrollEnabled && !state.overflows && !state.isSelectionActive
    when (layout.orientation) {
        Orientation.Horizontal -> HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = pageSpacing,
            beyondViewportPageCount = layout.offscreenPages,
            userScrollEnabled = pagerScrollEnabled,
            reverseLayout = layout.reverseLayout,
            // Semantic keys: a chapter landing before the reader re-anchors
            // the pager on the same content at measure time (issue #5).
            key = { state.items[it].key },
        ) { pageContent(it) }
        Orientation.Vertical -> VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = pageSpacing,
            beyondViewportPageCount = layout.offscreenPages,
            userScrollEnabled = pagerScrollEnabled,
            reverseLayout = layout.reverseLayout,
            key = { state.items[it].key },
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
    chapterPlaceholder: (@Composable (chapter: Int) -> Unit)?,
    onTap: ((Offset) -> Unit)?,
) {
    // The index counts the pages of the whole document, as for a PDF, not the slots of the strip.
    // Each chapter that lands recomposes this: a book that is still laying out places the page
    // once the chapters before it are ready, and shows a placeholder until then (#336).
    val known = state.knownPageCount
    val wanted = when {
        layout.pageIndex < 0 -> 0
        state.isComplete && layout.pageIndex >= known -> (known - 1).coerceAtLeast(0)
        else -> layout.pageIndex
    }
    if (wanted != layout.pageIndex) {
        remember(layout.pageIndex, wanted) {
            io.github.yuroyami.kitepdf.core.kiteWarn {
                "SinglePage(${layout.pageIndex}) is outside the document's $known page(s); showing page $wanted"
            }
        }
    }
    val slot = state.document.locationOf(wanted)?.let { state.slotFor(it) }?.takeIf { it >= 0 }
        ?: state.firstPendingSlot()
        ?: 0
    DisposableEffect(state, slot) {
        val adapter = FixedPageAdapter(slot)
        state.adapter = adapter
        onDispose {
            state.park(adapter.currentPage)
            if (state.adapter === adapter) state.adapter = null
        }
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val only = state.pageAt(slot)
    if (only == null) {
        // The same taps and zoom gestures as a page, so a host's onTap still works here (#409).
        ChapterGapSlot(
            state, slot, Orientation.Vertical, colors, chapterPlaceholder, letterboxed = true,
            gestures = Modifier.kiteTransformGestures(state, zoomSpec, scope, onTap),
            zoom = state.zoom,
            pan = state.panOffset,
        )
        return
    }
    PageBox(
        page = only,
        pageIndex = slot,
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
            PageSlotContent(
                state, page, pageIndex, fit, settledZoom, renderSpec, colors,
                onPageRendered, pagePlaceholder, Modifier.size(dpSize),
            )
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
    /** True when a form layer draws this page's widgets, so the bitmap must leave them out. */
    drawsFormLayer: Boolean = false,
    /** Where the page's render state goes, and its retry count comes from. */
    state: KiteDocViewState? = null,
) {
    // The spec's long-side cap is the sizing authority in this path, so the
    // rasterizer's pixel ceiling must never undercut maxBitmapLongSide².
    val rasterizer = rememberKitePageRasterizer(
        maxOf(
            KITE_DEFAULT_MAX_RASTER_PIXELS,
            spec.maxBitmapLongSide.toLong() * spec.maxBitmapLongSide,
        ),
    )
    val onRendered by rememberUpdatedState(onPageRendered)

    // The slot's size reaches the raster size only once it stops changing: a window drag, a split
    // screen or an animated size would start a raster per frame at sizes never used again. The
    // bitmap on screen is scaled into the slot meanwhile, and a first size is taken at once (#390).
    val latestBase by rememberUpdatedState(baseSize)
    val settledBase by produceState(baseSize, page) {
        snapshotFlow { latestBase }.collectLatest { size ->
            if (size == value) return@collectLatest
            if (value.width > 0 && value.height > 0) {
                delay(RESIZE_SETTLE_DEBOUNCE_MS)
                backOnComposeThread()
            }
            value = size
        }
    }

    // A zoom that is not finite never reaches here, but the raster size must not round NaN (#338).
    val zoomScale = if (settledZoom.isFinite()) settledZoom.coerceAtLeast(0.01f) else 1f
    val scale = spec.quality * zoomScale
    // A side that rounds below one pixel keeps one, so every accepted quality gives a page (#422).
    val raster = fitWithin(
        (settledBase.width * scale).roundToInt().coerceAtLeast(if (settledBase.width > 0) 1 else 0),
        (settledBase.height * scale).roundToInt().coerceAtLeast(if (settledBase.height > 0) 1 else 0),
        kitePageAspect(page),
        spec.maxBitmapLongSide,
    )
    // Hairline compensation: the engine draws a zero-width stroke 1 *raster* px wide
    // and floors other strokes at a fifth of that. When the raster is larger than its
    // final on-screen size (supersampling), both must grow by the same ratio or
    // sub-pixel strokes fade in the downscale. (Upscaling can only thicken them, so 1 is safe.)
    val visualWidth = settledBase.width * zoomScale
    val hairline = if (spec.preserveHairlines && visualWidth > 0f) {
        max(1f, raster.width / visualWidth)
    } else 1f

    // The page the shown bitmap belongs to, so a failed upgrade keeps it for that page only.
    val shownFor = remember { arrayOfNulls<KitePage>(1) }
    var render by remember { mutableStateOf(KitePageRenderState.Loading) }
    val retry = state?.retriesOf(pageIndex) ?: 0
    // Keyed on the paper the page is drawn on, so a background change that a theme hides renders nothing again (#394).
    val paper = paperColor(colors.pageBackground, colors.theme)
    // Keyed on the rasterizer too: a new font environment gives a new one, and the text renders again (#421).
    val rastered by produceState<Pair<ImageBitmap, Boolean>?>(null, page, raster, paper, colors.theme, hairline, cache, drawsFormLayer, spec.canvasDecorator, retry, rasterizer) {
        // Off the main thread: a 10-30ms page raster on the UI thread
        // janks scroll and pinch. The rasterizer serializes pages on its mutex
        // (TextMeasurer's cache is not thread-safe) but the main thread stays
        // free; the bitmap cache turns scroll-back into a lookup, and neighbour
        // prefetch (KiteDocLayout.Paged offscreenPages) hides first-render latency.
        // rasterizeCachedOrNull carries the mandatory failure guard: an
        // exception escaping produceState aborts the HOST APP.
        if (raster == IntSize.Zero) {
            value = null
            return@produceState
        }
        render = KitePageRenderState.Loading
        val result = rasterizer.rasterizeCachedOrNull(
            cache, page, raster.width, raster.height,
            colors.pageBackground, hairline, colors.theme, pageIndex,
            skipWidgets = drawsFormLayer,
            canvasDecorator = spec.canvasDecorator,
        )
        backOnComposeThread()
        // A failed upgrade, such as a crisp-zoom raster out of memory, keeps the last good
        // bitmap of this page instead of blanking it, and says it failed (#430).
        value = when {
            result != null -> result.also { shownFor[0] = page }
            shownFor[0] === page -> value?.let { it.first to false }
            else -> null
        }
        render = if (result != null) KitePageRenderState.Ready else KitePageRenderState.Failed
    }
    val shownRender = render
    if (state != null) {
        SideEffect { state.noteRender(pageIndex, shownRender) }
        DisposableEffect(state, pageIndex) { onDispose { state.noteRender(pageIndex, null) } }
    }
    val bitmap = rastered?.first

    // Fade the bitmap in once it lands instead of popping (and keep the previous
    // frame visible across a re-raster), so the placeholder→page hand-off and any
    // crisp-zoom refresh read as a smooth dissolve rather than a flash.
    // onPageRendered fires only on FRESH rasterization, never on cache hits.
    ReportFreshRaster(rastered) { bmp -> onRendered?.invoke(pageIndex, bmp) }
    Crossfade(
        targetState = bitmap,
        animationSpec = tween(durationMillis = PAGE_FADE_MS),
        modifier = modifier,
        label = "pdf-page-raster",
    ) { bmp ->
        if (bmp != null) {
            // The raster has the page's shape up to rounding, so it fills the slot. Fitted, a
            // raster of a pixel or two kept its rounded shape and drew as a sliver (#422).
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (pagePlaceholder != null) {
            pagePlaceholder(pageIndex)
        } else {
            // The theme's paper, so night mode does not flash white before a page lands (#419).
            Box(Modifier.fillMaxSize().background(paperColor(colors.pageBackground, colors.theme)))
        }
    }
}

/**
 * Calls [report] once for each fresh bitmap in [rastered]. The effect reads the value
 * it is keyed on, not the live state. A raster that lands while the effect for the old
 * value starts would otherwise be reported by that effect and again by the effect for
 * the new value (#229).
 */
@Composable
internal fun ReportFreshRaster(rastered: Pair<ImageBitmap, Boolean>?, report: (ImageBitmap) -> Unit) {
    LaunchedEffect(rastered) {
        rastered?.let { (bmp, fresh) -> if (fresh) report(bmp) }
    }
}

/* ── the vector slot: live content-stream draw, no bitmap ─────────────────── */

/**
 * Draws [page] straight into a live [Canvas]
 * at the slot's layout resolution. No intermediate bitmap, so memory stays low.
 * Zoom/pan are applied by the enclosing `graphicsLayer` (strip-level in
 * continuous mode, per-page in paged/single). In continuous mode a gesture frame
 * only moves that layer, so the page does not redraw; in the paged layouts a
 * gesture frame recomposes the page slot, and the page redraws with it (#373).
 * Once a zoom settles, the page draws again at it: image sampling and the
 * hairline follow the pixels on screen, not the pixels of the slot (#418).
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
    /** True when a form layer draws this page's widgets, so the page must leave them out. */
    skipWidgets: Boolean = false,
    /** The settled zoom the layer shows the page at, which image sampling and hairlines follow. */
    magnification: Float = 1f,
) {
    val textMeasurer = rememberTextMeasurer()
    val theme = colors.theme
    val failure = remember(page) { DrawFailure() }
    Canvas(modifier) {
        val paper = theme?.background?.let { Color(it.r.toFloat(), it.g.toFloat(), it.b.toFloat()) } ?: colors.pageBackground
        drawRect(paper)
        val w = size.width
        val h = size.height
        val scale = if (page.displayWidth > 0.0) w / page.displayWidth else 0.0
        if (!scale.isFinite() || scale <= 0.0 || w <= 0f || h <= 0f) return@Canvas
        // displayToDeviceBase() maps page space onto a top-left, Y-down device box
        // (PDF folds in the display-box origin + /Rotate; EPUB its top-left flip).
        val deviceCtm = KiteMatrix.scaling(scale, scale).concat(page.displayToDeviceBase())
        // The page ends at its slot, as the bitmap's edge ends it in Rasterized mode: content
        // outside the page, such as bleed, never paints the gap or the next page (#417).
        clipRect {
            val base = ComposeCanvas(this, textMeasurer, spec.hairlineWidthPx, skipSystemFontText = false, magnification = magnification)
            val themed = theme?.wrap(base) ?: base
            val target = spec.canvasDecorator?.invoke(themed) ?: themed
            failure.guard("page") {
                if (skipWidgets && page is PdfPage) {
                    page.renderTo(target, deviceCtm, formState = null, cancellation = NEVER_CANCELLED) {
                        it.subtype != PdfAnnotation.Subtype.Widget
                    }
                } else {
                    page.renderTo(target, deviceCtm)
                }
            }
        }
        // Paper over whatever the failed draw left, so the page shows as blank, not half drawn.
        if (failure.failed) drawRect(paper)
    }
}

/**
 * Keeps a draw in the Compose draw pass from ending the host app, as the raster guard does for
 * rasters (#333). An exception there reaches the platform's uncaught-exception handler. A failed
 * draw is logged once, and the same page is not parsed again on every frame.
 */
internal class DrawFailure {
    var failed: Boolean = false
        private set

    inline fun guard(what: String, draw: () -> Unit) {
        if (failed) return
        try {
            draw()
        } catch (error: Throwable) {
            failed = true
            io.github.yuroyami.kitepdf.core.kiteWarn { "render: the $what failed to draw: ${error.message ?: error::class.simpleName}" }
        }
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
            state.selectionCarets, sx, sy, colors.selectionHandle,
            colors.selectionHandlePainter ?: KiteSelectionHandleDefaults.CaretAndDot,
        )
    }
}

/**
 * The two grab markers that bound the active selection, placed on the carets at
 * its logical start and end. The marker's look
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
    carets: Pair<io.github.yuroyami.kitepdf.core.KiteCaret?, io.github.yuroyami.kitepdf.core.KiteCaret?>?,
    sx: Float,
    sy: Float,
    color: Color,
    painter: KiteSelectionHandlePainter,
) {
    val (start, end) = carets ?: return
    // Each end sits on its own caret: the logical start and end of the selected text, which is
    // on the right of a right-to-left run and across the column of a vertical one (#406).
    with(painter) {
        for ((edge, caret) in listOf(KiteSelectionHandleEdge.Start to start, KiteSelectionHandleEdge.End to end)) {
            if (caret == null) continue
            if (caret.vertical) {
                drawColumnHandle(
                    edge = edge,
                    y = (caret.position * sy).toFloat(),
                    left = (caret.from * sx).toFloat(),
                    right = (caret.to * sx).toFloat(),
                    color = color,
                )
            } else {
                drawHandle(
                    edge = edge,
                    x = (caret.position * sx).toFloat(),
                    top = (caret.from * sy).toFloat(),
                    bottom = (caret.to * sy).toFloat(),
                    color = color,
                )
            }
        }
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

/** How long a page slot's size must hold before its raster follows it (#390). */
private const val RESIZE_SETTLE_DEBOUNCE_MS = 220L

/** Fade-in duration for a freshly rasterized page bitmap. */
private const val PAGE_FADE_MS = 160

/** The cancellation of a live draw, which nothing cancels. */
private val NEVER_CANCELLED = io.github.yuroyami.kitepdf.core.KiteCancellation { false }

/**
 * The slot a chapter occupies while it is still being laid out: one page-shaped
 * box, so the strip has a sensible length and scrolling into an unread part of
 * the book does not hit a wall.
 */
@Composable
private fun ChapterGapSlot(
    state: KiteDocViewState,
    index: Int,
    orientation: Orientation,
    colors: KiteDocViewColors,
    chapterPlaceholder: (@Composable (chapter: Int) -> Unit)?,
    /** True in the continuous strip, where the slot's length follows its shape and must stay representable. */
    inStrip: Boolean = false,
    /** True in a pager, where the slot fills the viewport and the placeholder is fitted inside it as a page is. */
    letterboxed: Boolean = false,
    /** A pager slot's taps and zoom gestures, and the zoom and pan it draws at, as a page's. */
    gestures: Modifier = Modifier,
    zoom: Float = 1f,
    pan: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero,
) {
    val chapter = state.chapterAt(index) ?: return
    val aspect = state.placeholderAspect()
    if (letterboxed) {
        // The shape of the page that replaces it, so the slot does not jump when the chapter lands (#353).
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
            val fit = fitWithin(constraints.maxWidth, constraints.maxHeight, aspect)
            if (fit == IntSize.Zero) return@BoxWithConstraints
            val size = with(LocalDensity.current) { DpSize(fit.width.toDp(), fit.height.toDp()) }
            Box(Modifier.size(size).background(paperColor(colors.pageBackground, colors.theme)), contentAlignment = Alignment.Center) {
                chapterPlaceholder?.invoke(chapter)
            }
        }
        return
    }
    val sizing = if (inStrip) {
        Modifier.stripSlot(orientation, aspect) { 1 }
    } else {
        Modifier
            .then(if (orientation == Orientation.Vertical) Modifier.fillMaxWidth() else Modifier.fillMaxHeight())
            .aspectRatio(aspect)
    }
    Box(
        sizing.background(paperColor(colors.pageBackground, colors.theme)),
        contentAlignment = Alignment.Center,
    ) {
        chapterPlaceholder?.invoke(chapter)
    }
}

/**
 * Sizes a slot of the continuous strip: the full cross axis, and the length that the page's
 * [aspect] gives. Compose packs constraints into one Long, so a slot far longer than wide
 * cannot be represented and threw in measure (#332). Such a page is capped at the longest
 * length Compose can hold, keeps its shape, and is centred on the cross axis. [naturalCrossPx]
 * sizes a strip whose cross axis is unbounded.
 */
private fun Modifier.stripSlot(orientation: Orientation, aspect: Float, naturalCrossPx: () -> Int): Modifier =
    layout { measurable, constraints ->
        val vertical = orientation == Orientation.Vertical
        val bounded = if (vertical) constraints.hasBoundedWidth else constraints.hasBoundedHeight
        val cross = (if (!bounded) naturalCrossPx() else if (vertical) constraints.maxWidth else constraints.maxHeight)
            .coerceAtLeast(1)
        val wanted = wantedSlotLength(vertical, aspect, cross)
        val length = stripSlotLength(vertical, aspect, cross)
        val pageCross = if (length >= wanted) {
            cross
        } else {
            (if (vertical) length * aspect else length / aspect).roundToInt().coerceIn(1, cross)
        }
        val placeable = measurable.measure(
            if (vertical) {
                androidx.compose.ui.unit.Constraints.fixed(pageCross, length)
            } else {
                androidx.compose.ui.unit.Constraints.fixed(length, pageCross)
            },
        )
        layout(if (vertical) cross else length, if (vertical) length else cross) {
            val offset = (cross - pageCross) / 2
            placeable.place(if (vertical) offset else 0, if (vertical) 0 else offset)
        }
    }

/** More than any length Compose can represent, to keep the Float to Int step in range. */
private const val MAX_SLOT_LENGTH = 1_000_000f

/** The length a strip slot asks for on the scroll axis, for a page of [aspect] that is [cross] px across. */
private fun wantedSlotLength(vertical: Boolean, aspect: Float, cross: Int): Int =
    // As a Float first: a very long page overflows an Int.
    (if (vertical) cross / aspect else cross * aspect).coerceIn(1f, MAX_SLOT_LENGTH).roundToInt()

/** The length a strip slot gets on the scroll axis: [wantedSlotLength], capped to what Compose can hold. */
internal fun stripSlotLength(vertical: Boolean, aspect: Float, cross: Int): Int {
    val wanted = wantedSlotLength(vertical, aspect, cross)
    val fitted = if (vertical) {
        androidx.compose.ui.unit.Constraints.fitPrioritizingWidth(cross, cross, wanted, wanted)
    } else {
        androidx.compose.ui.unit.Constraints.fitPrioritizingHeight(wanted, wanted, cross, cross)
    }
    return if (vertical) fitted.maxHeight else fitted.maxWidth
}

/**
 * Lets the reader reach the ends of a zoomed strip (#397). The zoom scales the strip around the
 * viewport centre, so with the list at its start or its end a band of the zoomed content lies
 * outside the viewport. The drag the list cannot use there moves the page along the scroll
 * axis instead, within that band, and a drag back spends that pan before the list scrolls. The
 * list works in its own unscaled pixels, and the pan in viewport pixels, hence the zoom factor.
 */
private class StripEndsPan(private val state: KiteDocViewState) : NestedScrollConnection {

    private fun along(offset: Offset): Float = if (state.panAxes == KiteDocViewState.PanAxes.XOnly) offset.y else offset.x

    private fun offsetAlong(value: Float): Offset =
        if (state.panAxes == KiteDocViewState.PanAxes.XOnly) Offset(0f, value) else Offset(value, 0f)

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val drag = along(available)
        val pan = along(state.panOffset)
        if (drag == 0f || pan == 0f || (drag > 0f) == (pan > 0f)) return Offset.Zero
        // A drag back toward the list takes the page back first.
        val wanted = drag * state.zoom
        val moved = state.panAlongScrollAxis(if (kotlin.math.abs(wanted) > kotlin.math.abs(pan)) -pan else wanted)
        return offsetAlong(moved / state.zoom)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        val drag = along(available)
        if (drag == 0f) return Offset.Zero
        return offsetAlong(state.panAlongScrollAxis(drag * state.zoom) / state.zoom)
    }
}

/* ── spread pager: two pages per item, like an open book ───────────── */

/**
 * Two-page spreads. Pairing is by page index, so inserting a chapter would
 * re-pair the whole book under the reader. Spreads therefore lay the document
 * out fully before composing; they are meant for fixed-layout content anyway.
 */
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
    chapterPlaceholder: (@Composable (chapter: Int) -> Unit)?,
    onTap: ((Offset) -> Unit)?,
) {
    // Spreads pair pages across the whole book, so they wait until every chapter is laid out and
    // on the strip. Until then the chapter placeholder shows, and the loader lays the book out
    // behind it, off this thread. Reading pageCount here laid the book out on this thread (#337).
    if (!state.stripSettled) {
        val scope = rememberCoroutineScope()
        val slot = state.currentPage.takeIf { state.pageAt(it) == null } ?: state.firstPendingSlot() ?: state.currentPage
        ChapterGapSlot(
            state, slot, Orientation.Vertical, colors, chapterPlaceholder, letterboxed = true,
            gestures = Modifier.kiteTransformGestures(state, zoomSpec, scope, onTap),
            zoom = state.zoom,
            pan = state.panOffset,
        )
        return
    }
    val spreadCount = (state.itemCount + 1) / 2
    // Seeded from the state and saving nothing of its own; see ContinuousLayout's seed comment.
    val pagerState = remember {
        PagerState(currentPage = (state.currentPage / 2).coerceIn(0, spreadCount - 1)) { (state.itemCount + 1) / 2 }
    }
    DisposableEffect(state, pagerState) {
        // The page the state holds, which the pager's last adapter parked when it left (#402).
        val adapter = SpreadScrollAdapter(pagerState, initialPage = state.currentPage)
        state.adapter = adapter
        onDispose {
            state.park(adapter.currentPage)
            if (state.adapter === adapter) state.adapter = null
        }
    }
    // Landing on another spread recentres the pan and, per spec, resets the zoom. The spread the
    // pager appears on is not a change, and a spread is compared by its first page (#403).
    LaunchedEffect(state, pagerState, zoomSpec.resetZoomOnPageChange) {
        var last = state.anchorAt(pagerState.settledPage * 2)
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            val location = state.anchorAt(settled * 2)
            if (location == last) return@collect
            last = location
            state.panOffset = Offset.Zero
            if (zoomSpec.resetZoomOnPageChange) state.resetZoom()
        }
    }

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val pagerScrollEnabled = userScrollEnabled && !state.overflows && !state.isSelectionActive
    val spreadContent: @Composable (Int) -> Unit = { spread ->
        val isCurrent = spread == pagerState.currentPage
        SpreadBox(
            state = state,
            leftIndex = 2 * spread,
            rightIndex = (2 * spread + 1).takeIf { it < state.itemCount },
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
            chapterPlaceholder = chapterPlaceholder,
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
    chapterPlaceholder: (@Composable (chapter: Int) -> Unit)?,
) {
    if (recordGeometry) {
        DisposableEffect(state, leftIndex, rightIndex) {
            onDispose {
                state.pageGeometry.remove(leftIndex)
                rightIndex?.let { state.pageGeometry.remove(it) }
            }
        }
    }
    // Pages are placed from the physical left, as their hit rectangles are, so a right-to-left
    // app does not draw a page on one side and hit-test it on the other (#401).
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
        contentAlignment = AbsoluteAlignment.TopLeft,
    ) {
        val density = LocalDensity.current
        val fullW = constraints.maxWidth
        val fullH = constraints.maxHeight

        @Composable
        fun slot(pageIndex: Int, regionLeft: Int, regionWidth: Int) {
            val page = state.pageAt(pageIndex)
            if (page == null) {
                // A chapter whose layout failed stays a placeholder, in its half of the spread.
                val region = with(density) { DpSize(regionWidth.toDp(), fullH.toDp()) }
                Box(Modifier.absoluteOffset(x = with(density) { regionLeft.toDp() }).size(region)) {
                    ChapterGapSlot(state, pageIndex, Orientation.Vertical, colors, chapterPlaceholder, letterboxed = true)
                }
                return
            }
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
                    .absoluteOffset(x = dpOffset.width, y = dpOffset.height)
                    .size(dpSize),
            ) {
                PageSlotContent(
                    state, page, pageIndex, fit, settledZoom, renderSpec, colors,
                    onPageRendered, pagePlaceholder, Modifier.fillMaxSize(),
                )
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
