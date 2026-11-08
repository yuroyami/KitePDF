package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.test.Test
import kotlin.test.assertFalse

/** A redacted page loses its thumbnail and its page-piece data (ISO 32000-1, 12.3.4 and 14.5; #1011). */
class RedactionThumbTest {

    @Test
    fun the_thumbnail_and_the_private_data_go() {
        val pdf = TestPdf.build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> " +
                    "/Contents 5 0 R /Thumb 6 0 R /PieceInfo << /Illustrator << /LastModified (D:20260101) /Private 7 0 R >> >> >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                TestPdf.stream("BT /F1 24 Tf 100 700 Td (SECRET) Tj ET"),
                TestPdf.stream("THUMBPIXELSOFTHEPAGE", "/Width 20 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8"),
                TestPdf.stream("PRIVATE ARTWORK SECRET"),
            ),
        )
        val doc = PdfDocument.open(pdf)
        val out = doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(90.0, 690.0, 300.0, 730.0)) }.saveRewritten()
        val text = out.decodeToString()
        assertFalse("THUMBPIXELS" in text, "the thumbnail is gone")
        assertFalse("PRIVATE ARTWORK" in text, "the private data is gone")
    }
}
