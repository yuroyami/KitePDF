package com.yuroyami.kitepdf.nativerenderer

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import com.yuroyami.kitepdf.PdfPage
import com.yuroyami.kitepdf.render.Matrix as PdfMatrix

/**
 * Headless rendering on Android — produces an ARGB_8888 [Bitmap] sized to
 * the page in pt units multiplied by [scale]. No Compose dependency.
 *
 * Typical uses:
 *
 *  - Custom View `onDraw(Canvas)` overrides that paint the bitmap onto the
 *    screen.
 *  - Generating cached page thumbnails on disk.
 *  - Pre-rendering pages off the main thread (call from a coroutine on
 *    Dispatchers.Default).
 */
object AndroidPdfBitmapRenderer {

    fun renderToBitmap(
        page: PdfPage,
        scale: Double = 1.0,
        background: Int = Color.WHITE,
    ): Bitmap {
        val w = (page.width * scale).toInt().coerceAtLeast(1)
        val h = (page.height * scale).toInt().coerceAtLeast(1)
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bm)
        canvas.drawColor(background)
        val pdfCanvas = AndroidNativeCanvas(canvas)
        val deviceCtm = PdfMatrix(scale, 0.0, 0.0, -scale, 0.0, page.height * scale)
        page.renderTo(pdfCanvas, deviceCtm)
        return bm
    }
}
