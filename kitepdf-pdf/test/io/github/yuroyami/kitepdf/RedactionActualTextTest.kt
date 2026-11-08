package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A sequence that loses content loses its /ActualText, /Alt and /E (ISO 32000-1, 14.9; #1008). */
class RedactionActualTextTest {

    private fun pdf() = TestPdf.build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R " +
                "/Resources << /Font << /F1 5 0 R >> /Properties << /MC0 << /ActualText (SECRET-ACTUAL-NAMED) >> /MC1 << /ActualText (KEPT-NAMED) >> >> >> >>",
            TestPdf.stream(
                "/Span << /ActualText (SECRET-ACTUAL-INLINE) >> BDC BT /F1 24 Tf 72 700 Td (Visible one) Tj ET EMC\n" +
                    "/Span /MC0 BDC BT /F1 24 Tf 72 640 Td (Visible two) Tj ET EMC\n" +
                    "/Span << /Alt (SECRET-ALT) >> BDC BT /F1 24 Tf 72 580 Td (Visible three) Tj ET EMC\n" +
                    "/Span << /E (SECRET-EXPANSION) >> BDC BT /F1 24 Tf 72 520 Td (Visible four) Tj ET EMC\n" +
                    "/Span /MC1 BDC BT /F1 24 Tf 72 100 Td (Footer stays) Tj ET EMC",
            ),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        ),
    )

    @OptIn(io.github.yuroyami.kitepdf.core.KiteRawApi::class)
    private fun everything(bytes: ByteArray): String {
        val doc = PdfDocument.open(bytes)
        val streams = doc.xref.keys.mapNotNull { n ->
            (runCatching { doc.resolve(io.github.yuroyami.kitepdf.core.parser.PdfReference(n, 0)) }.getOrNull() as? PdfStream)
                ?.let { runCatching { FilterChain.decode(it).decodeToString() }.getOrNull() }
        }
        return bytes.decodeToString() + streams.joinToString("\n")
    }

    @Test
    fun the_replacement_text_of_redacted_sequences_goes_and_the_rest_stays() {
        val doc = PdfDocument.open(pdf())
        val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(60.0, 500.0, 400.0, 740.0)) }.saveRewritten()
        val all = everything(out)
        for (secret in listOf("SECRET-ACTUAL-INLINE", "SECRET-ACTUAL-NAMED", "SECRET-ALT", "SECRET-EXPANSION")) {
            assertFalse(secret in all, "$secret is gone")
        }
        assertTrue("KEPT-NAMED" in all, "a sequence the region misses keeps its own")
        val text = PdfDocument.open(out).pages[0].extractText()
        assertFalse("SECRET" in text, text)
        assertTrue("Footer stays" in text || "KEPT-NAMED" in text, text)
    }
}
