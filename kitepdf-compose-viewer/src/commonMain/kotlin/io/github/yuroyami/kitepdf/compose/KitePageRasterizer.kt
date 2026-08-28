package io.github.yuroyami.kitepdf.compose

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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.kiteWarn
import io.github.yuroyami.kitepdf.core.render.KITE_DEFAULT_MAX_RASTER_PIXELS
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.ReaderTheme
import kotlinx.coroutines.sync.withLock

/**
 * Imperative page → [ImageBitmap] pipeline. This is the raster engine behind
 * [KiteDocView]; it is public so apps with custom viewers (own pagers, thumbnail
 * grids, PNG export jobs) don't have to re-implement the CTM/flip/hairline
 * math themselves.
 *
 * Obtain one inside composition with [rememberKitePageRasterizer], or construct it
 * directly off-composition when you already hold a [TextMeasurer].
 *
 * [rasterize] runs synchronously on the calling thread; [rasterizeOffMain]
 * moves the work to [kitepdfRasterDispatcher] (a background pool on
 * JVM/Android/Apple, Main on JS/Wasm) so a complex page never janks scrolling
 * or pinch. [KiteDocView] uses that path.
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

    private companion object {
        /**
         * One mutex for the whole process, not one per rasterizer. Compose's
         * skiko text stack keeps a PROCESS-GLOBAL style cache (a plain HashMap
         * behind `ParagraphBuilder.makeSkTextStyle`), so two pages measuring
         * text on different pool threads corrupt it even when each owns a
         * private [TextMeasurer]. A per-instance mutex looked safe and was not:
         * every page slot remembers its own rasterizer, so slots serialized
         * against themselves and raced each other, which is exactly the
         * ConcurrentModificationException abort seen in production (iOS,
         * 2026-08-05). Parallelism between pages is lost, but the MAIN thread
         * stays free, which is the point of the off-main path.
         */
        private val renderMutex = kotlinx.coroutines.sync.Mutex()

        /**
         * True when this platform provides a usable [kotlinx.coroutines.Dispatchers.Main].
         * A headless JVM without a Swing/JavaFX main loop has none; there the
         * system-font re-render runs on the calling context instead.
         */
        private val mainDispatcherAvailable: Boolean by lazy {
            runCatching {
                kotlinx.coroutines.Dispatchers.Main.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext)
            }.isSuccess
        }
    }

    /**
     * [rasterize], off the main thread where the platform allows. One page
     * runs to completion once started (the synchronous renderer has no
     * cancellation points; the operation budget bounds the worst case), so
     * cancellation takes effect between pages.
     *
     * Pages that fall back to system-font text (EPUB body text, PDFs without
     * embedded outlines) are re-rendered on [Dispatchers.Main]: skiko's text
     * stack shares process-global state with the host UI thread, and no lock
     * of ours can exclude that thread, so the only safe place to measure or
     * draw through it is the main thread itself. Pages whose glyphs all have
     * embedded outlines (the common PDF case) stay entirely on the pool.
     */
    public suspend fun rasterizeOffMain(
        page: KitePage,
        widthPx: Int,
        heightPx: Int,
        background: Color = Color.White,
        hairlineWidthPx: Float = 1f,
        theme: ReaderTheme? = null,
    ): ImageBitmap = renderMutex.withLock {
        rasterizeOffMainLocked(page, widthPx, heightPx, background, hairlineWidthPx, theme)
    }

    /**
     * The off-main render body. Must be called with [renderMutex] held.
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
    ): ImageBitmap {
        val (probe, usedSystemFont) = kotlinx.coroutines.withContext(kitepdfRasterDispatcher()) {
            rasterizeInternal(page, widthPx, heightPx, background, hairlineWidthPx, theme, skipSystemFontText = true)
        }
        if (!usedSystemFont) return probe
        return onMainOrCaller {
            rasterizeInternal(page, widthPx, heightPx, background, hairlineWidthPx, theme, skipSystemFontText = false).first
        }
    }

    /**
     * Runs [block] on [Dispatchers.Main] when a Main dispatcher exists on this
     * platform, else on the calling context (headless JVM without a Swing/JavaFX
     * main loop, where the pre-fix behaviour is also the only option).
     */
    private suspend fun <T> onMainOrCaller(block: () -> T): T =
        if (mainDispatcherAvailable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
        } else {
            block()
        }

    /**
     * [rasterizeOffMain] through [cache]: a hit returns the cached
     * bitmap, a miss rasterizes and inserts. The cache is touched only under
     * [renderMutex], honouring its single-owner contract. Second value of the
     * pair: true when this call actually rasterized (drives `onPageRendered`,
     * which must not re-fire on cache hits).
     */
    internal suspend fun rasterizeCachedOffMain(
        cache: PageBitmapCache?,
        page: KitePage,
        widthPx: Int,
        heightPx: Int,
        background: Color,
        hairlineWidthPx: Float,
        theme: ReaderTheme?,
    ): Pair<ImageBitmap, Boolean> = renderMutex.withLock {
        if (cache == null) {
            rasterizeOffMainLocked(page, widthPx, heightPx, background, hairlineWidthPx, theme) to true
        } else {
            val key = PageBitmapCache.Key(
                pageIdentity = page,
                w = widthPx,
                h = heightPx,
                bgArgb = background.toArgb(),
                themeId = theme?.hashCode() ?: 0,
                hairlineBits = hairlineWidthPx.toRawBits(),
            )
            val hit = cache.get(key)
            if (hit != null) {
                hit to false
            } else {
                val bmp = rasterizeOffMainLocked(page, widthPx, heightPx, background, hairlineWidthPx, theme)
                cache.put(key, bmp)
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
    ): Pair<ImageBitmap, Boolean>? {
        for (attempt in 0 until 2) {
            try {
                return rasterizeCachedOffMain(cache, page, widthPx, heightPx, background, hairlineWidthPx, theme)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
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
     * @param background colour painted before page content (documents assume paper).
     * @param hairlineWidthPx minimum stroke width in raster pixels. See
     *   [ComposeCanvas]. Pass the raster:on-screen ratio (>1) when rendering
     *   supersampled so sub-pixel strokes survive the downscale.
     */
    public fun rasterize(
        page: KitePage,
        widthPx: Int,
        heightPx: Int,
        background: Color = Color.White,
        hairlineWidthPx: Float = 1f,
        theme: ReaderTheme? = null,
    ): ImageBitmap =
        rasterizeInternal(page, widthPx, heightPx, background, hairlineWidthPx, theme, skipSystemFontText = false).first

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
    ): Pair<ImageBitmap, Boolean> {
        require(widthPx > 0 && heightPx > 0) { "bitmap dimensions must be > 0" }
        require(widthPx.toLong() * heightPx.toLong() <= maxBitmapPixels) {
            "page bitmap is ${widthPx}x$heightPx pixels; limit is $maxBitmapPixels pixels"
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
        // The theme owns the paper colour when set; else use `background`.
        val bg = theme?.background?.let { Color(it.r.toFloat(), it.g.toFloat(), it.b.toFloat()) } ?: background
        val bitmap = ImageBitmap(w, h)
        var usedSystemFont = false
        CanvasDrawScope().draw(density, layoutDirection, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
            drawRect(bg, size = size)
            // concat(b) applies b FIRST, so displayToDeviceBase() runs before the scale.
            val deviceCtm = KiteMatrix.scaling(s, s).concat(page.displayToDeviceBase())
            val base = ComposeCanvas(this, textMeasurer, hairlineWidthPx, skipSystemFontText)
            page.renderTo(theme?.wrap(base) ?: base, deviceCtm)
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
internal fun kitePageAspect(page: KitePage): Float =
    (page.displayWidth / page.displayHeight).toFloat().let { if (it.isFinite() && it > 0f) it else 1f }

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
