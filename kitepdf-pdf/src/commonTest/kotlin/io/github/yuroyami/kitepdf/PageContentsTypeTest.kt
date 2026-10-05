package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `/Contents` that is null (ISO 32000-1, 7.3.9: the same as absent) or of no type a
 * content stream can have is no content: the page is empty and still draws its
 * annotations, as MuPDF has it (#583).
 */
class PageContentsTypeTest {

    @Test
    fun contents_that_are_null_or_not_a_stream_leave_an_empty_page_with_its_annotations() {
        for (contents in listOf("null", "5", "(text)", "/Name", "<< /Length 0 >>", "true")) {
            val pdf = TestPdf.build(
                listOf(
                    "<< /Type /Catalog /Pages 2 0 R >>",
                    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents $contents " +
                        "/Annots [<< /Type /Annot /Subtype /Square /Rect [10 10 20 20] /AP << /N 4 0 R >> >>] >>",
                    TestPdf.stream("1 0 0 rg 0 0 10 10 re f", "/Type /XObject /Subtype /Form /BBox [0 0 10 10]"),
                ),
            )
            val page = PdfDocument.open(pdf).pages.single()
            assertEquals(0, page.contentBytes.size, contents)
            val calls = RecordingCanvas().also { page.renderTo(it, KiteMatrix.IDENTITY) }.calls
            assertEquals(1, calls.filterIsInstance<RecordingCanvas.Call.Fill>().size, contents)
            assertEquals("", page.textContent().plainText, contents)
        }
    }
}
