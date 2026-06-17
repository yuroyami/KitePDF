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
        val scale = w / page.width
        val bitmap = ImageBitmap(w, h)
        CanvasDrawScope().draw(density, layoutDirection, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
            drawRect(background, size = size)
            // PDF user space is Y-up from bottom-left; flip to device Y-down + scale.
            val deviceCtm = PdfMatrix(
                a = scale, b = 0.0,
                c = 0.0, d = -scale,
                e = 0.0, f = h.toDouble(),
            )
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

/** Page aspect ratio (w/h), guarded against degenerate boxes. */
internal fun pdfPageAspect(page: PdfPage): Float =
    (page.width / page.height).toFloat().let { if (it.isFinite() && it > 0f) it else 1f }

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
