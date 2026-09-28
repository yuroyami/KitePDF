package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageDecoderTest {

    /** A smooth [w] by [h] picture encoded by ImageIO as [format]. */
    private fun encoded(w: Int, h: Int, format: String): ByteArray {
        val image = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) image.setRGB(x, y, ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or 128)
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, format, it) }.toByteArray()
    }

    @Test
    fun decodeSampled_shrinks_a_jpeg_inside_the_decoder() {
        // Skia's JPEG codec decodes straight to 1/2, 1/4 or 1/8 of the size (#381).
        val bytes = encoded(203, 157, "jpg")
        val full = ImageDecoder.decode(bytes)?.toPixelMap() ?: error("decode returned null")
        for (sample in listOf(2, 4, 8)) {
            val (bitmap, done) = decodeSampled(bytes, sample) ?: error("decodeSampled returned null")
            assertEquals(sample, done)
            assertEquals((203 + sample - 1) / sample, bitmap.width)
            assertEquals((157 + sample - 1) / sample, bitmap.height)
            // Each pixel stands for a block of the full decode: compare it with that block's mean.
            val small = bitmap.toPixelMap()
            var worst = 0f
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                var red = 0f
                var n = 0
                for (fy in y * sample until minOf((y + 1) * sample, 157)) for (fx in x * sample until minOf((x + 1) * sample, 203)) {
                    red += full[fx, fy].red
                    n++
                }
                worst = maxOf(worst, kotlin.math.abs(small[x, y].red - red / n))
            }
            assertTrue(worst < 0.05f, "1/$sample: worst red difference $worst")
        }
    }

    @Test
    fun decodeSampled_decodes_what_it_cannot_shrink_at_full_size() {
        // Skia's PNG codec decodes only at its own size, so the canvas shrinks the whole image.
        val png = encoded(203, 157, "png")
        val (bitmap, done) = decodeSampled(png, 4) ?: error("decodeSampled returned null")
        assertEquals(1, done)
        assertEquals(203, bitmap.width)
        val (jpeg, one) = decodeSampled(encoded(203, 157, "jpg"), 1) ?: error("decodeSampled returned null")
        assertEquals(1, one)
        assertEquals(203, jpeg.width)
    }
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
