package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test: build a tiny PDF in memory, parse it, and check that the
 * catalog/pages/text round-trip works. Exercises Lexer → Parser → XrefParser
 * → PdfDocument → ContentStreamParser → TextExtractor in one go.
 */
class DocumentTest {

    @Test
    fun parses_minimal_pdf_with_one_page_and_extracts_text() {
        val bytes = buildMinimalPdf("(Hello, KitePDF!)")
        val doc = KitePDF.open(bytes)

        assertEquals("1.4", doc.version)
        assertEquals(1, doc.pageCount)
        assertEquals(612.0, doc.pages[0].width)
        assertEquals(792.0, doc.pages[0].height)
        assertContains(doc.pages[0].extractText(), "Hello, KitePDF!")
    }

    @Test
    fun mediabox_inherited_from_parent_pages_node() {
        val bytes = buildMinimalPdf("(child)", omitMediaBoxOnPage = true, parentHasMediaBox = true)
        val doc = KitePDF.open(bytes)
        assertEquals(1, doc.pageCount)
        assertEquals(612.0, doc.pages[0].width)
    }

    @Test
    fun multiple_pages_are_enumerated_in_order() {
        val bytes = buildTwoPagesPdf()
        val doc = KitePDF.open(bytes)
        assertEquals(2, doc.pageCount)
        assertContains(doc.pages[0].extractText(), "first")
        assertContains(doc.pages[1].extractText(), "second")
    }

    @Test
    fun trailing_garbage_before_eof_is_tolerated() {
        val good = buildMinimalPdf("(stable)")
        val padded = ByteArrayBuilder().apply {
            append(good)
            append("\n% trailing comment\n".encodeToByteArray())
        }.toByteArray()
        val doc = KitePDF.open(padded)
        assertTrue(doc.pageCount == 1)
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    /** Tiny PDF emitter that keeps offsets correct as we tweak content. */
    private fun buildMinimalPdf(
        textShow: String,
        omitMediaBoxOnPage: Boolean = false,
        parentHasMediaBox: Boolean = false,
    ): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()

        fun write(s: String) = buf.append(s.encodeToByteArray())

        write("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        val pagesDict = buildString {
            append("<< /Type /Pages /Kids [3 0 R] /Count 1")
            if (parentHasMediaBox) append(" /MediaBox [0 0 612 792]")
            append(" >>")
        }
        write("2 0 obj\n$pagesDict\nendobj\n")
        offsets.add(buf.size())
        val pageDict = buildString {
            append("<< /Type /Page /Parent 2 0 R")
            if (!omitMediaBoxOnPage) append(" /MediaBox [0 0 612 792]")
            append(" /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>")
        }
        write("3 0 obj\n$pageDict\nendobj\n")
        offsets.add(buf.size())
        write("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        offsets.add(buf.size())
        val payload = "BT /F1 18 Tf 72 720 Td $textShow Tj ET".encodeToByteArray()
        write("5 0 obj\n<< /Length ${payload.size} >>\nstream\n")
        buf.append(payload)
        write("\nendstream\nendobj\n")
        val xref = buf.size()
        write("xref\n0 6\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }

    private fun buildTwoPagesPdf(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())

        write("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>\nendobj\n")
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 7 0 R >> >> /Contents 4 0 R >>\nendobj\n")
        offsets.add(buf.size())
        val p1 = "BT /F1 18 Tf 72 720 Td (the first page) Tj ET".encodeToByteArray()
        write("4 0 obj\n<< /Length ${p1.size} >>\nstream\n")
        buf.append(p1); write("\nendstream\nendobj\n")
        offsets.add(buf.size())
        write("5 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 7 0 R >> >> /Contents 6 0 R >>\nendobj\n")
        offsets.add(buf.size())
        val p2 = "BT /F1 18 Tf 72 720 Td (the second page) Tj ET".encodeToByteArray()
        write("6 0 obj\n<< /Length ${p2.size} >>\nstream\n")
        buf.append(p2); write("\nendstream\nendobj\n")
        offsets.add(buf.size())
        write("7 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")

        val xref = buf.size()
        write("xref\n0 8\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size 8 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
