package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kiteimagecodec.KiteBitmap
import io.github.yuroyami.kiteimagecodec.KiteImageCodec

/** These pixels as an image that a canvas draws, smoothed when it is scaled. */
public fun KiteRaster.toImageData(): KiteImageData = KiteBitmap(width, height, pixels.copyOf()).toKiteImageData()

/** These pixels as a PNG file, with their alpha. */
public fun KiteRaster.encodePng(): ByteArray = KiteImageCodec.encodePng(KiteBitmap(width, height, pixels))

/**
 * These pixels as a JPEG file of [quality] from 1 to 100. JPEG has no alpha, so each pixel is
 * first drawn over black, as a browser does for `toDataURL('image/jpeg')`.
 */
public fun KiteRaster.encodeJpeg(quality: Int): ByteArray {
    val opaque = IntArray(pixels.size) { i ->
        val p = pixels[i]
        val a = p ushr 24
        if (a == 255) p else {
            val r = ((p ushr 16 and 255) * a + 127) / 255
            val g = ((p ushr 8 and 255) * a + 127) / 255
            val b = ((p and 255) * a + 127) / 255
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return KiteImageCodec.encodeJpeg(KiteBitmap(width, height, opaque), quality.coerceIn(1, 100))
}
