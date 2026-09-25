package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.difftest.PdfiumOracle
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * A Japan1 font that is neither embedded nor mapped to Unicode, through a Shift-JIS, a
 * Unicode-keyed and an Identity CMap. Each line reads あいう (#309).
 *
 * Each engine draws such a font with a substitute face of its own choice (ISO 32000-1,
 * 9.8.1), and the face depends on the fonts a machine has. So the pixels are not compared
 * with MuPDF and PDFium: each line must show, and the text must match PDFium's.
 */
class NonEmbeddedCjkTextTest {

    @Test
    fun each_line_draws_and_extracts_its_text() {
        val page = PdfDocument.open(PDF).pages[0]
        assertEquals(listOf("あいう", "あいう", "あいう"), page.extractText().lines().filter { it.isNotBlank() })
        val image = AwtPdfRasterizer.renderToImage(page)
        for (baseline in BASELINES) {
            // The band of one line at 72 dpi, from its ascent down to a little below its baseline.
            val dark = (300 - baseline - 28 until 300 - baseline + 4).sumOf { y ->
                (20 until 120).count { x -> (image.getRGB(x, y) and 0xFF) < 128 }
            }
            assertTrue(dark > 50, "the line at y $baseline draws, $dark dark pixels")
        }
    }

    @Test
    fun the_text_matches_pdfium() {
        assumeTrue("PDFium not found, skipping: ${PdfiumOracle.unavailableReason}", PdfiumOracle.available)
        val file = File.createTempFile("kite-cjk", ".pdf").apply { deleteOnExit(); writeBytes(PDF) }
        val pdfium = PdfiumOracle.extractText(file, 1, 1)[1]
        assertTrue(pdfium is PdfiumOracle.TextResult.Success, "PDFium extracts the text: $pdfium")
        val kite = PdfDocument.open(PDF).pages[0].extractText()
        assertEquals("", ParityHarness.missingCharacters(pdfium.text, kite), "characters PDFium extracts and KitePDF does not")
    }

    private companion object {
        val BASELINES = listOf(220, 150, 80)

        val PDF: ByteArray = run {
            val content = "BT /C1 28 Tf 20 220 Td <82a082a282a4> Tj ET BT /C2 28 Tf 20 150 Td <304230443046> Tj ET " +
                "BT /C3 28 Tf 20 80 Td <020e020f0210> Tj ET"
            val objects = listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /C1 5 0 R /C2 6 0 R /C3 7 0 R >> >> /Contents 4 0 R >>",
                "<< /Length ${content.length} >>\nstream\n$content\nendstream",
                "<< /Type /Font /Subtype /Type0 /BaseFont /MS-Mincho /Encoding /90ms-RKSJ-H /DescendantFonts [8 0 R] >>",
                "<< /Type /Font /Subtype /Type0 /BaseFont /MS-Mincho /Encoding /UniJIS-UCS2-H /DescendantFonts [8 0 R] >>",
                "<< /Type /Font /Subtype /Type0 /BaseFont /MS-Mincho /Encoding /Identity-H /DescendantFonts [8 0 R] >>",
                "<< /Type /Font /Subtype /CIDFontType0 /BaseFont /MS-Mincho /CIDSystemInfo << /Registry (Adobe) " +
                    "/Ordering (Japan1) /Supplement 2 >> /FontDescriptor 9 0 R /DW 1000 >>",
                "<< /Type /FontDescriptor /FontName /MS-Mincho /Flags 6 /FontBBox [0 -141 1000 859] /ItalicAngle 0 " +
                    "/Ascent 859 /Descent -141 /CapHeight 700 /StemV 80 >>",
            )
            val out = ByteArrayOutputStream()
            out.write("%PDF-1.7\n".toByteArray())
            val offsets = objects.mapIndexed { i, body -> out.size().also { out.write("${i + 1} 0 obj\n$body\nendobj\n".toByteArray()) } }
            val xref = out.size()
            out.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray())
            for (o in offsets) out.write("${o.toString().padStart(10, '0')} 00000 n \n".toByteArray())
            out.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
            out.toByteArray()
        }
    }
}
