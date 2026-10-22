package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/** One broken glyph of an embedded TrueType font draws nothing, and the page around it still draws (#582). */
class BrokenGlyphTest {

    @Test
    fun a_glyph_that_reads_past_its_font_leaves_the_rest_of_the_page() {
        val pdf = TestPdf.onePage(
            "BT /F1 20 Tf 10 10 Td (A A) Tj ET 1 0 0 rg 100 100 20 20 re f",
            resources = "/Font << /F1 5 0 R >>",
            extra = listOf(
                "<< /Type /Font /Subtype /TrueType /BaseFont /SquareTest /FirstChar 32 /LastChar 65 " +
                    "/Widths [250 ${(33..64).joinToString(" ") { "0" }} 600] /FontDescriptor 6 0 R /Encoding /WinAnsiEncoding >>",
                "<< /Type /FontDescriptor /FontName /SquareTest /Flags 32 /FontBBox [0 0 500 500] /ItalicAngle 0 " +
                    "/Ascent 500 /Descent 0 /CapHeight 500 /StemV 80 /FontFile2 7 0 R >>",
                TestPdf.Stream("", TestFonts.squareAndSpaceTtf(instructions = 0xFFFF)),
            ),
        )
        val calls = TestPdf.calls(pdf)
        assertEquals(1, calls.filterIsInstance<RecordingCanvas.Call.Fill>().size)
        assertEquals("A A", PdfDocument.open(pdf).pages.single().textContent().plainText)
    }
}
