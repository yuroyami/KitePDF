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
import io.github.yuroyami.kitepdf.KitePage
import io.github.yuroyami.kitepdf.render.Matrix as PdfMatrix
import io.github.yuroyami.kitepdf.render.ReaderTheme
import kotlinx.coroutines.sync.withLock

/**
 * Imperative page → [ImageBitmap] pipeline. This is the raster engine behind
 * [PdfView]; it is public so apps with custom viewers (own pagers, thumbnail
 * grids, PNG export jobs) don't have to re-implement the CTM/flip/hairline
 * math themselves.
 *
 * Obtain one inside composition with [rememberPdfRasterizer], or construct it
 * directly off-composition when you already hold a [TextMeasurer].
 *
 * [rasterize] runs synchronously on the calling thread; [rasterizeOffMain]
 * moves the work to [kitepdfRasterDispatcher] (a background pool on
 * JVM/Android/Apple, Main on JS/Wasm) so a complex page never janks scrolling
 * or pinch — that is what [PdfView] uses (T-14).
 */
@Stable
public class PdfRasterizer(
    private val density: Density,
    private val layoutDirection: LayoutDirection,
    private val textMeasurer: TextMeasurer,
) {

    /**
     * [TextMeasurer]'s internal cache is not documented thread-safe, and the
     * system-font fallback path is its only consumer here. Whether a page
     * will hit that path isn't knowable cheaply up front, so background
     * rasters serialize on this mutex: parallelism between pages is lost,
     * but the MAIN thread stays free, which is the point.
     */
    private val renderMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * [rasterize], off the main thread where the platform allows. One page
     * runs to completion once started (the synchronous renderer has no
     * cancellation points; T-02's operation budget bounds the worst case) —
     * cancellation takes effect between pages.
     */
    public suspend fun rasterizeOffMain(
        page: KitePage,
        widthPx: Int,
        heightPx: Int,
        background: Color = Color.White,
        hairlineWidthPx: Float = 1f,
        theme: ReaderTheme? = null,
    ): ImageBitmap = kotlinx.coroutines.withContext(kitepdfRasterDispatcher()) {
        renderMutex.withLock {
            rasterize(page, widthPx, heightPx, background, hairlineWidthPx, theme)
        }
    }

    /**
     * [rasterizeOffMain] through [cache] (T-15): a hit returns the cached
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
    ): Pair<ImageBitmap, Boolean> = kotlinx.coroutines.withContext(kitepdfRasterDispatcher()) {
        renderMutex.withLock {
            if (cache == null) {
                rasterize(page, widthPx, heightPx, background, hairlineWidthPx, theme) to true
            } else {
                var fresh = false
                val key = PageBitmapCache.Key(
                    pageIdentity = page,
                    w = widthPx,
                    h = heightPx,
                    bgArgb = background.toArgb(),
                    themeId = theme?.hashCode() ?: 0,
                    hairlineBits = hairlineWidthPx.toRawBits(),
                )
                val bmp = cache.getOrPut(key) {
                    fresh = true
                    rasterize(page, widthPx, heightPx, background, hairlineWidthPx, theme)
                }
                bmp to fresh
            }
        }
    }

    /**
     * Renders [page] into a fresh [widthPx]×[heightPx] bitmap.
     *
     * @param background colour painted before page content (PDFs assume paper).
     * @param hairlineWidthPx minimum stroke width in raster pixels — see
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
    ): ImageBitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        // Fit scale from the display box: displayToDeviceBase() already maps
        // unscaled page space into a top-left, Y-down device box of
        // [0,displayWidth] x [0,displayHeight] (PDF folds in the display-box origin
        // and normalized /Rotate; EPUB folds in its top-left flip). Scaling it by
        // `s` in device space gives the final CTM; no manual Y-flip here.
        val s = w / page.displayWidth
        // The theme owns the paper colour when set; else use `background`.
        val bg = theme?.background?.let { Color(it.r.toFloat(), it.g.toFloat(), it.b.toFloat()) } ?: background
        val bitmap = ImageBitmap(w, h)
        CanvasDrawScope().draw(density, layoutDirection, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
            drawRect(bg, size = size)
            // concat(b) applies b FIRST, so displayToDeviceBase() runs before the scale.
            val deviceCtm = PdfMatrix.scaling(s, s).concat(page.displayToDeviceBase())
            val base = ComposeCanvas(this, textMeasurer, hairlineWidthPx)
            page.renderTo(theme?.wrap(base) ?: base, deviceCtm)
        }
        return bitmap
    }
}

/** [PdfRasterizer] wired to the composition's density, layout direction and font resolver. */
@Composable
public fun rememberPdfRasterizer(): PdfRasterizer {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val textMeasurer = rememberTextMeasurer()
    return remember(density, layoutDirection, textMeasurer) {
        PdfRasterizer(density, layoutDirection, textMeasurer)
    }
}

/**
 * Page aspect ratio (w/h), guarded against degenerate boxes. Uses the display
 * box so landscape /Rotate 90/270 PDF pages report the on-screen aspect the
 * rasterized bitmap actually has, not the unrotated MediaBox aspect.
 */
internal fun pdfPageAspect(page: KitePage): Float =
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
