package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Text that a page draws only through a soft mask goes with a redaction of its area (ISO 32000-1, 11.6.5.2; #631). */
class RedactionSoftMaskTest {

    private fun pdf() = TestPdf.build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Contents 4 0 R " +
                "/Resources << /ExtGState << /GS1 << /SMask << /S /Luminosity /G 5 0 R >> >> >> >> >>",
            TestPdf.stream("/GS1 gs 0 0 1 rg 0 0 300 300 re f"),
            TestPdf.stream(
                "1 g BT /F1 24 Tf 100 140 Td (SECRET) Tj ET 1 g BT /F1 24 Tf 20 20 Td (PUBLIC) Tj ET",
                "/Type /XObject /Subtype /Form /BBox [0 0 300 300] /Group << /S /Transparency /CS /DeviceGray >> " +
                    "/Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >>",
            ),
        ),
    )

    @OptIn(io.github.yuroyami.kitepdf.core.KiteRawApi::class)
    private fun streamsOf(bytes: ByteArray): String {
        val doc = PdfDocument.open(bytes)
        return doc.xref.keys.mapNotNull { n ->
            (runCatching { doc.resolve(io.github.yuroyami.kitepdf.core.parser.PdfReference(n, 0)) }.getOrNull() as? PdfStream)
                ?.let { runCatching { FilterChain.decode(it).decodeToString() }.getOrNull() }
        }.joinToString("\n")
    }

    @Test
    fun the_mask_groups_text_in_the_region_goes_and_the_rest_stays() {
        assertTrue("(SECRET)" in streamsOf(pdf()), "the fixture carries the text")
        val doc = PdfDocument.open(pdf())
        val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(90.0, 130.0, 220.0, 170.0)) }.saveRewritten()
        val after = streamsOf(out)
        assertFalse("SECRET" in after, "the mask group's text in the region is gone")
        assertTrue("(PUBLIC)" in after, "the mask's text outside the region stays, so the mask still works")
    }
}
