package io.github.yuroyami.kitepdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `/Contents` that is a reference to an array of streams (ISO 32000-1, 7.7.3.3, Table 30: any
 * value may be indirect) joins the streams, as a direct array does (#1026).
 */
class PageContentsReferencedArrayTest {

    private val pdf = TestPdf.build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Contents 4 0 R " +
                "/Resources << /Font << /F1 7 0 R >> >> >>",
            "[5 0 R 6 0 R]",
            TestPdf.stream("BT /F1 12 Tf 20 60 Td (Hello) Tj ET"),
            TestPdf.stream("BT /F1 12 Tf 20 40 Td (World) Tj ET"),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        ),
    )

    @Test
    fun a_referenced_array_joins_its_streams() {
        val page = PdfDocument.open(pdf).pages.single()
        assertEquals(
            "BT /F1 12 Tf 20 60 Td (Hello) Tj ET\nBT /F1 12 Tf 20 40 Td (World) Tj ET",
            page.contentBytes.decodeToString(),
        )
        val text = page.extractText()
        assertTrue("Hello" in text && "World" in text, text)
    }

    @Test
    fun an_edit_starts_from_both_streams_of_a_referenced_array() {
        val doc = PdfDocument.open(pdf)
        val out = doc.edit().apply { editPageContent(doc.pages[0]) { it } }.saveIncremental()
        val text = PdfDocument.open(out).pages.single().extractText()
        assertTrue("Hello" in text && "World" in text, text)
    }
}
