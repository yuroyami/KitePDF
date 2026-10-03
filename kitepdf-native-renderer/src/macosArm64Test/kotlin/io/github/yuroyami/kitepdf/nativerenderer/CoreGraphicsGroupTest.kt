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
 * Non-isolated transparency groups whose paints blend, on a CoreGraphics bitmap context
 * (ISO 32000-1, 11.4.8, #308). A layer on CoreGraphics starts transparent, so the renderer
 * composites these groups from rasters of the context. The expected pixels are mutool's for
 * the same pages.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreGraphicsGroupTest {

    private val side = 200

    /**
     * A page that runs [content] with the ExtGStates [pageStates] over the form Fm1, a
     * non-isolated group, [knockout] or not, that runs [form] with the ExtGStates [formStates].
     */
    private fun pdf(content: String, pageStates: String, form: String, formStates: String, knockout: Boolean = false): ByteArray {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $side $side] " +
                "/Resources << /ExtGState << $pageStates >> /XObject << /Fm1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /XObject /Subtype /Form /BBox [0 0 $side $side] /Group << /S /Transparency /I false /K $knockout >> " +
                "/Resources << /ExtGState << $formStates >> >> /Length ${form.length} >>\nstream\n$form\nendstream",
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
    fun a_group_at_half_alpha_blends_its_paints_with_the_page() {
        // The yellow square multiplies onto the light green page, (0.5, 1, 0), and the group
        // then lands at half alpha. A transparent layer would show yellow at half alpha.
        val pixels = render(
            pdf("0.5 1 0.5 rg 0 0 200 200 re f /GS1 gs /Fm1 Do", "/GS1 << /ca 0.5 >>", "/GM gs 1 1 0 rg 40 40 120 120 re f", "/GM << /BM /Multiply >>"),
        )
        pixels.assertPixel(100, 100, listOf(128, 255, 64))
        pixels.assertPixel(10, 10, listOf(128, 255, 128))
    }

    @Test
    fun a_group_with_a_blend_mode_of_its_own_takes_the_page_out_first() {
        val pixels = render(
            pdf("0.5 1 0.5 rg 0 0 200 200 re f /GS1 gs /Fm1 Do", "/GS1 << /BM /Screen >>", "/GM gs 1 1 0 rg 40 40 120 120 re f", "/GM << /BM /Multiply >>"),
        )
        pixels.assertPixel(100, 100, listOf(191, 255, 127))
        pixels.assertPixel(10, 10, listOf(128, 255, 128))
    }

    @Test
    fun a_knockout_group_blends_each_object_with_the_page() {
        // The yellow square multiplies onto the green page, not onto the red square it knocks out.
        val pixels = render(
            pdf("0 1 0 rg 0 0 200 200 re f /Fm1 Do", "", "1 0 0 rg 20 20 100 100 re f /GM gs 1 1 0 rg 80 80 100 100 re f", "/GM << /BM /Multiply >>", knockout = true),
        )
        pixels.assertPixel(100, 100, listOf(0, 255, 0))
        pixels.assertPixel(50, 50, listOf(255, 0, 0))
        pixels.assertPixel(150, 150, listOf(0, 255, 0))
    }
}
