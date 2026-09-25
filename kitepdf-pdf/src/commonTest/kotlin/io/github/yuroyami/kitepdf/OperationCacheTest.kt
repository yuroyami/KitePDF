package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A page drawn again, and a form drawn many times, parse their content once per document,
 * within the byte budget of the operation cache (#118).
 */
class OperationCacheTest {

    /**
     * Page 1 draws the form Fm1, which has resources of its own, three times, and Fm2, which
     * reads the page's resources, twice. Page 2 draws Fm1 once.
     */
    private fun doc(): PdfDocument {
        val fm1 = "0 0 1 rg 0 0 10 10 re f"
        val fm2 = "1 0 0 rg 0 0 5 5 re f"
        val page1 = "/Fm1 Do /Fm1 Do /Fm1 Do /Fm2 Do /Fm2 Do"
        val page2 = "q 2 0 0 2 0 0 cm /Fm1 Do Q"
        val xobjects = "/XObject << /Fm1 7 0 R /Fm2 8 0 R >>"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << $xobjects >> /Contents 5 0 R >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << $xobjects >> /Contents 6 0 R >>",
            "<< /Length ${page1.length} >>\nstream\n$page1\nendstream",
            "<< /Length ${page2.length} >>\nstream\n$page2\nendstream",
            "<< /Type /XObject /Subtype /Form /BBox [0 0 10 10] /Resources << >> /Length ${fm1.length} >>\nstream\n$fm1\nendstream",
            "<< /Type /XObject /Subtype /Form /BBox [0 0 10 10] /Length ${fm2.length} >>\nstream\n$fm2\nendstream",
        )
        val out = StringBuilder("%PDF-1.7\n")
        val offsets = objects.mapIndexed { i, body -> out.length.also { out.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) out.append("${o.toString().padStart(10, '0')} 00000 n \n")
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(out.toString().encodeToByteArray())
    }

    private fun PdfDocument.render(index: Int): RecordingCanvas =
        RecordingCanvas().also { pages[index].renderTo(it, KiteMatrix.IDENTITY) }

    @Test
    fun a_page_and_a_form_parse_once_however_often_they_are_drawn() {
        val doc = doc()
        val first = doc.render(0)
        // The page and Fm1 parse; Fm2 reads the page's resources, so it parses each time outside the cache.
        assertEquals(2, doc.operationParseCount)
        assertEquals(5, first.calls.count { it is RecordingCanvas.Call.Fill }, "every draw still paints")
        doc.render(0)
        doc.pages[0].extractText()
        doc.render(1)
        // Page 2 parses; its Fm1 is the one page 1 parsed.
        assertEquals(3, doc.operationParseCount)
        assertTrue(doc.cachedOperationBytes > 0)
    }

    @Test
    fun a_budget_of_zero_keeps_nothing() {
        val doc = doc()
        doc.operationCacheBudgetBytes = 0
        doc.render(0)
        doc.render(0)
        // The page and each of the three draws of Fm1 parse, twice over.
        assertEquals(8, doc.operationParseCount)
        assertEquals(0L, doc.cachedOperationBytes)
    }

    @Test
    fun the_cache_drops_the_content_used_least_recently() {
        val doc = doc()
        // Page 2, object 4, parses first, then Fm1, object 7.
        doc.render(1)
        val both = doc.cachedOperationBytes
        doc.operationCacheBudgetBytes = both - 1
        assertTrue(doc.cachedOperationBytes < both, "the cache fits its new budget")
        doc.operations(7) { error("the form, used last, should still be cached") }
        var parsed = false
        doc.operations(4) { parsed = true; emptyList() }
        assertTrue(parsed, "page 2 left the cache first")
    }

    @Test
    fun dropping_the_cache_parses_again() {
        val doc = doc()
        doc.render(0)
        doc.dropOperationCache()
        assertEquals(0L, doc.cachedOperationBytes)
        doc.render(0)
        assertEquals(4, doc.operationParseCount)
    }
}
