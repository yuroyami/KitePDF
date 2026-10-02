package io.github.yuroyami.kitepdf.compose

import kotlinx.coroutines.ensureActive
import io.github.yuroyami.kitepdf.core.KiteCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.kiteWarn
import io.github.yuroyami.kitepdf.core.render.KITE_DEFAULT_MAX_RASTER_PIXELS
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.ReaderTheme

/**
 * Imperative page → [ImageBitmap] pipeline. This is the raster engine behind
 * [KiteDocView]; it is public so apps with custom viewers (own pagers, thumbnail
 * grids, PNG export jobs) don't have to re-implement the CTM/flip/hairline
 * math themselves.
 *
 * Obtain one inside composition with [rememberKitePageRasterizer], or construct it
 * directly off-composition when you already hold a [TextMeasurer].
 *
 * [rasterize] requires the platform UI thread and refuses other callers before
 * drawing. On desktop JVM this is the AWT event dispatch thread, including headless
 * exports. [rasterizeOffMain] is the suspend entry point for any calling thread; it
 * moves the work to [kitepdfRasterDispatcher] (a background pool on
 * JVM/Android/Apple, Main on JS/Wasm) so a complex page does not jank scrolling
 * or pinch. [KiteDocView] uses that path. A page with text in a system font is
 * the exception: the host's text stack belongs to the main thread, so such a
 * page is drawn a second time there, in full (#131).
 */
@Stable
public class KitePageRasterizer(
    private val density: Density,
    private val layoutDirection: LayoutDirection,
    private val textMeasurer: TextMeasurer,
    private val maxBitmapPixels: Long,
) {

    /** Binary-compatible constructor using the default bitmap ceiling. */
    public constructor(
        density: Density,
        layoutDirection: LayoutDirection,
        textMeasurer: TextMeasurer,
    ) : this(density, layoutDirection, textMeasurer, KITE_DEFAULT_MAX_RASTER_PIXELS)

    init {
        require(maxBitmapPixels > 0L) { "maxBitmapPixels must be > 0" }
    }

    internal companion object {
        /**
         * One gate for the whole process: two rasters run at once where the platform has
         * threads to spare, and a free slot goes to a page on screen before a page drawn ahead
         * or a thumbnail (#370). A browser has one thread, so it runs one raster at a time.
         */
        internal val rasterGate = RasterGate(if (rastersOnUiThread) 1 else 2)

        /** The cancellation of a render nobody can cancel. */
        private val NEVER_CANCELLED = KiteCancellation { false }
    }

    /**
     * The last page that drew host-font text, so its next raster, after a zoom or a resize, goes
     * to Main without a probe (#131). Two rasters can race on it, which costs at most one probe.
     */
    @kotlin.concurrent.Volatile
    private var hostFontPage: KitePage? = null

    /**
     * True when a page is first drawn off the main thread without host-font text, to find out
     * whether it needs the main thread at all (#131). Where rasters already run on the UI
     * thread, as in a browser, that probe only doubles the work, so a page draws once (#389).
     */
    internal var probesOffMain: Boolean = !rastersOnUiThread

    /**
     * [rasterize], off the main thread where the platform allows. Cancelling the
     * calling coroutine stops a PDF page between operators and throws a
     * CancellationException instead of returning a partial bitmap (#188).
     *
     * Pages that fall back to system-font text (EPUB body text, PDFs without
     * embedded outlines) are re-rendered on the platform UI thread: skiko's text
     * stack shares process-global state with the host UI thread, and no lock
     * of ours can exclude that thread, so the only safe place to measure or
     * draw through it is the main thread itself. Pages whose glyphs all have
     * embedded outlines (the common PDF case) stay entirely on the pool.
     * Desktop JVM uses the AWT event dispatch thread even in headless mode, without
     * requiring a coroutine Main dispatcher. Never block that thread waiting for this call.
     *
     * Two rasters run at once across the process. This call waits for a free slot
     * with the priority of a page on screen, ahead of the pages that a viewer draws
     * in advance and of thumbnails (#370).
     */
    public suspend fun rasterizeOffMain(
        page: KitePage,
        widthPx: Int,
        heightPx: Int,
        background: Color = Color.White,
        hairlineWidthPx: Float = 1f,
        theme: ReaderTheme? = null,
    ): ImageBitmap = rasterizeOffMain(page, widthPx, heightPx, background, hairlineWidthPx, theme, canvasDecorator = null)

    /**
     * [rasterizeOffMain] with an application canvas wrapper. See [KiteCanvasDecorator]
     * for ordering, repeatable paint passes and threading requirements.
     */
    public suspend fun rasterizeOffMain(
        page: KitePage,
        widthPx: Int,
        heightPx: Int,
        background: Color = Color.White,
        hairlineWidthPx: Float = 1f,
        theme: ReaderTheme? = null,
        canvasDecorator: KiteCanvasDecorator?,
    ): ImageBitmap = rasterGate.withPermit({ RasterPriority.VISIBLE }) {
        rasterizeOffMainLocked(page, widthPx, heightPx, background, hairlineWidthPx, theme, canvasDecorator = canvasDecorator)
    }

    /**
     * Exports the accepted live values of [formState] from any coroutine dispatcher.
     * The state is copied before waiting for a raster slot, so both paint passes use
     * the same field values, choice selections and visibility (ISO 32000-1,
     * 12.7). Later edits do not change an export already in progress. Other page types
     * ignore the form state. Unlike the viewer's cached page bitmap, this includes widgets.
     *
     * This is the suspend replacement for synchronous [rasterize] form exports on a worker.
     * Cancellation and text-thread dispatch follow the other [rasterizeOffMain] overloads.
     */
    public suspend fun rasterizeOffMain(
        page: KitePage,
        widthPx: Int,
        heightPx: Int,
        formState: io.github.yuroyami.kitepdf.PdfFormState?,
        background: Color = Color.White,
        hairlineWidthPx: Float = 1f,
        theme: ReaderTheme? = null,
        canvasDecorator: KiteCanvasDecorator? = null,
    ): ImageBitmap {
        val snapshot = formState?.snapshot()
        return rasterGate.withPermit({ RasterPriority.VISIBLE }) {
            rasterizeOffMainLocked(
                page, widthPx, heightPx, background, hairlineWidthPx, theme,
                canvasDecorator = canvasDecorator, formState = snapshot,
            )
        }
    }

    /**
     * The off-main render body. Must be called with a slot of [rasterGate].
     * Probes on the raster pool with system-font text skipped; if the page
     * needed such text, discards the probe and re-renders fully on Main so
     * the skiko text stack is only touched from the host UI thread.
     */
    private suspend fun rasterizeOffMainLocked(
        page: KitePage,
        widthPx: Int,
        heightPx: Int,
        background: Color,
        hairlineWidthPx: Float,
        theme: ReaderTheme?,
        skipWidgets: Boolean = false,
        canvasDecorator: KiteCanvasDecorator? = null,
        region: IntRect? = null,
        formState: io.github.yuroyami.kitepdf.PdfFormState? = null,
    ): ImageBitmap {
        // A page the viewer no longer needs stops between operators when its coroutine is cancelled (#188).
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val cancellation = job?.let { KiteCancellation { !it.isActive } }
        val probe = if (!probesOffMain) null else kotlinx.coroutines.withContext(kitepdfRasterDispatcher()) {
            // A page that draws host-font text, as it says or as it did the last time, goes to Main
            // at once: a probe would draw it in full only to throw the bitmap away (#131). The page
            // answers here, off Main, because an answer can lay its chapter out.
            if (hostFontPage === page || page.drawsHostFontText == true) null
            else rasterizeInternal(page, widthPx, heightPx, background, hairlineWidthPx, theme, skipSystemFontText = true, skipWidgets = skipWidgets, canvasDecorator = canvasDecorator, cancellation = cancellation, region = region, formState = formState)
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (probe != null && !probe.second) return probe.first
        hostFontPage = page
        return onHostTextThread {
            rasterizeInternal(page, widthPx, heightPx, background, hairlineWidthPx, theme, skipSystemFontText = false, skipWidgets = skipWidgets, canvasDecorator = canvasDecorator, cancellation = cancellation, region = region, formState = formState).first
        }.also { kotlinx.coroutines.currentCoroutineContext().ensureActive() }
    }

    /**
     * [rasterizeOffMain] through [cache]: a hit returns the cached bitmap at once, a miss
     * waits for a slot of [rasterGate] at [priority], then rasterizes and inserts. Second value
     * of the pair: true when this call actually rasterized (drives `onPageRendered`, which must
     * not re-fire on cache hits).
     */
    internal suspend fun rasterizeCachedOffMain(
        cache: PageBitmapCache?,
        page: KitePage,
        widthPx: Int,
        heightPx: Int,
        background: Color,
        hairlineWidthPx: Float,
        theme: ReaderTheme?,
        skipWidgets: Boolean = false,
        canvasDecorator: KiteCanvasDecorator? = null,
        priority: () -> Int = { RasterPriority.VISIBLE },
        region: IntRect? = null,
    ): Pair<ImageBitmap, Boolean> {
        val key = cache?.let {
            PageBitmapCache.Key(
                pageIdentity = page,
                w = widthPx,
                h = heightPx,
                bgArgb = paperColor(background, theme).toArgb(),
                theme = theme,
                hairlineBits = hairlineWidthPx.toRawBits(),
                withoutWidgets = skipWidgets,
                canvasDecorator = canvasDecorator,
                fontEnvironment = textMeasurer,
                region = region,
            )
        }
        if (cache != null && key != null) cache.get(key)?.let { return it to false }
        return rasterGate.withPermit(priority) {
            // Another raster of the same page may have filled the cache while this one waited.
            val hit = if (cache != null && key != null) cache.get(key) else null
            if (hit != null) {
                hit to false
            } else {
                val bmp = rasterizeOffMainLocked(page, widthPx, heightPx, background, hairlineWidthPx, theme, skipWidgets, canvasDecorator, region)
                if (cache != null && key != null) cache.put(key, bmp)
                bmp to true
            }
        }
    }

    /**
     * [rasterizeCachedOffMain] behind the failure guard every composable call
     * site must use: produceState installs no exception handler, so an escaped
     * throwable (a torn page, an OOM bitmap) walks straight to the platform's
     * unhandled hook and aborts the HOST APP. One retry covers transient
     * conditions; a page that fails twice reports null so its slot keeps the
     * placeholder. CancellationException is rethrown so cancellation stays
     * prompt. Composables must route through this instead of calling the
     * unguarded methods, so a new call site cannot regress the guard.
     *
     * The catch is [Throwable], not [Exception]: an OutOfMemoryError is an
     * Error, and the guard once let it through to kill the app it was
     * written to protect (#219). The failed bitmap is garbage by the time
     * the retry runs, so the second attempt often has the room the first
     * lacked.
     */
    internal suspend fun rasterizeCachedOrNull(
        cache: PageBitmapCache?,
        page: KitePage,
        widthPx: Int,
        heightPx: Int,
        background: Color,
        hairlineWidthPx: Float,
        theme: ReaderTheme?,
        pageIndex: Int,
        skipWidgets: Boolean = false,
        canvasDecorator: KiteCanvasDecorator? = null,
        priority: () -> Int = { RasterPriority.VISIBLE },
        region: IntRect? = null,
    ): Pair<ImageBitmap, Boolean>? {
        for (attempt in 0 until 2) {
            try {
                return rasterizeCachedOffMain(cache, page, widthPx, heightPx, background, hairlineWidthPx, theme, skipWidgets, canvasDecorator, priority, region)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                kiteWarn {
                    "render: page $pageIndex failed to rasterize " +
                        "(attempt ${attempt + 1}): ${failure.message ?: failure::class.simpleName}"
                }
            }
        }
        return null
    }

    /**
     * Renders [page] into a fresh [widthPx]×[heightPx] bitmap.
     *
     * The page is scaled to fill [widthPx]. Give [heightPx] the page's own aspect
     * ratio, `widthPx * displayHeight / displayWidth`: a shorter bitmap cuts off
     * the bottom of the page, and a taller one leaves background below it.
     *
     * Requires the platform UI thread (AWT event dispatch thread on desktop JVM).
     * Throws [IllegalStateException] before allocation or page traversal on another
     * thread. Use [rasterizeOffMain] for a background export, even for an outlined page:
     * a decorator or fallback may still reach Compose's shared text cache (#428).
     *
     * @param background colour painted before page content (documents assume paper).
     * @param hairlineWidthPx the width in raster pixels of a stroke whose line width
     *   is 0. See [ComposeCanvas]. Pass the raster:on-screen ratio (>1) when rendering
     *   supersampled so sub-pixel strokes keep their weight after the downscale.
     */
    public fun rasterize(
        page: KitePage,
        widthPx: Int,
        heightPx: Int,
        background: Color = Color.White,
        hairlineWidthPx: Float = 1f,
        theme: ReaderTheme? = null,
    ): ImageBitmap = rasterize(page, widthPx, heightPx, background, hairlineWidthPx, theme, canvasDecorator = null)

    /**
     * [rasterize] with an application canvas wrapper. See [KiteCanvasDecorator]
     * for ordering, repeatable paint passes and threading requirements.
     */
    public fun rasterize(
        page: KitePage,
        widthPx: Int,
        heightPx: Int,
        background: Color = Color.White,
        hairlineWidthPx: Float = 1f,
        theme: ReaderTheme? = null,
        canvasDecorator: KiteCanvasDecorator?,
    ): ImageBitmap {
        requireHostTextThread()
        return rasterizeInternal(page, widthPx, heightPx, background, hairlineWidthPx, theme, skipSystemFontText = false, canvasDecorator = canvasDecorator).first
    }

    /**
     * [rasterize] for an export of a form: a PDF page draws its fields with the values that
     * [formState] holds, the ones the reader typed and the scripts wrote, as the viewer shows
     * them. Other pages ignore [formState].
     *
     * The bitmaps that [KiteDocView]'s `onPageRendered` gives leave the fields out while a form
     * layer draws them, so export a filled form with this call (#431).
     *
     * ```kotlin
     * val bitmap = rasterizer.rasterizeOffMain(page, 1240, 1754, formState = scripts.formState)
     * val png = bitmap.encodeToPng()
     * ```
     */
    public fun rasterize(
        page: KitePage,
        widthPx: Int,
        heightPx: Int,
        formState: io.github.yuroyami.kitepdf.PdfFormState?,
        background: Color = Color.White,
        hairlineWidthPx: Float = 1f,
        theme: ReaderTheme? = null,
        canvasDecorator: KiteCanvasDecorator? = null,
    ): ImageBitmap {
        requireHostTextThread()
        return rasterizeInternal(
            page, widthPx, heightPx, background, hairlineWidthPx, theme,
            skipSystemFontText = false, canvasDecorator = canvasDecorator, formState = formState,
        ).first
    }

    /**
     * [rasterize] plus the system-font probe flag: second value is true when
     * the page hit the system-font fallback while [skipSystemFontText] was set
     * (those runs were left undrawn and the bitmap is incomplete).
     */
    private fun rasterizeInternal(
        page: KitePage,
        widthPx: Int,
        heightPx: Int,
        background: Color,
        hairlineWidthPx: Float,
        theme: ReaderTheme?,
        skipSystemFontText: Boolean,
        skipWidgets: Boolean = false,
        canvasDecorator: KiteCanvasDecorator? = null,
        cancellation: KiteCancellation? = null,
        formState: io.github.yuroyami.kitepdf.PdfFormState? = null,
        /** The part of the page drawn [widthPx] × [heightPx] that the bitmap holds, or null for all of it (#375). */
        region: IntRect? = null,
    ): Pair<ImageBitmap, Boolean> {
        require(widthPx > 0 && heightPx > 0) { "bitmap dimensions must be > 0" }
        require(region == null || (region.width > 0 && region.height > 0)) { "a region must not be empty" }
        val bitmapW = region?.width ?: widthPx
        val bitmapH = region?.height ?: heightPx
        require(bitmapW.toLong() * bitmapH.toLong() <= maxBitmapPixels) {
            "page bitmap is ${bitmapW}x$bitmapH pixels; limit is $maxBitmapPixels pixels"
        }
        require(page.displayWidth.isFinite() && page.displayWidth > 0.0) {
            "page display width must be finite and > 0"
        }
        require(page.displayHeight.isFinite() && page.displayHeight > 0.0) {
            "page display height must be finite and > 0"
        }
        require(hairlineWidthPx.isFinite() && hairlineWidthPx >= 0f) {
            "hairlineWidthPx must be finite and >= 0"
        }
        val w = widthPx
        val h = heightPx
        // Fit scale from the display box: displayToDeviceBase() already maps
        // unscaled page space into a top-left, Y-down device box of
        // [0,displayWidth] x [0,displayHeight] (PDF folds in the display-box origin
        // and normalized /Rotate; EPUB folds in its top-left flip). Scaling it by
        // `s` in device space gives the final CTM; no manual Y-flip here.
        val s = w / page.displayWidth
        val fittedHeight = page.displayHeight * s
        if (kotlin.math.abs(h - fittedHeight) > 1.0) {
            kiteWarn {
                "rasterize: a ${w}x$h bitmap does not match the page's aspect ratio " +
                    "(${w}x${fittedHeight.toInt()}), so the page fills the width and the height does not fit"
            }
        }
        // The theme owns the paper colour when set; else use `background`.
        val bg = paperColor(background, theme)
        val bitmap = ImageBitmap(bitmapW, bitmapH)
        var usedSystemFont = false
        CanvasDrawScope().draw(density, layoutDirection, Canvas(bitmap), Size(bitmapW.toFloat(), bitmapH.toFloat())) {
            drawRect(bg, size = size)
            // concat(b) applies b FIRST, so displayToDeviceBase() runs before the scale, and a
            // region's offset last: the page is drawn whole, and the bitmap keeps that part of it.
            val whole = KiteMatrix.scaling(s, s).concat(page.displayToDeviceBase())
            val deviceCtm = region?.let { KiteMatrix.translation(-it.left.toDouble(), -it.top.toDouble()).concat(whole) } ?: whole
            val base = ComposeCanvas(this, textMeasurer, hairlineWidthPx, skipSystemFontText)
            val themed = theme?.wrap(base) ?: base
            val canvas = canvasDecorator?.invoke(themed) ?: themed
            // A viewer with a live form draws the widgets in its own layer, so the bitmap must
            // leave them out or each field would be drawn twice, the stale one underneath.
            when {
                formState != null && page is io.github.yuroyami.kitepdf.PdfPage -> page.renderTo(
                    canvas, deviceCtm, formState, cancellation = cancellation ?: NEVER_CANCELLED,
                )
                skipWidgets && page is io.github.yuroyami.kitepdf.PdfPage -> page.renderTo(
                    canvas, deviceCtm, formState = null, cancellation = cancellation ?: NEVER_CANCELLED,
                ) { it.subtype != io.github.yuroyami.kitepdf.PdfAnnotation.Subtype.Widget }
                cancellation != null -> page.renderTo(canvas, deviceCtm, cancellation)
                else -> page.renderTo(canvas, deviceCtm)
            }
            usedSystemFont = base.usedSystemFontText
        }
        return bitmap to usedSystemFont
    }
}

/** [KitePageRasterizer] wired to the composition's density, layout direction and font resolver. */
@Composable
public fun rememberKitePageRasterizer(): KitePageRasterizer =
    rememberKitePageRasterizer(KITE_DEFAULT_MAX_RASTER_PIXELS)

/** [rememberKitePageRasterizer] with an explicit per-bitmap allocation ceiling. */
@Composable
public fun rememberKitePageRasterizer(maxBitmapPixels: Long): KitePageRasterizer {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val textMeasurer = rememberTextMeasurer()
    return remember(density, layoutDirection, textMeasurer, maxBitmapPixels) {
        KitePageRasterizer(density, layoutDirection, textMeasurer, maxBitmapPixels)
    }
}

/**
 * Page aspect ratio (w/h), guarded against degenerate boxes. Uses the display
 * box so landscape /Rotate 90/270 PDF pages report the on-screen aspect the
 * rasterized bitmap actually has, not the unrotated MediaBox aspect.
 */
internal fun kitePageAspect(page: KitePage): Float {
    // A page whose size cannot be read is drawn square rather than ending the app in composition (#330).
    val aspect = try {
        (page.displayWidth / page.displayHeight).toFloat()
    } catch (failure: Exception) {
        io.github.yuroyami.kitepdf.core.kiteWarn { "layout: a page size cannot be read: ${failure.message}" }
        1f
    }
    return if (aspect.isFinite() && aspect > 0f) aspect else 1f
}

/**
 * Largest size with aspect ratio [aspect] (w/h) that fits inside [boxW]×[boxH],
 * optionally capped so the longest side never exceeds [maxLongSide].
 * Returns [IntSize.Zero] for degenerate inputs.
 */
internal fun fitWithin(boxW: Int, boxH: Int, aspect: Float, maxLongSide: Int = Int.MAX_VALUE): IntSize {
    if (boxW <= 0 || boxH <= 0 || aspect <= 0f || !aspect.isFinite()) return IntSize.Zero
    var w: Int
    var h: Int
    if (boxW.toFloat() / boxH >= aspect) {
        h = boxH; w = (boxH * aspect).toInt()
    } else {
        w = boxW; h = (boxW / aspect).toInt()
    }
    val longest = maxOf(w, h)
    if (longest > maxLongSide) {
        val k = maxLongSide.toFloat() / longest
        w = (w * k).toInt(); h = (h * k).toInt()
    }
    return IntSize(w.coerceAtLeast(1), h.coerceAtLeast(1))
}

/** The colour a page is drawn on: the theme's paper when a theme is set, else [background]. */
internal fun paperColor(background: Color, theme: ReaderTheme?): Color =
    theme?.background?.let { Color(it.r.toFloat(), it.g.toFloat(), it.b.toFloat()) } ?: background
