package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

public actual object ImageDecoder {
    /**
     * Compose/JS uses Skiko (CanvasKit), which bundles Skia's image codecs,
     * so decoding is synchronous here too, no `createImageBitmap` Promise dance.
     * Falls back to null (→ placeholder rectangle) if Skia can't parse the bytes.
     */
    public actual fun decode(bytes: ByteArray): ImageBitmap? = try {
        Image.makeFromEncoded(bytes).let { image ->
            // Closed once its pixels are in the bitmap, not when the collector finalizes it (#393).
            try {
                image.toComposeImageBitmap()
            } finally {
                image.close()
            }
        }
    } catch (t: Throwable) {
        null
    }

    /** Raw pixels are synchronous, so Skiko (which Compose/JS rides on) can build the bitmap directly. */
    public actual fun decodeRaw(rgba: ByteArray, width: Int, height: Int): ImageBitmap? = try {
        // UNPREMUL, not OPAQUE: the core writes straight (non-premultiplied) alpha from the
        // image's /SMask. OPAQUE made Skia ignore that alpha, so transparent logo backgrounds
        // rendered as their opaque base RGB (the grey box).
        // The pixels go into the bitmap once. A raster image drawn into a new bitmap copied them
        // twice, and the image stayed until the collector finalized it (#393).
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val bitmap = Bitmap()
        if (!bitmap.installPixels(info, rgba, width * 4)) {
            bitmap.close()
            null
        } else {
            bitmap.setImmutable().asComposeImageBitmap()
        }
    } catch (t: Throwable) {
        null
    }
}

public actual fun ImageBitmap.encodeToPng(): ByteArray? = try {
    Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
} catch (t: Throwable) {
    null
}
