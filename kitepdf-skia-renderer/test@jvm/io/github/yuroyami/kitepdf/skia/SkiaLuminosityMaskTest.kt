package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.PdfDocument
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A luminosity soft mask weighs a colour as ISO 32000-1, 10.3.2 does, 0.30 R + 0.59 G + 0.11 B, on
 * the Skia canvas as on every other one, not with Skia's Rec. 709 luma filter (#799).
 */
class SkiaLuminosityMaskTest {

    /** A black page under a luminosity mask whose group fills the page with [color]. */
    private fun maskedPage(color: String): ByteArray {
        val group = "$color 0 0 200 200 re f"
        val content = "q /GS1 gs 0 g 0 0 200 200 re f Q"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R " +
                "/Resources << /ExtGState << /GS1 << /SMask << /S /Luminosity /G 5 0 R >> >> >> >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /XObject /Subtype /Form /BBox [0 0 200 200] /Group << /S /Transparency /CS /DeviceRGB >> " +
                "/Length ${group.length} >>\nstream\n$group\nendstream",
        )
        val out = StringBuilder("%PDF-1.7\n")
        val offsets = objects.mapIndexed { i, body -> out.length.also { out.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) out.append("${o.toString().padStart(10, '0')} 00000 n \n")
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().encodeToByteArray()
    }

    @Test
    fun a_coloured_mask_group_gates_by_the_pdf_weights() {
        // 255 * (1 - mask) at the centre; Poppler 24.02 draws 178, 105 and 227.
        for ((color, expected) in listOf("1 0 0 rg" to 178, "0 1 0 rg" to 105, "0 0 1 rg" to 227)) {
            val png = PdfPageRasterizer.encodeToPng(PdfDocument.open(maskedPage(color)).pages[0], 1.0)
            val image = ImageIO.read(ByteArrayInputStream(png))
            val level = image.getRGB(image.width / 2, image.height / 2) shr 8 and 0xFF
            assertTrue(abs(level - expected) <= 2, "$color: centre is $level, expected about $expected")
        }
    }
}
