package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A run goes when its ink meets a region, wherever the font or the pen puts that ink (#1017). */
class RedactionTextInkTest {

    private fun textAfter(content: String, fonts: String, extra: List<Any>, region: KiteRectangle): String {
        val pdf = TestPdf.build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << $fonts >> >> >>",
                TestPdf.stream(content),
            ) + extra,
        )
        val doc = PdfDocument.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], region) }.saveRewritten()
        return PdfDocument.open(out).pages[0].extractText().trim()
    }

    private val type3 = listOf(
        "<< /Type /Font /Subtype /Type3 /FontMatrix [0.001 0 0 0.001 0 0] /FontBBox [0 2000 1000 3000] " +
            "/CharProcs << /g 6 0 R >> /Encoding << /Type /Encoding /Differences [65 /g /g /g /g /g /g /g /g /g /g /g /g /g /g /g /g /g /g /g /g /g /g /g /g /g /g] >> " +
            "/FirstChar 65 /LastChar 90 /Widths [${List(26) { 1000 }.joinToString(" ")}] /Resources << >> " +
            "/ToUnicode 7 0 R >>",
        TestPdf.stream("1000 0 0 2000 1000 3000 d1 100 2000 800 1000 re f"),
        TestPdf.stream(
            "/CIDInit /ProcSet findresource begin 12 dict begin begincmap 1 begincodespacerange <00> <FF> endcodespacerange " +
                "1 beginbfrange <41> <5A> <0041> endbfrange endcmap CMapName currentdict /CMap defineresource pop end end",
        ),
    )

    @Test
    fun a_type3_glyph_drawn_above_its_origin_goes_with_a_region_over_its_ink() {
        val content = "BT /T3 20 Tf 100 100 Td (SECRET) Tj ET"
        assertEquals("", textAfter(content, "/T3 5 0 R", type3, KiteRectangle(95.0, 135.0, 230.0, 165.0)), "the region over the ink")
        assertEquals("", textAfter(content, "/T3 5 0 R", type3, KiteRectangle(95.0, 98.0, 230.0, 104.0)), "the control over the baseline")
    }

    @Test
    fun stroked_text_goes_with_a_region_over_its_pen() {
        val content = "0 g 0 G 30 w 2 Tr BT /F1 10 Tf 100 100 Td (SECRET) Tj ET"
        val helvetica = "/F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        assertFalse("SECRET" in textAfter(content, helvetica, emptyList(), KiteRectangle(95.0, 113.0, 170.0, 125.0)))
    }
}
