package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A form with no appearance streams, drawn from the file and then from the live values a reader
 * or a script has changed (ISO 32000-1 §12.7.3.3 on constructing field appearances).
 */
class FormStateRenderTest {

    /** One page carrying a text field with a value, a push button with a caption, and no `/AP`. */
    private fun formPdf(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = LinkedHashMap<Int, Int>()
        fun obj(n: Int, body: String) {
            offsets[n] = buf.size()
            buf.append("$n 0 obj\n$body\nendobj\n".encodeToByteArray())
        }
        buf.append("%PDF-1.7\n%Äå\n".encodeToByteArray())
        obj(1, "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R] /DA (/Helv 0 Tf 0 g) >> >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Annots [4 0 R 5 0 R] >>")
        obj(
            4,
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (name) /V (Ada) /Rect [20 150 220 175] " +
                "/DA (/Helv 12 Tf 0 g) /MK << /BG [0.9 0.9 0.6] /BC [0 0 1] >> /BS << /W 2 /S /S >> >>",
        )
        obj(
            5,
            "<< /Type /Annot /Subtype /Widget /FT /Btn /Ff 65536 /T (go) /Rect [20 40 120 80] " +
                "/MK << /BG [0.8 0.8 0.8] /BC [0 0 0] /CA (Fire) >> /BS << /W 1 >> >>",
        )
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

    private fun drawnText(canvas: RecordingCanvas): String =
        canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().joinToString("") { call ->
            call.glyphs.joinToString("") { it.text ?: "" }
        }

    @Test
    fun a_form_without_appearance_streams_still_draws_its_boxes_and_values() {
        val doc = PdfDocument.open(formPdf())
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        val fills = canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        val strokes = canvas.calls.filterIsInstance<RecordingCanvas.Call.Stroke>()
        assertTrue(fills.size >= 2, "expected a background for each widget, got ${fills.size}")
        assertTrue(strokes.size >= 2, "expected a border for each widget, got ${strokes.size}")
        val text = drawnText(canvas)
        assertTrue("Ada" in text, "the field value is missing from <$text>")
        assertTrue("Fire" in text, "the button caption is missing from <$text>")
    }

    @Test
    fun a_value_in_the_live_state_is_what_gets_drawn() {
        val doc = PdfDocument.open(formPdf())
        val state = PdfFormState(doc)
        state.setValue("name", "Grace")
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY, state)
        val text = drawnText(canvas)
        assertTrue("Grace" in text, "the live value is missing from <$text>")
        assertFalse("Ada" in text, "the file's old value is still drawn in <$text>")
        // The file itself did not change.
        assertEquals("Ada", doc.formField("name")?.value)
    }

    @Test
    fun a_field_the_state_hides_is_not_drawn() {
        val doc = PdfDocument.open(formPdf())
        val state = PdfFormState(doc)
        state.setHidden("go", true)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY, state)
        val text = drawnText(canvas)
        assertTrue("Ada" in text, "the other field should still draw, got <$text>")
        assertFalse("Fire" in text, "the hidden button is still drawn in <$text>")
    }

    /** A check box with no appearance stream draws its box, and its mark when it is ticked. */
    @Test
    fun a_check_box_without_an_appearance_stream_draws_its_box() {
        val buf = ByteArrayBuilder()
        val offsets = LinkedHashMap<Int, Int>()
        fun obj(n: Int, body: String) {
            offsets[n] = buf.size()
            buf.append("$n 0 obj\n$body\nendobj\n".encodeToByteArray())
        }
        buf.append("%PDF-1.7\n%\u00c4\u00e5\n".encodeToByteArray())
        obj(1, "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [4 0 R] >>")
        obj(
            4,
            "<< /Type /Annot /Subtype /Widget /FT /Btn /T (agree) /V /Off /Rect [30 60 60 90] " +
                "/MK << /BG [1 1 1] /BC [1 0 0] >> /BS << /W 2 >> >>",
        )
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

        val doc = PdfDocument.open(buf.toByteArray())
        val off = RecordingCanvas().also { doc.pages[0].renderTo(it, KiteMatrix.IDENTITY) }
        assertTrue(
            off.calls.filterIsInstance<RecordingCanvas.Call.Fill>().isNotEmpty(),
            "the unticked box still draws its background",
        )
        assertTrue(
            off.calls.filterIsInstance<RecordingCanvas.Call.Stroke>().isNotEmpty(),
            "the unticked box still draws its red border",
        )
        assertTrue(drawnText(off).isEmpty(), "an unticked box has no mark")

        val state = PdfFormState(doc)
        state.setValue("agree", "Yes")
        val on = RecordingCanvas().also { doc.pages[0].renderTo(it, KiteMatrix.IDENTITY, state) }
        assertTrue(drawnText(on).isNotEmpty(), "a ticked box draws its mark")
    }

    @Test
    fun the_state_reports_changes_and_can_be_reset() {
        val doc = PdfDocument.open(formPdf())
        val state = PdfFormState(doc)
        val seen = mutableListOf<String>()
        val stop = state.onChange { seen.add("${it.fieldName}=${it.value}") }

        state.setValue("name", "Grace")
        assertEquals("Grace", state.value("name"))
        assertTrue(state.isChanged("name"))
        state.setValue("name", "Grace") // the same value again is not a change
        state.setValue("missing", "x") // a field the document does not have is ignored
        assertEquals(listOf("name=Grace"), seen)
        assertEquals(setOf("name"), state.changedFields)

        state.reset("name")
        assertEquals("Ada", state.value("name"))
        assertFalse(state.isChanged("name"))

        stop()
        state.setValue("name", "Hopper")
        assertEquals(listOf("name=Grace", "name=Ada"), seen)
    }

    @Test
    fun read_only_follows_the_file_until_the_state_says_otherwise() {
        val doc = PdfDocument.open(formPdf())
        val state = PdfFormState(doc)
        assertFalse(state.isReadOnly("name"))
        state.setReadOnly("name", true)
        assertTrue(state.isReadOnly("name"))
        state.resetAll()
        assertFalse(state.isReadOnly("name"))
    }
}
