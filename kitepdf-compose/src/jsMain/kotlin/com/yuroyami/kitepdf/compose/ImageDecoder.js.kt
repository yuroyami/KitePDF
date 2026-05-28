package com.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

actual object ImageDecoder {
    /**
     * JS image decoding is async (createImageBitmap returns a Promise). The
     * synchronous [ImageDecoder.decode] contract can't bridge that without a
     * pre-fetched cache, which v0.0.4 doesn't ship. Returns null; the
     * ComposeCanvas falls back to the placeholder rectangle.
     */
    actual fun decode(bytes: ByteArray): ImageBitmap? = null

    /** Raw pixels are synchronous, so Skiko (which Compose/JS rides on) can build the bitmap directly. */
    actual fun decodeRaw(rgba: ByteArray, width: Int, height: Int): ImageBitmap? = try {
        // UNPREMUL, not OPAQUE: the core writes straight (non-premultiplied) alpha from the
        // image's /SMask. OPAQUE made Skia ignore that alpha, so transparent logo backgrounds
        // rendered as their opaque base RGB (the grey box).
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        Image.makeRaster(info, rgba, width * 4).toComposeImageBitmap()
    } catch (t: Throwable) {
        null
    }
}
