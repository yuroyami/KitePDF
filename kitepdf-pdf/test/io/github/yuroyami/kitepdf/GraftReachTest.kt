package io.github.yuroyami.kitepdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A grafted page brings what it draws or shows, not the other pages of its source (ISO 32000-1, 12.3.2.2; #1015). */
class GraftReachTest {

    private fun source(annot: String, extra: List<Any> = emptyList()) = TestPdf.build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 9 0 R >> >> /Contents 6 0 R /Annots [10 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 9 0 R >> >> /Contents 7 0 R >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 9 0 R >> >> /Contents 8 0 R /Annots [12 0 R] >>",
            TestPdf.stream("BT /F1 24 Tf 72 700 Td (SOURCEPAGEONE) Tj ET"),
            TestPdf.stream("BT /F1 24 Tf 72 700 Td (SOURCEPAGETWOSECRET) Tj ET"),
            TestPdf.stream("BT /F1 24 Tf 72 700 Td (SOURCEPAGETHREESECRET) Tj ET"),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            annot,
        ) + extra,
    )

    private fun grafted(src: ByteArray): ByteArray {
        val target = PdfDocument.open(TestPdf.onePage("BT /F1 12 Tf 72 72 Td (Target) Tj ET", resources = "/Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >>"))
        val editor = target.edit()
        val ref = editor.graftPage(PdfDocument.open(src), 0)
        editor.insertPageAt(1, ref)
        return editor.saveRewritten()
    }

    @Test
    fun a_link_to_another_page_does_not_pull_the_source_in() {
        val out = grafted(source("<< /Type /Annot /Subtype /Link /Rect [72 690 300 720] /Border [0 0 0] /Dest [5 0 R /Fit] >>", listOf("<< >>", "<< >>")))
        val text = out.decodeToString()
        assertFalse("SOURCEPAGETWOSECRET" in text || "SOURCEPAGETHREESECRET" in text, "the other source pages stayed behind")
        assertTrue("SOURCEPAGEONE" in text)
        assertEquals(2, PdfDocument.open(out).pageCount)
    }

    @Test
    fun a_widget_becomes_a_field_of_its_own() {
        val out = grafted(
            source(
                "<< /Type /Annot /Subtype /Widget /Parent 11 0 R /P 3 0 R /Rect [72 600 300 620] >>",
                listOf(
                    "<< /FT /Tx /T (Name) /V (Jane) /Kids [10 0 R 12 0 R] >>",
                    "<< /Type /Annot /Subtype /Widget /Parent 11 0 R /P 5 0 R /Rect [72 600 300 620] >>",
                ),
            ),
        )
        val text = out.decodeToString()
        assertFalse("SOURCEPAGETHREESECRET" in text, "the field's other widget and its page stayed behind")
        val annot = PdfDocument.open(out).pages[1].annotations.single()
        assertEquals("Widget", annot.raw.getName("Subtype"))
        assertTrue("Jane" in text, "the widget keeps its field's value")
    }
}
