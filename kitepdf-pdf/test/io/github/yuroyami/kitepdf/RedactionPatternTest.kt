package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A fill in a tiling pattern colour that meets a region goes, cell content and all (ISO 32000-1, 8.7.3.1; #1012). */
class RedactionPatternTest {

    @Test
    fun a_page_wide_pattern_fill_over_the_region_goes() {
        val pdf = TestPdf.build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 5 0 R /Resources << /Font << /F1 4 0 R >> /Pattern << /P1 6 0 R >> >> >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                TestPdf.stream("/Pattern cs /P1 scn 0 0 612 792 re f 0 g BT /F1 24 Tf 72 100 Td (Footer stays) Tj ET"),
                TestPdf.stream(
                    "BT /F1 24 Tf 72 700 Td (PATTERN-SECRET) Tj ET",
                    "/Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 612 792] /XStep 612 /YStep 792 /Resources << /Font << /F1 4 0 R >> >>",
                ),
            ),
        )
        val doc = PdfDocument.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(60.0, 690.0, 400.0, 730.0)) }.saveRewritten()
        val after = PdfDocument.open(out)
        assertFalse("PATTERN-SECRET" in after.pages[0].readingOrder().joinToString(" ") { it.text })
        assertFalse("PATTERN-SECRET" in out.decodeToString(), "the cell is no longer reachable")
        assertTrue("Footer stays" in after.pages[0].extractText())
    }
}
