package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.render.ImageXObject
import io.github.yuroyami.kitepdf.render.toRgbaBytes
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the pure-Kotlin core GIF path ([ImageXObject.fromEncodedImage] →
 * `GifDecoder`) against the JVM's `ImageIO` GIF decoder as the oracle. GIF is
 * palette-exact, so the decoded RGB must match ImageIO's output exactly.
 */
class GifDecoderTest {

    private fun buildIndexed(w: Int, h: Int): BufferedImage {
        // A 6-colour indexed image so GIF's palette is exercised.
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val cols = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0x00FFFF, 0xFFFFFF)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, cols[(x + y) % cols.size])
        return img
    }

    private fun encodeGif(img: BufferedImage): ByteArray {
        val baos = ByteArrayOutputStream()
        assertTrue(ImageIO.write(img, "gif", baos), "ImageIO can write GIF")
        return baos.toByteArray()
    }

    @Test
    fun gif_matches_imageio() {
        val w = 40; val h = 24
        val gif = encodeGif(buildIndexed(w, h))
        val iox = ImageXObject.fromEncodedImage(gif)
        assertNotNull(iox, "GIF decodes")
        assertEquals(ImageXObject.Kind.RAW, iox.kind, "GIF decodes to a RAW image in core")
        assertEquals(w, iox.width); assertEquals(h, iox.height)
        val rgba = iox.toRgbaBytes()
        assertNotNull(rgba)

        val ref = ImageIO.read(ByteArrayInputStream(gif))
        assertNotNull(ref)
        var maxDiff = 0
        var o = 0
        for (y in 0 until h) for (x in 0 until w) {
            val p = ref.getRGB(x, y)
            val rr = (p shr 16) and 0xFF; val rg = (p shr 8) and 0xFF; val rb = p and 0xFF
            maxDiff = maxOf(
                maxDiff,
                abs((rgba[o].toInt() and 0xFF) - rr),
                abs((rgba[o + 1].toInt() and 0xFF) - rg),
                abs((rgba[o + 2].toInt() and 0xFF) - rb),
            )
            o += 4
        }
        // Palette colours are reproduced exactly (GIF is lossless per index).
        assertEquals(0, maxDiff, "decoded GIF matches ImageIO exactly")
    }

    @Test
    fun gif_is_not_blank() {
        val gif = encodeGif(buildIndexed(20, 20))
        val rgba = ImageXObject.fromEncodedImage(gif)!!.toRgbaBytes()!!
        val distinct = HashSet<Int>()
        var o = 0
        repeat(20 * 20) { distinct.add((rgba[o].toInt() and 0xFF) or ((rgba[o + 1].toInt() and 0xFF) shl 8) or ((rgba[o + 2].toInt() and 0xFF) shl 16)); o += 4 }
        assertTrue(distinct.size >= 4, "multiple palette colours decoded (${distinct.size})")
    }
}
