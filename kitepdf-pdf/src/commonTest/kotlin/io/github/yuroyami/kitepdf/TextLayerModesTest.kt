package io.github.yuroyami.kitepdf

import kotlin.test.Test
import kotlin.test.assertEquals

/** Every text run reaches the text layer that search and selection read, whatever it paints. */
class TextLayerModesTest {

    private fun layerText(pdf: ByteArray) = KitePDF.open(pdf).pages[0].structuredText.plainText.trim()

    @Test
    fun text_in_every_render_mode_reaches_the_text_layer() {
        // ISO 32000-1, 9.3.6: mode 3 paints nothing, and it is how an OCR layer sits over a scan.
        for (mode in 0..7) {
            val pdf = RawPdf.page("BT /F1 12 Tf $mode Tr 100 700 Td (word$mode) Tj ET".encodeToByteArray())
            assertEquals("word$mode", layerText(pdf), "render mode $mode")
        }
    }

    @Test
    fun text_in_a_colour_that_paints_nothing_reaches_the_text_layer() {
        val pdf = TestPdf.onePage(
            content = "BT /F1 12 Tf /None cs 1 sc 20 100 Td (hidden ink) Tj ET",
            resources = "/Font << /F1 5 0 R >> /ColorSpace << /None [/Separation /None /DeviceGray 6 0 R] >>",
            extra = listOf(
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /FunctionType 2 /Domain [0 1] /C0 [1] /C1 [0] /N 1 >>",
            ),
        )
        assertEquals("hidden ink", layerText(pdf))
    }

    @Test
    fun type3_text_reaches_the_text_layer() {
        // A Type 3 glyph is a drawing, so its text comes from the encoding (ISO 32000-1, 9.6.5).
        val pdf = TestPdf.onePage(
            content = "BT /T3 20 Tf 50 50 Td (ab) Tj ET",
            resources = "/Font << /T3 5 0 R >>",
            mediaBox = "0 0 300 300",
            extra = listOf(
                "<< /Type /Font /Subtype /Type3 /FontMatrix [0.001 0 0 0.001 0 0] /FontBBox [0 0 700 700] " +
                    "/Encoding << /Differences [97 /a /b] >> /FirstChar 97 /LastChar 98 /Widths [600 700] " +
                    "/CharProcs << /a 6 0 R /b 6 0 R >> >>",
                TestPdf.stream("600 0 0 0 600 700 d1 0 0 600 700 re f"),
            ),
        )
        val text = KitePDF.open(pdf).pages[0].structuredText
        assertEquals("ab", text.plainText.trim())
        // The line ends where the Type 3 advances end: (600 + 700) / 1000 em at 20 points.
        val line = text.blocks.single().lines.single()
        assertEquals(50.0 + 1.3 * 20.0, line.bounds.right, 1e-6)
    }
}
