package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A damaged page tree still opens as the pages it really holds: a `/Count` that says more or
 * fewer pages than the tree has (#329), and pages with no usable `/MediaBox` (#330).
 */
class PageTreeSalvageTest {

    /** A PDF whose root `/Pages` node says [pagesNode] and whose [pageCount] leaves carry [pageEntries]. */
    private fun pdf(pagesNode: String, pageCount: Int, pageEntries: String = ""): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        val kids = (0 until pageCount).joinToString(" ") { "${it + 3} 0 R" }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [$kids] $pagesNode >>")
        val content = pageCount + 3
        repeat(pageCount) { add("<< /Type /Page /Parent 2 0 R /Resources << >> /Contents $content 0 R $pageEntries >>") }
        val square = "1 0 0 rg 10 10 50 50 re f"
        add("<< /Length ${square.length} >>\nstream\n$square\nendstream")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    private val letter = KiteRectangle(0.0, 0.0, 612.0, 792.0)

    @Test
    fun a_count_larger_than_the_tree_gives_the_real_pages() {
        val doc = PdfDocument.open(pdf("/Count 3 /MediaBox [0 0 100 100]", pageCount = 1))
        assertEquals(1, doc.pageCountIn(0))
        assertEquals(1, doc.pageCount, "once the pages exist their number is exact")
    }

    @Test
    fun a_count_smaller_than_the_tree_gives_the_real_pages() {
        val doc = PdfDocument.open(pdf("/Count 1 /MediaBox [0 0 100 100]", pageCount = 3))
        assertEquals(3, doc.pageCountIn(0))
    }

    @Test
    fun a_count_larger_than_the_file_is_not_trusted_even_before_the_walk() {
        val doc = PdfDocument.open(pdf("/Count 100000000 /MediaBox [0 0 100 100]", pageCount = 2))
        assertEquals(2, doc.pageCount)
    }

    @Test
    fun a_page_without_a_media_box_is_shown_as_letter() {
        val doc = PdfDocument.open(pdf("/Count 1", pageCount = 1))
        val page = doc.pages[0]
        assertEquals(letter, page.mediaBox)
        assertEquals(612.0, page.displayWidth)
        val canvas = RecordingCanvas()
        page.renderTo(canvas, KiteMatrix.IDENTITY)
        assertTrue(canvas.calls.any { it is RecordingCanvas.Call.Fill }, "the page still draws")
    }

    @Test
    fun an_inherited_media_box_of_three_numbers_is_shown_as_letter() {
        val doc = PdfDocument.open(pdf("/Count 1 /MediaBox [0 0 612]", pageCount = 1))
        assertEquals(letter, doc.pages[0].mediaBox)
    }

    @Test
    fun an_empty_media_box_is_shown_as_letter() {
        val doc = PdfDocument.open(pdf("/Count 1", pageCount = 1, pageEntries = "/MediaBox [0 0 0 0]"))
        assertEquals(letter, doc.pages[0].mediaBox)
    }

    @Test
    fun a_well_formed_inherited_media_box_still_applies() {
        val doc = PdfDocument.open(pdf("/Count 1 /MediaBox [0 0 200 300]", pageCount = 1))
        assertEquals(KiteRectangle(0.0, 0.0, 200.0, 300.0), doc.pages[0].mediaBox)
    }
}
