package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A host switches the layers that rendering and text extraction follow (ISO 32000-1, 8.11; #938). */
class LayerSwitchTest {

    // Groups 6 (English, on), 7 (French, off) and 8 (Locked, on) form a radio-button group of 6 and 7.
    private fun pdf(): ByteArray = TestPdf.build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R /OCProperties << /OCGs [6 0 R 7 0 R 8 0 R] /D << /OFF [7 0 R] /RBGroups [[6 0 R 7 0 R]] /Locked [8 0 R] /AS [<< /Event /Print /Category [/Print] /OCGs [7 0 R] >>] >> /Configs [<< /Name (French) /OFF [6 0 R] >>] >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> /Properties << /En 6 0 R /Fr 7 0 R >> >> >>",
            TestPdf.stream(
                "/OC /En BDC 1 0 0 rg 0 0 50 100 re f BT /F1 10 Tf 5 50 Td (Hello) Tj ET EMC " +
                    "/OC /Fr BDC 0 0 1 rg 50 0 50 100 re f BT /F1 10 Tf 55 50 Td (Bonjour) Tj ET EMC",
            ),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Type /OCG /Name (English) /Usage << /Print << /PrintState /ON >> >> >>",
            "<< /Type /OCG /Name (French) /Usage << /Print << /PrintState /ON >> >> >>",
            "<< /Type /OCG /Name (Locked) >>",
        ),
    )

    private fun pixel(doc: PdfDocument, x: Int): Int {
        val canvas = KiteRasterCanvas(100, 100)
        canvas.clear(RgbColor.WHITE)
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.toRaster()[x, 20] and 0xFFFFFF
    }

    @Test
    fun switching_a_layer_changes_what_draws_and_reads() {
        val doc = PdfDocument.open(pdf())
        assertEquals(0xFF0000, pixel(doc, 25))
        assertEquals(0xFFFFFF, pixel(doc, 75))
        assertFalse("Bonjour" in doc.pages[0].extractText())
        val oc = assertNotNull(doc.layers)
        doc.layers = oc.withVisible("7", true)
        assertEquals(0x0000FF, pixel(doc, 75), "French is on")
        assertEquals(0xFFFFFF, pixel(doc, 25), "and its radio group turned English off")
        assertTrue("Bonjour" in doc.pages[0].extractText())
        assertFalse("Hello" in doc.pages[0].extractText())
        assertTrue("Bonjour" in doc.pages[0].structuredText.blocks.flatMap { it.lines }.joinToString { it.text }, "structured text follows")
        doc.layers = null
        assertEquals(0xFF0000, pixel(doc, 25), "null goes back to the defaults")
    }

    @Test
    fun a_locked_group_does_not_switch() {
        val oc = assertNotNull(PdfDocument.open(pdf()).layers)
        assertEquals(oc, oc.withVisible("8", false))
        assertEquals(setOf("8"), oc.locked)
    }

    @Test
    fun an_alternate_configuration_and_the_print_states() {
        val oc = assertNotNull(PdfDocument.open(pdf()).layers)
        assertEquals(listOf("French"), oc.configurations)
        val french = assertNotNull(oc.withConfiguration("French"))
        assertFalse(french.isVisibleByDefault("6"))
        assertTrue(french.isVisibleByDefault("7"))
        assertTrue(oc.forEvent("Print").isVisibleByDefault("7"), "the print state turns French on")
    }
}
