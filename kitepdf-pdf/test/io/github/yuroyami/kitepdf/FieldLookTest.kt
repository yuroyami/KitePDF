package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.writer.FieldAppearance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A filled field keeps its box (#715), turns with /MK /R (#722) and fills a comb cell by cell (#719). */
class FieldLookTest {

    private fun pdf(field: String): ByteArray = TestPdf.build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] /DA (/Helv 12 Tf 0 g) >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 400] /Annots [4 0 R] >>",
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (F) /DA (/Helv 12 Tf 0 g) /P 3 0 R $field >>",
        ),
    )

    private fun filled(field: String, value: String): Pair<PdfStream, String> {
        val doc = KitePDF.open(pdf(field))
        val out = doc.edit().apply { setTextFieldValue(doc.formField("F")!!, value) }.saveIncremental()
        val ap = KitePDF.open(out).pages[0].annotations.first().appearanceStream!!
        return ap to FilterChain.decode(ap).decodeToString()
    }

    private fun numbers(o: Any?) = (o as PdfArray).map { (it as? PdfReal)?.value ?: it.toString().toDouble() }

    @Test
    fun theValueDrawsOverTheWidgetsBackgroundAndBorder() {
        val (_, content) = filled("/Rect [10 200 250 225] /MK << /BG [1 1 0] /BC [1 0 0] >> /BS << /W 2 >>", "Serif text")
        assertTrue("1 1 0 rg\n0 0 240 25 re f" in content, content)
        assertTrue("1 0 0 RG 2 w" in content && "re S" in content, content)
        assertTrue(content.indexOf("re f") < content.indexOf("(Serif text) Tj"), "the box before the text")
    }

    @Test
    fun aTurnedWidgetLaysItsValueOutAlongItsLongSide() {
        val (ap, content) = filled("/Rect [300 100 320 300] /MK << /R 90 >>", "Hello world")
        assertEquals(listOf(0.0, 0.0, 200.0, 20.0), numbers(ap.dict["BBox"]), "the box turned")
        assertEquals(listOf(0.0, 1.0, -1.0, 0.0, 20.0, 0.0), numbers(ap.dict["Matrix"]))
        assertTrue("(Hello world) Tj" in content)
        // The viewer's own appearance turns the same way.
        val widget = PdfDictionary(linkedMapOf("FT" to io.github.yuroyami.kitepdf.core.parser.PdfName("Tx")))
        val mk = PdfDictionary(linkedMapOf("R" to io.github.yuroyami.kitepdf.core.parser.PdfInt(90)))
        val synthesized = FieldAppearance.synthesize(PdfDictionary(widget.map + ("MK" to mk)), 20.0, 200.0, { null }, valueOverride = "Hi")!!
        assertEquals(listOf(0.0, 0.0, 200.0, 20.0), numbers(synthesized.dict["BBox"]))
    }

    @Test
    fun aCombDrawsOneCharacterInEachCellAndKeepsToItsLength() {
        val (_, content) = filled("/Ff 16777216 /MaxLen 5 /Rect [10 300 110 320]", "123456")
        assertEquals(5, Regex("\\) Tj").findAll(content).count(), content)
        assertTrue("(6)" !in content, "the sixth character is cut")
        // Each character sits in the middle of its 20-point cell.
        val xs = Regex("1 0 0 1 ([0-9.]+) [0-9.]+ Tm").findAll(content).map { it.groupValues[1].toDouble() }.toList()
        assertEquals(5, xs.size)
        for (i in 1 until xs.size) assertEquals(20.0, xs[i] - xs[i - 1], 1e-6)
        val doc = KitePDF.open(pdf("/Ff 16777216 /MaxLen 5 /Rect [10 300 110 320]"))
        val out = doc.edit().apply { setTextFieldValue(doc.formField("F")!!, "123456") }.saveIncremental()
        assertEquals("12345", KitePDF.open(out).formField("F")?.value, "/V keeps to /MaxLen too")
    }

    @Test
    fun aTurnedCheckBoxTurnsItsMarkWithIt() {
        // ISO 32000-1, 12.5.6.19, Table 189: /R turns the whole appearance, the mark of a check box too (#722).
        val box = TestPdf.build(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] /DA (/Helv 12 Tf 0 g) >> >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 400] /Annots [4 0 R] >>",
                "<< /Type /Annot /Subtype /Widget /FT /Btn /T (C) /P 3 0 R /Rect [100 100 120 140] /MK << /R 90 >> >>",
            ),
        )
        val doc = KitePDF.open(box)
        val out = doc.edit().apply { setCheckbox(doc.formField("C")!!, true) }.saveIncremental()
        val ap = KitePDF.open(out).pages[0].annotations.first().appearanceStream!!
        assertEquals(listOf(0.0, 0.0, 40.0, 20.0), numbers(ap.dict["BBox"]), "the box turned")
        assertEquals(listOf(0.0, 1.0, -1.0, 0.0, 20.0, 0.0), numbers(ap.dict["Matrix"]))
        // The viewer's own appearance turns the same way.
        val widget = PdfDictionary(linkedMapOf(
            "FT" to io.github.yuroyami.kitepdf.core.parser.PdfName("Btn"),
            "V" to io.github.yuroyami.kitepdf.core.parser.PdfName("Yes"),
            "MK" to PdfDictionary(linkedMapOf("R" to io.github.yuroyami.kitepdf.core.parser.PdfInt(270))),
        ))
        val synthesized = FieldAppearance.synthesize(widget, 20.0, 40.0, { null })!!
        assertEquals(listOf(0.0, -1.0, 1.0, 0.0, 0.0, 40.0), numbers(synthesized.dict["Matrix"]))
    }
}
