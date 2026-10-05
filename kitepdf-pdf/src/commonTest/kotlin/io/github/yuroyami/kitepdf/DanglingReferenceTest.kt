package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ISO 32000-1, 7.3.10: a reference to an object that does not exist is the null
 * object, never an error. Object 99 is missing from every file here.
 */
class DanglingReferenceTest {

    private fun fills(pdf: ByteArray) = TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Fill>().size

    private fun text(pdf: ByteArray) = PdfDocument.open(pdf).pages.single().textContent().plainText

    @Test
    fun an_image_that_names_no_object_draws_nothing() {
        val pdf = TestPdf.onePage("0 0 1 rg 0 0 10 10 re f /Im1 Do 20 20 10 10 re f", resources = "/XObject << /Im1 99 0 R >>")
        assertEquals(2, fills(pdf))
        assertEquals("", text(pdf))
    }

    @Test
    fun a_graphics_state_that_names_no_object_changes_nothing() {
        assertEquals(1, fills(TestPdf.onePage("/G1 gs 0 0 10 10 re f", resources = "/ExtGState << /G1 99 0 R >>")))
    }

    @Test
    fun a_font_whose_parts_are_missing_still_draws_its_text() {
        for (font in listOf(
            "<< /Type /Font /Subtype /TrueType /BaseFont /Helvetica /FontDescriptor 99 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /ToUnicode 99 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /FontDescriptor 6 0 R >>",
            "<< /Type /Font /Subtype /Type0 /BaseFont /Helvetica /Encoding 99 0 R /DescendantFonts [6 0 R] /ToUnicode 99 0 R >>",
        )) {
            val pdf = TestPdf.onePage(
                "BT /F1 12 Tf 10 10 Td <0048> Tj ET 0 0 10 10 re f",
                resources = "/Font << /F1 5 0 R >>",
                extra = listOf(
                    font,
                    "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /Helvetica /FontDescriptor 7 0 R >>",
                    "<< /Type /FontDescriptor /FontName /Helvetica /Flags 32 /FontFile2 99 0 R /FontFile3 99 0 R >>",
                ),
            )
            assertEquals(1, fills(pdf), font)
            PdfDocument.open(pdf).pages.single().textContent()
        }
    }

    @Test
    fun a_marked_content_property_that_names_no_object_keeps_the_text() {
        val pdf = TestPdf.onePage(
            "/Span /P1 BDC BT /F1 12 Tf 10 10 Td (Hi) Tj ET EMC",
            resources = "/Font << /F1 5 0 R >> /Properties << /P1 99 0 R >>",
            extra = listOf("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
        )
        assertEquals("Hi", text(pdf))
        assertEquals("Hi", PdfDocument.open(pdf).pages.single().extractText().trim())
    }

    @Test
    fun an_outline_item_that_names_no_object_ends_its_list() {
        val pdf = TestPdf.onePage(
            "",
            catalogEntries = "/Outlines 5 0 R",
            extra = listOf(
                "<< /Type /Outlines /First 6 0 R /Last 6 0 R /Count 1 >>",
                "<< /Title (Kept) /Parent 5 0 R /Next 99 0 R /First 99 0 R /Dest 99 0 R >>",
            ),
        )
        val doc = PdfDocument.open(pdf)
        assertEquals(listOf("Kept"), doc.outline.map { it.title })
        assertEquals(listOf("Kept"), doc.outlines.map { it.title })
        val none = PdfDocument.open(TestPdf.onePage("", catalogEntries = "/Outlines 99 0 R"))
        assertEquals(emptyList(), none.outline)
        assertEquals(emptyList(), none.outlines)
    }

    @Test
    fun catalog_entries_that_name_no_object_read_as_absent() {
        val doc = PdfDocument.open(
            TestPdf.onePage(
                "",
                catalogEntries = "/Names << /EmbeddedFiles 99 0 R /Dests 99 0 R >> /AcroForm << /Fields 99 0 R /DR 99 0 R >> " +
                    "/PageLabels 99 0 R /ViewerPreferences 99 0 R /MarkInfo 99 0 R /OpenAction 99 0 R",
            ),
        )
        assertEquals(emptyList(), doc.attachments)
        assertEquals(emptyList(), doc.formFields)
        assertEquals(0, doc.acroForm?.fieldCount ?: 0)
        assertEquals("1", doc.pages.single().label)
        doc.viewerPreferences
        doc.markInfo
        doc.openAction
    }
}
