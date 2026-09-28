package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

internal actual fun decodeSampled(bytes: ByteArray, sample: Int): Pair<ImageBitmap, Int>? {
    if (sample > 1) skiaSampled(bytes, sample)?.let { return it to sample }
    return ImageDecoder.decode(bytes)?.let { it to 1 }
}

/**
 * Skia's JPEG codec decodes straight to 1/2, 1/4 or 1/8 of the size when the bitmap has that
 * size, as libjpeg's scaled decode does. Other codecs refuse a size that is not their own, and
 * this returns null.
 */
private fun skiaSampled(bytes: ByteArray, sample: Int): ImageBitmap? {
    val data = Data.makeFromBytes(bytes)
    val codec = try {
        Codec.makeFromData(data)
    } catch (t: Throwable) {
        data.close()
        return null
    }
    val bitmap = Bitmap()
    return try {
        val info = codec.imageInfo
        check(bitmap.allocPixels(info.withWidthHeight((info.width + sample - 1) / sample, (info.height + sample - 1) / sample)))
        codec.readPixels(bitmap) // throws when the codec cannot decode to this size
        bitmap.setImmutable().asComposeImageBitmap()
    } catch (t: Throwable) {
        bitmap.close()
        null
    } finally {
        codec.close()
        data.close()
    }
}
