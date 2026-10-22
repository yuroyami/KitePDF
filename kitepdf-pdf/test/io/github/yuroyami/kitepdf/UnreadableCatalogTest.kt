package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.PdfFormatException
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A file is a document only through a catalog that leads to a page tree. When repair finds
 * none, [PdfDocument.open] refuses the file, as mutool does, instead of handing back a
 * document whose first use throws; when the trailer names a broken one and the file holds
 * another, the other is the catalog, as pdf.js reads it (#584).
 */
class UnreadableCatalogTest {

    private val pages = "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
    private val page = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R >>"
    private val content = TestPdf.stream("0 0 1 rg 0 0 10 10 re f")

    @Test
    fun a_file_with_no_catalog_that_leads_to_pages_does_not_open() {
        val files = mapOf(
            "catalog is an array" to TestPdf.build(listOf("[1 2 3]", pages, page, content)),
            "catalog has no page tree" to TestPdf.build(listOf("<< /Type /Catalog >>", pages, page, content)),
            "page tree is a number" to TestPdf.build(listOf("<< /Type /Catalog /Pages 5 0 R >>", pages, page, content, "5")),
            "page tree is missing" to TestPdf.build(listOf("<< /Type /Catalog /Pages 9 0 R >>", pages, page, content)),
            "catalog is missing" to TestPdf.build(listOf("<< /Type /Font >>", pages, page, content), root = "9 0 R"),
        )
        for ((what, pdf) in files) {
            assertFailsWith<PdfFormatException>(what) { PdfDocument.open(pdf) }
            assertNull(PdfDocument.openOrNull(pdf), what)
        }
    }

    @Test
    fun a_broken_root_gives_way_to_a_catalog_that_leads_to_pages() {
        val catalog = "<< /Type /Catalog /Pages 2 0 R >>"
        for ((what, pdf) in mapOf(
            "root is a string" to TestPdf.build(listOf(catalog, pages, page, content, "(not a catalog)"), root = "5 0 R"),
            "root is missing" to TestPdf.build(listOf(catalog, pages, page, content), root = "9 0 R"),
            "root has no page tree" to TestPdf.build(listOf(catalog, pages, page, content, "<< /Type /Catalog >>"), root = "5 0 R"),
        )) {
            val doc = PdfDocument.open(pdf)
            assertEquals(1, doc.pageCount, what)
            val canvas = RecordingCanvas()
            doc.pages.single().renderTo(canvas, KiteMatrix.IDENTITY)
            assertEquals(1, canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().size, what)
        }
    }
}
