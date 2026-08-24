package io.github.yuroyami.kitepdf.nativerenderer

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.rasterGeometry

/**
 * Headless rendering on Android. Produces an ARGB_8888 [Bitmap] sized by
 * [PdfPage.rasterGeometry] (rotation, crop box and `/UserUnit` all included),
 * at [scale] device pixels per point. No Compose dependency.
 *
 * Typical uses:
 *
 *  - Custom View `onDraw(Canvas)` overrides that paint the bitmap onto the
 *    screen.
 *  - Generating cached page thumbnails on disk.
 *  - Pre-rendering pages off the main thread (call from a coroutine on
 *    Dispatchers.Default).
 */
public object AndroidPdfBitmapRenderer {

    public fun renderToBitmap(
        page: PdfPage,
        scale: Double = 1.0,
        background: Int = Color.WHITE,
    ): Bitmap {
        val geometry = page.rasterGeometry(scale)
        val bm = Bitmap.createBitmap(geometry.widthPx, geometry.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bm)
        canvas.drawColor(background)
        val pdfCanvas = AndroidNativeCanvas(canvas)
        page.renderTo(pdfCanvas, geometry.deviceCtm)
        return bm
    }
}
