package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import kotlin.math.abs
import kotlin.test.assertTrue

/** A one-page PDF, [width] by [height] points, that draws [content] with [resources]. The [extra] objects are numbered from 5. */
internal fun cgTestPage(content: String, resources: String, extra: List<String> = emptyList(), width: Int = 200, height: Int = width): ByteArray {
    val objects = listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] /Resources << $resources >> /Contents 4 0 R >>",
        "<< /Length ${content.length} >>\nstream\n$content\nendstream",
    ) + extra
    val out = StringBuilder("%PDF-1.7\n")
    val offsets = ArrayList<Int>()
    objects.forEachIndexed { i, body ->
        offsets += out.length
        out.append("${i + 1} 0 obj\n$body\nendobj\n")
    }
    val xref = out.length
    out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
    for (o in offsets) out.append("${o.toString().padStart(10, '0')} 00000 n \n")
    out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return out.toString().encodeToByteArray()
}

/** RGBA pixels of a CoreGraphics bitmap context, first row on top. */
internal class CgPixels(val width: Int, val height: Int, val bytes: UByteArray) {
    /** The red, green and blue levels at device pixel ([x], [y]), y up from the bottom row. */
    fun rgb(x: Int, y: Int): List<Int> {
        val p = ((height - 1 - y) * width + x) * 4
        return listOf(bytes[p].toInt(), bytes[p + 1].toInt(), bytes[p + 2].toInt())
    }

    /** Asserts that the pixel at ([x], [y]), y up, is [expected] within [tolerance] levels. */
    fun assertPixel(x: Int, y: Int, expected: List<Int>, tolerance: Int = 3) {
        val actual = rgb(x, y)
        assertTrue(actual.zip(expected).all { (a, e) -> abs(a - e) <= tolerance }, "pixel ($x, $y): expected $expected, got $actual")
    }
}

/**
 * [draw] on a [width] by [height] premultiplied RGBA bitmap context filled white first. The
 * context's user space is its pixels, y up.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun cgDraw(width: Int, height: Int, draw: (CoreGraphicsCanvas) -> Unit): CgPixels {
    val pixels = UByteArray(width * height * 4)
    pixels.usePinned { pinned ->
        val space = CGColorSpaceCreateDeviceRGB()
        val ctx = CGBitmapContextCreate(
            pinned.addressOf(0), width.toULong(), height.toULong(), 8u, (width * 4).toULong(), space,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
        )!!
        CGColorSpaceRelease(space)
        CGContextSetRGBFillColor(ctx, 1.0, 1.0, 1.0, 1.0)
        CGContextFillRect(ctx, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
        try {
            draw(CoreGraphicsCanvas(ctx))
        } finally {
            CGContextRelease(ctx)
        }
    }
    return CgPixels(width, height, pixels)
}

/** The first page of [bytes] drawn on CoreGraphics over white, at [scale] pixels a point. */
internal fun cgRender(bytes: ByteArray, scale: Double = 1.0): CgPixels {
    val page = PdfDocument.open(bytes).pages[0]
    val w = kotlin.math.ceil(page.width * scale).toInt()
    val h = kotlin.math.ceil(page.height * scale).toInt()
    return cgDraw(w, h) { page.renderTo(it, KiteMatrix.scaling(scale, scale)) }
}
