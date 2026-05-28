package com.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

actual object ImageDecoder {
    actual fun decode(bytes: ByteArray): ImageBitmap? = try {
        // Compose Multiplatform on iOS ships Skiko; Skia loads JPEG natively.
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (t: Throwable) {
        null
    }

    actual fun decodeRaw(rgba: ByteArray, width: Int, height: Int): ImageBitmap? = try {
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
        Image.makeRaster(info, rgba, width * 4).toComposeImageBitmap()
    } catch (t: Throwable) {
        null
    }
}
