package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.KiteRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A PDF page reads in the order of its structure tree when it is tagged, with the role of each
 * element, and in layout order when it is not (#208).
 */
class ReadingOrderTest {

    /** A PDF of [objects], numbered from 1. A body `STREAM<entries>:<data>` is a stream with those dictionary entries. */
    private fun pdf(vararg objects: String): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = ArrayList<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())
        w("%PDF-1.7\n")
        for ((i, body) in objects.withIndex()) {
            offsets += buf.size()
            if (body.startsWith("STREAM")) {
                val entries = body.substringBefore(':').removePrefix("STREAM")
                val payload = body.substringAfter(':').encodeToByteArray()
                w("${i + 1} 0 obj\n<<$entries /Length ${payload.size} >>\nstream\n")
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
        /* 10 */ "<< /Type /StructElem /S /Figure /P 7 0 R /Pg 3 0 R /Alt (A red square) /A << /O /Layout /BBox [50 200 90 240] >> /K 3 >>",
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
        // The heading's box, in display space: the 400 point page runs down from the top, and the
        // 24 point run on the baseline at 350 reaches 0.8 of its size up and 0.2 down.
        val box = assertNotNullBox(items[0].bounds)
        assertEquals(50.0, box.left, 0.5)
        assertEquals(400.0 - 350.0 - 24.0 * 0.8, box.bottom, 0.5)
        assertEquals(400.0 - 350.0 + 24.0 * 0.2, box.top, 0.5)
        // The paragraph in two runs holds both of them.
        val paragraph = assertNotNullBox(items[1].bounds)
        assertEquals(400.0 - 320.0 - 12.0 * 0.8, paragraph.bottom, 0.5)
        assertEquals(400.0 - 300.0 + 12.0 * 0.2, paragraph.top, 0.5)
        // The figure's box is the one its layout attributes give.
        assertEquals(KiteRectangle(50.0, 160.0, 90.0, 200.0), items[2].bounds)
        // Replacement text keeps the box of the run it replaces.
        val replaced = assertNotNullBox(items[4].bounds)
        assertEquals(400.0 - 120.0 - 12.0 * 0.8, replaced.bottom, 0.5)
        assertEquals(400.0 - 120.0 + 12.0 * 0.2, replaced.top, 0.5)
        // Each page reads its own part of the paragraph that runs on, though the ids repeat.
        assertEquals(listOf("to the next page.", "Second page text."), doc.pages[1].readingOrder().map { it.text })
    }

    private fun assertNotNullBox(box: KiteRectangle?): KiteRectangle = assertNotNull(box, "the item has no bounds")

    /**
     * A one-page tagged PDF of 400 x 400 points with [content], the structure tree [root] as
     * object 5, and [objects] numbered from 6. [resources] adds entries to the page's resources.
     */
    private fun taggedPage(content: String, root: String, vararg objects: String, resources: String = ""): PdfDocument = PdfDocument.open(
        pdf(
            "<< /Type /Catalog /Pages 2 0 R /StructTreeRoot 5 0 R /MarkInfo << /Marked true >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 400 400] >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> $resources >> /Contents ${6 + objects.size} 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            root,
            *objects,
            "STREAM:$content",
        ),
    )

    @Test
    fun a_figure_takes_its_box_from_an_attribute_class_or_else_from_what_it_paints() {
        val doc = taggedPage(
            "/Figure <</MCID 0>> BDC 0 0 1 rg 100 50 60 40 re f EMC\n/Figure <</MCID 1>> BDC 0 1 0 rg 200 50 20 20 re f EMC",
            "<< /Type /StructTreeRoot /K [6 0 R 7 0 R] /ClassMap << /Pic [<< /O /List /ListNumbering /None >> << /O /Layout /BBox [90 40 170 100] >>] >> >>",
            "<< /Type /StructElem /S /Figure /Pg 3 0 R /Alt (A chart) /C [/Other 0 /Pic 1] /K 0 >>",
            "<< /Type /StructElem /S /Figure /Pg 3 0 R /Alt (A table box) /A [<< /O /Table /BBox [1 1 2 2] >> 0] /K 1 >>",
        )
        val items = doc.pages[0].readingOrder()
        // A page of figures alone is tagged too.
        assertEquals(listOf("A chart", "A table box"), items.map { it.text })
        assertEquals(KiteRectangle(90.0, 300.0, 170.0, 360.0), items[0].bounds)
        // A BBox is a layout attribute, so a table's one says nothing, and the box is what the figure paints.
        assertEquals(KiteRectangle(200.0, 330.0, 220.0, 350.0), items[1].bounds)
    }

    @Test
    fun content_drawn_through_a_form_belongs_to_the_id_the_page_put_around_it() {
        val doc = taggedPage(
            "/P <</MCID 0>> BDC BT /F1 12 Tf 50 350 Td (Page text) Tj ET EMC\n" +
                "/P <</MCID 1>> BDC /Fm0 Do EMC\n" +
                // The figure fills the page through a clip of 50 x 50 points.
                "/Figure <</MCID 2>> BDC q 100 100 50 50 re W n 0 0 1 rg 0 0 400 400 re f Q EMC",
            "<< /Type /StructTreeRoot /K [6 0 R 7 0 R 8 0 R] >>",
            "<< /Type /StructElem /S /P /Pg 3 0 R /K 0 >>",
            "<< /Type /StructElem /S /P /Pg 3 0 R /K 1 >>",
            "<< /Type /StructElem /S /Figure /Pg 3 0 R /Alt (A square) /K 2 >>",
            // The form's own id 0 names a sequence of the form's stream, not the page's first paragraph.
            "STREAM /Type /XObject /Subtype /Form /BBox [0 0 400 400] /Resources << /Font << /F1 4 0 R >> >>:" +
                "/Span <</MCID 0>> BDC BT /F1 12 Tf 50 300 Td (Inside a form) Tj ET EMC",
            resources = "/XObject << /Fm0 9 0 R >>",
        )
        val items = doc.pages[0].readingOrder()
        assertEquals(listOf("Page text", "Inside a form", "A square"), items.map { it.text })
        assertEquals(400.0 - 300.0 - 12.0 * 0.8, assertNotNullBox(items[1].bounds).bottom, 0.5)
        assertEquals(KiteRectangle(100.0, 250.0, 150.0, 300.0), items[2].bounds)
    }

    @Test
    fun text_between_blocks_takes_the_box_of_its_runs() {
        val doc = taggedPage(
            "/Span <</MCID 0>> BDC BT /F1 12 Tf 50 300 Td (Loose) Tj 150 0 Td (words) Tj ET EMC\n" +
                "/Span <</MCID 1>> BDC BT /F1 12 Tf 50 280 Td (X) Tj ET EMC\n" +
                "/Span <</MCID 2>> BDC BT /F1 12 Tf 200 100 Td (Later) Tj ET EMC",
            "<< /Type /StructTreeRoot /K [6 0 R 8 0 R] >>",
            "<< /Type /StructElem /S /Div /Pg 3 0 R /K [0 7 0 R] >>",
            "<< /Type /StructElem /S /Span /Pg 3 0 R /ActualText (Replaced) /K 1 >>",
            "<< /Type /StructElem /S /Div /Pg 3 0 R /K 2 >>",
        )
        val items = doc.pages[0].readingOrder()
        assertEquals(2, items.size, "$items")
        assertTrue("Loose words" in items[0].text && "Replaced" in items[0].text, "$items")
        // The two loose runs and the replaced one, from the baseline at 300 down to the one at 280.
        val first = assertNotNullBox(items[0].bounds)
        assertEquals(50.0, first.left, 0.5)
        assertTrue(first.right > 220.0, "the box reaches the second run: $first")
        assertEquals(400.0 - 300.0 - 12.0 * 0.8, first.bottom, 0.5)
        assertEquals(400.0 - 280.0 + 12.0 * 0.2, first.top, 0.5)
        // The next block starts a box of its own.
        val second = assertNotNullBox(items[1].bounds)
        assertEquals(200.0, second.left, 0.5)
        assertEquals(400.0 - 100.0 - 12.0 * 0.8, second.bottom, 0.5)
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
        // Each block's box comes from its lines, the top one nearer the top of the page.
        assertTrue(assertNotNullBox(items[0].bounds).bottom < assertNotNullBox(items[1].bounds).bottom)
    }
}
