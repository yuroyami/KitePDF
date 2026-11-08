package io.github.yuroyami.kitepdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A removed page leaves the rewrite even when a bookmark, a link or /OpenAction names it (ISO 32000-1, 12.3.2; #1016). */
class RemovedPageReachTest {

    private fun pdf(catalogExtra: String, pageOneExtra: String = "") = TestPdf.build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R /Outlines 8 0 R $catalogExtra >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 7 0 R >> >> /Contents 5 0 R $pageOneExtra >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 7 0 R >> >> /Contents 6 0 R >>",
            TestPdf.stream("BT /F1 24 Tf 72 700 Td (Page one stays) Tj ET"),
            TestPdf.stream("BT /F1 24 Tf 72 700 Td (REMOVEDPAGESECRET) Tj ET"),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Type /Outlines /First 9 0 R /Last 9 0 R /Count 1 >>",
            "<< /Title (Chapter two) /Parent 8 0 R /Dest [4 0 R /Fit] >>",
        ),
    )

    @Test
    fun a_bookmark_a_link_or_an_open_action_does_not_keep_the_page() {
        for (bytes in listOf(
            pdf(""),
            pdf("/OpenAction [4 0 R /Fit]"),
            pdf("", pageOneExtra = "/Annots [<< /Type /Annot /Subtype /Link /Rect [0 0 10 10] /Dest [4 0 R /Fit] >>]"),
        )) {
            val doc = PdfDocument.open(bytes)
            val out = doc.edit().apply { removePage(doc.pages[1]) }.saveRewritten()
            assertFalse("REMOVEDPAGESECRET" in out.decodeToString())
            val after = PdfDocument.open(out)
            assertEquals(1, after.pageCount)
            assertTrue("Page one stays" in after.pages[0].extractText())
            assertEquals("Chapter two", after.outline.single().title, "the bookmark stays, with no destination")
        }
    }
}
