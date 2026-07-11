package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.core.render.Matrix as PdfMatrix
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Headless raster output using AWT + ImageIO — no Skia, no Compose, no
 * native binaries beyond what the JDK already ships. Perfect for:
 *
 *  - CI / server-side PDF → PNG / JPEG conversion
 *  - Embedded thumbnails inside JavaFX / Swing apps
 *  - "I just need a BufferedImage" use cases
 */
public object AwtPdfRasterizer {

    public fun renderToImage(
        page: PdfPage,
        scale: Double = 1.0,
        background: Color = Color.WHITE,
    ): BufferedImage {
        val w = (page.width * scale).toInt().coerceAtLeast(1)
        val h = (page.height * scale).toInt().coerceAtLeast(1)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.color = background
            g.fillRect(0, 0, w, h)
            // Top-level clip = the surface: offscreen passes (soft masks) size
            // their buffers from g.clip, and image graphics report no device bounds.
            g.clip = java.awt.Rectangle(0, 0, w, h)
            val canvas = AwtCanvas(g)
            val deviceCtm = PdfMatrix(scale, 0.0, 0.0, -scale, 0.0, page.height * scale)
            page.renderTo(canvas, deviceCtm)
        } finally {
            g.dispose()
        }
        return img
    }

    /** Returns PNG bytes ready to write to disk / a network response. */
    public fun encodeToPng(page: PdfPage, scale: Double = 1.0, background: Color = Color.WHITE): ByteArray {
        val img = renderToImage(page, scale, background)
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)
        return baos.toByteArray()
    }

    /** Returns JPEG bytes. Quality is JDK default; tweak with custom ImageWriter when needed. */
    public fun encodeToJpeg(page: PdfPage, scale: Double = 1.0, background: Color = Color.WHITE): ByteArray {
        // JPEG doesn't support alpha; force opaque RGB.
        val src = renderToImage(page, scale, background)
        val rgb = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        try {
            g.drawImage(src, 0, 0, null)
        } finally {
            g.dispose()
        }
        val baos = ByteArrayOutputStream()
        ImageIO.write(rgb, "jpg", baos)
        return baos.toByteArray()
    }
}
