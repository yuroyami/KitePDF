package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlinx.browser.document
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.abs
import kotlin.test.assertTrue

/** The side of every test page, in points and in canvas pixels. */
internal const val SIDE = 200

/**
 * A one-page PDF, [SIDE] points square, that draws [content] with [resources].
 * The [extra] objects are numbered from 5.
 */
internal fun testPage(content: String, resources: String, extra: List<String> = emptyList()): ByteArray {
    val objects = listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $SIDE $SIDE] /Resources << $resources >> /Contents 4 0 R >>",
        "<< /Length ${content.length} >>\nstream\n$content\nendstream",
    ) + extra
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

/** A form XObject over the whole page that is a transparency group and paints [content] with [resources]. */
internal fun groupForm(content: String, resources: String = ""): String =
    "<< /Type /XObject /Subtype /Form /BBox [0 0 $SIDE $SIDE] /Group << /S /Transparency /CS /DeviceRGB >> " +
        "/Resources << $resources >> /Length ${content.length} >>\nstream\n$content\nendstream"

/** The page of [bytes] drawn with [Canvas2dCanvas] onto a canvas over white. */
internal fun renderOnCanvas(bytes: ByteArray): CanvasRenderingContext2D {
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.width = SIDE
    canvas.height = SIDE
    val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
    ctx.fillStyle = "white"
    ctx.fillRect(0.0, 0.0, SIDE.toDouble(), SIDE.toDouble())
    PdfDocument.open(bytes).pages[0].renderTo(Canvas2dCanvas(ctx), KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, SIDE.toDouble()))
    return ctx
}

/** Asserts that the pixel at page point ([x], [y]), with y up, is [expected] within 3 levels. */
internal fun CanvasRenderingContext2D.assertPixel(x: Int, y: Int, expected: List<Int>) {
    val data = getImageData(x.toDouble(), (SIDE - 1 - y).toDouble(), 1.0, 1.0).data.asDynamic()
    val actual = listOf(data[0] as Int, data[1] as Int, data[2] as Int)
    assertTrue(actual.zip(expected).all { (a, e) -> abs(a - e) <= 3 }, "pixel ($x, $y): expected $expected, got $actual")
}
