package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.rasterGeometry
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
 * The result is a sRGB raster sized by [PdfPage.rasterGeometry] (rotation,
 * crop box and `/UserUnit` all included), at [scale] device pixels per PDF
 * user-space point. Pass `scale = 2.0` for retina / "2× density" thumbnails.
 */
public object PdfPageRasterizer {

    /**
     * Render [page] into a freshly-allocated Skia [Image]. The caller owns
     * the returned object and should call `close()` (or use [encodeToPng])
     * once done. Skia images hold off-heap memory.
     */
    public fun renderToImage(
        page: PdfPage,
        scale: Double = 1.0,
        background: Int = Color.WHITE,
    ): Image {
        val geometry = page.rasterGeometry(scale)
        val surface = Surface.makeRasterN32Premul(geometry.widthPx, geometry.heightPx)
        try {
            val skCanvas = surface.canvas
            if (background != 0) skCanvas.clear(background)

            val pdfCanvas = SkiaCanvas(skCanvas)
            page.renderTo(pdfCanvas, geometry.deviceCtm)
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
