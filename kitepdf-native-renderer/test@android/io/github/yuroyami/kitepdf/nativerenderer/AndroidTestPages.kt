package io.github.yuroyami.kitepdf.nativerenderer

import android.graphics.Bitmap
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.math.abs
import kotlin.test.assertTrue

/** The side of every test page, in points and in bitmap pixels. */
internal const val SIDE = 200

/**
 * A one-page PDF, [side] points square, that draws [content] with [resources].
 * The [extra] objects are numbered from 5.
 */
internal fun testPage(content: String, resources: String, extra: List<String> = emptyList(), side: Int = SIDE): ByteArray {
    val objects = listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $side $side] /Resources << $resources >> /Contents 4 0 R >>",
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

/** A form XObject over the whole page that is a transparency group and paints [content] with [resources]. */
internal fun groupForm(content: String, resources: String = ""): String =
    "<< /Type /XObject /Subtype /Form /BBox [0 0 $SIDE $SIDE] /Group << /S /Transparency /CS /DeviceRGB >> " +
        "/Resources << $resources >> /Length ${content.length} >>\nstream\n$content\nendstream"

/** The page of [bytes] drawn with [AndroidPdfBitmapRenderer] over white, at [scale] pixels per point. */
internal fun renderOnBitmap(bytes: ByteArray, scale: Double = 1.0): Bitmap =
    AndroidPdfBitmapRenderer.renderToBitmap(PdfDocument.open(bytes).pages[0], scale)

/** The red, green and blue levels of the pixel at page point ([x], [y]), with y up. */
internal fun Bitmap.rgbAt(x: Int, y: Int): List<Int> {
    val p = getPixel(x, height - 1 - y)
    return listOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
}

/** Asserts that the pixel at page point ([x], [y]), with y up, is [expected] within [tolerance] levels. */
internal fun Bitmap.assertPixel(x: Int, y: Int, expected: List<Int>, tolerance: Int = 3) {
    val actual = rgbAt(x, y)
    assertTrue(actual.zip(expected).all { (a, e) -> abs(a - e) <= tolerance }, "pixel ($x, $y): expected $expected, got $actual")
}
