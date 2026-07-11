package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

public actual object ImageDecoder {
    /**
     * Compose/JS rides on Skiko (CanvasKit), which bundles Skia's image codecs —
     * so decoding is synchronous here too, no `createImageBitmap` Promise dance.
     * Falls back to null (→ placeholder rectangle) if Skia can't parse the bytes.
     */
    public actual fun decode(bytes: ByteArray): ImageBitmap? = try {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (t: Throwable) {
        null
    }

    /** Raw pixels are synchronous, so Skiko (which Compose/JS rides on) can build the bitmap directly. */
    public actual fun decodeRaw(rgba: ByteArray, width: Int, height: Int): ImageBitmap? = try {
        // UNPREMUL, not OPAQUE: the core writes straight (non-premultiplied) alpha from the
        // image's /SMask. OPAQUE made Skia ignore that alpha, so transparent logo backgrounds
        // rendered as their opaque base RGB (the grey box).
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        Image.makeRaster(info, rgba, width * 4).toComposeImageBitmap()
    } catch (t: Throwable) {
        null
    }
}

public actual fun ImageBitmap.encodeToPng(): ByteArray? = try {
    Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
} catch (t: Throwable) {
    null
}
