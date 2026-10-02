package io.github.yuroyami.kitepdf.core.render

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A scanned page drawn small averages its samples and never becomes full-size RGBA on the way.
 * Each pixel was converted to RGBA in bands and then averaged, and that was half the time of a
 * scanned page (#462). The JVM counts the bytes each thread allocates, so this measures the work
 * instead of trusting the code.
 */
class ScanShrinkAllocationTest {

    // A Letter page at 300 dpi.
    private val width = 2550
    private val height = 3300

    private fun allocated(block: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    private fun scan(bits: Int, isImageMask: Boolean = false) = KiteImageData(
        width = width, height = height, bitsPerComponent = bits, colorSpace = "test", kind = KiteImageData.Kind.RAW,
        encodedBytes = ByteArray(0), pixelBytes = ByteArray((width * bits + 7) / 8 * height) { ((it * 37) xor (it ushr 7)).toByte() },
        resolvedColorSpace = if (isImageMask) null else KiteColorSpace.DeviceGray,
        isImageMask = isImageMask, maskFill = if (isImageMask) RgbColor.BLACK else null,
    )

    @Test
    fun a_scanned_page_drawn_at_a_quarter_allocates_about_its_output() {
        val output = 638 * 825 * 4
        for ((name, image) in listOf("bilevel" to scan(1), "greyscale" to scan(8), "stencil" to scan(1, isImageMask = true))) {
            repeat(2) { image.toShrunkRgbaBytes(4, 4) } // warm up
            var small: ByteArray? = null
            val bytes = allocated { small = image.toShrunkRgbaBytes(4, 4) }
            assertEquals(output, small?.size, name)
            println("a $name page of $width by $height drawn at a quarter: $bytes bytes allocated")
            // The page as full-size RGBA is 33.7 MB.
            assertTrue(bytes < 2L * output, "$name: $bytes bytes allocated for $output bytes of output")
        }
    }
}
