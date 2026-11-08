package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.PdfEditor
import io.github.yuroyami.kitepdf.writer.PdfOutlineItem
import io.github.yuroyami.kitepdf.writer.PdfPageLabel
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The editor writes attachments, an outline, page labels and XMP, and they read back (#921). */
class DocumentPartsTest {

    private fun fivePages(): ByteArray = PdfBuilder().apply {
        for (i in 1..5) page(200.0, 200.0) { text(StandardFont.Helvetica, 12.0, 20.0, 100.0, "Page $i") }
    }.build()

    /** [edit] saved both ways, each opened again. */
    private fun bothSaves(edit: PdfEditor.() -> Unit): List<PdfDocument> {
        val doc = PdfDocument.open(fivePages())
        return listOf(doc.edit().apply(edit).saveIncremental(), doc.edit().apply(edit).saveRewritten()).map { PdfDocument.open(it) }
    }

    @Test
    fun attachments_are_added_replaced_and_removed() {
        val xml = "<invoice>42</invoice>".encodeToByteArray()
        for (doc in bothSaves {
            setAttachment("notes.txt", "first".encodeToByteArray(), mimeType = "text/plain")
            setAttachment("factur-x.xml", xml, mimeType = "text/xml", description = "The invoice", relationship = "Data")
            setAttachment("notes.txt", "second".encodeToByteArray(), mimeType = "text/plain")
        }) {
            val byName = doc.attachments.associateBy { it.name }
            assertEquals(setOf("factur-x.xml", "notes.txt"), byName.keys)
            assertEquals("second", byName.getValue("notes.txt").bytes.decodeToString())
            val invoice = byName.getValue("factur-x.xml")
            assertContentEquals(xml, invoice.bytes)
            assertEquals("text/xml", invoice.mimeType)
            assertEquals("The invoice", invoice.description)
            assertEquals(xml.size.toLong(), invoice.size)
            val af = assertNotNull(doc.catalog.getArray("AF", doc))
            assertEquals(1, af.size, "only the associated file is in /AF")
        }
        val doc = PdfDocument.open(fivePages())
        val once = PdfDocument.open(doc.edit().apply { setAttachment("a.bin", byteArrayOf(1), relationship = "Source") }.saveRewritten())
        val removed = PdfDocument.open(once.edit().apply { removeAttachment("a.bin") }.saveRewritten())
        assertEquals(emptyList(), removed.attachments)
        assertEquals(null, removed.catalog["AF"])
    }

    @Test
    fun an_outline_is_built_with_its_links() {
        val items = listOf(
            PdfOutlineItem("Front matter", pageIndex = 0),
            PdfOutlineItem(
                "Chapter 1", pageIndex = 2, top = 180.0,
                children = listOf(PdfOutlineItem("Section 1.1", pageIndex = 3), PdfOutlineItem("Section 1.2", pageIndex = 4)),
            ),
            PdfOutlineItem("Closed", pageIndex = 4, open = false, children = listOf(PdfOutlineItem("Hidden", pageIndex = 4))),
        )
        for (doc in bothSaves { setOutline(items) }) {
            val outline = doc.outlines
            assertEquals(listOf("Front matter", "Chapter 1", "Closed"), outline.map { it.title })
            assertEquals(listOf("Section 1.1", "Section 1.2"), outline[1].children.map { it.title })
            assertEquals(2, outline[1].count)
            assertEquals(-1, outline[2].count)
            assertEquals(2, doc.resolveDestination(assertNotNull(outline[1].rawDestination))?.pageIndex)
            assertEquals(3, doc.resolveDestination(assertNotNull(outline[1].children[0].rawDestination))?.pageIndex)
            assertEquals(5L, doc.catalog.getDict("Outlines", doc)?.getInt("Count"), "three items and the two open children")
        }
    }

    @Test
    fun page_labels_read_back() {
        val labels = listOf(
            PdfPageLabel(0, PdfPageLabel.Style.LowercaseRoman),
            PdfPageLabel(2, PdfPageLabel.Style.Decimal),
            PdfPageLabel(4, PdfPageLabel.Style.UppercaseLetters, prefix = "Appendix ", start = 1),
        )
        for (doc in bothSaves { setPageLabels(labels) }) {
            assertEquals(listOf("i", "ii", "1", "2", "Appendix A"), doc.pages.map { it.label })
        }
        val doc = PdfDocument.open(fivePages())
        assertFailsWith<IllegalArgumentException> { doc.edit().setPageLabels(listOf(PdfPageLabel(1))) }
    }

    @Test
    fun xmp_is_set_and_follows_set_info() {
        val packet = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title><rdf:Alt><rdf:li xml:lang="x-default">Draft</rdf:li></rdf:Alt></dc:title></rdf:Description>""" +
            """</rdf:RDF></x:xmpmeta><?xpacket end="w"?>"""
        for (doc in bothSaves { setXmpMetadata(packet) }) {
            assertEquals("Draft", doc.xmp?.title)
        }
        val doc = PdfDocument.open(fivePages())
        val out = PdfDocument.open(doc.edit().apply { setXmpMetadata(packet); setInfo(title = "Final") }.saveRewritten())
        assertEquals("Final", out.xmp?.title)
    }

    @Test
    fun the_builder_writes_the_same_parts() {
        val pdf = PdfBuilder()
            .page(200.0, 200.0) { text(StandardFont.Helvetica, 12.0, 20.0, 100.0, "Cover") }
            .page(200.0, 200.0) { text(StandardFont.Helvetica, 12.0, 20.0, 100.0, "Body") }
            .attach("data.csv", "a,b".encodeToByteArray(), mimeType = "text/csv")
            .outline(listOf(PdfOutlineItem("Body", pageIndex = 1)))
            .pageLabels(listOf(PdfPageLabel(0, null, prefix = "Cover"), PdfPageLabel(1)))
            .build()
        val doc = PdfDocument.open(pdf)
        assertEquals("a,b", doc.attachments.single().bytes.decodeToString())
        assertEquals(1, doc.resolveDestination(doc.outlines.single().rawDestination!!)?.pageIndex)
        assertEquals(listOf("Cover", "1"), doc.pages.map { it.label })
        assertTrue("Body" in doc.pages[1].extractText())
    }
}
