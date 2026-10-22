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
 * Soft masks on CoreGraphics (ISO 32000-1, 11.6.5.2): the alpha or the luminosity of
 * the mask group gates a red square, and the mask group's own colours never reach the
 * page (#79). The /BC backdrop and the /TR transfer function apply (#68). The expected
 * pixels are mutool's for the same pages.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreGraphicsSoftMaskTest {

    private val side = 200

    /** A /TR entry that inverts each mask value. */
    private val INVERTER = "/TR << /FunctionType 2 /Domain [0 1] /C0 [1] /C1 [0] /N 1 >>"

    /** A /TR entry that squares each mask value. */
    private val SQUARE = "/TR << /FunctionType 2 /Domain [0 1] /C0 [0] /C1 [1] /N 2 >>"

    /**
     * A page that fills a red square under a soft mask of [kind], whose group draws
     * [mask] with the resources in [maskResources]. The mask dictionary also holds
     * [entries], and the group has the box [bbox] and the colour space [space].
     */
    private fun pdf(
        kind: String, mask: String, maskResources: String = "",
        entries: String = "", bbox: String = "0 0 $side $side", space: String = "DeviceRGB",
    ): ByteArray {
        val content = "q /GS1 gs 1 0 0 rg 20 20 160 160 re f Q"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $side $side] /Resources << /ExtGState << /GS1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /ExtGState /SMask << /S /$kind /G 6 0 R $entries >> >>",
            "<< /Type /XObject /Subtype /Form /BBox [$bbox] /Group << /S /Transparency /CS /$space >> " +
                "$maskResources /Length ${mask.length} >>\nstream\n$mask\nendstream",
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
    fun an_alpha_mask_shows_the_content_where_its_group_painted() {
        // The group paints blue on the left half. Blue must not reach the page.
        val pixels = render(pdf("Alpha", "0 0 1 rg 0 0 100 200 re f"))
        pixels.assertPixel(60, 100, listOf(255, 0, 0))
        pixels.assertPixel(140, 100, listOf(255, 255, 255))
        pixels.assertPixel(10, 10, listOf(255, 255, 255))
    }

    @Test
    fun an_alpha_mask_takes_the_alpha_its_group_painted_with() {
        // Black at half alpha: the square shows at half strength, not darkened.
        val pixels = render(pdf("Alpha", "/A gs 0 g 0 0 200 200 re f", "/Resources << /ExtGState << /A << /ca 0.5 >> >> >>"))
        pixels.assertPixel(100, 100, listOf(255, 129, 129))
    }

    @Test
    fun a_luminosity_mask_shows_the_content_by_the_brightness_of_its_group() {
        val pixels = render(pdf("Luminosity", "0.25 g 0 0 100 200 re f 1 g 100 0 100 200 re f"))
        pixels.assertPixel(60, 100, listOf(255, 192, 192))
        pixels.assertPixel(140, 100, listOf(255, 0, 0))
    }

    @Test
    fun a_luminosity_mask_shows_its_backdrop_colour_where_its_group_painted_nothing() {
        // A white backdrop lets the content through outside the box of the group (#68).
        val pixels = render(pdf("Luminosity", "0 g 40 40 80 80 re f", entries = "/BC [1]", bbox = "40 40 120 120", space = "DeviceGray"))
        pixels.assertPixel(30, 30, listOf(255, 0, 0))
        pixels.assertPixel(150, 150, listOf(255, 0, 0))
        pixels.assertPixel(80, 80, listOf(255, 255, 255))
        pixels.assertPixel(10, 10, listOf(255, 255, 255))
    }

    @Test
    fun a_transfer_function_maps_each_luminosity_mask_value() {
        // The inverter hides the content where the group is white and shows it where the group painted nothing (#68).
        val pixels = render(pdf("Luminosity", "1 g 0 0 100 200 re f", entries = INVERTER))
        pixels.assertPixel(60, 100, listOf(255, 255, 255))
        pixels.assertPixel(140, 100, listOf(255, 0, 0))
    }

    @Test
    fun a_transfer_function_maps_each_alpha_mask_value() {
        // Squaring maps the half alpha of the group to a quarter (#68).
        val pixels = render(pdf("Alpha", "/A gs 0 g 0 0 200 200 re f", "/Resources << /ExtGState << /A << /ca 0.5 >> >> >>", entries = SQUARE))
        pixels.assertPixel(100, 100, listOf(255, 191, 191))
    }

    @Test
    fun a_luminosity_mask_hides_the_content_where_its_group_painted_nothing() {
        // Unpainted parts of the group show the black backdrop, whose luminosity is zero.
        val pixels = render(pdf("Luminosity", "1 g 0 0 100 200 re f"))
        pixels.assertPixel(60, 100, listOf(255, 0, 0))
        pixels.assertPixel(140, 100, listOf(255, 255, 255))
    }
}
