package com.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Platform-supplied image decoder. KitePDF's core stays pure-Kotlin-stdlib;
 * actual JPEG / PNG / etc. decoding rides on whatever the host platform
 * already provides — Skia on Desktop, the OS framework on Android / iOS,
 * createImageBitmap on JS.
 *
 * The actual implementations live in :kitepdf-compose's platform source sets.
 * If a platform can't decode the bytes (corrupt JPEG, unsupported format),
 * the actual returns null and the renderer paints a placeholder rectangle.
 */
expect object ImageDecoder {
    fun decode(bytes: ByteArray): ImageBitmap?

    /**
     * Build a bitmap from already-decoded RGBA8888 pixels (R,G,B,A per pixel,
     * row-major, [width]*[height]*4 bytes). Unlike [decode] this is fully
     * synchronous on every platform — the samples are already in hand — so it
     * works on JS too. Used for RAW (FlateDecode) PDF images.
     */
    fun decodeRaw(rgba: ByteArray, width: Int, height: Int): ImageBitmap?
}
