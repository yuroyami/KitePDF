package com.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap

actual object ImageDecoder {
    /**
     * JS image decoding is async (createImageBitmap returns a Promise). The
     * synchronous [ImageDecoder.decode] contract can't bridge that without a
     * pre-fetched cache, which v0.0.4 doesn't ship. Returns null; the
     * ComposeCanvas falls back to the placeholder rectangle.
     */
    actual fun decode(bytes: ByteArray): ImageBitmap? = null
}
