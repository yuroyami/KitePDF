package com.yuroyami.kitepdf.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual object ImageDecoder {
    actual fun decode(bytes: ByteArray): ImageBitmap? = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (t: Throwable) {
        null
    }

    actual fun decodeRaw(rgba: ByteArray, width: Int, height: Int): ImageBitmap? = try {
        val argb = IntArray(width * height)
        var j = 0
        for (i in argb.indices) {
            val r = rgba[j++].toInt() and 0xFF
            val g = rgba[j++].toInt() and 0xFF
            val b = rgba[j++].toInt() and 0xFF
            val a = rgba[j++].toInt() and 0xFF
            argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    } catch (t: Throwable) {
        null
    }
}
