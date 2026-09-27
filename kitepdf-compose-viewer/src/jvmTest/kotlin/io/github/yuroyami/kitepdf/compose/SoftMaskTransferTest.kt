package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A soft mask's transfer function maps each mask value before it gates the content (ISO
 * 32000-1, 11.6.5.2, Table 144). For `f(x) = x * x` a mask value of 0.5 gates at 0.25. The
 * Compose canvas used the straight line closest to the table and gated at about 0.33 (#414).
 */
class SoftMaskTransferTest {

    /**
     * A 100 x 100 page that fills black through a soft mask of [kind] with the transfer x * x.
     * The mask group is 0.5 everywhere: a black fill at alpha 0.5 for an alpha mask, a grey
     * of 0.5 for a luminosity mask.
     */
    private fun maskedPdf(kind: String): PdfDocument {
        val group = if (kind == "Alpha") "/GS2 gs 0 g 0 0 100 100 re f" else "0.5 g 0 0 100 100 re f"
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        add(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R " +
                "/Resources << /ExtGState << /GS1 << /SMask << /Type /Mask /S /$kind /G 5 0 R /TR 6 0 R >> >> >> >> >>",
        )
        val content = "/GS1 gs 0 g 0 0 100 100 re f"
        add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        add(
            "<< /Type /XObject /Subtype /Form /BBox [0 0 100 100] /Group << /S /Transparency /CS /DeviceRGB >> " +
                "/Resources << /ExtGState << /GS2 << /ca 0.5 >> >> >> /Length ${group.length} >>\nstream\n$group\nendstream",
        )
        add("<< /FunctionType 2 /Domain [0 1] /C0 [0] /C1 [1] /N 2 >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    private fun rasterizer(): KitePageRasterizer {
        val density = Density(1f)
        return KitePageRasterizer(density, LayoutDirection.Ltr, TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr))
    }

    @Test
    fun a_curved_transfer_gates_the_content_by_its_table() {
        for (kind in listOf("Alpha", "Luminosity")) {
            val page = maskedPdf(kind).pages[0]
            val pixel = rasterizer().rasterize(page, 100, 100).toPixelMap()[50, 50]
            // Black gated at 0.25 over white paper leaves 0.75. The line fit gave about 0.67.
            assertEquals(0.75f, pixel.red, 0.02f, "$kind mask: $pixel")
        }
    }
}
