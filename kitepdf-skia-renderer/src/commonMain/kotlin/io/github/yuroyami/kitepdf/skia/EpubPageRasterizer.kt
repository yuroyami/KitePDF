package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.epub.EpubPage
import io.github.yuroyami.kitepdf.render.Matrix as PdfMatrix
import io.github.yuroyami.kitepdf.render.ReaderTheme
import io.github.yuroyami.kitepdf.render.RgbColor
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface

/**
 * Headless raster output for an [EpubPage] using Skia (no Compose) — the EPUB
 * twin of [PdfPageRasterizer]. Both draw through the shared core [PdfCanvas]
 * seam ([SkiaCanvas]), so one Skiko backend serves PDF and EPUB alike on every
 * target (JVM, Android, iOS, macOS, Linux, JS/Wasm).
 *
 * Typical use cases:
 *
 *  - Reader-app page bitmaps (the Compose viewer rasterises through this)
 *  - Cover / thumbnail / preview generation
 *  - CI screenshot baselines for the EPUB engine
 *
 * The result is an sRGB raster sized to the page in points multiplied by
 * [scale]. Pass `scale = 2.0` for retina / "2× density" output.
 */
public object EpubPageRasterizer {

    /**
     * Render [page] into a freshly-allocated Skia [Image]. The caller owns the
     * returned object and should call `close()` (or use [encodeToPng]) once
     * done — Skia images hold off-heap memory.
     */
    public fun renderToImage(
        page: EpubPage,
        scale: Double = 1.0,
        background: Int = Color.WHITE,
        theme: ReaderTheme? = null,
    ): Image {
        val widthPx = kotlin.math.ceil(page.width * scale).toInt().coerceAtLeast(1)
        val heightPx = kotlin.math.ceil(page.height * scale).toInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(widthPx, heightPx)
        try {
            val skCanvas = surface.canvas
            // The theme owns the paper colour when set; else use `background`.
            val bg = theme?.let { skiaColor(it.background) } ?: background
            if (bg != 0) skCanvas.clear(bg)

            // EpubPage paints in y-up user space; flip to a top-left-origin,
            // y-down device box and scale in one CTM (same mapping the AWT
            // raster gate uses): (x, yUp) -> (scale*x, scale*(height - yUp)).
            val deviceCtm = PdfMatrix(scale, 0.0, 0.0, -scale, 0.0, page.height * scale)
            val base = SkiaCanvas(skCanvas)
            page.renderTo(theme?.wrap(base) ?: base, deviceCtm)
            return surface.makeImageSnapshot()
        } finally {
            surface.close()
        }
    }

    /** Convenience: render and return PNG bytes. */
    public fun encodeToPng(page: EpubPage, scale: Double = 1.0, background: Int = Color.WHITE, theme: ReaderTheme? = null): ByteArray {
        val image = renderToImage(page, scale, background, theme)
        try {
            val data = image.encodeToData(EncodedImageFormat.PNG)
                ?: error("Skia: failed to encode EPUB page to PNG")
            try {
                return data.bytes
            } finally {
                data.close()
            }
        } finally {
            image.close()
        }
    }

    /** [RgbColor] (0..1 doubles) → opaque ARGB int, the shape a Skia colour is. */
    private fun skiaColor(c: RgbColor): Int {
        fun ch(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
        return (0xFF shl 24) or (ch(c.r) shl 16) or (ch(c.g) shl 8) or ch(c.b)
    }
}
