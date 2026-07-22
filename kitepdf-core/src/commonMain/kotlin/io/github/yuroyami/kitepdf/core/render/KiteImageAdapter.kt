package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kiteimage.KiteBitmap

/**
 * Bridge from KiteImage's ARGB [KiteBitmap] to KitePDF's [ImageXObject] RAW
 * shape (interleaved DeviceRGB samples + an optional soft-mask alpha plane).
 * Since the codec consolidation, KitePDF decodes PNG/GIF/JPEG/JP2 through
 * KiteImage and only keeps the PDF-semantics layer (color spaces, /Decode,
 * SMask compositing) on its own side.
 */

/** Interleaved RGB bytes, alpha dropped (callers wire alpha separately). */
internal fun KiteBitmap.toRgbBytes(): ByteArray {
    val n = width * height
    val out = ByteArray(n * 3)
    var o = 0
    for (p in argb) {
        out[o++] = ((p ushr 16) and 0xFF).toByte()
        out[o++] = ((p ushr 8) and 0xFF).toByte()
        out[o++] = (p and 0xFF).toByte()
    }
    return out
}

/** Full conversion: RGB pixel bytes + alpha plane when any pixel is not opaque. */
internal fun KiteBitmap.toImageXObject(): ImageXObject {
    val n = width * height
    var alpha: ByteArray? = null
    for (i in 0 until n) {
        if ((argb[i] ushr 24) != 0xFF) {
            alpha = ByteArray(n) { (argb[it] ushr 24).toByte() }
            break
        }
    }
    return ImageXObject(
        width = width, height = height, bitsPerComponent = 8,
        colorSpace = "DeviceRGB", kind = ImageXObject.Kind.RAW,
        encodedBytes = ByteArray(0), pixelBytes = toRgbBytes(),
        softMaskAlpha = alpha,
        softMaskWidth = if (alpha != null) width else 0,
        softMaskHeight = if (alpha != null) height else 0,
        resolvedColorSpace = ColorSpace.DeviceRGB,
    )
}
