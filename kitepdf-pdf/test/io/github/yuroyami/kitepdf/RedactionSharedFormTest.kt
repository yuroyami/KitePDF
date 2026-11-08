package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Redacting a form on one page leaves every other place that draws it as it was (ISO 32000-1, 8.10.1; #1007). */
class RedactionSharedFormTest {

    @Test
    fun the_other_page_keeps_drawing_the_original_form() {
        val pdf = TestPdf.build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 5 0 R /Resources << /XObject << /Fx 6 0 R >> /Font << /F1 7 0 R >> >> >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 8 0 R /Resources << /XObject << /Fx 6 0 R >> /Font << /F1 7 0 R >> >> >>",
                TestPdf.stream("/Fx Do BT /F1 24 Tf 72 100 Td (PageOneFooter) Tj ET"),
                TestPdf.stream("BT /F1 24 Tf 72 700 Td (Letterhead) Tj ET", "/Type /XObject /Subtype /Form /BBox [0 0 612 792] /Resources << /Font << /F1 7 0 R >> >>"),
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                TestPdf.stream("/Fx Do BT /F1 24 Tf 72 100 Td (PageTwoFooter) Tj ET"),
            ),
        )
        val doc = PdfDocument.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(60.0, 690.0, 400.0, 730.0)) }.saveRewritten()
        val after = PdfDocument.open(out)
        val one = after.pages[0].extractText()
        val two = after.pages[1].extractText()
        assertFalse("Letterhead" in one, "page one lost the letterhead: $one")
        assertTrue("PageOneFooter" in one, one)
        assertTrue("Letterhead" in two, "page two, which nobody redacted, keeps it: $two")
    }
}
