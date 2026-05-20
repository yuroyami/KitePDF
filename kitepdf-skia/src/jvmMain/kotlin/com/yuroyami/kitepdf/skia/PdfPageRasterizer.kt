package com.yuroyami.kitepdf.skia

import com.yuroyami.kitepdf.PdfPage
import com.yuroyami.kitepdf.render.Matrix as PdfMatrix
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
object PdfPageRasterizer {

    /**
     * Render [page] into a freshly-allocated Skia [Image]. The caller owns
     * the returned object and should call `close()` (or use [encodeToPng])
     * once done — Skia images hold off-heap memory.
     */
    fun renderToImage(
        page: PdfPage,
        scale: Double = 1.0,
        background: Int = Color.WHITE,
    ): Image {
        val widthPx = (page.width * scale).toInt().coerceAtLeast(1)
        val heightPx = (page.height * scale).toInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(widthPx, heightPx)
        try {
            val skCanvas = surface.canvas
            if (background != 0) skCanvas.clear(background)

            // PDF Y axis goes up from the bottom-left; Skia (and every UI
            // toolkit) puts (0,0) at top-left with Y down. Flip + scale.
            val deviceCtm = PdfMatrix(scale, 0.0, 0.0, -scale, 0.0, page.height * scale)
            val pdfCanvas = SkiaCanvas(skCanvas)
            page.renderTo(pdfCanvas, deviceCtm)
            return surface.makeImageSnapshot()
        } finally {
            surface.close()
        }
    }

    /** Convenience: render and return PNG bytes. */
    fun encodeToPng(page: PdfPage, scale: Double = 1.0, background: Int = Color.WHITE): ByteArray {
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
