package com.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

actual object ImageDecoder {
    actual fun decode(bytes: ByteArray): ImageBitmap? = try {
        // Compose Multiplatform on iOS ships Skiko; Skia loads JPEG natively.
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (t: Throwable) {
        null
    }
}
