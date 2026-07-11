package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.render.Matrix as PdfMatrix
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface

/**
 * Headless raster output for a [PdfPage] using Skia (no Compose).
 *
 * Typical use cases:
 *
 *  - Server-side thumbnail / preview generation
 *  - "PDF → PNG" CLI converters
 *  - CI screenshot baselines for visual regression tests
 *  - Embedding rendered PDF previews into non-Compose JVM UIs (AWT, Swing,
 *    JavaFX) by drawing the returned [Image] / [ByteArray] through their
 *    own image APIs.
 *
 * The result is a sRGB raster sized to the page in PDF user-space units
 * (`1pt = 1/72in`) multiplied by [scale]. Pass `scale = 2.0` for retina /
 * "2× density" thumbnails.
 */
public object PdfPageRasterizer {

    /**
     * Render [page] into a freshly-allocated Skia [Image]. The caller owns
     * the returned object and should call `close()` (or use [encodeToPng])
     * once done — Skia images hold off-heap memory.
     */
    public fun renderToImage(
        page: PdfPage,
        scale: Double = 1.0,
        background: Int = Color.WHITE,
    ): Image {
        // Size to the ROTATED display box (width/height swapped for /Rotate 90
        // or 270), so rotated pages get a correctly-shaped bitmap.
        val widthPx = kotlin.math.ceil(page.rotatedWidth * scale).toInt().coerceAtLeast(1)
        val heightPx = kotlin.math.ceil(page.rotatedHeight * scale).toInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(widthPx, heightPx)
        try {
            val skCanvas = surface.canvas
            if (background != 0) skCanvas.clear(background)

            // pageToDeviceBase() already maps unscaled user-space to a
            // top-left-origin, Y-down device box [0,rotatedWidth]×[0,rotatedHeight],
            // honouring the display-box origin and normalized /Rotate. Scale it
            // up by `scale` in device space: scaling FIRST-applies the base
            // (a.concat(b) applies b then a).
            val deviceCtm = PdfMatrix.scaling(scale, scale).concat(page.pageToDeviceBase())
            val pdfCanvas = SkiaCanvas(skCanvas)
            page.renderTo(pdfCanvas, deviceCtm)
            return surface.makeImageSnapshot()
        } finally {
            surface.close()
        }
    }

    /** Convenience: render and return PNG bytes. */
    public fun encodeToPng(page: PdfPage, scale: Double = 1.0, background: Int = Color.WHITE): ByteArray {
        val image = renderToImage(page, scale, background)
        try {
            val data = image.encodeToData(EncodedImageFormat.PNG)
                ?: error("Skia: failed to encode page to PNG")
            try {
                return data.bytes
            } finally {
                data.close()
            }
        } finally {
            image.close()
        }
    }
}
