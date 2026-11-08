package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A font only the redacted text used goes, glyph procedures and all (ISO 32000-1, 9.6.5; #1013). */
class RedactionFontTest {

    @OptIn(io.github.yuroyami.kitepdf.core.KiteRawApi::class)
    private fun everything(bytes: ByteArray): String {
        val doc = PdfDocument.open(bytes)
        return bytes.decodeToString() + doc.xref.keys.mapNotNull { n ->
            (runCatching { doc.resolve(io.github.yuroyami.kitepdf.core.parser.PdfReference(n, 0)) }.getOrNull() as? PdfStream)
                ?.let { runCatching { FilterChain.decode(it).decodeToString() }.getOrNull() }
        }.joinToString("\n")
    }

    @Test
    fun the_type3_font_of_the_removed_text_goes_and_the_footers_stays() {
        val pdf = TestPdf.build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 5 0 R /Resources << /Font << /F1 4 0 R /T3 6 0 R >> >> >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                TestPdf.stream("BT /T3 24 Tf 72 700 Td (a) Tj ET BT /F1 24 Tf 72 100 Td (Footer stays) Tj ET"),
                "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 1000 1000] /FontMatrix [0.001 0 0 0.001 0 0] " +
                    "/CharProcs << /a 7 0 R >> /Encoding << /Type /Encoding /Differences [97 /a] >> /FirstChar 97 /LastChar 97 /Widths [1000] /Resources << >> >>",
                TestPdf.stream("1000 0 0 0 1000 1000 d1 % GLYPH-PICTURE-OF-A\n0 0 1000 1000 re f"),
            ),
        )
        val doc = PdfDocument.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(60.0, 690.0, 200.0, 740.0)) }.saveRewritten()
        val all = everything(out)
        assertFalse("GLYPH-PICTURE-OF-A" in all, "the Type 3 glyph procedure is gone")
        assertTrue("Footer stays" in PdfDocument.open(out).pages[0].extractText())
    }
}
