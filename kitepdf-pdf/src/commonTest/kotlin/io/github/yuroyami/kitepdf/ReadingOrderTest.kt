package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.KiteRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A PDF page reads in the order of its structure tree when it is tagged, with the role of each
 * element, and in layout order when it is not (#208).
 */
class ReadingOrderTest {

    /** A PDF of [objects], numbered from 1. A body that starts with `STREAM:` is a stream. */
    private fun pdf(vararg objects: String): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = ArrayList<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())
        w("%PDF-1.7\n")
        for ((i, body) in objects.withIndex()) {
            offsets += buf.size()
            if (body.startsWith("STREAM:")) {
                val payload = body.removePrefix("STREAM:").encodeToByteArray()
                w("${i + 1} 0 obj\n<< /Length ${payload.size} >>\nstream\n")
                buf.append(payload)
                w("\nendstream\nendobj\n")
            } else {
                w("${i + 1} 0 obj\n$body\nendobj\n")
            }
        }
        val xref = buf.size()
        w("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }

    /**
     * Two tagged pages. The first draws its content in another order than its structure names it,
     * with an artifact and text that no element names.
     */
    private fun tagged(): ByteArray = pdf(
        /* 1 */ "<< /Type /Catalog /Pages 2 0 R /StructTreeRoot 6 0 R /MarkInfo << /Marked true >> /Lang (en-US) >>",
        /* 2 */ "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 400 400] >>",
        /* 3 */ "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 5 0 R >> /Properties << /MC9 << /MCID 5 >> >> >> /Contents 12 0 R >>",
        /* 4 */ "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 5 0 R >> >> /Contents 13 0 R >>",
        /* 5 */ "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        /* 6 */ "<< /Type /StructTreeRoot /K 7 0 R /RoleMap << /Heading1 /H1 /Footnote /Note >> >>",
        /* 7 */ "<< /Type /StructElem /S /Document /P 6 0 R /Pg 3 0 R /K [8 0 R 9 0 R 10 0 R 11 0 R 14 0 R 15 0 R 20 0 R 16 0 R] >>",
        /* 8 */ "<< /Type /StructElem /S /Heading1 /P 7 0 R /Pg 3 0 R /K 1 >>",
        /* 9 */ "<< /Type /StructElem /S /P /P 7 0 R /Pg 3 0 R /K [0 2] >>",
        /* 10 */ "<< /Type /StructElem /S /Figure /P 7 0 R /Pg 3 0 R /Alt (A red square) /K 3 >>",
        /* 11 */ "<< /Type /StructElem /S /L /P 7 0 R /Pg 3 0 R /K 17 0 R >>",
        /* 12 */ "STREAM:" +
            "/P <</MCID 2>> BDC BT /F1 12 Tf 50 300 Td (second part.) Tj ET EMC\n" +
            "/H1 <</MCID 1>> BDC BT /F1 24 Tf 50 350 Td (The title) Tj ET EMC\n" +
            "/Artifact BMC BT /F1 8 Tf 50 20 Td (Page 1) Tj ET EMC\n" +
            "/P <</MCID 0>> BDC BT /F1 12 Tf 50 320 Td (First part,) Tj ET EMC\n" +
            "/Figure <</MCID 3>> BDC 1 0 0 rg 50 200 40 40 re f EMC\n" +
            "/Lbl <</MCID 4>> BDC BT /F1 12 Tf 50 150 Td (1.) Tj ET EMC\n" +
            "/LBody /MC9 BDC BT /F1 12 Tf 70 150 Td (First item) Tj ET EMC\n" +
            "/Span <</MCID 6>> BDC BT /F1 12 Tf 50 120 Td (X) Tj ET EMC\n" +
            "/Note <</MCID 7>> BDC BT /F1 10 Tf 50 60 Td (A note.) Tj ET EMC\n" +
            "/P <</MCID 8>> BDC BT /F1 12 Tf 50 40 Td (Runs on) Tj ET EMC\n" +
            "BT /F1 12 Tf 50 100 Td (Untagged words) Tj ET",
        /* 13 */ "STREAM:/P <</MCID 0>> BDC BT /F1 12 Tf 50 300 Td (Second page text.) Tj ET EMC\n" +
            "/P <</MCID 1>> BDC BT /F1 12 Tf 50 350 Td (to the next page.) Tj ET EMC",
        /* 14 */ "<< /Type /StructElem /S /P /P 7 0 R /Pg 3 0 R /K 21 0 R >>",
        /* 15 */ "<< /Type /StructElem /S /Footnote /P 7 0 R /Pg 3 0 R /K 7 >>",
        /* 16 */ "<< /Type /StructElem /S /P /P 7 0 R /Pg 4 0 R /K 0 >>",
        /* 17 */ "<< /Type /StructElem /S /LI /P 11 0 R /Pg 3 0 R /K [18 0 R 19 0 R] >>",
        /* 18 */ "<< /Type /StructElem /S /Lbl /P 17 0 R /Pg 3 0 R /K 4 >>",
        /* 19 */ "<< /Type /StructElem /S /LBody /P 17 0 R /K << /Type /MCR /Pg 3 0 R /MCID 5 >> >>",
        // A paragraph that runs from the first page onto the second. The ids restart on each page.
        /* 20 */ "<< /Type /StructElem /S /P /P 7 0 R /K [<< /Type /MCR /Pg 3 0 R /MCID 8 >> << /Type /MCR /Pg 4 0 R /MCID 1 >>] >>",
        /* 21 */ "<< /Type /StructElem /S /Span /P 14 0 R /Pg 3 0 R /ActualText (fi) /K 6 >>",
    )

    @Test
    fun a_tagged_page_reads_in_the_order_of_its_structure_with_roles() {
        val doc = PdfDocument.open(tagged())
        val items = doc.pages[0].readingOrder()
        assertEquals(
            listOf("The title", "First part, second part.", "A red square", "1. First item", "fi", "A note.", "Runs on"),
            items.map { it.text },
        )
        assertEquals(
            listOf(KiteRole.HEADING, KiteRole.TEXT, KiteRole.IMAGE, KiteRole.LIST_ITEM, KiteRole.TEXT, KiteRole.TEXT, KiteRole.TEXT),
            items.map { it.role },
        )
        // The role map gives the standard type, and the item keeps the type the file names.
        assertEquals(1, items[0].headingLevel)
        assertEquals("Heading1", items[0].sourceType)
        assertEquals("Footnote", items[5].sourceType)
        assertEquals("en-US", items[0].language)
        // The artifact and the text that no element names stay out.
        assertTrue(items.none { "Page 1" in it.text || "Untagged" in it.text }, "$items")
        // Each page reads its own part of the paragraph that runs on, though the ids repeat.
        assertEquals(listOf("to the next page.", "Second page text."), doc.pages[1].readingOrder().map { it.text })
    }

    @Test
    fun a_page_without_tags_reads_its_text_blocks_in_layout_order() {
        val doc = PdfDocument.open(
            pdf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 400 400] >>",
                "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "STREAM:BT /F1 12 Tf 50 350 Td (Top line) Tj ET BT /F1 12 Tf 50 100 Td (Bottom line) Tj ET",
            ),
        )
        val items = doc.pages[0].readingOrder()
        assertEquals(listOf("Top line", "Bottom line"), items.map { it.text })
        assertTrue(items.all { it.role == KiteRole.TEXT })
    }
}
