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
 * Images on CoreGraphics, drawn smaller and larger than their pixels: an image drawn
 * small is averaged, and an enlarged one keeps hard edges unless it asks for
 * /Interpolate (#122, #123).
 */
@OptIn(ExperimentalForeignApi::class)
class CoreGraphicsImageSamplingTest {

    private val side = 40

    /** A PDF page [side] points square that draws one image over the whole page. */
    private fun pdf(width: Int, height: Int, colorSpace: String, samples: ByteArray, interpolate: Boolean): ByteArray {
        val content = "q $side 0 0 $side 0 0 cm /Im1 Do Q".encodeToByteArray()
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>".encodeToByteArray(),
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".encodeToByteArray(),
            ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $side $side] " +
                "/Resources << /XObject << /Im1 5 0 R >> >> /Contents 4 0 R >>").encodeToByteArray(),
            "<< /Length ${content.size} >>\nstream\n".encodeToByteArray() + content + "\nendstream".encodeToByteArray(),
            ("<< /Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace $colorSpace " +
                "/BitsPerComponent 8 /Interpolate $interpolate /Length ${samples.size} >>\nstream\n").encodeToByteArray() +
                samples + "\nendstream".encodeToByteArray(),
        )
        var out = "%PDF-1.7\n".encodeToByteArray()
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body ->
            offsets += out.size
            out += "${i + 1} 0 obj\n".encodeToByteArray() + body + "\nendobj\n".encodeToByteArray()
        }
        val xref = out.size
        var table = "xref\n0 ${objects.size + 1}\n0000000000 65535 f \n"
        for (o in offsets) table += "${o.toString().padStart(10, '0')} 00000 n \n"
        table += "trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n"
        return out + table.encodeToByteArray()
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

    private fun UByteArray.rgb(x: Int, y: Int): List<Int> {
        val p = (y * side + x) * 4
        return listOf(this[p].toInt(), this[p + 1].toInt(), this[p + 2].toInt())
    }

    /** Two columns of 2 by 2 pixels, red on the left and green on the right. */
    private fun redGreen(interpolate: Boolean) = pdf(
        2, 2, "/DeviceRGB", byteArrayOf(-1, 0, 0, 0, -1, 0, -1, 0, 0, 0, -1, 0), interpolate,
    )

    @Test
    fun single_pixel_squares_drawn_at_a_quarter_average_to_grey() {
        val board = ByteArray(160 * 160) { i -> if ((i % 160 + i / 160) % 2 == 0) 0 else -1 }
        val pixels = render(pdf(160, 160, "/DeviceGray", board, interpolate = false))
        for (y in 2 until side - 2) for (x in 2 until side - 2) {
            val level = pixels.rgb(x, y)[0]
            assertTrue(abs(level - 128) <= 10, "pixel ($x, $y) must be grey, got $level")
        }
    }

    @Test
    fun enlarged_pixels_keep_hard_edges() {
        val pixels = render(redGreen(interpolate = false))
        // Each image pixel covers 20 device pixels, so column 19 is red and column 20 green.
        assertTrue(pixels.rgb(19, 10).zip(listOf(255, 0, 0)).all { (a, e) -> abs(a - e) <= 2 }, "red: ${pixels.rgb(19, 10)}")
        assertTrue(pixels.rgb(20, 10).zip(listOf(0, 255, 0)).all { (a, e) -> abs(a - e) <= 2 }, "green: ${pixels.rgb(20, 10)}")
    }

    @Test
    fun the_interpolate_flag_blends_enlarged_pixels() {
        val (r, g, _) = render(redGreen(interpolate = true)).rgb(19, 10)
        assertTrue(r < 230 && g > 25, "red and green blend at the edge, got ($r, $g)")
    }
}
