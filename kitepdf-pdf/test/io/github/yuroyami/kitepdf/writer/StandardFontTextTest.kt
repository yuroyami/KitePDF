package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.KitePDF
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Standard-font text is written through WinAnsiEncoding and names it (ISO 32000-1, Annex D.2; #668, #670). */
class StandardFontTextTest {

    private fun roundTrip(text: String): String {
        val pdf = PdfBuilder().page(width = 300.0, height = 100.0) {
            text(StandardFont.Helvetica, 14.0, 10.0, 40.0, text)
        }.build(compress = false)
        assertTrue("/WinAnsiEncoding" in pdf.decodeToString(), "the font dictionary names its encoding")
        return KitePDF.open(pdf).pages[0].extractText().trim()
    }

    @Test
    fun latinLettersReadBackAsWritten() {
        // Without /Encoding, StandardEncoding read é as Oslash (#668).
        assertEquals("café Müller ©", roundTrip("café Müller ©"))
    }

    @Test
    fun quotesDashesAndTheEuroAreWinAnsiBytes() {
        // U+2019 is 0x92 and U+20AC is 0x80 in WinAnsi; they were '?' (#670).
        val text = "${Char(0x2019)}q${Char(0x2019)} ${Char(0x20AC)}5 ${Char(0x2013)} end"
        assertEquals(text, roundTrip(text))
        assertEquals(0x92, PdfText.winAnsiCode(Char(0x2019)))
        assertEquals(0x80, PdfText.winAnsiCode(Char(0x20AC)))
        assertEquals(null, PdfText.winAnsiCode(Char(0x85)), "a C1 control is not passed through")
    }

    @Test
    fun theWidthMeasuresWhatIsDrawn() {
        // Helvetica's quoteright is 222 and endash 556 units; both fell to the 500 fallback.
        assertEquals(0.222, StandardFont.Helvetica.stringWidth("${Char(0x2019)}", 1.0), 1e-9)
        assertEquals(0.556, StandardFont.Helvetica.stringWidth("${Char(0x2013)}", 1.0), 1e-9)
    }
}
