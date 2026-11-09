package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.KitePDF
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A paint under a soft mask keeps its blend mode on the Compose canvas of the page rasterizer
 * (ISO 32000-1, 11.6.4.3 and 11.3.5; #764), as on AWT, Skia and Canvas2D.
 */
class ComposeMaskedBlendTest {

    private fun page(state: String, mask: String, maskResources: String = ""): ByteArray {
        val objects: List<Any> = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << /ExtGState << /GS << $state /SMask << /S /Alpha /G 5 0 R >> >> >> >> /Contents 4 0 R >>",
            "" to "0 0 1 rg 0 0 100 100 re f /GS gs 1 0 0 rg 0 0 100 100 re f",
            "/Type /XObject /Subtype /Form /BBox [0 0 100 100] /Group << /S /Transparency >> /Resources << $maskResources >>" to mask,
        )
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        for ((i, o) in objects.withIndex()) {
            offsets += sb.length
            sb.append("${i + 1} 0 obj\n")
            if (o is Pair<*, *>) {
                val data = o.second as String
                sb.append("<< ${o.first} /Length ${data.length} >>\nstream\n").append(data).append("\nendstream")
            } else {
                sb.append(o)
            }
            sb.append("\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    private fun centre(pdf: ByteArray): Triple<Int, Int, Int> {
        val density = Density(1f)
        val rasterizer = KitePageRasterizer(density, LayoutDirection.Ltr, TextMeasurer(testFontFamilyResolver(), density, LayoutDirection.Ltr))
        val page = KitePDF.open(pdf).pages[0]
        val c = onTestUiThread { rasterizer.rasterize(page, 100, 100) }.toPixelMap()[50, 50]
        return Triple((c.red * 255 + 0.5f).toInt(), (c.green * 255 + 0.5f).toInt(), (c.blue * 255 + 0.5f).toInt())
    }

    private fun near(actual: Triple<Int, Int, Int>, r: Int, g: Int, b: Int) =
        kotlin.math.abs(actual.first - r) <= 3 && kotlin.math.abs(actual.second - g) <= 3 && kotlin.math.abs(actual.third - b) <= 3

    @Test
    fun an_opaque_mask_leaves_a_multiply_paint_as_it_is() {
        val c = centre(page("/BM /Multiply", "1 g 0 0 100 100 re f"))
        assertTrue(near(c, 0, 0, 0), "red multiplied onto blue under an opaque mask gives $c")
    }

    @Test
    fun a_half_mask_keeps_half_of_the_backdrop() {
        val half = "/ExtGState << /H << /ca 0.5 >> >>"
        val multiply = centre(page("/BM /Multiply", "/H gs 1 g 0 0 100 100 re f", half))
        assertTrue(near(multiply, 0, 0, 128), "a half mask over Multiply gives $multiply")
        val normal = centre(page("", "/H gs 1 g 0 0 100 100 re f", half))
        assertTrue(near(normal, 128, 0, 128), "a half mask over Normal gives $normal")
    }
}
