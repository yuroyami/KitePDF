package io.github.yuroyami.kitepdf

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Form values go out as XFDF and FDF and come back in (ISO 19444-1; ISO 32000-1, 12.7.7; #950). */
class FormDataTest {

    /** A form with a text field in a group, a check box and a list box, all empty. */
    private fun empty(): ByteArray = TestPdf.build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 6 0 R 7 0 R] /DA (/Helv 10 Tf 0 g) /DR << /Font << /Helv 9 0 R >> >> >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Annots [5 0 R 6 0 R 7 0 R] >>",
            "<< /T (person) /Kids [5 0 R] >>",
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (name) /Parent 4 0 R /Rect [10 250 150 270] /F 4 /DA (/Helv 10 Tf 0 g) >>",
            "<< /Type /Annot /Subtype /Widget /FT /Btn /T (agree) /Rect [10 200 30 220] /F 4 /AS /Off /AP << /N << /Yes 8 0 R /Off 8 0 R >> >> >>",
            "<< /Type /Annot /Subtype /Widget /FT /Ch /Ff 2097152 /T (fruits) /Opt [(apple) (pear) (plum)] /Rect [10 100 150 180] /F 4 /DA (/Helv 10 Tf 0 g) >>",
            TestPdf.stream("", "/Type /XObject /Subtype /Form /BBox [0 0 20 20]"),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        ),
    )

    private fun filled(): PdfDocument {
        val doc = PdfDocument.open(empty())
        val editor = doc.edit()
        editor.setTextFieldValue(doc.formField("person.name")!!, "Ada & <Co>")
        editor.setButtonValue(doc.formField("agree")!!, "Yes")
        val data = """<?xml version="1.0"?><xfdf xmlns="http://ns.adobe.com/xfdf/"><fields><field name="fruits"><value>apple</value><value>plum</value></field></fields></xfdf>"""
        assertEquals(emptyList(), editor.importFormData(data.encodeToByteArray()))
        return PdfDocument.open(editor.saveIncremental())
    }

    private fun values(doc: PdfDocument) = doc.formFields.associate { it.fullyQualifiedName to (it.value to it.choiceSelection.indices) }

    @Test
    fun xfdf_nests_the_names_and_escapes_the_text() {
        val xfdf = filled().exportXfdf("form.pdf")
        assertContains(xfdf, """<f href="form.pdf"/>""")
        assertContains(xfdf, """<field name="person"><field name="name"><value>Ada &amp; &lt;Co&gt;</value></field>""")
        assertContains(xfdf, """<field name="agree"><value>Yes</value></field>""")
        assertContains(xfdf, """<field name="fruits"><value>apple</value><value>plum</value></field>""")
    }

    @Test
    fun the_empty_form_takes_the_values_back_from_either_format() {
        val source = filled()
        for (data in listOf(source.exportXfdf().encodeToByteArray(), source.exportFdf("form.pdf"))) {
            val doc = PdfDocument.open(empty())
            val editor = doc.edit()
            assertEquals(emptyList(), editor.importFormData(data))
            val back = PdfDocument.open(editor.saveIncremental())
            assertEquals(values(source), values(back))
            assertEquals(listOf(0, 2), back.formField("fruits")!!.choiceSelection.indices)
            val widget = back.pages[0].annotations.single { it.raw.getName("FT") == "Btn" }
            assertEquals("Yes", widget.raw.getName("AS"), "the check box shows its state")
        }
    }

    @Test
    fun fdf_has_its_catalog_and_names() {
        val fdf = filled().exportFdf("form.pdf").decodeToString()
        assertTrue(fdf.startsWith("%FDF-1.2"))
        assertContains(fdf, "/FDF")
        assertContains(fdf, "/F (form.pdf)")
        assertContains(fdf, "/V /Yes")
        assertContains(fdf, "/T (person)")
    }

    @Test
    fun a_name_the_form_lacks_is_reported() {
        val doc = PdfDocument.open(empty())
        val data = """<xfdf><fields><field name="nobody"><value>x</value></field><field name="agree"><value>Maybe</value></field></fields></xfdf>"""
        assertEquals(listOf("nobody", "agree"), doc.edit().importFormData(data.encodeToByteArray()))
    }
}
