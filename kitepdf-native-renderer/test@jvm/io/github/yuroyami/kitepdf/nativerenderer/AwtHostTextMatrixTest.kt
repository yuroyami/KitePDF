package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Host-font text takes the whole text matrix: a slant slants it and a reflection mirrors it, as
 * outline text does (ISO 32000-1, 9.4.4; #848). The canvas reduced the matrix to a rotation and
 * two lengths, so a slanted Verdana run drew the same pixels as an upright one 1.25 times as tall.
 */
class AwtHostTextMatrixTest {

    /** A 220 point square page that draws one H at 60 points in /F1 under [tm]. */
    private fun pdf(font: String, tm: String): ByteArray {
        val content = "BT /F1 60 Tf $tm Tm (H) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 220 220] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /Font /Subtype /TrueType /BaseFont /$font >>",
        )
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, body -> sb.length.also { sb.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** The dark pixels of the page, as a set of x, y pairs. */
    private fun ink(font: String, tm: String): Set<Pair<Int, Int>> {
        val image = AwtPdfRasterizer.renderToImage(PdfDocument.open(pdf(font, tm)).pages[0])
        val out = HashSet<Pair<Int, Int>>()
        for (y in 0 until image.height) for (x in 0 until image.width) if ((image.getRGB(x, y) and 0xFF) < 128) out += x to y
        return out
    }

    @Test
    fun a_slant_and_a_reflection_reach_host_font_text() {
        val tall = ink("Verdana", "1 0 0 1.25 60 100")
        assertTrue(tall.size > 300, "the H draws: ${tall.size} pixels")
        val slanted = ink("Verdana", "1 0 0.75 1 60 100")
        val mirrored = ink("Verdana", "1 0 0 -1.25 60 100")
        assertTrue((slanted - tall).size > 200, "the slanted H draws as the tall one: ${(slanted - tall).size} pixels differ")
        assertTrue((mirrored - tall).size > 200, "the mirrored H draws as the upright one: ${(mirrored - tall).size} pixels differ")
        // The mirror lies below the baseline, the upright H above it: 100 points up from the bottom is row 120.
        assertTrue(mirrored.all { it.second >= 115 }, "the mirrored H hangs from the baseline")
        assertTrue(tall.all { it.second <= 125 }, "the upright H stands on the baseline")
        // An upright run at 1.25 x draws as before.
        val wide = ink("Verdana", "1.25 0 0 1 60 100")
        assertTrue(wide.maxOf { it.first } - wide.minOf { it.first } > tall.maxOf { it.first } - tall.minOf { it.first }, "a wide H is wider")
    }
}
