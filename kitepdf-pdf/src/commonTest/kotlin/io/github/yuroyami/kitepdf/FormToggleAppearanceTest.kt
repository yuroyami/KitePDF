package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A checkbox or radio widget with no `/AP` of its own shows nothing when it is
 * ticked, because a viewer has no appearance to paint. The editor draws one.
 */
class FormToggleAppearanceTest {

    /**
     * A form whose widgets carry NO appearance streams at all: one checkbox,
     * and a two-widget radio group whose `/Opt` names the kids' export values.
     */
    private fun bareFormPdf(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = LinkedHashMap<Int, Int>()
        fun obj(n: Int, body: String) {
            offsets[n] = buf.size(); buf.append("$n 0 obj\n$body\nendobj\n".encodeToByteArray())
        }
        buf.append("%PDF-1.6\n%Äå\n".encodeToByteArray())
        obj(1, "<< /Type /Catalog /Pages 2 0 R /AcroForm 6 0 R >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Annots [4 0 R 7 0 R] /Contents 5 0 R >>")
        obj(
            4,
            "<< /Type /Annot /Subtype /Widget /FT /Btn /T (cb) /Rect [50 50 70 70] " +
                "/MK << /BG [1 1 1] /BC [0 0 0] >> /V /Off >>",
        )
        offsets[5] = buf.size()
        buf.append("5 0 obj\n<< /Length 1 >>\nstream\n \nendstream\nendobj\n".encodeToByteArray())
        obj(6, "<< /Fields [4 0 R 7 0 R] /DA (0 g) >>")
        // A radio group: /Ff bit 16 (32768) with two kid widgets.
        obj(7, "<< /FT /Btn /T (radio) /Ff 32768 /Opt [(Choice1) (Choice2)] /Kids [8 0 R 9 0 R] /V /Off >>")
        obj(8, "<< /Type /Annot /Subtype /Widget /Parent 7 0 R /Rect [50 100 66 116] /MK << /BC [0 0 0] >> >>")
        obj(9, "<< /Type /Annot /Subtype /Widget /Parent 7 0 R /Rect [50 130 66 146] /MK << /BC [0 0 0] >> >>")
        val xref = buf.size()
        val maxN = offsets.keys.max()
        buf.append("xref\n0 ${maxN + 1}\n0000000000 65535 f \n".encodeToByteArray())
        for (n in 1..maxN) {
            val off = offsets[n]
            buf.append(
                (
                    if (off == null) "0000000000 65535 f \n"
                    else "${off.toString().padStart(10, '0')} 00000 n \n"
                    ).encodeToByteArray(),
            )
        }
        buf.append("trailer\n<< /Size ${maxN + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".encodeToByteArray())
        return buf.toByteArray()
    }

    private fun appearanceStates(doc: PdfDocument, name: String): Set<String> {
        val f = doc.formField(name) ?: return emptySet()
        val widget = f.widgetDict
        val n = widget.getDict("AP", doc)?.get("N")?.resolve(doc) as? PdfDictionary
        return n?.keys ?: emptySet()
    }

    @Test
    fun a_checkbox_with_no_appearance_gets_one_drawn() {
        val doc = KitePDF.open(bareFormPdf())
        val cb = doc.formField("cb")
        assertNotNull(cb)
        assertTrue(appearanceStates(doc, "cb").isEmpty(), "the fixture really has no /AP")

        val editor = doc.edit()
        editor.setCheckbox(cb, true)
        val reopened = KitePDF.open(editor.saveIncremental())

        val states = appearanceStates(reopened, "cb")
        assertEquals(setOf("On", "Off"), states, "both states are drawn")
        assertEquals("On", reopened.formField("cb")!!.value)
        assertEquals("On", reopened.formField("cb")!!.widgetDict.getName("AS"))
    }

    @Test
    fun the_generated_appearance_actually_paints() {
        val doc = KitePDF.open(bareFormPdf())
        val editor = doc.edit()
        editor.setCheckbox(doc.formField("cb")!!, true)
        val reopened = KitePDF.open(editor.saveIncremental())

        val canvas = RecordingCanvas()
        reopened.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        val painted = canvas.calls.any {
            it is RecordingCanvas.Call.Fill || it is RecordingCanvas.Call.Stroke ||
                it is RecordingCanvas.Call.Glyphs
        }
        assertTrue(painted, "the ticked box draws something (got ${canvas.calls})")
    }

    @Test
    fun a_radio_group_draws_the_chosen_widget_and_clears_the_rest() {
        val doc = KitePDF.open(bareFormPdf())
        val radio = doc.formField("radio")
        assertNotNull(radio)
        val editor = doc.edit()
        editor.setButtonValue(radio, "Choice1")
        val reopened = KitePDF.open(editor.saveIncremental())

        assertEquals("Choice1", reopened.formField("radio")!!.value)
        val kids = reopened.formField("radio")!!.fieldDict.getArray("Kids", reopened)
        assertNotNull(kids)
        val states = kids.mapNotNull { k ->
            val d = (k as? PdfReference)?.let { reopened.resolve(it) } as? PdfDictionary
            d?.getName("AS")
        }
        assertEquals(listOf("Choice1", "Off"), states, "only the first widget is on")
    }

    @Test
    fun a_widget_that_already_has_an_appearance_keeps_it() {
        // The first pass draws the states; the second must reuse them.
        val doc = KitePDF.open(bareFormPdf())
        val editor = doc.edit()
        editor.setCheckbox(doc.formField("cb")!!, true)
        val once = editor.saveIncremental()

        val second = KitePDF.open(once)
        val before = appearanceStates(second, "cb")
        val editor2 = second.edit()
        editor2.setCheckbox(second.formField("cb")!!, true)
        val twice = KitePDF.open(editor2.saveIncremental())
        assertEquals(before, appearanceStates(twice, "cb"), "the second pass reuses the drawn states")
    }

    @Test
    fun a_radio_group_with_no_opt_is_left_alone() {
        // Nothing says which widget owns which value, so inventing one would
        // light up every radio in the group.
        val pdf = bareFormPdf().decodeToString().replace("/Opt [(Choice1) (Choice2)] ", "").encodeToByteArray()
        val doc = KitePDF.open(pdf)
        val editor = doc.edit()
        editor.setButtonValue(doc.formField("radio")!!, "Choice1")
        val reopened = KitePDF.open(editor.saveIncremental())
        val kids = reopened.formField("radio")!!.fieldDict.getArray("Kids", reopened)!!
        val states = kids.mapNotNull { k ->
            val d = (k as? PdfReference)?.let { reopened.resolve(it) } as? PdfDictionary
            d?.getDict("AP", reopened)
        }
        assertTrue(states.isEmpty(), "no appearances were invented")
    }
}
