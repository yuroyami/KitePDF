package io.github.yuroyami.kitepdf

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** extractText leaves out the text of optional content that the default configuration turns off, as the renderer does (#822). */
class TextOptionalContentTest {

    private fun pdf(): ByteArray = TestPdf.onePage(
        content = "/OC /L1 BDC BT /F1 12 Tf 10 100 Td (Hidden) Tj ET EMC\n" +
            "BT /F1 12 Tf 10 50 Td (Shown) Tj ET\n" +
            "/Fm Do\n" +
            "/OC /L2 BDC BT /F1 12 Tf 10 20 Td (OnLayer) Tj ET EMC",
        resources = "/Font << /F1 7 0 R >> /Properties << /L1 5 0 R /L2 8 0 R >> /XObject << /Fm 6 0 R >>",
        catalogEntries = "/OCProperties << /OCGs [5 0 R 8 0 R] /D << /OFF [5 0 R] >> >>",
        extra = listOf(
            "<< /Type /OCG /Name (Answers) >>",
            TestPdf.stream("BT /F1 12 Tf 10 150 Td (FormHidden) Tj ET", "/Type /XObject /Subtype /Form /BBox [0 0 200 200] /OC 5 0 R /Resources << /Font << /F1 7 0 R >> >>"),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Type /OCG /Name (Visible) >>",
        ),
    )

    @Test
    fun textInAnOffLayerIsNotExtracted() {
        val text = KitePDF.open(pdf()).pages[0].extractText()
        assertTrue("Shown" in text, text)
        assertTrue("OnLayer" in text, "a layer that is on stays: $text")
        assertFalse("Hidden" in text, "the marked content of the off layer: $text")
        assertFalse("FormHidden" in text, "the form whose /OC is off: $text")
    }
}
