package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.filters.FilterChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A field shown in several places shows a new value in every one (ISO 32000-1, 12.7.3.1; #702). */
class FieldWidgetsTest {

    /** Field `Name` with widgets 6 on page 1 and 7 on page 2, each drawing `(Old) Tj`. */
    private fun pdf(fieldType: String = "/FT /Tx"): ByteArray = TestPdf.build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [5 0 R] /DA (/Helv 12 Tf 0 g) >> >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Annots [6 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Annots [7 0 R] >>",
            "<< $fieldType /T (Name) /V (Old) /DA (/Helv 12 Tf 0 g) /Kids [6 0 R 7 0 R] >>",
            "<< /Type /Annot /Subtype /Widget /Parent 5 0 R /P 3 0 R /Rect [10 10 200 30] /AP << /N 8 0 R >> >>",
            "<< /Type /Annot /Subtype /Widget /Parent 5 0 R /P 4 0 R /Rect [10 10 150 40] /AP << /N 8 0 R >> >>",
            TestPdf.stream("BT /Helv 12 Tf 2 5 Td (Old) Tj ET", "/Type /XObject /Subtype /Form /BBox [0 0 190 20]"),
        ),
    )

    private fun shown(pdf: ByteArray, page: Int): String {
        val ap = KitePDF.open(pdf).pages[page].annotations.first().appearanceStream!!
        return FilterChain.decode(ap).decodeToString()
    }

    @Test
    fun everyWidgetOfATextFieldShowsTheNewValue() {
        val doc = KitePDF.open(pdf())
        val out = doc.edit().apply { setTextFieldValue(doc.formField("Name")!!, "New") }.saveIncremental()
        for (page in 0..1) {
            val content = shown(out, page)
            assertTrue("(New) Tj" in content && "Old" !in content, "page $page: $content")
        }
        // Each widget is drawn for its own rectangle.
        val boxes = (0..1).map { KitePDF.open(out).pages[it].annotations.first().appearanceStream!!.dict["BBox"].toString() }
        assertTrue(boxes[0] != boxes[1], "$boxes")
        assertEquals("New", KitePDF.open(out).formField("Name")?.value)
    }

    @Test
    fun everyWidgetOfAChoiceFieldShowsTheNewValue() {
        val doc = KitePDF.open(pdf("/FT /Ch /Ff 131072 /Opt [(Old) (New)]"))
        val out = doc.edit().apply { setChoiceValue(doc.formField("Name")!!, "New") }.saveIncremental()
        for (page in 0..1) assertTrue("(New) Tj" in shown(out, page), "page $page")
    }
}
