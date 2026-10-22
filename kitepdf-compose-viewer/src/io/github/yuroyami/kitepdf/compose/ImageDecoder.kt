package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Bitmaps for the Compose canvas. [decodeRaw] wraps pixels that KitePDF's core decoded with
 * ImageKodec. [decode] reads an encoded file with the decoder the host platform provides:
 * Skia on the JVM, iOS, macOS and the web, and BitmapFactory on Android. KitePDF does not call
 * [decode] for page images: an image that the shared decoders refuse draws as a placeholder on
 * every canvas (#184).
 *
 * The actual implementations live in :kitepdf-compose-viewer's platform source sets.
 * If a platform can't decode the bytes (corrupt file, unsupported format), [decode] returns null.
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

