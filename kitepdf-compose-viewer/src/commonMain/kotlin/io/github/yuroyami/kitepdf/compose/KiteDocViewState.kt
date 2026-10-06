package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.gestures.animateScrollBy
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerLayoutInfo
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.yuroyami.kitepdf.PdfChoiceSelection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormField
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.core.KiteRectangle
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntSize
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.KiteSearchHit
import io.github.yuroyami.kitepdf.core.KiteStructuredText
import io.github.yuroyami.kitepdf.core.render.KiteBitmapCache
import io.github.yuroyami.kitepdf.core.kiteWarn
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPage
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Remembers a [KiteDocViewState] for [document]. Hoist it to drive a
 * [KiteDocView] from anywhere: navigation buttons in your top bar, a zoom
 * slider, a HUD overlay. The state object is the single point of control.
 *
 * Takes any [KiteDocument], so a [io.github.yuroyami.kitepdf.PdfDocument] and
 * an [io.github.yuroyami.kitepdf.epub.EpubDocument] both go here; one viewer
 * path serves both formats.
 *
 * The state is saved with [KiteDocViewState.saver], so the reading position
 * survives a configuration change and the end of the process. The state is
 * the only thing that saves the position: the viewer's scroll containers save
 * nothing of their own.
 */
@Composable
public fun rememberKiteDocViewState(document: KiteDocument, initialPage: Int = 0): KiteDocViewState =
    rememberSaveable(document, saver = KiteDocViewState.saver(document)) { KiteDocViewState(document, initialPage) }

/**
 * Remembers a [KiteDocViewState] opened at [bookmark], a position saved from an
 * earlier reading session.
 *
 * This is the fast way into a big reflowable book: only the bookmark's chapter
 * is laid out before the page appears, and the rest follows in the background.
 * Take the bookmark back with [KiteDocViewState.currentBookmark].
 *
 * ```kotlin
 * val state = rememberKiteDocViewState(book, savedBookmark)
 * KiteDocView(state, Modifier.fillMaxSize())
 * ```
 */
@Composable
public fun rememberKiteDocViewState(document: KiteDocument, bookmark: KiteBookmark): KiteDocViewState =
    rememberSaveable(document, saver = KiteDocViewState.saver(document)) { KiteDocViewState(document, bookmark) }

/** Remembers a state reopened at a page and continuous scroll offset. */
@Composable
public fun rememberKiteDocViewState(document: KiteDocument, position: KiteScrollPosition): KiteDocViewState =
    rememberSaveable(document, saver = KiteDocViewState.saver(document)) { KiteDocViewState(document, position) }

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
 *
 * The suspend calls, such as [scrollTo] and [animateZoomTo], may come from any thread. They run
 * on the thread of the [KiteDocView] that shows the state, so do not block that thread to wait
 * for one. The calls that do not suspend, such as [setZoom] and [clearSelection], belong on the
 * main thread.
 */
@Stable
public class KiteDocViewState(
    public val document: KiteDocument,
    initialPage: Int = 0,
) {
    /** Opens at a saved reading position instead of a page number. */
    public constructor(document: KiteDocument, bookmark: KiteBookmark) : this(document, 0) {
        openAt = bookmark
        // A Flow bookmark names its chapter outright, and the chapter's slot
        // is knowable from the current strip (a gap or its first page), so
        // the first composed frame already shows the target chapter instead
        // of the start of the book (issue #5).
        pendingPage = when (bookmark) {
            is KiteBookmark.Flow -> {
                val chapter = bookmark.chapter.coerceIn(0, (document.chapterCount - 1).coerceAtLeast(0))
                slotFor(KiteLocation(chapter, 0)).coerceAtLeast(0)
            }
            is KiteBookmark.Page -> bookmark.pageIndex.coerceAtLeast(0)
        }
    }

    /** Opens at [position], including the continuous layout's pixel offset. */
    public constructor(document: KiteDocument, position: KiteScrollPosition) : this(document, 0) {
        openScrollAt = position
        pendingPage = slotFor(position.location).coerceAtLeast(0)
        pendingScrollOffset = position.offsetPx
    }

    private var openScrollAt: KiteScrollPosition? = null
    internal var pendingScrollOffset: Int = 0

    /** Where the viewer should start. Resolved on first composition. */
    internal var openAt: KiteBookmark? = null

    /* ── chapters and the item strip ──────────────────────────────────────── */

    /**
     * The strip as of the latest publication. One snapshot value, built again only by
     * [onChapterReady], so a read from any thread sees a whole strip (#429). Every publication
     * tells the readers, even one that left the strip as it was.
     */
    private var strip: List<DocItem> by mutableStateOf(buildItems(document), neverEqualPolicy())

    /** Call after a chapter lands, on the composition's thread. */
    internal fun onChapterReady() {
        strip = buildItems(document)
    }

    /**
     * What the viewer scrolls through: one entry per laid-out page, plus one
     * page-shaped placeholder for each chapter still being laid out.
     *
     * Built per publication: a chapter laid out but not yet published stays
     * invisible until [onChapterReady], so the pager's count, key and content
     * callbacks always agree on one strip version.
     */
    internal val items: List<DocItem> get() = strip

    /** How many slots the strip has. This is what the lists and pagers count. */
    internal val itemCount: Int get() = items.size

    /** The page in slot [index], or null when that slot is a placeholder. */
    internal fun pageAt(index: Int): KitePage? =
        (items.getOrNull(index) as? DocItem.Page)?.let { document.page(it.location) }

    /** The chapter whose placeholder is in slot [index], or null for a page. */
    internal fun chapterAt(index: Int): Int? =
        (items.getOrNull(index) as? DocItem.ChapterGap)?.chapter

    /**
     * Width-over-height for a placeholder slot: the first laid-out page's shape,
     * so a gap is the same size as the pages around it. A4-ish when nothing is
     * laid out yet.
     */
    internal fun placeholderAspect(): Float {
        for (i in items.indices) {
            val page = pageAt(i) ?: continue
            return kitePageAspect(page)
        }
        return 1f / 1.4142f
    }

    /**
     * The real page in [slot], or null for a placeholder or an out-of-range slot.
     * Reads only the published strip: it never lays out a chapter (#340).
     */
    public fun locationOf(slot: Int): KiteLocation? = (items.getOrNull(slot) as? DocItem.Page)?.location

    /**
     * The exact published slot of [location], or null while that page is unavailable.
     * Unlike navigation, this never substitutes a placeholder or lays out a chapter.
     */
    public fun slotOf(location: KiteLocation): Int? = slotsByLocation[location]

    private val slotsByLocation: Map<KiteLocation, Int> by androidx.compose.runtime.derivedStateOf {
        buildMap {
            items.forEachIndexed { slot, item -> if (item is DocItem.Page) put(item.location, slot) }
        }
    }

    /** The slot showing [location], or -1 when its chapter is not published. */
    internal fun indexOf(location: KiteLocation): Int = slotOf(location) ?: -1

    /** Only the prefix before the first gap has known global page indices. */
    private val knownGlobalPrefix: Int by androidx.compose.runtime.derivedStateOf {
        items.indexOfFirst { it is DocItem.ChapterGap }.let { if (it < 0) items.size else it }
    }

    private fun slotOf(hit: KiteSearchHit): Int? {
        val location = hit.location
        return if (location != null) slotOf(location)
        else hit.pageIndex.takeIf { it >= 0 && it < knownGlobalPrefix }
    }

    /**
     * Where [location] is now: its own slot, or the placeholder its chapter is
     * still holding. -1 only when the chapter is gone. Use this to follow a
     * reader across a change in the strip, since the slot they are on may be a
     * placeholder that has not become pages yet.
     */
    internal fun slotFor(location: KiteLocation): Int {
        val exact = indexOf(location)
        if (exact >= 0) return exact
        return items.indexOfFirst { it is DocItem.ChapterGap && it.chapter == location.chapter }
    }

    /**
     * The first slot at or after [location]: its own page, its chapter's placeholder, or the
     * next slot in reading order when the location has none, as for an empty chapter. The last
     * slot when nothing follows it (#347).
     */
    internal fun slotAtOrAfter(location: KiteLocation): Int {
        val strip = items
        val exact = slotFor(location)
        if (exact >= 0) return exact
        val next = strip.indexOfFirst { it.anchor >= location }
        return if (next >= 0) next else (strip.size - 1).coerceAtLeast(0)
    }

    /**
     * The reading position of slot [index]: a page's own location, or the first
     * page of the chapter whose placeholder sits there. Null past the strip.
     */
    internal fun anchorAt(index: Int): KiteLocation? = items.getOrNull(index)?.anchor

    /** The chapters of the page slots that the viewer has composed, which are the ones on screen. */
    internal fun chaptersOnScreen(): Set<Int> =
        pageGeometry.keys.mapNotNullTo(HashSet()) { slot -> (items.getOrNull(slot) as? DocItem.Page)?.location?.chapter }

    /**
     * Where the reader in slot [index] is, in terms that survive a change of the strip. A
     * placeholder remembers the side the reader came from, so a chapter the reader paged back
     * onto opens at its last page when it lands (#348).
     */
    internal fun readerAnchorAt(index: Int): ReaderAnchor? = when (val item = items.getOrNull(index)) {
        is DocItem.Page -> ReaderAnchor(item.location)
        is DocItem.ChapterGap -> ReaderAnchor(
            KiteLocation(item.chapter, 0),
            onPlaceholder = true,
            fromEnd = lastReadPage?.let { it.chapter > item.chapter } == true,
        )
        null -> null
    }

    /** The slot [anchor] names in the strip as it stands now. */
    internal fun slotOf(anchor: ReaderAnchor): Int {
        val chapter = anchor.location.chapter
        if (!anchor.onPlaceholder || !document.isChapterReady(chapter)) return slotAtOrAfter(anchor.location)
        val pages = document.pageCountIn(chapter)
        if (pages == 0) {
            // The chapter came out empty: the reader goes on in the direction they were moving.
            if (!anchor.fromEnd) return slotAtOrAfter(anchor.location)
            val before = items.indexOfLast { it.anchor.chapter < chapter }
            return if (before >= 0) before else slotAtOrAfter(anchor.location)
        }
        return slotFor(KiteLocation(chapter, if (anchor.fromEnd) pages - 1 else 0))
    }

    /**
     * The last page, not placeholder, that the reader was on. It tells which side a
     * placeholder was reached from.
     */
    internal var lastReadPage: KiteLocation? = null
        private set

    /** The item the reader is on now: a page, a placeholder, or null in an empty strip. */
    internal fun readerItem(): DocItem? = items.getOrNull(currentPage)

    /** Records the item the reader is on. The viewer calls it whenever that item changes. */
    internal fun noteReaderItem(item: DocItem?) {
        if (item is DocItem.Page) lastReadPage = item.location
    }

    /**
     * Where the reader is. Always exact, even mid-layout, unlike a global page
     * number which cannot exist until the pages before it are counted.
     */
    public val currentLocation: KiteLocation
        get() = anchorAt(currentPage) ?: KiteLocation.START

    /**
     * The leading visible page and how many pixels have scrolled past its
     * leading edge. Unlike [currentLocation], this anchors the viewport edge,
     * not its centre, so a continuous viewport can be restored without a jump.
     * Reads observe scroll state. Paged and single-page layouts report zero.
     * See [KiteScrollPosition] for the layout and zoom boundaries.
     */
    public val currentScrollPosition: KiteScrollPosition
        get() {
            val scroll = adapter ?: return openScrollAt ?: KiteScrollPosition(
                anchorAt(inStrip(pendingLeadingPage ?: pendingPage)) ?: KiteLocation.START, pendingScrollOffset,
            )
            return KiteScrollPosition(
                anchorAt(scroll.leadingPage) ?: KiteLocation.START, scroll.scrollOffsetPx,
            )
        }

    /**
     * True while the view scrolls or turns a page, by a gesture or by an animation, and false
     * with no viewer. Reads observe scroll state, so a `snapshotFlow` can wait for the view to
     * settle before it counts [currentLocation] as a place the reader went to, and something
     * that turns pages for the reader can hold off while the reader moves the view.
     */
    public val isScrollInProgress: Boolean
        get() = adapter?.isScrollInProgress == true

    /**
     * A position to save now and reopen with later. Survives a font size, page
     * size or margin change: hand it to [rememberKiteDocViewState].
     *
     * It lays nothing out: on a chapter that is not laid out yet, the reader is at
     * its start, which is the bookmark (#379).
     */
    public fun currentBookmark(): KiteBookmark {
        val here = currentLocation
        if (!document.isChapterReady(here.chapter)) return KiteBookmark.Flow(here.chapter, 0)
        return document.bookmarkOf(here)
    }

    /** True once every chapter is laid out and [pageCount] is final. */
    public val isComplete: Boolean
        get() {
            strip
            return document.isComplete
        }

    /**
     * Pages laid out so far. While a book is still paginating this grows; show
     * it as an approximate total until [isComplete].
     */
    public val knownPageCount: Int
        get() {
            strip
            return document.knownPageCount
        }

    /**
     * Pages in the whole document.
     *
     * Lays out every chapter of a reflowable book, which is the wait the
     * chapter API exists to avoid. Prefer [knownPageCount] with [isComplete].
     */
    public val pageCount: Int get() = document.pageCount

    /** Current zoom factor. 1 = fit. Bounded by [KiteZoomSpec.minZoom]/[maxZoom]. */
    public var zoom: Float by mutableFloatStateOf(1f)
        private set

    /** The viewer's reader theme, which the thumbnail strip draws with (#419). */
    internal var viewerTheme: io.github.yuroyami.kitepdf.core.render.ReaderTheme? by mutableStateOf(null)

    /** The viewer's canvas decorator, which the thumbnail strip draws with (#419). */
    internal var viewerDecorator: KiteCanvasDecorator? by mutableStateOf(null)

    /** How the raster of each page on screen stands, by slot (#430). */
    private val renderStates = mutableStateMapOf<Int, KitePageRenderState>()

    /** How many times the host asked each slot to render again. */
    private val retries = mutableStateMapOf<Int, Int>()

    /**
     * How the raster of the page in slot [pageIndex] stands: loading, ready or failed, or null
     * while that page is not on screen. A host can show a failed page with a retry button in its
     * `pagePlaceholder`. A page that failed keeps the last bitmap it had, and [retryPage] renders
     * it again. Rasterized mode only: a Vectorized page draws on every frame.
     */
    public fun pageRenderState(pageIndex: Int): KitePageRenderState? = renderStates[pageIndex]

    /** Renders the page in slot [pageIndex] again, for example after its raster failed. */
    public fun retryPage(pageIndex: Int) {
        retries[pageIndex] = (retries[pageIndex] ?: 0) + 1
    }

    internal fun retriesOf(pageIndex: Int): Int = retries[pageIndex] ?: 0

    internal fun noteRender(pageIndex: Int, render: KitePageRenderState?) {
        if (render == null) renderStates.remove(pageIndex) else if (renderStates[pageIndex] != render) renderStates[pageIndex] = render
    }

    /** Pan translation in viewport px, applied after [zoom] around the viewport centre. */
    public var panOffset: Offset by mutableStateOf(Offset.Zero)
        internal set

    /**
     * True once zoomed in beyond the minimum (with a small epsilon). A double tap from here goes
     * back to the minimum. Whether a drag pans or turns the page depends on whether the zoomed
     * page overflows the viewport, not on this flag, so a range that does not start at 1 still
     * routes the gestures by what shows (#398).
     */
    public val isZoomed: Boolean get() = zoom > zoomRange.start + EPSILON

    /**
     * True while the zoomed content overflows the viewport on an axis the reader can pan: a
     * one-finger drag then pans the page, and a pager does not swipe (#398).
     */
    internal val overflows: Boolean
        get() {
            if (viewportSize == IntSize.Zero) return false
            val (_, slack) = panRoom(zoom)
            return (panAxes.x && slack.x > EPSILON) || (panAxes.y && slack.y > EPSILON)
        }

    /**
     * Search hits to paint as translucent quads over their pages (colour:
     * [KiteDocViewColors.searchHighlight]). Feed it from `PdfDocument.search`,
     * `EpubDocument.search` or `KiteStructuredText.search` (quads are display-space, as both produce);
     * clear it by assigning an empty list.
     *
     * Every hit here paints in the same colour. For marks that each carry their
     * own colour, or that want a marker in the page margin, use [highlights]
     * instead. Both channels paint, [searchHighlights] first.
     *
     * A hit's location wins over its legacy integer. Without a location, the integer is a
     * global page index and paints only once all preceding chapter sizes are published.
     */
    public var searchHighlights: List<KiteSearchHit> by mutableStateOf(emptyList())

    /** Hits resolved to published slots once per list or strip change (#340, #372). */
    internal val searchHitsByPage: Map<Int, List<KiteSearchHit>> by androidx.compose.runtime.derivedStateOf {
        val groups = LinkedHashMap<Int, MutableList<KiteSearchHit>>()
        for (hit in searchHighlights) slotOf(hit)?.let { groups.getOrPut(it) { ArrayList() }.add(hit) }
        groups
    }

    /**
     * App-owned highlights, each with its own fill colour and its own optional
     * margin marker. Painted over the page after [searchHighlights], in list
     * order, so later entries win where they overlap. Clear by assigning an
     * empty list.
     *
     * ```kotlin
     * state.highlights = notes.map { note ->
     *     KiteHighlight(
     *         hit = KiteSearchHit(note.location, note.quads, note.text),
     *         color = note.category.tint,
     *         edgeMarker = true,
     *     )
     * }
     * ```
     *
     * Save the selection's location with the quads. They survive chapter publication and
     * reopening with the same content and layout, but must be recomputed after a reflow.
     * Unlocated hits follow the global-index fallback of [searchHighlights].
     * See [KiteHighlight] for the per-entry knobs.
     */
    public var highlights: List<KiteHighlight> by mutableStateOf(emptyList())

    /** Marks resolved to published slots, in host order, shared by painting and taps. */
    internal val highlightsByPage: Map<Int, List<KiteHighlight>> by androidx.compose.runtime.derivedStateOf {
        val groups = LinkedHashMap<Int, MutableList<KiteHighlight>>()
        for (highlight in highlights) slotOf(highlight.hit)?.let { groups.getOrPut(it) { ArrayList() }.add(highlight) }
        groups
    }

    /**
     * The slot the viewport rests on: the snapped page in paged mode, the page
     * nearest the viewport centre in continuous mode.
     *
     * This counts what is on screen. For a PDF, and for a book that has finished
     * laying out, it is the page index. While a reflowable book is still
     * paginating, chapters that are not laid out yet occupy one slot each, so it
     * reads a little low until they land, and then it is exact. Use
     * [currentLocation] when you need the position itself rather than a number.
     */
    public val currentPage: Int
        get() = adapter?.currentPage ?: inStrip(pendingPage)

    /** Explicitly named alias of [currentPage]; includes chapter placeholders. */
    public val currentSlot: Int get() = currentPage

    /** [slot] clamped into the strip, so a start page past the end opens at the last page (#262). */
    private fun inStrip(slot: Int): Int = slot.coerceIn(0, (itemCount - 1).coerceAtLeast(0))

    /* ── internal wiring (set by KiteDocView during composition) ─────────────── */

    /** The document's script handler, when the host gave the viewer one. */
    internal var scripts: io.github.yuroyami.kitepdf.PdfScriptHandler? by mutableStateOf(null)

    /** The book's script handler, when the host gave the viewer one (#41). */
    internal var epubScripts: io.github.yuroyami.kitepdf.epub.EpubScriptHandler? = null

    /**
     * The form field that has the caret, or null when none has. A viewer shows the platform
     * keyboard while it is set, and what the reader types goes through the document's scripts.
     */
    public var focusedField: String? by mutableStateOf(null)
        internal set

    /** Where script work is posted, so a long script never runs on the thread that draws. */
    internal var scriptScope: kotlinx.coroutines.CoroutineScope? = null

    /** The lane this state's view calls the scripts on, one call at a time (#365). */
    internal var scriptLane: kotlinx.coroutines.CoroutineDispatcher? = null

    /**
     * The handler whose document open scripts already ran for this state, so a view that leaves
     * and comes back does not run them again (#365). Written on the script lane.
     */
    @kotlin.concurrent.Volatile
    internal var openedScripts: io.github.yuroyami.kitepdf.PdfScriptHandler? = null

    /** Which widget of [focusedField] has the caret: its place in the field's widgets. */
    private var focusedWidget = 0

    /** A widget's slot, and its rectangle in the page's display space: y down, from the page's top-left. */
    internal class WidgetBox(val slot: Int, val rect: Rect)

    /** The widget of [focusedField] that took the caret, or null when that is not known. */
    internal var focusedWidgetBox: WidgetBox? = null
        private set

    /**
     * Where the widget with the caret sits in the viewport, in pixels, or null when that is not
     * known. The keyboard input sits there, so the platform keeps the widget in view (#362).
     */
    internal fun focusedWidgetArea(): Rect? {
        val box = focusedWidgetBox ?: return null
        val topLeft = displayToViewport(box.slot, box.rect.left.toDouble(), box.rect.top.toDouble()) ?: return null
        val bottomRight = displayToViewport(box.slot, box.rect.right.toDouble(), box.rect.bottom.toDouble()) ?: return null
        return Rect(topLeft, bottomRight)
    }

    /** One open widget, with transaction data owned by the serial script lane. */
    internal class ChoiceSession(
        val name: String,
        val widget: Int,
        val box: WidgetBox?,
        val handler: io.github.yuroyami.kitepdf.PdfScriptHandler,
    ) {
        var ready by mutableStateOf(false)
        @kotlin.concurrent.Volatile var cancelled = false
        @kotlin.concurrent.Volatile var expectedRevision = handler.formState.fieldRevision(name)
        var acceptedSelection = handler.formState.choiceSelection(name)
        var draft = acceptedSelection
        var dirty = false
        // The request counter belongs to the viewer thread and scopes asynchronous replies.
        var request = 0
    }

    private var choiceSession: ChoiceSession? by mutableStateOf(null)
    internal val choiceInputSession: ChoiceSession? get() = choiceSession
    internal var formInputGeneration: Int by mutableIntStateOf(0)
        private set
    internal val choiceField: String? get() = choiceSession?.name
    internal val choiceWidgetBox: WidgetBox? get() = choiceSession?.box
    internal val choiceWidgetIndex: Int get() = choiceSession?.widget ?: 0
    internal var choiceDraft: PdfChoiceSelection? by mutableStateOf(null)
        private set
    internal var choiceCommitPending: Boolean by mutableStateOf(false)
        private set
    internal var choiceRejected: Boolean by mutableStateOf(false)
        private set
    internal var captureChoiceInput: ((ChoiceSession) -> (() -> Unit)?)? = null
    internal var choiceKeyHandler: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null

    internal fun choiceWidgetArea(): Rect? {
        val box = choiceWidgetBox ?: return null
        return displayRectToViewport(box.slot, KiteRectangle(
            box.rect.left.toDouble(), box.rect.top.toDouble(), box.rect.right.toDouble(), box.rect.bottom.toDouble(),
        ))
    }

    /** Opens a picker or list, with a text caret only for an editable combo (ISO 32000-1, 12.7.4.4). */
    internal fun openChoice(fieldName: String, widgetIndex: Int = 0, box: WidgetBox? = null) {
        val handler = scripts ?: return
        val field = (document as? PdfDocument)?.formField(fieldName) ?: return
        if (field.type != PdfFormField.FieldType.Choice || handler.formState.isReadOnly(fieldName) || handler.formState.isHidden(fieldName)) return
        if (choiceField == fieldName && choiceWidgetIndex == widgetIndex && choiceSession?.handler === handler) return
        blurFocusedField()
        choiceSession = ChoiceSession(fieldName, widgetIndex, box, handler)
        choiceDraft = handler.formState.choiceSelection(fieldName)
        choiceCommitPending = true
        choiceRejected = false
        if (field.isEditableCombo) focusField(fieldName, widgetIndex, box)
        else focusChoice(requireNotNull(choiceSession))
    }

    /** Establishes the baseline after the previous widget's queued blur has finished. */
    private fun focusChoice(session: ChoiceSession) {
        val work = {
            scriptCall("focus", Unit) { session.handler.focus(session.name, session.widget) }
            session.expectedRevision = session.handler.formState.fieldRevision(session.name)
            session.acceptedSelection = session.handler.formState.choiceSelection(session.name)
            session.draft = session.acceptedSelection
            scriptsRan()
            session.draft
        }
        val answer: (PdfChoiceSelection?) -> Unit = { selection ->
            if (choiceSession === session) {
                session.ready = true
                if (session.request == 0) {
                    choiceDraft = selection
                    choiceCommitPending = false
                }
            }
        }
        val lane = scriptLane
        val scope = scriptScope
        if (lane == null || scope == null) answer(work())
        else kotlinx.coroutines.CoroutineScope(lane).launch {
            val selection = work()
            scope.launch {
                backOnComposeThread()
                answer(selection)
            }
        }
    }

    /** Every row proposal runs selection /K; deferred lists keep its accepted result until blur. */
    internal fun chooseChoice(selection: PdfChoiceSelection, commit: Boolean = true) {
        val session = choiceSession ?: return
        val field = (document as? PdfDocument)?.formField(session.name) ?: return
        val valid = field.validateChoiceSelection(selection)
        if (valid == null || session.handler.formState.isReadOnly(session.name) || session.handler.formState.isHidden(session.name)) {
            choiceRejected = true
            return
        }
        choiceDraft = valid
        choiceRejected = false
        val request = ++session.request
        choiceCommitPending = true
        val handler = session.handler
        val flushInput = captureChoiceInput?.invoke(session)
        val work = {
            flushInput?.invoke()
            val accepted = if (!choiceIsCurrent(session)) null else scriptCall("choice keystroke", null) {
                handler.choiceKeystroke(session.name, valid, session.draft ?: PdfChoiceSelection(emptyList()))
            }
            val kept = accepted != null && choiceIsCurrent(session)
            if (kept) {
                session.draft = accepted
                session.dirty = true
            }
            val finished = kept && (!commit || commitChoice(session))
            scriptsRan()
            Pair(finished, session.draft)
        }
        val answer: (Pair<Boolean, PdfChoiceSelection?>) -> Unit = { (accepted, draft) ->
            if (choiceSession === session && request == session.request) {
                choiceCommitPending = false
                choiceRejected = !accepted
                choiceDraft = draft
                if (commit) {
                    editingText = null
                    if (!accepted && focusedField == session.name) formInputGeneration++
                }
                if (handler.formState.fieldRevision(session.name) != session.expectedRevision) dismissChoice(commit = false)
            }
        }
        val lane = scriptLane
        val scope = scriptScope
        if (lane == null || scope == null) answer(work())
        else kotlinx.coroutines.CoroutineScope(lane).launch {
            val result = work()
            scope.launch {
                backOnComposeThread()
                answer(result)
            }
        }
    }

    /** Called on the script lane, including before and after a slow user callback. */
    private fun choiceIsCurrent(session: ChoiceSession): Boolean = !session.cancelled &&
        session.handler.formState.fieldRevision(session.name) == session.expectedRevision &&
        !session.handler.formState.isReadOnly(session.name) && !session.handler.formState.isHidden(session.name)

    private fun commitChoice(session: ChoiceSession): Boolean {
        if (!choiceIsCurrent(session)) return false
        val selection = session.draft ?: return false
        val accepted = scriptCall("choice commit", false) { session.handler.commitChoice(session.name, selection) }
        if (accepted) {
            session.expectedRevision = session.handler.formState.fieldRevision(session.name)
            session.acceptedSelection = session.handler.formState.choiceSelection(session.name)
            session.draft = session.acceptedSelection
            session.dirty = false
        } else restoreChoice(session)
        return accepted
    }

    /** Roll back only our own editable keystrokes, preserving newer resets or script writes. */
    private fun restoreChoice(session: ChoiceSession) {
        val accepted = session.acceptedSelection ?: return
        if (session.handler.formState.setChoiceSelection(session.name, accepted, session.expectedRevision)) {
            session.expectedRevision = session.handler.formState.fieldRevision(session.name)
            session.draft = accepted
            session.dirty = false
        }
    }

    /** Finalizes after queued previews, or cancels them and restores accepted editable input. */
    internal fun dismissChoice(commit: Boolean = true) {
        val session = choiceSession ?: return
        val flushInput = if (commit) captureChoiceInput?.invoke(session) else null
        if (!commit) {
            session.cancelled = true
            val revision = session.expectedRevision
            if (session.handler.formState.cancelChoiceTransaction(session.name, revision)) session.expectedRevision = revision + 1
        }
        choiceSession = null
        choiceDraft = null
        choiceCommitPending = false
        choiceRejected = false
        choiceKeyHandler = null
        if (focusedField == session.name) {
            formInputGeneration++
            focusedField = null
            focusedWidgetBox = null
            editingText = null
        }
        val work = {
            if (commit) {
                flushInput?.invoke()
                if (session.dirty) commitChoice(session)
            } else restoreChoice(session)
            scriptCall("blur", Unit) { session.handler.blur(session.name, session.widget) }
            scriptsRan()
        }
        val lane = scriptLane
        if (lane == null) work() else kotlinx.coroutines.CoroutineScope(lane).launch { work() }
    }

    /** Moves keyboard focus among the visible editable text and choice widgets in annotation order. */
    internal fun focusNextFormField(backwards: Boolean = false) {
        val handler = scripts ?: return
        val currentName = choiceField ?: focusedField
        val currentWidget = choiceSession?.widget ?: focusedWidget
        data class Entry(val name: String, val widget: Int, val box: WidgetBox)
        val entries = ArrayList<Entry>()
        for (slot in pageGeometry.keys.sorted()) {
            val page = pageAt(slot) as? PdfPage ?: continue
            for (annotation in page.annotations) {
                if (annotation.subtype != io.github.yuroyami.kitepdf.PdfAnnotation.Subtype.Widget) continue
                val rect = annotation.rect
                val hit = page.widgetAt((rect.left + rect.right) / 2, (rect.bottom + rect.top) / 2, handler.formState) ?: continue
                val field = hit.field
                val name = field.fullyQualifiedName
                if (field.type !in listOf(PdfFormField.FieldType.Text, PdfFormField.FieldType.Choice) || handler.formState.isReadOnly(name)) continue
                val display = page.pageToDisplay(rect)
                entries.add(Entry(name, hit.widgetIndex, WidgetBox(slot, Rect(display.left.toFloat(), display.bottom.toFloat(), display.right.toFloat(), display.top.toFloat()))))
            }
        }
        val at = entries.indexOfFirst { it.name == currentName && it.widget == currentWidget }
        blurFocusedField()
        val next = entries.getOrNull(if (backwards) at - 1 else at + 1) ?: return
        val field = (document as? PdfDocument)?.formField(next.name) ?: return
        if (field.type == PdfFormField.FieldType.Choice) openChoice(next.name, next.widget, next.box)
        else focusField(next.name, next.widget, next.box)
    }

    /** Puts the caret in one widget of a field, telling the document's scripts that it took the focus. */
    internal fun focusField(fieldName: String, widgetIndex: Int = 0, box: WidgetBox? = null) {
        val field = (document as? PdfDocument)?.formField(fieldName)
        if (field?.type == PdfFormField.FieldType.Choice &&
            (!field.isEditableCombo || choiceField != fieldName || choiceWidgetIndex != widgetIndex)) {
            openChoice(fieldName, widgetIndex, box)
            return
        }
        if (focusedField == fieldName && focusedWidget == widgetIndex) return
        if (choiceField != fieldName) blurFocusedField() else blurTextField()
        formInputGeneration++
        focusedWidgetBox = box
        focusedField = fieldName
        focusedWidget = widgetIndex
        val handler = scripts ?: return
        val choice = choiceSession
        if (choice != null && choice.name == fieldName) focusChoice(choice)
        else post("focus") { handler.focus(fieldName, widgetIndex) }
    }

    /**
     * Takes the caret out of the focused field and commits what the reader sees in it: the
     * document's keystroke, validate, calculate and format scripts run on the whole value, as
     * they do when a reader leaves a field.
     *
     * The commit runs on a scope of its own. The view may be leaving, and its scope with it, and
     * the value the reader typed must not be lost with them (#365).
     */
    internal fun blurFocusedField() {
        if (choiceSession != null) dismissChoice() else blurTextField()
    }

    private fun blurTextField(commit: Boolean = true) {
        val name = focusedField ?: return
        formInputGeneration++
        focusedField = null
        focusedWidgetBox = null
        val typed = editingText
        editingText = null
        val widget = focusedWidget
        val handler = scripts ?: return
        val work = {
            if (commit) scriptCall("commit", false) {
                if (typed != null && (document as? PdfDocument)?.formField(name)?.isEditableCombo == true) {
                    handler.commitChoice(name, PdfChoiceSelection(emptyList(), freeText = typed))
                } else handler.commit(name, typed ?: handler.formState.value(name) ?: "")
            }
            scriptCall("blur", Unit) { handler.blur(name, widget) }
            scriptsRan()
        }
        val lane = scriptLane
        if (lane == null) work() else kotlinx.coroutines.CoroutineScope(lane).launch { work() }
    }

    /**
     * Runs each page's open and close scripts as the reader lands on pages, with the page's index
     * in the document (ISO 32000-1, 12.6.3, Table 195). A page counts once the scroll or the page
     * turn settles on it, so a swipe that comes back runs nothing, and a fling runs the scripts of
     * the page it stops on only. The page that is open closes when the view leaves (#366).
     */
    internal suspend fun runPageScripts(
        handler: io.github.yuroyami.kitepdf.PdfScriptHandler,
        lane: kotlinx.coroutines.CoroutineDispatcher,
    ) {
        var open: Int? = null
        try {
            androidx.compose.runtime.snapshotFlow { if (adapter?.isScrollInProgress == true) null else currentLocation.page }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { page ->
                    withContext(lane) {
                        open?.let { previous -> scriptCall("pageClosed", Unit) { handler.pageClosed(previous) } }
                        open = page
                        scriptCall("pageOpened", Unit) { handler.pageOpened(page) }
                        scriptsRan()
                    }
                    // The flow takes its next snapshot on the thread this returns on (#443).
                    backOnComposeThread()
                }
        } finally {
            val last = open
            // The view is leaving and its scope with it, so the close runs on a scope of its own.
            if (last != null) {
                kotlinx.coroutines.CoroutineScope(lane).launch { scriptCall("pageClosed", Unit) { handler.pageClosed(last) } }
            }
        }
    }

    /**
     * Commits the field that has the caret, as leaving it does, and returns once the document's
     * scripts have finished with it and with every call the viewer made to them before.
     *
     * A tap on the host's own button, such as Save or Submit, does not take the caret out of the
     * field. Call this first, so the value the reader typed last has gone through the field's
     * scripts and into the form before the host reads the form.
     */
    public suspend fun commitFocusedField(): Unit = onViewerThread {
        blurFocusedField()
        val lane = scriptLane ?: return@onViewerThread
        // The lane runs one call at a time in order, so this returns after the calls before it.
        kotlinx.coroutines.withContext(lane) {}
        backOnComposeThread()
    }

    /** What the reader sees in the focused field, which a keystroke script may not have answered for yet. */
    internal var editingText: String? by mutableStateOf(null)

    /**
     * Runs [work] on the script lane, or here when there is no view to give one. The form's own
     * listener publishes what the work changed (#357), so nothing here writes state that
     * composition reads from the script thread (#364). A handler that fails is logged under
     * [what] (#365).
     */
    internal fun post(what: String, work: () -> Unit) {
        val scope = scriptScope
        val lane = scriptLane
        if (scope == null || lane == null) {
            scriptCall(what, Unit, work)
            scriptsRan()
            return
        }
        scope.launch(lane) {
            scriptCall(what, Unit, work)
            scriptsRan()
        }
    }

    /** Wakes the timer pump, which sleeps while no timer waits. Set by the pump (#368). */
    @kotlin.concurrent.Volatile
    internal var timerWake: kotlinx.coroutines.channels.SendChannel<Unit>? = null

    /** Tells the timer pump that the scripts ran, because a script may have set a timer (#368). */
    internal fun scriptsRan() {
        timerWake?.trySend(Unit)
    }

    /** Wakes the pump of a book's timers, as [timerWake] does a document's (#41). */
    @kotlin.concurrent.Volatile
    internal var epubTimerWake: kotlinx.coroutines.channels.SendChannel<Unit>? = null

    /** Tells the pump of a book's timers that its scripts ran (#41). */
    internal fun epubScriptsRan() {
        epubTimerWake?.trySend(Unit)
    }

    /**
     * How many times the book's scripts changed its chapters, as [EpubDocument.chapterChanges]
     * last said (#41). Written only on the composition's thread, by [followChapterChanges].
     */
    internal var chapterRevision: Int by mutableIntStateOf(0)

    /**
     * Takes the page counts again each time the book's scripts change a chapter, since a change
     * can add or take pages, and draws the pages on screen again through [contentVersionOf] (#41).
     */
    internal suspend fun followChapterChanges() {
        val epub = document as? EpubDocument ?: return
        epub.chapterChanges.collect { changes ->
            backOnComposeThread()
            if (changes == chapterRevision) return@collect
            chapterRevision = changes
            publishNow()
        }
    }

    /** A visit to a fragment of a chapter, which a serial number tells apart from an earlier one to the same fragment (#550). */
    internal class FragmentVisit(val serial: Int, val chapter: Int, val fragment: String)

    /**
     * The fragment the reader last went to, through a link, the table of contents, a bookmark or a
     * script (#550). Written only on the composition's thread, by [scrollTo].
     */
    internal var fragmentVisit: FragmentVisit? by mutableStateOf(null)
    private var fragmentVisits = 0

    /**
     * Follows the chapter that the reader reaches, once a scroll has settled on it, on [lane]: gives
     * the book the fragment the reader went to there, so that `:target` matches its element (#550),
     * then runs the chapter's scripts when it has some and [handler] is set (#41). The handler runs
     * a chapter's scripts the first time only, and takes a new fragment each time.
     */
    internal suspend fun followChapters(
        book: EpubDocument,
        handler: io.github.yuroyami.kitepdf.epub.EpubScriptHandler?,
        lane: kotlinx.coroutines.CoroutineDispatcher,
    ) {
        // A visit counts once: a scroll away and back keeps what a script made of the fragment since.
        var taken = 0
        androidx.compose.runtime.snapshotFlow {
            if (adapter?.isScrollInProgress == true) null
            else currentLocation.chapter.let { chapter -> chapter to fragmentVisit?.takeIf { it.chapter == chapter } }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { (chapter, visit) ->
                withContext(lane) {
                    if (visit != null && visit.serial != taken) {
                        taken = visit.serial
                        scriptCall("setFragment", Unit) { book.setFragment(chapter, visit.fragment) }
                    }
                    if (handler != null && scriptCall("isScripted", false) { book.isScripted(chapter) }) {
                        scriptCall("chapterOpened", Unit) { handler.chapterOpened(chapter) }
                        epubScriptsRan()
                    }
                }
                // The flow takes its next snapshot on the thread this returns on (#443).
                backOnComposeThread()
            }
    }

    /**
     * Changes whenever the form does, so the field layer repaints and the page bitmap does not.
     * A script that writes a field twenty times a second costs twenty overlay draws. Written
     * only on the composition's thread, by [KiteFormRevision].
     */
    internal var formRevision: Int by mutableIntStateOf(0)

    /**
     * How many of the book's remote resources have landed, as [EpubDocument.remoteArrivals] last
     * said (#38). Written only on the composition's thread, by [followRemoteArrivals].
     */
    internal var remoteArrivals: Int by mutableIntStateOf(0)

    /**
     * How many times a font landed for the host-font text of each page after the page drew, as
     * its [HostFontWatch] said (#595). Written only on the composition's thread, by [hostFontsLanded].
     */
    private val hostFontLandings = mutableStateMapOf<KitePage, Int>()

    /** Counts a font that landed for [page]'s host-font text, so the page draws again with it (#595). */
    internal fun hostFontsLanded(page: KitePage) {
        hostFontLandings[page] = (hostFontLandings[page] ?: 0) + 1
    }

    /**
     * What [page]'s pixels depend on besides the viewer's own settings: for an EPUB page, the
     * remote pictures of its chapter that have landed since it painted (#38), and the changes its
     * scripts made to its chapter (#41); for any page, the fonts that landed for its host-font
     * text since it drew (#595). It reads [remoteArrivals] and [chapterRevision], so a landing or a
     * change composes the pages on screen again, and a page whose chapter changed draws again.
     */
    internal fun contentVersionOf(page: KitePage): Int {
        // Every count only grows, so their sum moves whenever one does (#41).
        if (remoteArrivals < 0 || chapterRevision < 0) return 0
        val fonts = hostFontLandings[page] ?: 0
        return fonts + ((page as? EpubPage)?.let { it.remoteVersion + it.chapterVersion } ?: 0)
    }

    /** Copies [EpubDocument.remoteArrivals] into [remoteArrivals] for as long as the view shows this state (#38). */
    internal suspend fun followRemoteArrivals() {
        val epub = document as? EpubDocument ?: return
        epub.remoteArrivals.collect { landed ->
            backOnComposeThread()
            remoteArrivals = landed
        }
    }

    internal var adapter: KiteScrollAdapter? by mutableStateOf(null)
    internal var pendingPage: Int = initialPage.coerceAtLeast(0)

    init {
        // A large PDF builds its page list in the background, so the strip holds a placeholder
        // at first. A page it cannot hold yet opens like a saved position, once the list exists (#387).
        if (initialPage > 0 && document.chapterCount == 1 && !document.isChapterReady(0)) {
            openAt = KiteBookmark.Page(initialPage)
        }
    }

    /**
     * The first visible slot when a continuous layout detached, which [pendingScrollOffset]
     * is measured from. Null when that slot is [pendingPage]. [pendingPage] stays the slot
     * nearest the viewport centre, so the reading position does not move on detach (#258).
     */
    internal var pendingLeadingPage: Int? = null

    /**
     * True once the strip holds the pages of the whole document: every chapter is laid out and
     * published, or the loader has laid out all it can and a chapter that failed stays a
     * placeholder. Spreads pair pages from the strip only then (#337).
     */
    internal val stripSettled: Boolean get() = (isComplete && itemCount == knownPageCount) || loaderDone

    /** Set once the chapter loader has laid out every chapter it can. */
    private var loaderDone: Boolean by mutableStateOf(false)

    /** False while the viewer shows one fixed page, which navigation does not turn (#336). */
    internal val canNavigate: Boolean get() = adapter !is FixedPageAdapter

    /** The slot of the first chapter that is not laid out yet, or null when every chapter is. */
    internal fun firstPendingSlot(): Int? {
        val chapter = (0 until document.chapterCount).firstOrNull { !document.isChapterReady(it) } ?: return null
        return slotFor(KiteLocation(chapter, 0)).takeIf { it >= 0 }
    }

    /** Remembers the position of a viewer that is not attached. */
    internal fun park(page: Int, leadingPage: Int? = null, offsetPx: Int = 0, slotLength: Int = 0) {
        pendingPage = page
        pendingLeadingPage = leadingPage
        pendingScrollOffset = offsetPx
        parkedSlotLength = slotLength
    }

    /**
     * The axis of the continuous strip this state was last shown in. A strip on the other axis
     * converts the scroll offset by the two lengths of the leading slot (#352).
     */
    internal var stripOrientation: androidx.compose.foundation.gestures.Orientation? = null

    /** The length of the leading slot on the scroll axis when a continuous strip parked. */
    private var parkedSlotLength: Int = 0

    /**
     * Where a new continuous list for this state starts: the leading slot, and the offset into it
     * on [orientation]'s axis. A slot [crossPx] across is as long as the strip will lay it out.
     */
    internal fun stripSeed(orientation: androidx.compose.foundation.gestures.Orientation, crossPx: Int): Pair<Int, Int> {
        val at = currentScrollPosition
        val index = inStrip(slotFor(at.location))
        val old = stripOrientation
        val oldLength = (adapter as? LazyListScrollAdapter)?.leadingSlotLength ?: parkedSlotLength
        val page = pageAt(index)
        if (old == null || old == orientation || oldLength <= 0 || crossPx <= 0 || page == null) return index to at.offsetPx
        val newLength = stripSlotLength(orientation == androidx.compose.foundation.gestures.Orientation.Vertical, kitePageAspect(page), crossPx)
        return index to (at.offsetPx.toLong() * newLength / oldLength).toInt().coerceIn(0, newLength)
    }
    internal var zoomRange: ClosedFloatingPointRange<Float> by mutableStateOf(1f..8f)
    /**
     * The viewport's size in px. A change keeps the point of the page at the viewport's centre
     * where it can, and fits the pan into the new bounds, so a zoomed page never ends up outside a
     * smaller viewport, whether it shrank from a resize, a split screen or a rotation (#399).
     */
    internal var viewportSize: IntSize
        get() = viewportSizeState
        set(value) {
            val old = viewportSizeState
            if (value == old) return
            viewportSizeState = value
            val pan = panOffset
            if (pan == Offset.Zero) return
            val ratio = if (old.width > 0 && old.height > 0) pageWidthIn(value) / pageWidthIn(old) else 1f
            panOffset = clampPan(if (ratio.isFinite() && ratio > 0f) pan * ratio else pan, zoom)
        }
    private var viewportSizeState by mutableStateOf(IntSize.Zero)

    /**
     * How wide the current page is drawn in a viewport of [size] before zoom: the viewport's width
     * in a vertical strip, its height times the page's shape in a horizontal one, and the fitted
     * page in a pager. The pan scales with it when the viewport changes size.
     */
    private fun pageWidthIn(size: IntSize): Float {
        val aspect = pageAt(currentPage)?.let(::kitePageAspect) ?: 1f
        return when (panAxes) {
            PanAxes.XOnly -> size.width.toFloat()
            PanAxes.YOnly -> size.height * aspect
            else -> minOf(size.width.toFloat(), size.height * aspect)
        }
    }

    /**
     * Per-page on-screen geometry in UNTRANSFORMED viewport space (before the
     * zoom/pan `graphicsLayer`): page slots report their rects during layout
     * and remove them on dispose. [hitTest] inverts the layer transform onto
     * this space, so the map never needs to update on zoom/pan alone.
     */
    internal val pageGeometry = mutableStateMapOf<Int, Rect>()

    /**
     * True when slot [index] shows in the viewport at the current zoom and pan, by the geometry
     * its layout reported. A slot with no geometry yet counts as shown.
     */
    internal fun inZoomedView(index: Int): Boolean {
        val rect = pageGeometry[index] ?: return true
        val size = viewportSize
        if (size == IntSize.Zero || zoom <= 0f) return true
        // The viewport in content space: the zoom layer scales about the centre, then pans.
        val centre = Offset(size.width / 2f, size.height / 2f)
        val topLeft = centre + (Offset.Zero - centre - panOffset) / zoom
        val bottomRight = centre + (Offset(size.width.toFloat(), size.height.toFloat()) - centre - panOffset) / zoom
        return rect.overlaps(Rect(topLeft, bottomRight))
    }

    /**
     * The part of slot [index] that shows in the viewport at the current zoom and pan, in the
     * slot's own pixels, or null when none of it shows or its geometry is not known yet (#375).
     */
    internal fun visiblePartOf(index: Int): Rect? {
        val rect = pageGeometry[index] ?: return null
        val size = viewportSize
        if (size == IntSize.Zero || zoom <= 0f) return null
        // The viewport in content space, as in inZoomedView.
        val centre = Offset(size.width / 2f, size.height / 2f)
        val topLeft = centre + (Offset.Zero - centre - panOffset) / zoom
        val bottomRight = centre + (Offset(size.width.toFloat(), size.height.toFloat()) - centre - panOffset) / zoom
        val shown = rect.intersect(Rect(topLeft, bottomRight))
        if (shown.width <= 0f || shown.height <= 0f) return null
        return shown.translate(-rect.left, -rect.top)
    }

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
        if (budgetBytes <= 0L) {
            // A cache the host turned off lets go of the bitmaps it held (#395).
            bitmapCache = null
            bitmapCacheBudget = 0L
            return null
        }
        if (bitmapCacheBudget != budgetBytes) {
            bitmapCache = PageBitmapCache(budgetBytes)
            bitmapCacheBudget = budgetBytes
        }
        return bitmapCache
    }

    /** One converted-image budget for all vector slots, including pages recycled by the strip (#371). */
    private var vectorImageCache: KiteBitmapCache<ImageBitmap>? = null
    private var vectorImageCacheBudget = 0L

    internal fun vectorImageCacheFor(budgetBytes: Long): KiteBitmapCache<ImageBitmap>? {
        if (budgetBytes <= 0L || vectorImageCacheBudget != budgetBytes) {
            vectorImageCache?.clear()
            vectorImageCache = if (budgetBytes > 0L) KiteBitmapCache(budgetBytes) else null
            vectorImageCacheBudget = budgetBytes.coerceAtLeast(0L)
        }
        return vectorImageCache
    }

    /* ── zoom ─────────────────────────────────────────────────────────────── */

    /**
     * Sets [zoom] immediately, clamped to the active [KiteZoomSpec] range. A zoom that is not a
     * finite number, such as a fit ratio of 0 / 0 taken before layout, is ignored (#338).
     *
     * @param focal viewport-space point to keep visually stationary (e.g. the
     *   pinch centroid or double-tap position). Unspecified = viewport centre.
     */
    public fun setZoom(zoom: Float, focal: Offset = Offset.Unspecified) {
        // A zoom request takes over from a zoom animation still running (#405).
        stopZoomAnimation()
        applyZoom(zoom, focal)
    }

    private fun applyZoom(zoom: Float, focal: Offset) {
        if (!zoom.isFinite()) return
        val new = zoom.coerceIn(zoomRange.start, zoomRange.endInclusive)
        val old = this.zoom
        if (new == old) return
        val stationary = focal.isSpecified && focal.x.isFinite() && focal.y.isFinite()
        panOffset = if (stationary && viewportSize != IntSize.Zero) {
            // Keep the focal point stationary: screen = centre + (content-centre)·zoom + pan
            val centre = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
            val f = focal - centre
            clampPan((panOffset - f) * (new / old) + f, new)
        } else {
            clampPan(panOffset, new)
        }
        this.zoom = new
    }

    /**
     * Animates zoom to [target] (clamped), keeping [focal] stationary throughout. A target that is
     * not finite is ignored.
     *
     * One zoom animation runs at a time. A new one takes over, and a finger on the page, [setZoom],
     * [panBy] or [resetZoom] stops it where it is (#405). This call then throws a
     * `CancellationException`, as a Compose scroll animation does when it is interrupted.
     */
    public suspend fun animateZoomTo(
        target: Float,
        focal: Offset = Offset.Unspecified,
        animationSpec: AnimationSpec<Float> = spring(),
    ): Unit = onViewerThread {
        if (!target.isFinite()) return@onViewerThread
        val clamped = target.coerceIn(zoomRange.start, zoomRange.endInclusive)
        kotlinx.coroutines.coroutineScope {
            stopZoomAnimation()
            val job = coroutineContext[kotlinx.coroutines.Job]
            zoomAnimation = job
            try {
                animate(zoom, clamped, animationSpec = animationSpec) { value, _ -> applyZoom(value, focal) }
            } finally {
                if (zoomAnimation === job) zoomAnimation = null
            }
        }
    }

    /** The zoom animation that runs now, so a finger or a zoom request can stop it. */
    private var zoomAnimation: kotlinx.coroutines.Job? = null

    private fun stopZoomAnimation() {
        zoomAnimation?.cancel()
        zoomAnimation = null
    }

    /**
     * Snaps back to fit, zoom 1, clamped into the zoom range, and recentres. A range that starts
     * below 1 lets a reader zoom out, but a page turn still shows the page at fit (#398).
     */
    public fun resetZoom() {
        stopZoomAnimation()
        zoom = 1f.coerceIn(zoomRange.start, zoomRange.endInclusive)
        panOffset = Offset.Zero
    }

    /**
     * Pans by [delta] (viewport px), clamped to the zoomed content bounds.
     * Returns the portion actually consumed. The gesture layer hands the
     * remainder back to the underlying scroll container. A delta that is not
     * finite is ignored.
     */
    public fun panBy(delta: Offset): Offset {
        if (!delta.x.isFinite() || !delta.y.isFinite()) return Offset.Zero
        // A finger that pans takes over from a zoom animation still running (#405).
        stopZoomAnimation()
        val allowed = Offset(
            if (panAxes.x) delta.x else 0f,
            if (panAxes.y) delta.y else 0f,
        )
        val old = panOffset
        panOffset = clampPan(old + allowed, zoom)
        return panOffset - old
    }

    /**
     * [offset] kept inside the pan bounds at [zoom]. The bounds follow the content, not the
     * viewport: on each axis the zoomed content may move by half of what it overflows the
     * viewport, around the pan that centres it. So a letterboxed page that still fits one axis
     * does not move on it, and never slides into empty margins (#400).
     */
    /**
     * Moves the pan along a continuous strip's scroll axis by [delta] viewport px, within the
     * zoomed strip's bounds, and returns what it moved. Only the strip's ends use it: a
     * one-finger pan leaves that axis to the list (#397).
     */
    internal fun panAlongScrollAxis(delta: Float): Float {
        if (!delta.isFinite() || delta == 0f) return 0f
        stopZoomAnimation()
        val old = panOffset
        val vertical = panAxes == PanAxes.XOnly
        panOffset = clampPan(if (vertical) Offset(old.x, old.y + delta) else Offset(old.x + delta, old.y), zoom)
        return if (vertical) panOffset.y - old.y else panOffset.x - old.x
    }

    internal fun clampPan(offset: Offset, zoom: Float): Offset {
        val (centred, slack) = panRoom(zoom)
        return Offset(
            offset.x.coerceIn(centred.x - slack.x, centred.x + slack.x),
            offset.y.coerceIn(centred.y - slack.y, centred.y + slack.y),
        )
    }

    /** Per axis at [zoom]: the pan that centres the content, and how far it may move from there. */
    private fun panRoom(zoom: Float): Pair<Offset, Offset> {
        val width = viewportSize.width.toFloat()
        val height = viewportSize.height.toFloat()
        val content = panContent() ?: Rect(0f, 0f, width, height)
        val centred = Offset(-zoom * (content.center.x - width / 2f), -zoom * (content.center.y - height / 2f))
        val slack = Offset(
            ((zoom * content.width - width) / 2f).coerceAtLeast(0f),
            ((zoom * content.height - height) / 2f).coerceAtLeast(0f),
        )
        return centred to slack
    }

    /**
     * What a pager shows before zoom: the union of the rectangles of its current page or spread.
     * Null in a continuous strip, whose content spans the viewport, and before the first layout.
     */
    private fun panContent(): Rect? {
        if (panAxes != PanAxes.Both) return null
        var union: Rect? = null
        for (rect in pageGeometry.values) union = union?.let { Rect(minOf(it.left, rect.left), minOf(it.top, rect.top), maxOf(it.right, rect.right), maxOf(it.bottom, rect.bottom)) } ?: rect
        return union
    }

    /* ── text selection ────────────────────────────────────────────── */

    /**
     * The active text selection, or null. Set by the long-press-drag gesture,
     * and by a mouse press and drag on text; observe via snapshot reads or
     * [onSelectionChange]. The viewer never touches the clipboard itself. Read
     * [KiteTextSelection.text] and copy in the app (see the sample's selection
     * actions). On a desktop, bind Ctrl or Cmd with C to that copy.
     */
    public var selection: KiteTextSelection? by mutableStateOf(null)
        internal set

    /**
     * The carets at the start and the end of [selection], in its page's display space, set
     * with it. The handles sit on them in every direction of text (#406).
     */
    internal var selectionCarets: Pair<io.github.yuroyami.kitepdf.core.KiteCaret?, io.github.yuroyami.kitepdf.core.KiteCaret?>? = null
        private set

    /**
     * True while text selection owns the pointer, and while the selection it
     * produced is still on screen.
     *
     * [KiteDocView] yields to it: one-finger pan and the list/pager's own scrolling
     * are both suppressed while this is set, so a drag that began as a
     * selection never slides the page out from under the finger, and the page
     * stays put afterwards while the user acts on the selected text.
     * Two-finger pinch zoom is unaffected, and so is the mouse wheel over a
     * continuous layout (#594).
     *
     * It goes true the instant the long press fires, which is BEFORE
     * [selection] exists (the hit test and the text extraction still have to
     * run), and it stays true after the finger lifts. [clearSelection] turns it
     * off, and so does a long press that never anchored anything (an empty page
     * region), which releases the lock when that drag ends.
     */
    public var isSelectionActive: Boolean by mutableStateOf(false)
        private set

    /** True when the last pointer to press or move over the view was a mouse, as [kitePointerKind] keeps it. */
    internal var mousePointer: Boolean by mutableStateOf(false)

    /**
     * True while a selection holds the strip's own scrolling. The hold is against a finger, whose
     * drag would slide the page from under the words. The strip never drags with a mouse, so for
     * a mouse the hold would only stop the wheel, and the page could not move until a click
     * cleared the selection (#594).
     */
    internal val selectionHoldsScroll: Boolean
        get() = isSelectionActive && !mousePointer

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

    /**
     * True while a thumb drag reshapes the selection. A finger held still on a thumb also
     * reaches the long-press timeout, and the long press underneath must leave it alone (#313).
     */
    internal var handleDragInProgress: Boolean = false
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

    /**
     * The fixed anchor of an active drag: the page and a flattened char index. A location, not a
     * slot, so a chapter landing mid-drag does not strand it (#350).
     */
    private var selectionAnchor: Pair<KiteLocation, Int>? = null

    public fun clearSelection() {
        selectionAnchor = null
        isSelectionActive = false
        selectionInProgress = false
        handleDragInProgress = false
        selectionCarets = null
        if (selection != null) {
            selection = null
            onSelectionChange?.invoke(null)
        }
    }

    /**
     * The bounds of [selection] in viewport pixels, under the current zoom and pan, or null without
     * a selection or while its page has no place on screen. It reads snapshot state, so a menu
     * placed by it follows the page as it moves (#408).
     */
    public val selectionBounds: androidx.compose.ui.geometry.Rect?
        get() {
            val sel = selection ?: return null
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            for (quad in sel.quads) {
                for ((x, y) in listOf(quad.left to quad.bottom, quad.right to quad.top)) {
                    val point = displayToViewport(sel.pageIndex, x, y) ?: return null
                    left = minOf(left, point.x)
                    top = minOf(top, point.y)
                    right = maxOf(right, point.x)
                    bottom = maxOf(bottom, point.y)
                }
            }
            return if (left <= right && top <= bottom) androidx.compose.ui.geometry.Rect(left, top, right, bottom) else null
        }

    /** Long-press: anchor the selection at the char under [viewportOffset]. */
    internal fun beginSelection(viewportOffset: Offset) {
        if (!selectionEnabled) return
        selectionAnchor = null
        val (pageIndex, x, y) = hitTestDisplay(viewportOffset) ?: return
        // A page without text, such as a scan or a comic page, never takes the lock, so the
        // press still pans it (#408).
        val text = textAt(pageIndex)?.takeIf { it.blocks.isNotEmpty() } ?: return
        // Claim the gesture before the char lookup. A press between the words of a page with
        // text anchors nothing, and pan has to be off for the whole drag all the same.
        // `endSelectionGesture` hands the lock back if nothing anchored.
        isSelectionActive = true
        selectionInProgress = true
        val location = anchorAt(pageIndex) ?: return
        val idx = text.charIndexAt(x, y) ?: return
        selectionAnchor = location to idx
        applySelection(text, pageIndex, idx, idx)
    }

    /** The text of the page in slot [index], or null when it cannot be read: such a page acts as one without text (#334). */
    /**
     * Builds [page]'s text on the raster dispatcher. A page keeps its text once built, so the
     * selection gesture then finds it ready instead of building it on the main thread (#380).
     * A failure is left to the gesture, which reads the text again and says why it cannot.
     */
    internal suspend fun prepareText(page: KitePage) {
        try {
            withContext(kitepdfRasterDispatcher()) { page.textContent() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
        }
    }

    private fun textAt(index: Int): KiteStructuredText? {
        val page = pageAt(index) ?: return null
        // Its chapter would lay out here on the UI thread (#377). The page is on screen, so its
        // raster or its draw is bringing the content back; until then it acts as a page without text.
        if (!page.isContentLoaded) return null
        return try {
            page.textContent()
        } catch (failure: Throwable) {
            kiteWarn { "selection: the text of page $index cannot be read: ${failure.message}" }
            null
        }
    }

    /**
     * Drag: extend from the anchor to the char under [viewportOffset].
     * Both ends stay on the anchor page (cross-page selection is out of
     * scope); points past the page or off any line keep the last state.
     */
    internal fun extendSelection(viewportOffset: Offset) {
        val (location, anchor) = selectionAnchor ?: return
        val page = indexOf(location)
        val (pageIndex, x, y) = hitTestDisplay(viewportOffset) ?: return
        if (page < 0 || pageIndex != page) return
        val text = textAt(page) ?: return
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
        handleDragInProgress = false
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
        val location = anchorAt(sel.pageIndex) ?: return
        selectionAnchor = location to if (edge == KiteSelectionHandleEdge.Start) sel.end else sel.start
        isSelectionActive = true
        selectionInProgress = true
        handleDragInProgress = true
    }

    /**
     * Where the [edge] thumb of the active selection sits, in viewport pixels,
     * or null when nothing is selected (or the page has no geometry yet).
     *
     * The point is at the caret of that end, halfway across its line and a
     * quarter of a char inside the selection, rather than on whatever a
     * [KiteSelectionHandlePainter] drew around it. That keeps the grab target
     * the same for every painter and every direction of text, and it doubles
     * as the text position a drag maps back to, so grabbing a thumb never
     * nudges the selection by itself (#406).
     */
    internal fun handlePoint(edge: KiteSelectionHandleEdge): Offset? {
        val sel = selection ?: return null
        val caret = (if (edge == KiteSelectionHandleEdge.Start) selectionCarets?.first else selectionCarets?.second) ?: return null
        // A quarter of the char inside its caret: the boundary itself belongs to the char next to
        // it too, and a still grab there would add that char to the selection.
        val along = caret.position + (caret.inside - caret.position) * 0.25
        val across = (caret.from + caret.to) / 2.0
        return if (caret.vertical) displayToViewport(sel.pageIndex, across, along) else displayToViewport(sel.pageIndex, along, across)
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
            // As a reader copies it: a wrapped paragraph is one line, a hyphenated word whole (#438).
            text = text.copyText(start, end),
            quads = text.quadsFor(start, end),
            location = locationOf(page),
        )
        if (sel.start == selection?.start && sel.end == selection?.end && sel.pageIndex == selection?.pageIndex) return
        selectionCarets = text.caretAt(start, after = false) to text.caretAt(end, after = true)
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
        val page = pageAt(pageIndex) ?: return null
        if (rect.width <= 0f || rect.height <= 0f || page.displayWidth <= 0.0 || page.displayHeight <= 0.0) return null
        val content = Offset(
            rect.left + (x / page.displayWidth).toFloat() * rect.width,
            rect.top + (y / page.displayHeight).toFloat() * rect.height,
        )
        val centre = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        return centre + (content - centre) * zoom + panOffset
    }

    /**
     * [hitTestDisplay] run backwards for a rectangle: [rect], in the display space of the page
     * [pageIndex], to the viewport pixels it is painted at under the current zoom and pan. Null
     * while that page has no place in the layout: a paged layout places only its current page.
     *
     * It reads snapshot state, so an element placed with it in the viewer's `overlay` follows
     * the page as it scrolls and zooms, and keeps its own size. To draw at the page's scale
     * instead, use the viewer's `pageOverlay` (#30).
     */
    public fun displayRectToViewport(pageIndex: Int, rect: KiteRectangle): Rect? {
        val corner = displayToViewport(pageIndex, minOf(rect.left, rect.right), minOf(rect.bottom, rect.top)) ?: return null
        val opposite = displayToViewport(pageIndex, maxOf(rect.left, rect.right), maxOf(rect.bottom, rect.top)) ?: return null
        return Rect(corner, opposite)
    }

    /**
     * [hitTest] run backwards for a rectangle: [rect], in the page space of the page [pageIndex],
     * such as the rectangle of a PDF annotation, to the viewport pixels it is painted at. Null
     * while that page has no place in the layout. See [displayRectToViewport].
     */
    public fun pageRectToViewport(pageIndex: Int, rect: KiteRectangle): Rect? {
        val page = pageAt(pageIndex) ?: return null
        return displayRectToViewport(pageIndex, page.pageToDisplay(rect))
    }

    /**
     * Maps a viewport point to the page under it, in the page's display space: points from the
     * top-left corner of the page as it is shown, y down, with its rotation applied, whatever
     * the format. Null when the point lands on background or spacing.
     *
     * Search hits, [highlights] and the geometry of
     * [io.github.yuroyami.kitepdf.core.KiteStructuredText] use this space, so a point from here
     * can mark the page where the reader tapped. [hitTest] gives the page's own space instead
     * (#432).
     */
    public fun hitTestDisplay(viewportOffset: Offset): KitePageHit? {
        if (viewportSize == IntSize.Zero || zoom <= 0f) return null
        val centre = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        val content = centre + (viewportOffset - centre - panOffset) / zoom
        for ((index, rect) in pageGeometry) {
            if (rect.width <= 0f || rect.height <= 0f || !rect.contains(content)) continue
            val page = pageAt(index) ?: continue
            return KitePageHit(
                index,
                (content.x - rect.left) / rect.width * page.displayWidth,
                (content.y - rect.top) / rect.height * page.displayHeight,
                locationOf(index),
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
     * space (y-up, rotation unfolded), EPUB pages their document space. To
     * mark the page where the reader tapped, use [hitTestDisplay].
     */
    public fun hitTest(viewportOffset: Offset): KitePageHit? {
        val (index, devX, devY) = hitTestDisplay(viewportOffset) ?: return null
        val page = pageAt(index) ?: return null
        val inv = page.displayToDeviceBase().invert() ?: return null
        val (x, y) = inv.transformPoint(devX, devY)
        return KitePageHit(index, x, y, locationOf(index))
    }

    /**
     * The topmost app-owned highlight under a viewport tap, or null on unmarked paper.
     * Uses the same display-space geometry as painting, including page rotation, zoom and pan.
     * Search results are intentionally excluded: only [highlights] belong to the host's marks.
     */
    public fun highlightAt(viewportOffset: Offset): KiteHighlight? {
        val (page, x, y) = hitTestDisplay(viewportOffset) ?: return null
        return highlightsByPage[page].orEmpty().asReversed().firstOrNull { highlight ->
            highlight.hit.quads.any { quad ->
                val rect = quad.normalized()
                x >= rect.left && x <= rect.right && y >= rect.bottom && y <= rect.top
            }
        }
    }

    /* ── navigation ───────────────────────────────────────────────────────── */

    /** The composition's thread of the viewer that shows this state, and a context that runs there. */
    private class ViewerThread(val marker: Any, val context: CoroutineContext)

    @kotlin.concurrent.Volatile
    private var viewerThread: ViewerThread? = null

    /**
     * Makes the calling thread this state's viewer thread until the caller is cancelled. The
     * viewer that shows this state calls it from an effect, which starts on the composition's
     * thread.
     */
    internal suspend fun attachViewerThread() {
        val context = kotlinx.coroutines.currentCoroutineContext()
        val viewer = ViewerThread(
            currentThreadMarker(),
            (context[ContinuationInterceptor] ?: EmptyCoroutineContext) + (context[MonotonicFrameClock] ?: EmptyCoroutineContext),
        )
        viewerThread = viewer
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            if (viewerThread === viewer) viewerThread = null
        }
    }

    /**
     * Runs [block] on the thread of the viewer that shows this state, with the viewer's frame
     * clock, so a host may call the suspend API from any thread (#429). On that thread it runs in
     * place. With no viewer it runs in place too, and a navigation waits for the viewer.
     */
    private suspend fun <T> onViewerThread(block: suspend () -> T): T {
        val viewer = viewerThread
        if (viewer == null || currentThreadMarker() == viewer.marker) return block()
        return withContext(viewer.context) {
            backOnComposeThread()
            block()
        }
    }

    /** Jumps to [slot] without animation. Slots include chapter placeholders. */
    public suspend fun scrollToSlot(slot: Int): Unit = scrollToPage(slot)

    /** Animates to [slot]. Slots include chapter placeholders. */
    public suspend fun animateScrollToSlot(slot: Int): Unit = animateScrollToPage(slot)

    /** Jumps to slot [page] (coerced into range) without animation. See [scrollToSlot]. */
    public suspend fun scrollToPage(page: Int): Unit = onViewerThread {
        val target = page.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
        leaveSelectionFor(target)
        park(target)
        adapter?.scrollToPage(target)
    }

    /** Animates to slot [page] (coerced into range). */
    public suspend fun animateScrollToPage(page: Int): Unit = onViewerThread {
        val target = page.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
        leaveSelectionFor(target)
        park(target)
        adapter?.animateScrollToPage(target)
    }

    /**
     * Brings the display-space height [y] of the page in [slot] to the top of the viewport, at the
     * reader's zoom and pan, as a link to a place on a page asks (#433). A vertical strip scrolls
     * there. A horizontal strip turns to the page and pans across it, and a pager does the same
     * once it lands. The view goes as far as the page lets it. Without [y] it turns to the page.
     */
    internal suspend fun animateScrollToPagePoint(slot: Int, y: Double?): Unit = onViewerThread {
        val page = pageAt(slot)
        if (y == null || !y.isFinite() || page == null || page.displayHeight <= 0.0) {
            animateScrollToPage(slot)
            return@onViewerThread
        }
        val continuous = adapter as? LazyListScrollAdapter
        if (continuous == null) {
            // A pager recentres the pan when it lands on another page, so the landing pans then.
            if (pageGeometry.containsKey(slot) && adapter?.isScrollInProgress != true) {
                panToShowAtTop(slot, y)?.let { panOffset = it }
            } else {
                landing = slot to y
                animateScrollToPage(slot)
            }
            return@onViewerThread
        }
        if (stripOrientation == androidx.compose.foundation.gestures.Orientation.Horizontal) {
            animateScrollToPage(slot)
            panToShowAtTop(slot, y)?.let { panOffset = it }
            return@onViewerThread
        }
        val length = continuous.verticalSlotLength(kitePageAspect(page)) { pageAt(it)?.let(::kitePageAspect) }
        if (length == null) {
            animateScrollToPage(slot)
            return@onViewerThread
        }
        // The zoom scales about the viewport's centre and the pan moves the result, so the top of
        // the screen shows the strip at (centre + pan) / zoom above the centre, not at its top.
        val centre = viewportSize.height / 2f
        val shift = (centre + panOffset.y) / zoom - centre
        val offset = ((y / page.displayHeight).coerceIn(0.0, 1.0) * length + shift).roundToInt()
        leaveSelectionFor(slot)
        if (offset >= 0) {
            park(slot, offsetPx = offset)
            continuous.animateScrollToPageOffset(slot, offset)
        } else {
            // The place is nearer the top of its page than that, so the strip goes on into the page before.
            park(slot)
            continuous.animateScrollToPageOffset(slot, 0)
            continuous.animateScrollBy(offset.toFloat())
        }
    }

    /** A height on a page that a link asks a pager to show at the top once it lands there (#433). */
    private var landing: Pair<Int, Double>? = null

    /**
     * The pan that shows the height a link asked for, now that the pager landed, or null. The
     * request is spent either way, so no later landing uses it.
     */
    internal fun takeLandingPan(): Offset? {
        val (slot, y) = landing ?: return null
        landing = null
        return panToShowAtTop(slot, y)
    }

    /**
     * The pan that brings the display-space height [y] of the page in [slot] to the top of the
     * viewport at the present zoom, inside the pan bounds, or null before the page has a place.
     */
    private fun panToShowAtTop(slot: Int, y: Double): Offset? {
        val rect = pageGeometry[slot] ?: return null
        val page = pageAt(slot) ?: return null
        if (page.displayHeight <= 0.0 || viewportSize == IntSize.Zero) return null
        val centre = viewportSize.height / 2f
        val at = rect.top + (y / page.displayHeight).coerceIn(0.0, 1.0).toFloat() * rect.height
        // A content point shows at centre + (point - centre) * zoom + pan, as hitTestDisplay inverts.
        return clampPan(Offset(panOffset.x, -(centre + (at - centre) * zoom)), zoom)
    }

    /**
     * Drops a selection on another slot than [slot]. A selection holds the scroll and the pan
     * while the reader acts on its words, so one left behind by a navigation would lock the page
     * the reader went to, and the selection menu would offer words they cannot see (#407).
     */
    private fun leaveSelectionFor(slot: Int) {
        val selected = selection
        if (selected != null && selected.pageIndex != slot) clearSelection()
    }

    /**
     * The location a programmatic navigation is flying toward, or null. A
     * publication that lands mid-flight corrects toward this instead of the
     * pager's transient raw slot, so navigation wins the race by design.
     */
    internal var navigationTarget: KiteLocation? = null

    /**
     * Jumps to [location], laying out its chapter first if needed.
     *
     * Prefer this over [scrollToPage] on a reflowable book: a location is exact
     * whatever has been laid out so far, while a page number is not. A location
     * that does not exist (stale bookmark, empty or out-of-range chapter)
     * goes to the nearest real slot and still counts as done: the last page of
     * its chapter when the page is past the chapter's end, else the next slot in
     * reading order.
     */
    public suspend fun scrollTo(location: KiteLocation, animate: Boolean = false): Unit = onViewerThread {
        scrollToLocation(location, animate, 0)
    }

    private suspend fun scrollToLocation(location: KiteLocation, animate: Boolean, offsetPx: Int) {
        navigationTarget = location
        try {
            prepareFor(location)
            val index = navigableSlot(location)
            val continuous = adapter as? LazyListScrollAdapter
            if (!animate && continuous != null) {
                leaveSelectionFor(index)
                park(index, offsetPx = offsetPx)
                continuous.scrollToPageOffset(index, offsetPx)
            } else {
                if (animate) animateScrollToPage(index) else scrollToPage(index)
                if (adapter == null) pendingScrollOffset = offsetPx
            }
        } finally {
            navigationTarget = null
        }
    }

    /**
     * The slot for [location], or the nearest real one when it has none: the last page of its
     * chapter for a page past the end, else the next slot in reading order (#347).
     */
    private fun navigableSlot(location: KiteLocation): Int {
        val exact = indexOf(location)
        if (exact >= 0) return exact
        val chapter = location.chapter
        if (chapter < document.chapterCount && document.isChapterReady(chapter)) {
            val last = document.pageCountIn(chapter) - 1
            if (last >= 0 && location.page > last) return slotFor(KiteLocation(chapter, last))
        }
        return slotAtOrAfter(location)
    }

    /**
     * Restores a page and scroll offset. Continuous layouts apply [position]'s
     * offset on their scroll axis; the other layouts navigate to its page.
     * Calls before composition are retained for the initial list measurement.
     */
    public suspend fun scrollTo(position: KiteScrollPosition): Unit = onViewerThread {
        scrollToLocation(position.location, animate = false, offsetPx = position.offsetPx)
    }

    /** Jumps to a saved reading position, laying out only its chapter. */
    public suspend fun scrollTo(bookmark: KiteBookmark, animate: Boolean = false): Unit = onViewerThread {
        // Cover the locate window too: a publication during locate() must
        // already correct toward the bookmark's chapter, not the old slot.
        if (bookmark is KiteBookmark.Flow) {
            navigationTarget = flowTarget(bookmark)
            bookmark.fragment?.let { fragmentVisit = FragmentVisit(++fragmentVisits, bookmark.chapter, it) }
        }
        try {
            val location = locateGuarded(bookmark) ?: return@onViewerThread
            publishChapter()
            scrollTo(location, animate)
        } finally {
            navigationTarget = null
        }
    }

    private fun flowTarget(bookmark: KiteBookmark.Flow): KiteLocation =
        KiteLocation(bookmark.chapter.coerceIn(0, (document.chapterCount - 1).coerceAtLeast(0)), 0)

    /** [KiteDocument.locate] off the main thread, or null when the layout it needs failed (#331). */
    private suspend fun locateGuarded(bookmark: KiteBookmark): KiteLocation? = try {
        withContext(kitepdfRasterDispatcher()) { document.locate(bookmark) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        if (bookmark is KiteBookmark.Flow) onComposeThread { failedChapters += flowTarget(bookmark).chapter }
        kiteWarn { "navigation: $bookmark could not be resolved: ${failure.message ?: failure::class.simpleName}" }
        null
    }

    /** Chapters whose layout threw. They stay placeholders, and nothing lays them out again (#331). */
    private val failedChapters = HashSet<Int>()

    /**
     * Lays [chapter] out off the main thread, unless it is ready or failed before. Returns false
     * when its layout throws: the failure is logged once and the chapter keeps its placeholder,
     * where an exception used to end the host app (#331).
     */
    internal suspend fun prepareChapterGuarded(chapter: Int): Boolean {
        if (chapter in failedChapters) return false
        if (document.isChapterReady(chapter)) return true
        return try {
            awaitRemoteLayout(chapter)
            if (layoutPausesForFrames) prepareInSlices(chapter)
            else withContext(kitepdfRasterDispatcher()) { document.prepareChapter(chapter) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            onComposeThread { failedChapters += chapter }
            kiteWarn { "layout: chapter $chapter failed: ${failure.message ?: failure::class.simpleName}" }
            false
        }
    }

    /**
     * Waits at most [remoteWait] for the remote fonts, and the remote images whose markup gives no
     * size, that size [chapter]'s layout, when the book has a fetcher (#38). Whatever lands later
     * keeps fetching: a picture with a declared size paints once it lands, and the rest show in
     * the next document over the book, so the chapter keeps its pages. A URL that a wait gave up
     * on is not waited for again, so a stalled font delays one chapter, not each (#492).
     */
    private suspend fun awaitRemoteLayout(chapter: Int) {
        val epub = document as? EpubDocument ?: return
        if (epub.settings.resourceFetcher == null) return
        withContext(kitepdfRasterDispatcher()) { epub.awaitLayoutResources(chapter, remoteWait) }
        backOnComposeThread()
    }

    /** How long a chapter waits for the remote resources that size its layout (#38). */
    internal var remoteWait: Duration = REMOTE_WAIT

    /**
     * True when a chapter lays out on the UI thread in slices, with a frame drawn between two. In a
     * browser layout cannot leave the UI thread, and a chapter laid out in one call stalled the
     * page for its whole layout (#389).
     */
    internal var layoutPausesForFrames: Boolean = rastersOnUiThread

    /**
     * True where the pages draw host-font text without Compose's text, off the UI thread (#131).
     * A browser draws it through Compose's text, and a test sets this false to draw as one (#595).
     */
    internal var hostTextOffMain: Boolean = hostTextAnyThread

    /** The time a slice of [prepareInSlices] may take before a frame is drawn. */
    internal var layoutSlice: Duration = LAYOUT_SLICE

    /**
     * Lays [chapter] out in steps, each a block of it or a stage such as its pages, and waits for
     * the next frame once a slice has taken [layoutSlice], so the page keeps drawing while a
     * chapter lands (#389). Outside a composition, where no frames come, it yields instead.
     */
    private suspend fun prepareInSlices(chapter: Int) {
        var slice = TimeSource.Monotonic.markNow()
        document.prepareChapter(chapter) {
            if (slice.elapsedNow() >= layoutSlice) {
                if (coroutineContext[MonotonicFrameClock] != null) withFrameNanos { } else yield()
                slice = TimeSource.Monotonic.markNow()
            }
        }
    }

    /** Publishes freshly laid-out chapters at the next frame. See [publishNow]. */
    internal suspend fun publishChapter() {
        onComposeThread { publishNow() }
    }

    /**
     * Publishes freshly laid-out chapters to the strip and keeps the reader on the same
     * content, in one block on the main thread with no suspension point in it.
     *
     * Capture, bump and correction cannot be split up. A suspension between them let the
     * composition react to the new strip first, and in an app the correction was cancelled
     * together with the loader (#343). The correction goes through the container's request
     * API, which applies at the next measure of the same frame, so no scroll in progress can
     * refuse it. It runs only where the container's own key matching would put the reader on
     * other content, so a landing the keys handle leaves a drag or a fling alone (#342).
     */
    internal fun publishNow() {
        val adapter = adapter
        val captured = adapter?.captureAnchor()
        val readerSlot = captured?.slot ?: pendingPage
        val reader = navigationTarget?.let { ReaderAnchor(it) } ?: readerAnchorAt(readerSlot)
        val keyedSlot = if (captured != null) adapter.keyedSlot else -1
        val keyed = items.getOrNull(keyedSlot)
        val parkedLeading = if (adapter == null) pendingLeadingPage?.let { readerAnchorAt(it) } else null
        val selected = selection?.let { it to (it.location ?: locationOf(it.pageIndex)) }

        onChapterReady()

        selected?.let { (sel, location) -> moveSelection(sel, location) }
        val target = reader?.let { slotOf(it) } ?: return
        if (adapter == null) {
            pendingPage = target
            pendingLeadingPage = parkedLeading?.let { slotOf(it) }
            return
        }
        if (captured == null) return
        // A navigation in flight wins: its target goes to the leading edge, as the jump puts it.
        if (navigationTarget != null) {
            adapter.requestSlot(target, ScrollAnchor(target))
            return
        }
        // Where the container's own key matching puts the reader: it finds the key of its anchor
        // slot again, but only within a window around the old index.
        val keyedAfter = keyed?.let { item -> items.indexOfFirst { it.key == item.key } } ?: -1
        val keyedLands = if (adapter.followsKeys && keyedAfter >= 0 && abs(keyedAfter - keyedSlot) < KEY_REACH) keyedAfter else keyedSlot
        val readerLands = keyedLands + (readerSlot - keyedSlot)
        if (readerLands != target) adapter.requestSlot(target, captured) else adapter.expectSlot(target, keyedLands)
    }

    /** Publishes when a ready chapter still holds a placeholder, as it does when someone else laid it out (#341). */
    private fun publishIfStale() {
        if (items.any { it is DocItem.ChapterGap && document.isChapterReady(it.chapter) }) publishNow()
    }

    /** Keeps the selection on its words after the strip changed, and tells the host (#350). */
    private fun moveSelection(sel: KiteTextSelection, location: KiteLocation?) {
        val moved = location?.let { indexOf(it) } ?: -1
        when {
            moved < 0 -> clearSelection()
            moved != sel.pageIndex -> {
                val next = sel.copy(pageIndex = moved)
                selection = next
                onSelectionChange?.invoke(next)
            }
        }
    }

    /**
     * Lays out every chapter that is not ready, the one nearest the reader first, and publishes
     * each as it lands.
     *
     * One loader serves a viewer for as long as it shows this state. It asks where the reader is
     * after every chapter, so it follows them without restarting, where a restart per chapter
     * left abandoned layouts running on the pool (#378). It publishes whenever the strip is
     * behind the document, as it is when someone else laid chapters out, so no ready chapter
     * stays a placeholder (#341). A chapter whose layout fails stays a placeholder (#331).
     */
    internal suspend fun loadChapters() {
        var around = onComposeThread {
            publishIfStale()
            readerChapter()
        }
        // A chapter whose layout returned without making it ready is left to a later navigation.
        val declined = HashSet<Int>()
        while (true) {
            val next = loadOrder(document.chapterCount, around).firstOrNull {
                !document.isChapterReady(it) && it !in failedChapters && it !in declined
            } ?: break
            // Away from the reader, a layout on the UI thread waits until the view rests (#389).
            if (layoutWaitsForRest && abs(next - around) > 1 && onComposeThread { restedMotion != motion() }) {
                awaitRest()
                around = onComposeThread {
                    publishIfStale()
                    readerChapter()
                }
                continue
            }
            if (prepareChapterGuarded(next) && !document.isChapterReady(next)) declined += next
            around = onComposeThread {
                publishIfStale()
                readerChapter()
            }
        }
        onComposeThread {
            publishIfStale()
            loaderDone = true
        }
    }

    private fun readerChapter(): Int = (navigationTarget ?: currentLocation).chapter

    /**
     * True when a chapter away from the reader lays out only while the view rests. Where layout
     * runs on the UI thread, as in a browser, a layout during a scroll or a pinch stalls it (#389).
     * The reader's chapter and its neighbours lay out at once in any case.
     */
    internal var layoutWaitsForRest: Boolean = rastersOnUiThread

    /** How long the view stays still before a chapter away from the reader lays out. */
    internal var restMillis: Long = REST_MILLIS

    /** The motion of the view when it last rested, or null. */
    private var restedMotion: List<Any?>? = null

    /** What moves the view: a scroll in progress, the scroll position, the zoom and the pan. */
    private fun motion(): List<Any?> = listOf(adapter?.isScrollInProgress == true, currentScrollPosition, zoom, panOffset)

    /** Waits until the view has not moved for [restMillis], and returns on the composition's thread. */
    private suspend fun awaitRest() {
        while (true) {
            androidx.compose.runtime.snapshotFlow { adapter?.isScrollInProgress == true }.first { !it }
            val before = onComposeThread { motion() }
            delay(restMillis)
            backOnComposeThread()
            val after = onComposeThread { motion() }
            if (after == before && after[0] == false) {
                restedMotion = after
                return
            }
        }
    }

    /**
     * Resolves the saved position the state was opened with, then drops it.
     *
     * A drag before the jump lands means the reader chose where to be, so the saved position is
     * dropped rather than pulling them back later. The jump goes through the container's
     * request API, which a scroll in progress cannot refuse (#344). A cancellation before the
     * jump keeps the position, and the next attach tries again.
     */
    internal suspend fun openSavedPosition() {
        val scrollAt = openScrollAt
        val mark = openAt
        if (scrollAt == null && mark == null) return
        opening = true
        try {
            val target = when {
                userMovedWhileOpening -> null
                scrollAt != null -> scrollAt.location
                else -> {
                    if (mark is KiteBookmark.Flow) navigationTarget = flowTarget(mark)
                    locateGuarded(mark!!)
                }
            }
            if (target != null && !userMovedWhileOpening) {
                navigationTarget = target
                prepareFor(target)
            }
            onComposeThread {
                if (target != null && !userMovedWhileOpening) jumpNow(navigableSlot(target), scrollAt?.offsetPx ?: 0)
                openScrollAt = null
                openAt = null
            }
        } finally {
            navigationTarget = null
            opening = false
        }
    }

    /** True while [openSavedPosition] runs. */
    private var opening = false

    /** True once the reader dragged the viewer, which cancels a pending open (#344). */
    private var userMovedWhileOpening = false

    /** The viewer calls this when a finger starts to drag its list or pager. */
    internal fun onUserDrag() {
        userMovedWhileOpening = true
        // The reader took over: a landing no longer pulls them toward the saved position.
        if (opening) navigationTarget = null
    }

    /** Puts [slot] at the leading edge, [offsetPx] past it, at the next measure, or parks it. */
    private fun jumpNow(slot: Int, offsetPx: Int) {
        val adapter = adapter
        if (adapter == null) {
            park(slot, offsetPx = offsetPx)
            return
        }
        pendingPage = slot
        adapter.requestSlot(slot, ScrollAnchor(slot, offsetPx = -offsetPx))
    }

    /** Test-only: attach [adapter] as the paged adapter. */
    internal fun attachPagedForTest(adapter: KiteScrollAdapter) { this.adapter = adapter }

    /** Test-only: [slotFor] without widening its visibility story. */
    internal fun slotForTest(location: KiteLocation): Int = slotFor(location)

    /**
     * Lays out [location]'s chapter off the main thread if needed, and makes
     * sure the published strip shows it. The background loader can lay a
     * chapter out moments before its publication lands; skipping on raw
     * readiness alone would leave navigation reading a stale strip where the
     * target does not exist yet.
     */
    private suspend fun prepareFor(location: KiteLocation) {
        val chapter = location.chapter
        if (chapter !in 0 until document.chapterCount) return
        // Ready and published, as pages or, for an empty chapter, as nothing (#347).
        val published = items.none { it is DocItem.ChapterGap && it.chapter == chapter }
        if (document.isChapterReady(chapter) && published) return
        if (!prepareChapterGuarded(chapter)) return
        publishChapter()
    }

    /**
     * The next page in reading order, crossing into the following chapter and
     * laying it out when the current one runs out. Chapters with no pages are
     * skipped (#347).
     */
    public suspend fun nextPage(): Unit = onViewerThread {
        val target = locationAfter(currentLocation) ?: return@onViewerThread
        scrollTo(target, animate = true)
    }

    /** The previous page in reading order, crossing back a chapter if needed and skipping empty ones. */
    public suspend fun previousPage(): Unit = onViewerThread {
        val target = locationBefore(currentLocation) ?: return@onViewerThread
        scrollTo(target, animate = true)
    }

    /**
     * The location after [here] in reading order, or null at the end. Chapters are laid out off
     * the main thread to count their pages. One that failed or is not ready keeps its
     * placeholder, which is then the next slot.
     */
    private suspend fun locationAfter(here: KiteLocation): KiteLocation? {
        if (prepareChapterGuarded(here.chapter) && document.isChapterReady(here.chapter) &&
            here.page + 1 < document.pageCountIn(here.chapter)
        ) {
            return KiteLocation(here.chapter, here.page + 1)
        }
        for (chapter in here.chapter + 1 until document.chapterCount) {
            if (!prepareChapterGuarded(chapter) || !document.isChapterReady(chapter)) return KiteLocation(chapter, 0)
            if (document.pageCountIn(chapter) > 0) return KiteLocation(chapter, 0)
        }
        return null
    }

    /** The location before [here] in reading order, or null at the start. See [locationAfter]. */
    private suspend fun locationBefore(here: KiteLocation): KiteLocation? {
        if (here.page > 0) return KiteLocation(here.chapter, here.page - 1)
        for (chapter in here.chapter - 1 downTo 0) {
            if (!prepareChapterGuarded(chapter) || !document.isChapterReady(chapter)) return KiteLocation(chapter, 0)
            val pages = document.pageCountIn(chapter)
            if (pages > 0) return KiteLocation(chapter, pages - 1)
        }
        return null
    }

    internal data class PanAxes(val x: Boolean, val y: Boolean) {
        companion object {
            val Both = PanAxes(x = true, y = true)
            val XOnly = PanAxes(x = true, y = false)
            val YOnly = PanAxes(x = false, y = true)
        }
    }

    /**
     * What a saved state holds: the page and scroll offset of a fixed-layout page, or a bookmark
     * in a reflowable book, which survives a change of font or page size. A chapter that is not
     * laid out yet saves its start, so saving never lays one out.
     */
    private fun savedPosition(): List<Any> {
        val at = currentScrollPosition
        val chapter = at.location.chapter
        val mark = if (document.isChapterReady(chapter)) document.bookmarkOf(at.location) else null
        return if (mark is KiteBookmark.Flow) {
            listOf(SAVED_FLOW, mark.chapter, mark.charOffset, mark.fragment ?: "")
        } else {
            listOf(SAVED_SCROLL, chapter, at.location.page, at.offsetPx)
        }
    }

    public companion object {
        private const val EPSILON = 0.001f

        /**
         * How far a lazy container searches for a key it anchors on, in slots. Compose looks
         * within 100 slots of the old index (`LazyLayoutNearestRangeState`), and a landing that
         * moves the key further is corrected by hand.
         */
        private const val KEY_REACH = 90

        /** How long the view must rest before a chapter away from the reader lays out on the UI thread (#389). */
        private const val REST_MILLIS = 400L

        /** Half a frame at 60 Hz: the rest of the frame is the page's own work (#389). */
        private val LAYOUT_SLICE = 8.milliseconds

        /** A remote font or image that sizes a chapter delays its layout this long at most (#38). */
        private val REMOTE_WAIT = 2000.milliseconds

        private const val SAVED_SCROLL = 0
        private const val SAVED_FLOW = 1

        /**
         * Saves the reading position of a state for [document], so it survives a configuration
         * change and the end of the process. [rememberKiteDocViewState] uses it. The viewer's
         * scroll containers save nothing of their own, so a host that builds its own state saves
         * it with this:
         *
         * ```kotlin
         * val state = rememberSaveable(book, saver = KiteDocViewState.saver(book)) { KiteDocViewState(book) }
         * ```
         *
         * It saves the position only: the page and scroll offset of a fixed-layout document, or
         * a bookmark in a reflowable book. The zoom and a selection start again.
         */
        public fun saver(document: KiteDocument): Saver<KiteDocViewState, Any> = listSaver(
            save = { it.savedPosition() },
            restore = { saved ->
                val a = (saved[1] as Int).coerceAtLeast(0)
                val b = (saved[2] as Int).coerceAtLeast(0)
                if (saved[0] == SAVED_FLOW) {
                    KiteDocViewState(document, KiteBookmark.Flow(a, b, (saved[3] as String).ifEmpty { null }))
                } else {
                    KiteDocViewState(document, KiteScrollPosition(KiteLocation(a, b), (saved[3] as Int).coerceAtLeast(0)))
                }
            },
        )
    }
}

/**
 * Where the reader is, in terms that survive a change of the strip: a page, or the placeholder
 * of a chapter together with the side the reader came from.
 */
internal class ReaderAnchor(
    val location: KiteLocation,
    /** True when [location] names a placeholder, whose chapter was not laid out. */
    val onPlaceholder: Boolean = false,
    /** True for a placeholder reached by moving backwards: its chapter opens at its last page. */
    val fromEnd: Boolean = false,
)

/**
 * Where a slot sits in the viewport: its offset from the leading edge in pixels for a list, the
 * fraction of a page it is scrolled by for a pager.
 */
internal class ScrollAnchor(val slot: Int, val offsetPx: Int = 0, val pageFraction: Float = 0f)

/**
 * The page under a viewport point, and a point on that page.
 *
 * From [KiteDocViewState.hitTest], the point is in the page's own space. For a PDF
 * that is the page's user space, y-up, with rotation unfolded, in the file's own
 * coordinates: a page whose crop box does not start at 0 gives points offset by
 * that start. For an EPUB page it is display points, y-up from the page's
 * bottom-left. Other formats use their own page space.
 *
 * From [KiteDocViewState.hitTestDisplay], the point is in display space for every
 * format: points from the top-left corner of the page as it is shown, y down. That
 * is the space of search hits and highlights (#432).
 */
public data class KitePageHit(
    /** The viewer slot when this result was produced. Use [location] after publication. */
    val pageIndex: Int,
    val x: Double,
    val y: Double,
    /** Exact page in the current layout; always present on viewer-produced results. */
    val location: KiteLocation? = null,
) {
    /** Legacy constructor for callers that do not yet carry a location. */
    public constructor(pageIndex: Int, x: Double, y: Double) : this(pageIndex, x, y, null)

    /** Keeps the original copy signature and preserves this result's location. */
    public fun copy(pageIndex: Int = this.pageIndex, x: Double = this.x, y: Double = this.y): KitePageHit =
        KitePageHit(pageIndex, x, y, location)
}

/**
 * A finalized or in-progress text selection on one page (cross-page
 * selection is out of scope). [start]/[end] are INCLUSIVE flattened char
 * indices into the page's [io.github.yuroyami.kitepdf.core.KiteStructuredText]
 * reading order; [text] is the range as a reader copies it
 * ([io.github.yuroyami.kitepdf.core.KiteStructuredText.copyText]): `\n\n`
 * between blocks, and a wrapped paragraph of a book as one line; [quads] are
 * display-space, one per line touched.
 */
public data class KiteTextSelection(
    /** The viewer slot when this selection was produced. Persist [location] instead. */
    val pageIndex: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val quads: List<io.github.yuroyami.kitepdf.core.KiteRectangle>,
    /** Exact page in this layout. A reflow changes both this coordinate and the quads. */
    val location: KiteLocation? = null,
) {
    /** Legacy constructor for callers that do not yet carry a location. */
    public constructor(pageIndex: Int, start: Int, end: Int, text: String, quads: List<KiteRectangle>) :
        this(pageIndex, start, end, text, quads, null)

    /** Keeps the original copy signature and preserves this selection's location. */
    public fun copy(
        pageIndex: Int = this.pageIndex,
        start: Int = this.start,
        end: Int = this.end,
        text: String = this.text,
        quads: List<KiteRectangle> = this.quads,
    ): KiteTextSelection = KiteTextSelection(pageIndex, start, end, text, quads, location)
}

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
    /** Stable host identity, returned intact by [KiteDocViewState.highlightAt]. */
    val id: String? = null,
) {
    /** The page of [hit], in its current layout, when the producer supplied it. */
    public val location: KiteLocation? get() = hit.location
}

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
    val leadingPage: Int get() = currentPage
    val scrollOffsetPx: Int get() = 0
    suspend fun scrollToPage(page: Int)
    suspend fun animateScrollToPage(page: Int)

    /**
     * The reader's slot and where it sits in the viewport, taken before the strip changes. Null
     * for a layout that does not follow chapter landings.
     */
    fun captureAnchor(): ScrollAnchor? = null

    /** The slot whose key the container keeps in place when the strip changes under it. */
    val keyedSlot: Int get() = currentPage

    /** True when [slot] shows in the viewport now, and not only as a page composed ahead of it. */
    fun shows(slot: Int): Boolean = slot == currentPage

    /** True while a drag, a fling or an animation moves the container, so its page is not settled. */
    val isScrollInProgress: Boolean get() = false

    /**
     * True while a page turn that code started, not a finger, moves the container. A pager takes
     * the press of a tap to stop its scroll, so a pager turns its own drag off meanwhile (#455).
     */
    val isTurning: Boolean get() = false

    /**
     * False when the container keeps its index, not its key, at its next measure: always for a
     * container without keys, and while a correction waits for that measure.
     */
    val followsKeys: Boolean get() = true

    /**
     * Puts [slot] where [anchor] was, at the next measure. It does not suspend, so nothing can
     * cancel it between a change of the strip and the measure that shows it (#343).
     */
    fun requestSlot(slot: Int, anchor: ScrollAnchor) {}

    /**
     * Tells the container where its own key matching will put the reader, [slot], and its
     * leading edge, [leadingSlot], at the next measure. Until that measure its index still
     * counts slots of the old strip, so [currentPage] and [leadingPage] answer these instead:
     * a navigation that starts in between must not start from other content.
     */
    fun expectSlot(slot: Int, leadingSlot: Int) {}
}

/** Continuous mode: "current" = the visible item whose centre is nearest the viewport centre. */
internal class LazyListScrollAdapter(private val listState: LazyListState) : KiteScrollAdapter {
    override val isScrollInProgress: Boolean get() = listState.isScrollInProgress

    override fun shows(slot: Int): Boolean = listState.layoutInfo.visibleItemsInfo.any { it.index == slot }

    /** The length of the leading slot on the scroll axis, or null before the list has measured it. */
    val leadingSlotLength: Int? get() = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == leadingPage }?.size
    override val leadingPage: Int
        get() {
            expected?.let { (_, lead, before) -> if (listState.layoutInfo === before) return lead }
            return listState.firstVisibleItemIndex
        }
    override val scrollOffsetPx: Int get() = listState.firstVisibleItemScrollOffset
    suspend fun scrollToPageOffset(page: Int, offsetPx: Int) = listState.scrollToItem(page, offsetPx)

    suspend fun animateScrollToPageOffset(page: Int, offsetPx: Int) = listState.animateScrollToItem(page, offsetPx)

    /** Scrolls the strip by [px], back toward its start when [px] is negative. */
    suspend fun animateScrollBy(px: Float) {
        listState.animateScrollBy(px)
    }

    /**
     * The height of the slot of a page of [aspect] in this vertical strip, from the width of a slot
     * in view, whose page's aspect [aspectOf] gives, or null with no page in view.
     */
    fun verticalSlotLength(aspect: Float, aspectOf: (Int) -> Float?): Int? {
        for (item in listState.layoutInfo.visibleItemsInfo) {
            val shown = aspectOf(item.index) ?: continue
            val cross = (item.size * shown).roundToInt()
            return stripSlotLength(vertical = true, aspect = aspect, cross = cross)
        }
        return null
    }

    /**
     * A slot asked for through [requestSlot], with the layout it was asked against. Until the
     * list measures again its layout info still describes the old strip, and the requested slot
     * is where the reader is.
     */
    private var requested: Pair<Int, LazyListLayoutInfo>? = null

    /** The reader's and the leading slot that key matching gives at the next measure, and the layout they replace. */
    private var expected: Triple<Int, Int, LazyListLayoutInfo>? = null

    /**
     * The slot nearest the viewport's centre. The list's layout changes on every scroll frame,
     * and this changes only when another slot comes nearest, so a reader recomposes only then (#374).
     */
    private val centreSlot = androidx.compose.runtime.derivedStateOf {
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return@derivedStateOf listState.firstVisibleItemIndex
        val viewportCentre = (info.viewportStartOffset + info.viewportEndOffset) / 2
        visible.minByOrNull { abs((it.offset + it.size / 2) - viewportCentre) }?.index
            ?: listState.firstVisibleItemIndex
    }

    override val currentPage: Int
        get() {
            // An open request or expectation reads the layout until the list measures again, a
            // frame at most, and then it is dropped.
            requested?.let { (slot, before) -> if (listState.layoutInfo === before) return slot else requested = null }
            expected?.let { (slot, _, before) -> if (listState.layoutInfo === before) return slot else expected = null }
            return centreSlot.value
        }

    override val keyedSlot: Int get() = leadingPage

    // A request makes the list forget the key it anchors on until it measures again.
    override val followsKeys: Boolean get() = requested?.let { (_, before) -> listState.layoutInfo !== before } ?: true

    override fun captureAnchor(): ScrollAnchor {
        val slot = currentPage
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == slot }
        // An item the list has not measured yet was placed by a request, at its own offset.
        return ScrollAnchor(slot, offsetPx = item?.offset ?: -listState.firstVisibleItemScrollOffset)
    }

    override fun requestSlot(slot: Int, anchor: ScrollAnchor) {
        requested = slot to listState.layoutInfo
        expected = null
        listState.requestScrollToItem(slot, -anchor.offsetPx)
    }

    override fun expectSlot(slot: Int, leadingSlot: Int) {
        expected = Triple(slot, leadingSlot, listState.layoutInfo)
    }

    override suspend fun scrollToPage(page: Int) = listState.scrollToItem(page)
    override suspend fun animateScrollToPage(page: Int) = listState.animateScrollToItem(page)
}

internal class PagerScrollAdapter(private val pagerState: PagerState) : KiteScrollAdapter {
    override val isScrollInProgress: Boolean get() = pagerState.isScrollInProgress

    private var turns by mutableIntStateOf(0)
    override val isTurning: Boolean get() = turns > 0

    override fun shows(slot: Int): Boolean = pagerState.layoutInfo.visiblePagesInfo.any { it.index == slot }
    /** True while a finger drags the pager. A correction then keeps the page offset, so the drag goes on. */
    var dragging: Boolean = false

    /** The layout a correction was asked against. The pager forgets its key until it measures again. */
    private var requestedAgainst: PagerLayoutInfo? = null

    /** The slot that key matching gives at the next measure, and the layout it replaces. */
    private var expected: Pair<Int, PagerLayoutInfo>? = null

    override val followsKeys: Boolean get() = requestedAgainst?.let { pagerState.layoutInfo !== it } ?: true

    override val currentPage: Int
        get() {
            val pending = expected
            if (pending != null) {
                // Read the layout only while an expectation is open, so a reader does not follow every scroll frame.
                if (pagerState.layoutInfo === pending.second) return pending.first
                expected = null
            }
            return pagerState.currentPage
        }

    override fun expectSlot(slot: Int, leadingSlot: Int) {
        expected = slot to pagerState.layoutInfo
    }
    override suspend fun scrollToPage(page: Int) = pagerState.scrollToPage(page)
    override suspend fun animateScrollToPage(page: Int) {
        turns++
        try {
            pagerState.animateScrollToPage(page)
        } finally {
            turns--
        }
    }

    override fun captureAnchor(): ScrollAnchor =
        ScrollAnchor(pagerState.currentPage, pageFraction = pagerState.currentPageOffsetFraction)

    override fun requestSlot(slot: Int, anchor: ScrollAnchor) {
        // Outside a drag the page snaps straight into place: a correction that cancels a settle
        // animation must not leave the pager between two pages.
        requestedAgainst = pagerState.layoutInfo
        expected = null
        pagerState.requestScrollToPage(slot, if (dragging) anchor.pageFraction else 0f)
    }
}

/**
 * Spread mode: pager items are spreads of one or two pages, as [plans] pairs them. Logical page
 * indices stay the public currency ([KiteDocViewState.scrollToPage] etc.); this adapter maps them
 * to spread items ("current" reports the spread's first page in reading order), so
 * nextPage()/previousPage() remain plain index +1/-1 and the visible spread advances once the
 * step leaves it.
 */
internal class SpreadScrollAdapter(
    private val pagerState: PagerState,
    private val plans: SpreadPlans,
    /** The page the reader was on, so a pager that comes back keeps the second page of a spread. */
    initialPage: Int = plans.current.firstPageOf(pagerState.currentPage),
) : KiteScrollAdapter {
    override val isScrollInProgress: Boolean get() = pagerState.isScrollInProgress

    private var turns by mutableIntStateOf(0)
    override val isTurning: Boolean get() = turns > 0

    override fun shows(slot: Int): Boolean {
        val spread = plans.current.spreadOf(slot)
        return pagerState.layoutInfo.visiblePagesInfo.any { it.index == spread }
    }

    /**
     * The last logically-requested page. Within one spread, +1 must actually
     * advance (0 -> 1 stays on spread 0, the next +1 reaches spread 1), so
     * the adapter remembers it; a user swipe onto another spread supersedes
     * it and "current" snaps back to that spread's first page. It is snapshot
     * state, so a step inside one spread reaches every observer (#402).
     */
    private var logical by mutableIntStateOf(initialPage)

    override val currentPage: Int
        get() {
            val plan = plans.current
            return if (plan.spreadOf(logical) == pagerState.currentPage) logical else plan.firstPageOf(pagerState.currentPage)
        }

    override suspend fun scrollToPage(page: Int) {
        logical = page
        pagerState.scrollToPage(plans.current.spreadOf(page))
    }

    override suspend fun animateScrollToPage(page: Int) {
        logical = page
        turns++
        try {
            pagerState.animateScrollToPage(plans.current.spreadOf(page))
        } finally {
            turns--
        }
    }

    /** The first page of the spread after or before the current one, or null at that end of the book. */
    fun neighbourSpreadPage(forward: Boolean): Int? {
        val plan = plans.current
        val spread = plan.spreadOf(currentPage) + if (forward) 1 else -1
        return if (spread in 0 until plan.size) plan.firstPageOf(spread) else null
    }

    /**
     * Shows [plan] from the next measure, on the spread that holds the page the reader is on. A
     * turned device or a new pairing setting changes the plan (#37).
     */
    fun replan(plan: SpreadPlan) {
        val page = currentPage
        plans.current = plan
        logical = page
        pagerState.requestScrollToPage(plan.spreadOf(page))
    }
}

/** Single-page mode: no scrolling at all. */
internal class FixedPageAdapter(private val pageIndex: Int) : KiteScrollAdapter {
    override val currentPage: Int get() = pageIndex
    override suspend fun scrollToPage(page: Int) = Unit
    override suspend fun animateScrollToPage(page: Int) = Unit
}
