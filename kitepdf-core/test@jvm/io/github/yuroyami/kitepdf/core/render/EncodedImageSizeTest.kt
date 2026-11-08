package io.github.yuroyami.kitepdf.core.render

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An image that is a file of its own, as a comic page, an EPUB image or an XPS brush is, keeps a
 * JPEG encoded and decodes it at the size it draws, as a PDF's JPEG does (#381). It decoded in
 * full, so a 6,000 x 4,000 page drawn as a thumbnail allocated 360 MB on every draw (#839).
 */
class EncodedImageSizeTest {

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Test
    fun a_large_jpeg_drawn_small_never_holds_its_full_size_samples() {
        val w = 4000
        val h = 3000
        val picture = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) picture.setRGB(x, y, (x * 255 / w shl 16) or (y * 255 / h shl 8))
        val jpeg = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(picture, "jpg", it) }.toByteArray()
        val image = KiteImageData.fromEncodedImage(jpeg)!!
        assertEquals(w, image.width)
        assertEquals(h, image.height)
        val id = Thread.currentThread().id
        fun drawSmall(): Long {
            val canvas = KiteRasterCanvas(200, 150)
            val before = threads.getThreadAllocatedBytes(id)
            canvas.drawImage(image, KiteMatrix(200.0, 0.0, 0.0, 150.0, 0.0, 0.0), 1.0)
            val bytes = threads.getThreadAllocatedBytes(id) - before
            // The colour of the middle is the gradient's, so the image drew.
            val p = canvas.toRaster()[100, 75]
            assertTrue((p ushr 16 and 0xFF) in 100..160 && (p ushr 8 and 0xFF) in 100..160, "the middle pixel is ${p.toString(16)}")
            return bytes
        }
        val first = drawSmall()
        val second = drawSmall()
        // Full-size RGB samples are 36 MB, and their ARGB bitmap 48 MB more.
        assertTrue(first < 12_000_000, "the first draw allocated $first bytes")
        assertTrue(second < 12_000_000, "the second draw allocated $second bytes")
    }
}
