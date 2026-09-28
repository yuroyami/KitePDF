package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Platform-supplied image decoder. KitePDF's core stays pure-Kotlin-stdlib;
 * actual JPEG / PNG / etc. decoding rides on whatever the host platform
 * already provides: Skia on the JVM, iOS, macOS and the web, and
 * BitmapFactory on Android.
 *
 * The actual implementations live in :kitepdf-compose-viewer's platform source sets.
 * If a platform can't decode the bytes (corrupt JPEG, unsupported format),
 * the actual returns null and the renderer paints a placeholder rectangle.
 */
public expect object ImageDecoder {
    public fun decode(bytes: ByteArray): ImageBitmap?

    /**
     * Build a bitmap from already-decoded RGBA8888 pixels (R,G,B,A per pixel,
     * row-major, [width]*[height]*4 bytes). Unlike [decode] this is fully
     * synchronous on every platform (the samples are already decoded), so it
     * works on JS too. Used for RAW (FlateDecode) PDF images.
     */
    public fun decodeRaw(rgba: ByteArray, width: Int, height: Int): ImageBitmap?
}

/**
 * [bytes] decoded with each side divided by [sample], rounded up, when the platform decoder can
 * shrink while it decodes, and at full size when it cannot. [sample] is 1, 2, 4 or 8. Returns the
 * bitmap and the division that the decoder applied, [sample] or 1, or null when the bytes do not
 * decode. Android and Skia shrink a JPEG inside the decoder (#381).
 */
internal expect fun decodeSampled(bytes: ByteArray, sample: Int): Pair<ImageBitmap, Int>?
