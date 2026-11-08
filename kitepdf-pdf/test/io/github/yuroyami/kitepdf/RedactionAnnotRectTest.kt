package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Redaction judges an annotation by the rectangle the renderer draws it at (#630). */
class RedactionAnnotRectTest {

    private fun pdf(rect: String) = TestPdf.build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Contents 6 0 R /Annots [4 0 R] >>",
            "<< /Type /Annot /Subtype /Square /Rect $rect /Contents (secret note) /AP << /N 5 0 R >> >>",
            TestPdf.stream("0 0 1 rg 0 0 100 100 re f", "/Type /XObject /Subtype /Form /BBox [0 0 100 100]"),
            TestPdf.stream("q Q"),
            "100",
        ),
    )

    private fun annotsAfterRedacting(bytes: ByteArray): Int {
        val doc = PdfDocument.open(bytes)
        val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(90.0, 90.0, 210.0, 210.0)) }.saveRewritten()
        val after = PdfDocument.open(out)
        assertTrue("secret note" !in out.decodeToString() || after.pages[0].annotations.isNotEmpty())
        return after.pages[0].annotations.size
    }

    @Test
    fun an_annotation_with_a_damaged_or_indirect_rect_goes_with_the_region() {
        assertEquals(0, annotsAfterRedacting(pdf("[100 100 200 200]")), "a plain rectangle in the region")
        assertEquals(0, annotsAfterRedacting(pdf("[100 100 200 /Oops]")), "a damaged entry counts as a hit")
        assertEquals(0, annotsAfterRedacting(pdf("[100 100 200 7 0 R]")), "an indirect entry resolves")
        assertEquals(1, annotsAfterRedacting(pdf("[250 250 290 290]")), "one outside the region stays")
        // The reader resolves an indirect entry too, so it draws where redaction looks.
        assertEquals(KiteRectangle(100.0, 100.0, 200.0, 100.0), PdfDocument.open(pdf("[100 100 200 7 0 R]")).pages[0].annotations.single().rect)
    }
}
