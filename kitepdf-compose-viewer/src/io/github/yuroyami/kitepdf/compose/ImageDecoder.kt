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
     *
     * Returns null, on every target and before any native call, when [width] or [height] is not
     * positive or [rgba] holds fewer than `width * height * 4` bytes: Skia would read past a short
     * array (#892).
     */
    public fun decodeRaw(rgba: ByteArray, width: Int, height: Int): ImageBitmap?
}

/** True when [rgba] holds the `width * height * 4` bytes of a bitmap with positive sides (#892). */
internal fun rawPixelsFit(rgba: ByteArray, width: Int, height: Int): Boolean =
    width > 0 && height > 0 && rgba.size.toLong() >= width.toLong() * height * 4

