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
}
