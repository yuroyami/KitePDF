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
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An image on CoreGraphics composites with the blend mode of the graphics state
 * (ISO 32000-1, 11.3.5, #113). A two by two image of red, green, blue and yellow
 * covers the middle of a light blue page. The expected pixels are mutool's.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreGraphicsImageBlendTest {

    private val side = 200

    private fun pdf(state: String): ByteArray {
        val content = "0.5 0.5 1 rg 0 0 200 200 re f q /GS1 gs 100 0 0 100 50 50 cm /Im1 Do Q"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $side $side] " +
                "/Resources << /ExtGState << /GS1 << $state >> >> /XObject << /Im1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /ColorSpace /DeviceRGB /BitsPerComponent 8 " +
                "/Filter /ASCIIHexDecode /Length 25 >>\nstream\nFF000000FF000000FFFFFF00>\nendstream",
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

    /** The page of [bytes] drawn into an RGBA bitmap context over white. */
    private fun render(bytes: ByteArray): UByteArray {
        val pixels = UByteArray(side * side * 4)
        pixels.usePinned { pinned ->
            val ctx = CGBitmapContextCreate(
                pinned.addressOf(0), side.toULong(), side.toULong(),
                8u, (side * 4).toULong(), CGColorSpaceCreateDeviceRGB(),
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )!!
            CGContextSetRGBFillColor(ctx, 1.0, 1.0, 1.0, 1.0)
            CGContextFillRect(ctx, CGRectMake(0.0, 0.0, side.toDouble(), side.toDouble()))
            PdfDocument.open(bytes).pages[0].renderTo(CoreGraphicsCanvas(ctx), KiteMatrix.IDENTITY)
            CGContextRelease(ctx)
        }
        return pixels
    }

    /** Asserts that the pixel at ([x], [y]) in page points is [expected], within 3 levels. */
    private fun UByteArray.assertPixel(x: Int, y: Int, expected: List<Int>) {
        // Row 0 of the bitmap is the top of the page.
        val p = ((side - 1 - y) * side + x) * 4
        val actual = listOf(this[p].toInt(), this[p + 1].toInt(), this[p + 2].toInt())
        assertTrue(actual.zip(expected).all { (a, e) -> abs(a - e) <= 3 }, "pixel ($x, $y): expected $expected, got $actual")
    }

    @Test
    fun an_image_multiplies_onto_the_page() {
        val pixels = render(pdf("/BM /Multiply"))
        pixels.assertPixel(75, 125, listOf(127, 0, 0))
        pixels.assertPixel(125, 125, listOf(0, 127, 0))
        pixels.assertPixel(75, 75, listOf(0, 0, 255))
        pixels.assertPixel(125, 75, listOf(127, 127, 0))
    }

    @Test
    fun an_image_multiplies_at_its_alpha() {
        val pixels = render(pdf("/ca 0.5 /BM /Multiply"))
        pixels.assertPixel(75, 125, listOf(127, 64, 128))
        pixels.assertPixel(125, 75, listOf(127, 127, 128))
    }
}
