package io.github.yuroyami.kitepdf.core.render

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A scanned page of one sample a pixel below 8 bits, drawn at full size or larger, allocates about
 * its RGBA and nothing for each pixel. The general path converted each pixel on its own, and the
 * calibrated grey page made an [RgbColor] for each, 370 MB in all (#477). A DeviceGray page allocated
 * only its output once the JIT had warmed up, so that case guards the table path, not the old fault.
 * The JVM counts the bytes each thread allocates, so this measures the work instead of trusting the code.
 */
class LowBitAllocationTest {

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

    private fun scan(bits: Int, space: KiteColorSpace) = KiteImageData(
        width = width, height = height, bitsPerComponent = bits, colorSpace = "test", kind = KiteImageData.Kind.RAW,
        encodedBytes = ByteArray(0), pixelBytes = ByteArray((width * bits + 7) / 8 * height) { ((it * 37) xor (it ushr 7)).toByte() },
        resolvedColorSpace = space,
    )

    @Test
    fun a_scanned_page_at_full_size_allocates_about_its_output() {
        val output = width * height * 4
        val calGray = KiteColorSpace.CalGray(doubleArrayOf(0.9505, 1.0, 1.089), 2.2)
        val pages = listOf(
            "bilevel" to scan(1, KiteColorSpace.DeviceGray),
            "2-bit grey" to scan(2, KiteColorSpace.DeviceGray),
            "4-bit grey" to scan(4, KiteColorSpace.DeviceGray),
            "4-bit calibrated grey" to scan(4, calGray),
        )
        for ((name, image) in pages) {
            repeat(2) { image.toRgbaBytes() } // warm up
            var rgba: ByteArray? = null
            val bytes = allocated { rgba = image.toRgbaBytes() }
            assertEquals(output, rgba?.size, name)
            println("a $name page of $width by $height at full size: $bytes bytes allocated")
            assertTrue(bytes < output + output / 10, "$name: $bytes bytes allocated for $output bytes of output")
        }
    }
}
