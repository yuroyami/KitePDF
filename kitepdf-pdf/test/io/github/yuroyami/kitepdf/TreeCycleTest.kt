package io.github.yuroyami.kitepdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Name trees, number trees, the field tree and the page tree are trees (ISO 32000-1, 7.9.6, 7.9.7,
 * 12.7.3.1, 7.7.3.2). A node whose /Kids names itself once recursed until the stack ran out, and one
 * that names itself twice doubled the calls at each level (#964, #967). Each walk enters a node once.
 */
class TreeCycleTest {

    /** A one-page file whose catalog carries [catalogExtra], with object 6 being [obj6]. */
    private fun pdf(catalogExtra: String, obj6: String, pagesKids: String = "[3 0 R]"): ByteArray {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R $catalogExtra >>",
            "<< /Type /Pages /Kids $pagesKids /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>",
            "<< /Type /Outlines /First 5 0 R /Last 5 0 R /Count 1 >>",
            "<< /Title (Start) /Parent 4 0 R /Dest (start) >>",
            obj6,
        )
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, body -> sb.length.also { sb.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (off in offsets) sb.append("${off.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    private fun quickly(what: String, body: () -> Unit) {
        val start = TimeSource.Monotonic.markNow()
        body()
        assertTrue(start.elapsedNow().inWholeSeconds < 10, "$what took ${start.elapsedNow()}")
    }

    @Test
    fun aNodeThatNamesItselfEndsEveryWalk() {
        for (kids in listOf("[6 0 R]", "[6 0 R 6 0 R]")) {
            val node = "<< /Kids $kids >>"
            quickly("page labels $kids") {
                val doc = PdfDocument.open(pdf("/PageLabels 6 0 R", node))
                assertEquals("1", doc.pages[0].label)
            }
            quickly("destinations $kids") {
                val doc = PdfDocument.open(pdf("/Names << /Dests 6 0 R >> /Outlines 4 0 R", node))
                doc.outline
                doc.outlines
            }
            quickly("attachments and scripts $kids") {
                val doc = PdfDocument.open(pdf("/Names << /EmbeddedFiles 6 0 R /JavaScript 6 0 R >>", node))
                assertEquals(0, doc.attachments.size)
                assertEquals(0, doc.documentJavaScripts.size)
            }
            quickly("form fields $kids") {
                val doc = PdfDocument.open(pdf("/AcroForm << /Fields [6 0 R] >>", "<< /T (a) /FT /Tx /Kids $kids >>"))
                doc.formFields
            }
        }
    }
}
