package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A structure element whose content a redaction removes loses its /ActualText and /Alt (ISO 32000-1, 14.9; #1009). */
class RedactionStructureTextTest {

    @Test
    fun the_paragraphs_and_the_figures_text_go_and_the_footers_stays() {
        val pdf = TestPdf.build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R /MarkInfo << /Marked true >> /StructTreeRoot 7 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /StructParents 0 /Contents 4 0 R " +
                    "/Resources << /Font << /F1 5 0 R >> /XObject << /Im1 6 0 R >> >> >>",
                TestPdf.stream(
                    "/P << /MCID 0 >> BDC BT /F1 24 Tf 72 700 Td (Jane Doe 123-45-6789) Tj ET EMC\n" +
                        "/Figure << /MCID 1 >> BDC q 180 0 0 60 72 600 cm /Im1 Do Q EMC\n" +
                        "/P << /MCID 2 >> BDC BT /F1 24 Tf 72 100 Td (Footer stays) Tj ET EMC",
                ),
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                TestPdf.stream("FFFFFFFF", "/Type /XObject /Subtype /Image /Width 8 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8"),
                "<< /Type /StructTreeRoot /K 8 0 R /ParentTree 12 0 R >>",
                "<< /Type /StructElem /S /Document /P 7 0 R /K [9 0 R 10 0 R 11 0 R] >>",
                "<< /Type /StructElem /S /P /P 8 0 R /Pg 3 0 R /K 0 /ActualText (SECRET-ACTUAL) >>",
                "<< /Type /StructElem /S /Figure /P 8 0 R /Pg 3 0 R /K 1 /Alt (SECRET-ALT portrait) >>",
                "<< /Type /StructElem /S /P /P 8 0 R /Pg 3 0 R /K 2 /ActualText (Footer stays) >>",
                "<< /Nums [0 [9 0 R 10 0 R 11 0 R]] >>",
            ),
        )
        val doc = PdfDocument.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(60.0, 590.0, 400.0, 730.0)) }.saveRewritten()
        val text = out.decodeToString()
        assertFalse("SECRET-ACTUAL" in text, "the paragraph's text is gone")
        assertFalse("SECRET-ALT" in text, "the figure's description is gone")
        assertTrue("Footer stays" in PdfDocument.open(out).pages[0].readingOrder().joinToString(" ") { it.text })
    }
}
