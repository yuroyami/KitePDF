package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.render.Matrix as PdfMatrix

/**
 * Imperative page → [ImageBitmap] pipeline. This is the raster engine behind
 * [PdfView]; it is public so apps with custom viewers (own pagers, thumbnail
 * grids, PNG export jobs) don't have to re-implement the CTM/flip/hairline
 * math themselves.
 *
 * Obtain one inside composition with [rememberPdfRasterizer], or construct it
 * directly off-composition when you already hold a [TextMeasurer].
 *
 * Rasterization runs synchronously on the calling thread. Text measurement is
 * not thread-safe on every platform, so call it from the main thread; [PdfView]
 * does so post-frame to keep the cost out of the composition pass.
 */
@Stable
class PdfRasterizer(
    private val density: Density,
    private val layoutDirection: LayoutDirection,
    private val textMeasurer: TextMeasurer,
) {

    /**
     * Renders [page] into a fresh [widthPx]×[heightPx] bitmap.
     *
     * @param background colour painted before page content (PDFs assume paper).
     * @param hairlineWidthPx minimum stroke width in raster pixels — see
     *   [ComposeCanvas]. Pass the raster:on-screen ratio (>1) when rendering
     *   supersampled so sub-pixel strokes survive the downscale.
     */
    fun rasterize(
        page: PdfPage,
        widthPx: Int,
        heightPx: Int,
        background: Color = Color.White,
        hairlineWidthPx: Float = 1f,
    ): ImageBitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        // Fit scale from the *rotated* display box: pageToDeviceBase() already maps
        // unscaled user space into a top-left, Y-down device box of
        // [0,rotatedWidth] x [0,rotatedHeight] (accounting for the display-box origin
        // and normalized /Rotate). Scaling it by `s` in device space gives the final
        // CTM; no manual Y-flip here, the base transform already flipped.
        val s = w / page.rotatedWidth
        val bitmap = ImageBitmap(w, h)
        CanvasDrawScope().draw(density, layoutDirection, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
            drawRect(background, size = size)
            // concat(b) applies b FIRST, so pageToDeviceBase() runs before the scale.
            val deviceCtm = PdfMatrix.scaling(s, s).concat(page.pageToDeviceBase())
            page.renderTo(ComposeCanvas(this, textMeasurer, hairlineWidthPx), deviceCtm)
        }
        return bitmap
    }
}

/** [PdfRasterizer] wired to the composition's density, layout direction and font resolver. */
@Composable
fun rememberPdfRasterizer(): PdfRasterizer {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val textMeasurer = rememberTextMeasurer()
    return remember(density, layoutDirection, textMeasurer) {
        PdfRasterizer(density, layoutDirection, textMeasurer)
    }
}

/**
 * Page aspect ratio (w/h), guarded against degenerate boxes. Uses the *rotated*
 * display box so landscape /Rotate 90/270 pages report the on-screen aspect the
 * rasterized bitmap actually has, not the unrotated MediaBox aspect.
 */
internal fun pdfPageAspect(page: PdfPage): Float =
    (page.rotatedWidth / page.rotatedHeight).toFloat().let { if (it.isFinite() && it > 0f) it else 1f }

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
