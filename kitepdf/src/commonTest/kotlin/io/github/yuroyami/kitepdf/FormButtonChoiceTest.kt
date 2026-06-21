package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.parser.PdfInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Button (checkbox/radio) and choice (combo/list) form filling via [io.github.yuroyami.kitepdf.writer.PdfEditor].
 */
class FormButtonChoiceTest {

    private fun acroFormPdf(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = LinkedHashMap<Int, Int>()
        fun obj(n: Int, body: String) { offsets[n] = buf.size(); buf.append("$n 0 obj\n$body\nendobj\n".encodeToByteArray()) }
        fun stream(n: Int, dict: String, content: String) {
            val c = content.encodeToByteArray()
            offsets[n] = buf.size()
            buf.append("$n 0 obj\n<< $dict /Length ${c.size} >>\nstream\n".encodeToByteArray())
            buf.append(c); buf.append("\nendstream\nendobj\n".encodeToByteArray())
        }
        buf.append("%PDF-1.6\n%Äå\n".encodeToByteArray())
        obj(1, "<< /Type /Catalog /Pages 2 0 R /AcroForm 6 0 R >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Annots [4 0 R 9 0 R] /Contents 5 0 R >>")
        obj(4, "<< /Type /Annot /Subtype /Widget /FT /Btn /T (cb) /Rect [50 50 70 70] " +
            "/AP << /N << /Yes 7 0 R /Off 8 0 R >> >> /AS /Off /V /Off >>")
        stream(5, "", " ")
        obj(6, "<< /Fields [4 0 R 9 0 R] >>")
        obj(7, "<< /Type /XObject /Subtype /Form /BBox [0 0 20 20] /Length 24 >>\nstream\n0 0 1 rg 0 0 20 20 re f\nendstream")
        obj(8, "<< /Type /XObject /Subtype /Form /BBox [0 0 20 20] /Length 4 >>\nstream\n \nendstream")
        obj(9, "<< /Type /Annot /Subtype /Widget /FT /Ch /T (ch) /Rect [100 100 200 120] " +
            "/Opt [(Apple) (Banana) (Cherry)] /V (Apple) >>")
        val xref = buf.size()
        val maxN = offsets.keys.max()
        buf.append("xref\n0 ${maxN + 1}\n0000000000 65535 f \n".encodeToByteArray())
        for (n in 1..maxN) {
            val off = offsets[n]
            buf.append((if (off == null) "0000000000 65535 f \n"
                else "${off.toString().padStart(10, '0')} 00000 n \n").encodeToByteArray())
        }
        buf.append("trailer\n<< /Size ${maxN + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".encodeToByteArray())
        return buf.toByteArray()
    }

    // obj 7 has a hand-written /Length that may not match exactly; recovery handles it,
    // but keep the appearance content valid so the round-trip parses cleanly.

    @Test fun checkbox_fill_sets_value_and_AS() {
        val doc = KitePDF.open(acroFormPdf())
        val cb = doc.formField("cb")
        assertNotNull(cb)
        assertEquals(PdfFormField.FieldType.Button, cb.type)

        val editor = doc.edit()
        editor.setCheckbox(cb, true)
        val out = editor.saveIncremental()

        val reopened = KitePDF.open(out)
        val cb2 = reopened.formField("cb")!!
        assertEquals("Yes", cb2.value)
        assertEquals("Yes", cb2.widgetDict.getName("AS"))
    }

    @Test fun checkbox_uncheck_sets_off() {
        val doc = KitePDF.open(acroFormPdf())
        val editor = doc.edit()
        editor.setCheckbox(doc.formField("cb")!!, false)
        val reopened = KitePDF.open(editor.saveIncremental())
        assertEquals("Off", reopened.formField("cb")!!.value)
        assertEquals("Off", reopened.formField("cb")!!.widgetDict.getName("AS"))
    }

    @Test fun choice_fill_sets_value_and_index() {
        val doc = KitePDF.open(acroFormPdf())
        val ch = doc.formField("ch")
        assertNotNull(ch)
        assertEquals(PdfFormField.FieldType.Choice, ch.type)

        val editor = doc.edit()
        editor.setChoiceValue(ch, "Banana")
        val reopened = KitePDF.open(editor.saveIncremental())
        val ch2 = reopened.formField("ch")!!
        assertEquals("Banana", ch2.value)
        // /I should carry the selected index (1 = Banana).
        val i = ch2.fieldDict.getArray("I")?.firstOrNull()
        assertEquals(1L, (i as? PdfInt)?.value)
    }
}
