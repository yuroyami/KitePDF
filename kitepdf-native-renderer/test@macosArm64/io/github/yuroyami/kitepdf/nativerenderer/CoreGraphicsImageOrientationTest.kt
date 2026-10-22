package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An image on CoreGraphics stands the same way as the page: its first row is at the
 * top of the unit square (ISO 32000-1, 8.9.4, #289). The image is one pixel wide and
 * two high, green over blue, so a flip shows.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreGraphicsImageOrientationTest {

    private val side = 200

    private fun pdf(): ByteArray {
        val content = "q 100 0 0 100 50 25 cm /Im1 Do Q"
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

    /**
     * The page drawn into an RGBA bitmap context over white. With [yDown], the context is
     * flipped as a UIKit context is, and the page is drawn with a y-down device matrix.
     */
    private fun render(yDown: Boolean): UByteArray {
        val pixels = UByteArray(side * side * 4)
        pixels.usePinned { pinned ->
            val ctx = CGBitmapContextCreate(
                pinned.addressOf(0), side.toULong(), side.toULong(),
                8u, (side * 4).toULong(), CGColorSpaceCreateDeviceRGB(),
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )!!
            CGContextSetRGBFillColor(ctx, 1.0, 1.0, 1.0, 1.0)
            CGContextFillRect(ctx, CGRectMake(0.0, 0.0, side.toDouble(), side.toDouble()))
            val deviceCtm = if (yDown) {
                CGContextTranslateCTM(ctx, 0.0, side.toDouble())
                CGContextScaleCTM(ctx, 1.0, -1.0)
                KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, side.toDouble())
            } else {
                KiteMatrix.IDENTITY
            }
            PdfDocument.open(pdf()).pages[0].renderTo(CoreGraphicsCanvas(ctx), deviceCtm)
            CGContextRelease(ctx)
        }
        return pixels
    }

    /** Asserts that the pixel at ([x], [y]) in page points is [expected], within 3 levels. */
    private fun UByteArray.assertPixel(x: Int, y: Int, expected: List<Int>) {
        // Row 0 of the bitmap is the top of the page in both set-ups.
        val p = ((side - 1 - y) * side + x) * 4
        val actual = listOf(this[p].toInt(), this[p + 1].toInt(), this[p + 2].toInt())
        assertTrue(actual.zip(expected).all { (a, e) -> abs(a - e) <= 3 }, "pixel ($x, $y): expected $expected, got $actual")
    }

    @Test
    fun the_first_row_is_at_the_top_on_a_y_up_context() {
        val pixels = render(yDown = false)
        pixels.assertPixel(100, 100, listOf(0, 255, 0))
        pixels.assertPixel(100, 50, listOf(0, 0, 255))
    }

    @Test
    fun the_first_row_is_at_the_top_on_a_flipped_context() {
        val pixels = render(yDown = true)
        pixels.assertPixel(100, 100, listOf(0, 255, 0))
        pixels.assertPixel(100, 50, listOf(0, 0, 255))
    }
}
