package io.github.yuroyami.kitepdf.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream

public actual object ImageDecoder {
    public actual fun decode(bytes: ByteArray): ImageBitmap? = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (t: Throwable) {
        null
    }

    public actual fun decodeRaw(rgba: ByteArray, width: Int, height: Int): ImageBitmap? = try {
        // A band of rows at a time, so the bitmap is the only other copy of the image's size (#381).
        // setPixels takes straight ARGB and premultiplies it, as createBitmap from colours does.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rows = (BAND_PIXELS / width).coerceIn(1, height)
        val band = IntArray(rows * width)
        var y = 0
        while (y < height) {
            val n = minOf(rows, height - y)
            var j = y * width * 4
            for (i in 0 until n * width) {
                val r = rgba[j].toInt() and 0xFF
                val g = rgba[j + 1].toInt() and 0xFF
                val b = rgba[j + 2].toInt() and 0xFF
                val a = rgba[j + 3].toInt() and 0xFF
                band[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                j += 4
            }
            bitmap.setPixels(band, 0, width, 0, y, width, n)
            y += n
        }
        bitmap.asImageBitmap()
    } catch (t: Throwable) {
        null
    }
}

/** Pixels that [ImageDecoder.decodeRaw] converts at a time: a 256 KB band. */
private const val BAND_PIXELS = 1 shl 16

internal actual fun decodeSampled(bytes: ByteArray, sample: Int): Pair<ImageBitmap, Int>? = try {
    // BitmapFactory shrinks a JPEG inside libjpeg-turbo by inSampleSize. The bounds say whether it did.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    if (sample > 1) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { bitmap ->
        bitmap.asImageBitmap() to if (sample > 1 && bitmap.width < bounds.outWidth) sample else 1
    }
} catch (t: Throwable) {
    null
}

public actual fun ImageBitmap.encodeToPng(): ByteArray? = try {
    val out = ByteArrayOutputStream()
    if (asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
} catch (t: Throwable) {
    null
}
