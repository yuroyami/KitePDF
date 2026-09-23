package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [ApplePdfRasterizer] returns a PNG with the top of the page in its first row, and
 * images stand the same way as the page (#288, #289).
 */
@OptIn(ExperimentalForeignApi::class)
class ApplePdfRasterizerTest {

    private val side = 200

    /** A red band across the top of the page, and an image of one green pixel over one blue pixel. */
    private fun pdf(): ByteArray {
        val content = "1 0 0 rg 0 150 200 50 re f q 100 0 0 100 50 25 cm /Im1 Do Q"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $side $side] /Resources << /XObject << /Im1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /XObject /Subtype /Image /Width 1 /Height 2 /ColorSpace /DeviceRGB /BitsPerComponent 8 " +
                "/Filter /ASCIIHexDecode /Length 13 >>\nstream\n00FF000000FF>\nendstream",
        )
        var out = "%PDF-1.7\n"
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body ->
            offsets += out.length
            out += "${i + 1} 0 obj\n$body\nendobj\n"
        }
        val xref = out.length
        out += "xref\n0 ${objects.size + 1}\n0000000000 65535 f \n"
        for (o in offsets) out += "${o.toString().padStart(10, '0')} 00000 n \n"
        out += "trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n"
        return out.encodeToByteArray()
    }

    /** The RGBA pixels of [png], which must be [side] pixels square, with its first row first. */
    private fun decode(png: NSData): UByteArray {
        val data = CFDataCreate(null, png.bytes?.reinterpret<UByteVar>(), png.length.toLong())!!
        val source = CGImageSourceCreateWithData(data, null)!!
        val image = CGImageSourceCreateImageAtIndex(source, 0uL, null)!!
        try {
            assertEquals(side.toULong(), CGImageGetWidth(image))
            assertEquals(side.toULong(), CGImageGetHeight(image))
            val pixels = UByteArray(side * side * 4)
            pixels.usePinned { pinned ->
                val ctx = CGBitmapContextCreate(
                    pinned.addressOf(0), side.toULong(), side.toULong(),
                    8u, (side * 4).toULong(), CGColorSpaceCreateDeviceRGB(),
                    CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
                )!!
                // Drawn upright, the first row of the image is the first row of the buffer.
                CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, side.toDouble(), side.toDouble()), image)
                CGContextRelease(ctx)
            }
            return pixels
        } finally {
            CGImageRelease(image)
            CFRelease(source)
            CFRelease(data)
        }
    }

    /** Asserts that the pixel at ([x], [y]) in page points is [expected], within 3 levels. */
    private fun UByteArray.assertPixel(x: Int, y: Int, expected: List<Int>) {
        val p = ((side - 1 - y) * side + x) * 4
        val actual = listOf(this[p].toInt(), this[p + 1].toInt(), this[p + 2].toInt())
        assertTrue(actual.zip(expected).all { (a, e) -> abs(a - e) <= 3 }, "pixel ($x, $y): expected $expected, got $actual")
    }

    @Test
    fun the_png_holds_the_top_of_the_page_in_its_first_row() {
        val png = ApplePdfRasterizer.renderToPngData(PdfDocument.open(pdf()).pages[0])
        assertNotNull(png, "the rasterizer returned no PNG")
        val pixels = decode(png)
        pixels.assertPixel(100, 190, listOf(255, 0, 0))
        pixels.assertPixel(100, 10, listOf(255, 255, 255))
        pixels.assertPixel(100, 100, listOf(0, 255, 0))
        pixels.assertPixel(100, 50, listOf(0, 0, 255))
    }
}
