package io.github.yuroyami.kitepdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The decoded content and the text of a page stay for the pages used last only, and a drop call
 * frees them: each page kept both for the life of the document, so one search through a 1,000
 * page book outgrew a 192 MB heap (#819).
 */
class PageCacheBudgetTest {

    private fun book(pages: Int): ByteArray {
        val objects = ArrayList<Any>()
        objects += "<< /Type /Catalog /Pages 2 0 R >>"
        objects += "<< /Type /Pages /Kids [${(0 until pages).joinToString(" ") { "${4 + 2 * it} 0 R" }}] /Count $pages >>"
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        for (i in 0 until pages) {
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << /Font << /F1 3 0 R >> >> /Contents ${5 + 2 * i} 0 R >>"
            objects += TestPdf.stream("BT /F1 12 Tf 20 100 Td (page $i) Tj ET")
        }
        return TestPdf.build(objects)
    }

    @Test
    fun a_pass_over_every_page_keeps_the_text_of_the_last_pages_only() {
        val doc = PdfDocument.open(book(100))
        for (page in doc.pages) assertTrue("page ${page.index}" in page.structuredText.plainText, "page ${page.index}")
        assertTrue(doc.pagesHoldingCaches <= 32, "${doc.pagesHoldingCaches} pages keep their text")
        // A page whose text left reads it again.
        assertTrue("page 0" in doc.pages[0].structuredText.plainText)
        doc.dropPageCaches()
        assertEquals(0, doc.pagesHoldingCaches)
        assertTrue("page 5" in doc.pages[5].extractText())
    }
}
