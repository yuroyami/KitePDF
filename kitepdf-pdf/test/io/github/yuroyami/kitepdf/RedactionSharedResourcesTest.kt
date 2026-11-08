package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A redacted image that nothing draws leaves the file when the page inherits or shares its resources (#1010). */
class RedactionSharedResourcesTest {

    private val image = TestPdf.stream("SECRETIMAGEBYTES", "/Type /XObject /Subtype /Image /Width 16 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8")

    @OptIn(io.github.yuroyami.kitepdf.core.KiteRawApi::class)
    private fun everything(bytes: ByteArray): String {
        val doc = PdfDocument.open(bytes)
        return bytes.decodeToString() + doc.xref.keys.mapNotNull { n ->
            (runCatching { doc.resolve(io.github.yuroyami.kitepdf.core.parser.PdfReference(n, 0)) }.getOrNull() as? PdfStream)
                ?.let { runCatching { FilterChain.decode(it).decodeToString() }.getOrNull() }
        }.joinToString("\n")
    }

    private fun redacted(pdf: ByteArray): String {
        val doc = PdfDocument.open(pdf)
        return everything(doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(90.0, 690.0, 300.0, 720.0)) }.saveRewritten())
    }

    @Test
    fun an_inherited_image_goes() {
        val pdf = TestPdf.build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] /Resources << /XObject << /Im1 5 0 R >> >> >>",
                "<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>",
                TestPdf.stream("q 160 0 0 10 100 700 cm /Im1 Do Q"),
                image,
            ),
        )
        assertFalse("SECRETIMAGEBYTES" in redacted(pdf))
    }

    @Test
    fun a_shared_image_goes_when_no_other_page_draws_it_and_stays_when_one_does() {
        fun pdf(secondPage: String) = TestPdf.build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 612 792] /Resources 5 0 R >>",
                "<< /Type /Page /Parent 2 0 R /Contents 6 0 R /Resources 5 0 R >>",
                "<< /Type /Page /Parent 2 0 R /Contents 7 0 R /Resources 5 0 R >>",
                "<< /Font << /F1 9 0 R >> /XObject << /Im1 8 0 R >> >>",
                TestPdf.stream("q 160 0 0 10 100 700 cm /Im1 Do Q"),
                TestPdf.stream(secondPage),
                image,
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            ),
        )
        assertFalse("SECRETIMAGEBYTES" in redacted(pdf("BT /F1 12 Tf 72 72 Td (Page two) Tj ET")), "no other page draws it")
        assertTrue("SECRETIMAGEBYTES" in redacted(pdf("q 160 0 0 10 100 100 cm /Im1 Do Q")), "page two still draws it")
    }
}
