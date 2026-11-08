package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.writer.FlattenMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PdfEditor.flatten draws annotations into the page and takes them off it (#949). */
class FlattenTest {

    private fun box(color: String) = TestPdf.stream("$color rg 0 0 40 20 re f", "/Type /XObject /Subtype /Form /BBox [0 0 40 20]")

    /**
     * A page with a filled text field, a checked check box, a stamp, a hidden stamp and a stamp
     * that does not print, each drawn with a coloured box.
     */
    private fun form(): ByteArray = TestPdf.build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R] /DA (/Helv 10 Tf 0 g) /DR << /Font << /Helv 13 0 R >> >> >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 6 0 R /Annots [4 0 R 5 0 R 7 0 R 8 0 R 9 0 R] >>",
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (Name) /Rect [10 150 110 170] /F 4 /DA (/Helv 10 Tf 0 g) /V (Ada) /AP << /N 10 0 R >> >>",
            "<< /Type /Annot /Subtype /Widget /FT /Btn /T (Agree) /Rect [10 110 50 130] /F 4 /V /Yes /AS /Yes /AP << /N << /Yes 11 0 R /Off 12 0 R >> >> >>",
            TestPdf.stream("0 0 1 rg 150 150 20 20 re f"),
            "<< /Type /Annot /Subtype /Stamp /Rect [10 60 50 80] /F 4 /AP << /N 14 0 R >> >>",
            "<< /Type /Annot /Subtype /Stamp /Rect [60 60 100 80] /F 6 /AP << /N 14 0 R >> >>",
            "<< /Type /Annot /Subtype /Stamp /Rect [110 60 150 80] /F 0 /AP << /N 14 0 R >> >>",
            box("0 1 0"),
            box("1 0 0"),
            box("1 1 1"),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            box("1 0 1"),
        ),
    )

    private fun render(doc: PdfDocument, annotations: Boolean = true): KiteRaster {
        val canvas = KiteRasterCanvas(200, 200)
        canvas.clear(RgbColor.WHITE)
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY) { annotations }
        return canvas.toRaster()
    }

    /** The pixel at page point ([x], [y]): with the identity matrix a raster row is a page y. */
    private fun KiteRaster.at(x: Int, y: Int) = this[x, y] and 0xFFFFFF

    @Test
    fun the_flattened_page_looks_as_it_did_with_its_annotations() {
        val doc = PdfDocument.open(form())
        val out = PdfDocument.open(doc.edit().apply { flatten() }.saveRewritten())
        val before = render(doc)
        val after = render(out)
        for ((x, y) in listOf(30 to 160, 30 to 120, 30 to 70, 160 to 160, 80 to 70, 130 to 70)) {
            assertEquals(before.at(x, y), after.at(x, y), "($x, $y)")
        }
        // The page content alone, with no annotation drawn, holds what was flattened.
        val content = render(out, annotations = false)
        assertEquals(0xFF0000, content.at(30, 120), "the checked state is drawn")
        assertEquals(0xFF00FF, content.at(30, 70), "the printing stamp is drawn")
        assertEquals(0xFFFFFF, content.at(80, 70), "the hidden stamp is left out")
        assertEquals(0xFFFFFF, content.at(130, 70), "the stamp that does not print is left out")
        assertEquals(0, out.formFields.size)
        assertNull(out.catalog["AcroForm"], "a form with no field left goes")
        assertEquals(2, out.pages[0].annotations.size, "the hidden and the unprinted stamps stay as annotations")
    }

    @Test
    fun a_value_filled_in_the_same_editor_is_what_is_drawn() {
        val doc = PdfDocument.open(form())
        val editor = doc.edit()
        editor.setTextFieldValue(doc.formFields.single { it.fullyQualifiedName == "Name" }, "Grace")
        val out = PdfDocument.open(editor.apply { flatten(widgetsOnly = true) }.saveRewritten())
        assertTrue("Grace" in out.pages[0].extractText(), out.pages[0].extractText())
        assertEquals(3, out.pages[0].annotations.size, "the stamps stay with widgetsOnly")
    }

    @Test
    fun the_display_mode_draws_what_shows_on_screen() {
        val doc = PdfDocument.open(form())
        val out = PdfDocument.open(doc.edit().apply { flatten(FlattenMode.Display) }.saveRewritten())
        val after = render(out, annotations = false)
        assertEquals(0xFF00FF, after.at(130, 70), "a stamp that does not print still shows on screen")
        assertEquals(0xFFFFFF, after.at(80, 70), "the hidden stamp is left out")
    }
}
