package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A PDF's JPEG whose headers KiteImageCodec reads and whose data it cannot decode draws through
 * ImageIO, as an encoded JPEG does. The file is no longer decoded when it loads, so the damage
 * shows at the first draw, and the canvas falls back there (#475).
 */
class DamagedJpegFallbackTest {

    private val w = 96
    private val h = 64

    /** A JPEG of a smooth picture whose entropy-coded data has a run of ones, which no Huffman code is. */
    private val damaged: ByteArray = run {
        val picture = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) picture.setRGB(x, y, Color(x * 255 / w, y * 255 / h, 128).rgb)
        val file = ByteArrayOutputStream().also { ImageIO.write(picture, "jpg", it) }.toByteArray()
        fun u16(at: Int) = ((file[at].toInt() and 0xFF) shl 8) or (file[at + 1].toInt() and 0xFF)
        var at = 2
        while ((file[at + 1].toInt() and 0xFF) != 0xDA) at += 2 + u16(at + 2)
        val data = at + 2 + u16(at + 2)
        val from = data + (file.size - data) / 3
        for (i in from until from + 32 step 2) {
            file[i] = 0xFF.toByte()
            file[i + 1] = 0
        }
        file
    }

    @Test
    fun a_jpeg_whose_data_does_not_decode_draws_through_imageio() {
        val reference = assertNotNull(ImageIO.read(ByteArrayInputStream(damaged)), "ImageIO reads the damaged file")
        val image = assertNotNull(
            KiteImageData.from(
                PdfStream(
                    PdfDictionary(
                        mapOf(
                            "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                            "Width" to PdfInt(w.toLong()), "Height" to PdfInt(h.toLong()), "BitsPerComponent" to PdfInt(8),
                            "ColorSpace" to PdfName("DeviceRGB"), "Filter" to PdfName("DCTDecode"),
                            "Length" to PdfInt(damaged.size.toLong()),
                        ),
                    ),
                    damaged,
                ),
            ),
        )
        assertNull(image.toRgbaBytes(), "KiteImageCodec decodes the damaged file")

        // One pixel of the page for each pixel of the image, row 0 at the top.
        val page = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = page.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        AwtCanvas(g).drawImage(image, KiteMatrix(w.toDouble(), 0.0, 0.0, -h.toDouble(), 0.0, h.toDouble()))
        g.dispose()

        var worst = 0
        for (y in 0 until h) for (x in 0 until w) {
            val e = reference.getRGB(x, y)
            val a = page.getRGB(x, y)
            for (shift in intArrayOf(16, 8, 0)) worst = maxOf(worst, abs(((e ushr shift) and 0xFF) - ((a ushr shift) and 0xFF)))
        }
        assertTrue(worst <= 2, "the page differs from ImageIO's decode by $worst levels")
    }
}
