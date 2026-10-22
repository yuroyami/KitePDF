package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A kid of the page tree that is no page dictionary keeps its place in the page list, as
 * MuPDF reads it, so the pages after it keep their indices, labels and destinations (#585).
 * Objects 3 and 5 are pages, and object 7 is the kid between them.
 */
class BrokenPageKidTest {

    private fun file(kid: String, seventh: String = "<< >>"): ByteArray = TestPdf.build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R /PageLabels << /Nums [0 << /S /D /P (p) >>] >> " +
                "/Outlines 8 0 R >>",
            "<< /Type /Pages /Kids [3 0 R $kid 5 0 R] /Count 3 /MediaBox [0 0 200 200] >>",
            "<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>",
            TestPdf.stream("0 0 1 rg 0 0 10 10 re f"),
            "<< /Type /Page /Parent 2 0 R /Contents 6 0 R >>",
            TestPdf.stream("1 0 0 rg 0 0 50 50 re f"),
            seventh,
            "<< /Type /Outlines /First 9 0 R /Last 9 0 R /Count 1 >>",
            "<< /Title (Last) /Parent 8 0 R /Dest [5 0 R /Fit] >>",
        ),
    )

    private fun fills(page: PdfPage): Int {
        val canvas = RecordingCanvas()
        page.renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().size
    }

    private fun assertThreePages(pdf: ByteArray, what: String, middleFills: Int, middleWidth: Double) {
        val doc = PdfDocument.open(pdf)
        assertEquals(3, doc.pages.size, what)
        assertEquals(3, doc.pageCount, what)
        assertEquals(listOf("p1", "p2", "p3"), doc.pages.map { it.label }, what)
        assertEquals(middleFills, fills(doc.pages[1]), what)
        assertEquals(middleWidth, doc.pages[1].width, what)
        assertEquals(PdfReference(5, 0), doc.pages[2].reference, what)
        assertEquals(1, fills(doc.pages[2]), what)
        assertEquals(2, doc.resolveDestination(doc.outlines.single().rawDestination)?.pageIndex, what)
    }

    @Test
    fun a_kid_that_is_no_dictionary_is_a_blank_page() {
        for ((what, pdf) in mapOf(
            "missing object" to file("99 0 R"),
            "number object" to file("7 0 R", seventh = "42"),
            "inline null" to file("null"),
            "inline name" to file("/Page"),
        )) {
            assertThreePages(pdf, what, middleFills = 0, middleWidth = 200.0)
        }
    }

    @Test
    fun a_kid_dictionary_without_the_page_type_is_a_page() {
        for ((what, seventh) in mapOf(
            "no type" to "<< /Parent 2 0 R /MediaBox [0 0 300 300] /Contents 6 0 R >>",
            "another type" to "<< /Type /Foo /Parent 2 0 R /MediaBox [0 0 300 300] /Contents 6 0 R >>",
        )) {
            assertThreePages(file("7 0 R", seventh), what, middleFills = 1, middleWidth = 300.0)
        }
    }

    @Test
    fun a_blank_page_can_be_removed() {
        val doc = PdfDocument.open(file("99 0 R"))
        val editor = doc.edit()
        editor.removePage(doc.pages[1])
        val saved = PdfDocument.open(editor.saveIncremental())
        assertEquals(listOf(PdfReference(3, 0), PdfReference(5, 0)), saved.pages.map { it.reference })
    }
}
