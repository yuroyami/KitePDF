package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageDecoderTest {
    @Test
    fun decodeRaw_preserves_straight_alpha() {
        // 1×2 RGBA: pixel0 opaque red, pixel1 fully transparent. The decoder must keep the
        // alpha (UNPREMUL), not force it opaque, otherwise transparent image regions (e.g. a
        // logo's /SMask background) render as their opaque base RGB (the grey-box bug).
        val rgba = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(), // opaque red
            0, 0, 0, 0,                        // fully transparent
        )
        val bmp = ImageDecoder.decodeRaw(rgba, 1, 2) ?: error("decodeRaw returned null")
        val px = bmp.toPixelMap()
        assertTrue(px[0, 0].alpha > 0.9f, "opaque pixel lost its alpha: ${px[0, 0]}")
        assertTrue(px[0, 1].alpha < 0.1f, "transparent pixel forced opaque: ${px[0, 1]}")
    }

    /**
     * The pixels land in the bitmap as they came, straight RGBA, with no second bitmap drawn
     * from a first. A raster image drawn into a new bitmap copied every image twice (#393).
     */
    @Test
    fun decodeRaw_keeps_the_pixels_it_was_given() {
        val rgba = byteArrayOf(10, 20, 30, 128.toByte(), 40, 50, 60, 255.toByte())
        val bitmap = ImageDecoder.decodeRaw(rgba, 2, 1)?.asSkiaBitmap() ?: error("decodeRaw returned null")
        assertEquals(org.jetbrains.skia.ColorType.RGBA_8888, bitmap.imageInfo.colorType)
        assertEquals(org.jetbrains.skia.ColorAlphaType.UNPREMUL, bitmap.imageInfo.colorAlphaType)
        assertTrue(bitmap.isImmutable, "the bitmap cannot change under a cache that keeps it")
        assertEquals(rgba.toList(), bitmap.readPixels()?.toList(), "the pixels, byte for byte")
    }
}
