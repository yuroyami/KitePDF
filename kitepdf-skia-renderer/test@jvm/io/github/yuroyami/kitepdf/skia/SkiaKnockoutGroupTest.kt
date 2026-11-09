package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.KitePDF
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A group nested in a knockout group is one element of it, and replaces the earlier paints of its
 * parent where it draws (ISO 32000-1, 11.4.4 and 11.4.6, #756).
 */
class SkiaKnockoutGroupTest {

    /** A 100 by 100 page that draws a knockout group: a red square, then a half-transparent blue element. */
    private fun page(nested: Boolean): ByteArray {
        val inner = "0 0 1 rg 0 0 100 100 re f"
        val outer = "1 0 0 rg 0 0 100 100 re f /GA gs " + if (nested) "/In Do" else inner
        // A stream is its dictionary and its data.
        val objects: List<Any> = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << /XObject << /Out 5 0 R >> >> /Contents 4 0 R >>",
            "" to "/Out Do",
            ("/Type /XObject /Subtype /Form /BBox [0 0 100 100] /Group << /S /Transparency /I true /K true >> " +
                "/Resources << /ExtGState << /GA << /ca 0.5 >> >> /XObject << /In 6 0 R >> >>") to outer,
            "/Type /XObject /Subtype /Form /BBox [0 0 100 100] /Group << /S /Transparency /I true >>" to inner,
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

    private fun centre(nested: Boolean): Triple<Int, Int, Int> {
        val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(PdfPageRasterizer.encodeToPng(KitePDF.open(page(nested)).pages[0], 1.0)))
        val p = img.getRGB(50, 50)
        return Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
    }

    @Test
    fun a_nested_group_knocks_out_the_paints_before_it() {
        // A plain fill in the knockout group replaces the red: half-transparent blue over white.
        val control = centre(nested = false)
        val (r, g, b) = centre(nested = true)
        assertTrue(g > 100, "the red square shows through the nested group: ($r, $g, $b), the fill gives $control")
        assertTrue(kotlin.math.abs(r - control.first) <= 3 && kotlin.math.abs(g - control.second) <= 3 && kotlin.math.abs(b - control.third) <= 3,
            "the nested group gives ($r, $g, $b), the fill $control")
    }
}
