package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteCancellation
import io.github.yuroyami.kitepdf.core.KiteWarning
import io.github.yuroyami.kitepdf.core.KiteWarningListener
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A render that is cancelled while it decodes a large image stops inside the decode, and the
 * image is not cached as broken: the next render decodes and draws it (#905).
 */
class CancelledImageDecodeTest {

    /** A canvas that reads each image's full-size samples, as a canvas drawing at full size does. */
    private class FullDecodeCanvas : KiteCanvas by RecordingCanvas() {
        var drawn = 0
        override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double) {
            if (image.pixelBytes != null) drawn++
        }
        override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double, blendMode: KiteBlendMode) =
            drawImage(image, ctm, alpha)
    }

    /** A page that draws one baseline JPEG of [width] by [height] pixels of noise, which decodes slowly. */
    private fun pdf(width: Int, height: Int): ByteArray {
        val random = Random(905)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) row[x] = random.nextInt() and 0xFFFFFF
            image.setRGB(0, y, width, 1, row, 0, width)
        }
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        return TestPdf.build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 160] /Contents 4 0 R " +
                    "/Resources << /XObject << /Im0 5 0 R >> >> >>",
                TestPdf.stream("q 200 0 0 160 0 0 cm /Im0 Do Q"),
                TestPdf.Stream(
                    "/Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace /DeviceRGB " +
                        "/BitsPerComponent 8 /Filter /DCTDecode",
                    jpeg,
                ),
            ),
        )
    }

    private fun millis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun a_cancelled_render_stops_inside_the_image_decode_and_caches_nothing() {
        val warnings = ArrayList<KiteWarning>()
        val doc = PdfDocument.open(pdf(5000, 4000))
        doc.warnings = object : KiteWarningListener {
            override fun warn(warning: KiteWarning) { warnings += warning }
        }
        val page = doc.pages[0]

        val warm = FullDecodeCanvas()
        val full = millis { page.renderTo(warm, KiteMatrix.IDENTITY) }
        assertEquals(1, warm.drawn, "the image decodes")

        val cancelledCanvas = FullDecodeCanvas()
        val start = System.nanoTime()
        val cancelled = millis {
            page.renderTo(cancelledCanvas, KiteMatrix.IDENTITY, KiteCancellation { System.nanoTime() - start > 10_000_000 })
        }
        assertEquals(0, cancelledCanvas.drawn, "the cancelled render drew the image")
        assertTrue(cancelled * 4 < full, "the cancelled render took $cancelled ms, the full one $full ms")

        val again = FullDecodeCanvas()
        page.renderTo(again, KiteMatrix.IDENTITY)
        assertEquals(1, again.drawn, "the next render draws the image")
        assertTrue(warnings.isEmpty(), "warnings: ${warnings.map { it.message }}")
    }
}
