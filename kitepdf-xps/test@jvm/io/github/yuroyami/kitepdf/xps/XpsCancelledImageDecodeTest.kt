package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.KiteCancellation
import io.github.yuroyami.kitepdf.core.KiteWarning
import io.github.yuroyami.kitepdf.core.KiteWarningListener
import io.github.yuroyami.kitepdf.core.KiteWarningScope
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
 * An XPS page cancelled while it decodes a large image brush stops inside the decode, warns
 * nothing and keeps nothing as unreadable: the next render draws the image (#905).
 */
class XpsCancelledImageDecodeTest {

    /** A canvas that reads each image's full-size samples, as a canvas drawing at full size does. */
    private class FullDecodeCanvas : KiteCanvas by RecordingCanvas() {
        var drawn = 0
        override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double) {
            if (image.pixelBytes != null) drawn++
        }
        override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double, blendMode: KiteBlendMode) =
            drawImage(image, ctm, alpha)
    }

    private fun noiseJpeg(width: Int, height: Int): ByteArray {
        val random = Random(905)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) row[x] = random.nextInt() and 0xFFFFFF
            image.setRGB(0, y, width, 1, row, 0, width)
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun millis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun a_cancelled_render_stops_inside_the_image_decode_and_keeps_nothing() {
        val content = """<Path Data="M0,0 L192,0 192,96 0,96 Z"><Path.Fill>
            <ImageBrush ImageSource="../../Resources/big.jpg" Viewbox="0,0,5000,4000" Viewport="0,0,192,96"
              ViewboxUnits="Absolute" ViewportUnits="Absolute"/>
            </Path.Fill></Path>"""
        val bytes = XpsFixtures.packageBytes(content, listOf("Resources/big.jpg" to noiseJpeg(5000, 4000)))
        val warnings = ArrayList<KiteWarning>()
        val listener = KiteWarningListener { warnings += it }
        fun render(page: io.github.yuroyami.kitepdf.core.KitePage, canvas: KiteCanvas, cancellation: KiteCancellation? = null) =
            KiteWarningScope.with(listener, null, null) {
                if (cancellation == null) page.renderTo(canvas, KiteMatrix.IDENTITY) else page.renderTo(canvas, KiteMatrix.IDENTITY, cancellation)
            }

        val warm = FullDecodeCanvas()
        val full = millis { render(XpsDocument.open(bytes).pages.single(), warm) }
        assertEquals(1, warm.drawn, "the image decodes")

        val page = XpsDocument.open(bytes).pages.single()
        val cancelledCanvas = FullDecodeCanvas()
        val start = System.nanoTime()
        val cancelled = millis { render(page, cancelledCanvas, KiteCancellation { System.nanoTime() - start > 10_000_000 }) }
        assertEquals(0, cancelledCanvas.drawn, "the cancelled render drew the image")
        assertTrue(cancelled * 4 < full, "the cancelled render took $cancelled ms, the full one $full ms")

        val again = FullDecodeCanvas()
        render(page, again)
        assertEquals(1, again.drawn, "the next render draws the image")
        assertTrue(warnings.isEmpty(), "warnings: ${warnings.map { it.message }}")
    }
}
