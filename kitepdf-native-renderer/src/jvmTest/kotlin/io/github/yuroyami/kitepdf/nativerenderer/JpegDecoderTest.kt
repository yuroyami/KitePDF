package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.render.ImageXObject
import io.github.yuroyami.kitepdf.render.toRgbaBytes
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the pure-Kotlin core [ImageXObject.fromEncodedImage] JPEG path
 * (via `JpegDecoder`) against the JVM's libjpeg-backed `ImageIO` decoder as the
 * oracle. Both decode the SAME encoded bytes, so any per-channel difference is
 * only IDCT rounding + chroma upsampling — kept small by the assertions.
 */
class JpegDecoderTest {

    private fun buildRgb(w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val r = (x * 255 / w)
            val g = (y * 255 / h)
            val b = ((x + y) * 255 / (w + h))
            // A few sharp colour patches to stress chroma edges.
            val patch = when {
                x < w / 4 && y < h / 4 -> 0xE01010
                x > 3 * w / 4 && y < h / 4 -> 0x10E010
                x < w / 4 && y > 3 * h / 4 -> 0x1010E0
                else -> (r shl 16) or (g shl 8) or b
            }
            img.setRGB(x, y, patch)
        }
        return img
    }

    private fun encode(img: BufferedImage, quality: Float, progressive: Boolean): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam
        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = quality
        if (progressive && param.canWriteProgressive()) {
            param.progressiveMode = ImageWriteParam.MODE_DEFAULT
        }
        val baos = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(baos).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
        return baos.toByteArray()
    }

    /** Mean per-channel absolute error between our RGBA and ImageIO's RGB. */
    private fun compare(jpeg: ByteArray, w: Int, h: Int): Double {
        val iox = ImageXObject.fromEncodedImage(jpeg)
        assertNotNull(iox, "fromEncodedImage returned null")
        assertEquals(ImageXObject.Kind.RAW, iox.kind, "JPEG should decode to a RAW image in core")
        assertEquals(w, iox.width); assertEquals(h, iox.height)
        val rgba = iox.toRgbaBytes()
        assertNotNull(rgba, "toRgbaBytes returned null")

        val ref = ImageIO.read(ByteArrayInputStream(jpeg))
        assertNotNull(ref)
        var sum = 0.0
        var o = 0
        for (y in 0 until h) for (x in 0 until w) {
            val p = ref.getRGB(x, y)
            val rr = (p shr 16) and 0xFF; val rg = (p shr 8) and 0xFF; val rb = p and 0xFF
            sum += abs((rgba[o].toInt() and 0xFF) - rr) +
                abs((rgba[o + 1].toInt() and 0xFF) - rg) +
                abs((rgba[o + 2].toInt() and 0xFF) - rb)
            o += 4
        }
        return sum / (w * h * 3)
    }

    @Test
    fun baseline_rgb_matches_imageio() {
        val w = 64; val h = 48
        val mae = compare(encode(buildRgb(w, h), 0.92f, progressive = false), w, h)
        assertTrue(mae < 3.0, "baseline RGB MAE too high: $mae")
    }

    @Test
    fun progressive_rgb_matches_imageio() {
        val w = 80; val h = 64
        val jpeg = encode(buildRgb(w, h), 0.9f, progressive = true)
        val mae = compare(jpeg, w, h)
        assertTrue(mae < 3.5, "progressive RGB MAE too high: $mae")
    }

    @Test
    fun grayscale_matches_imageio() {
        val w = 64; val h = 40
        val img = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        for (y in 0 until h) for (x in 0 until w) {
            val g = ((x * 255 / w) + (y * 255 / h)) / 2
            img.setRGB(x, y, (g shl 16) or (g shl 8) or g)
        }
        val jpeg = encode(img, 0.92f, progressive = false)
        val iox = ImageXObject.fromEncodedImage(jpeg)
        assertNotNull(iox)
        assertEquals(ImageXObject.Kind.RAW, iox.kind)
        val rgba = iox.toRgbaBytes()!!
        // Compare against the reference's RAW luma samples (readRaster), NOT getRGB:
        // a TYPE_BYTE_GRAY BufferedImage applies a linear<->sRGB gamma on getRGB that
        // the JPEG's stored Y samples don't carry, which would be a false mismatch.
        val reader = ImageIO.getImageReadersByFormatName("jpeg").next()
        reader.setInput(ImageIO.createImageInputStream(ByteArrayInputStream(jpeg)))
        val raster = reader.readRaster(0, null)
        reader.dispose()
        var sum = 0.0
        var o = 0
        for (y in 0 until h) for (x in 0 until w) {
            val refY = raster.getSample(x, y, 0)
            sum += abs((rgba[o].toInt() and 0xFF) - refY)
            o += 4
        }
        val mae = sum / (w * h)
        assertTrue(mae < 2.0, "grayscale MAE too high: $mae")
    }

    @Test
    fun not_blank() {
        val w = 32; val h = 32
        val iox = ImageXObject.fromEncodedImage(encode(buildRgb(w, h), 0.9f, false))
        assertNotNull(iox)
        val rgba = iox.toRgbaBytes()!!
        // Distinct pixel values prove real content, not a flat/blank fallback.
        val distinct = HashSet<Int>()
        var o = 0
        repeat(w * h) {
            distinct.add((rgba[o].toInt() and 0xFF) or ((rgba[o + 1].toInt() and 0xFF) shl 8))
            o += 4
        }
        assertTrue(distinct.size > 20, "decoded image looks blank (${distinct.size} distinct)")
    }
}
