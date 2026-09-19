package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parts of a form a script reaches: the triggers of the document, the page and each widget
 * (ISO 32000-1 §12.6.3), every widget of a field (§12.7.3.1), and the widgets a malformed file
 * leaves out of the field tree (§12.7.2).
 */
class FormActionsAndWidgetsTest {

    /** A document whose objects are written out verbatim, so a test can shape a malformed file. */
    private fun pdf(objects: List<Pair<Int, String>>, trailerRoot: Int = 1): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = LinkedHashMap<Int, Int>()
        buf.append("%PDF-1.7\n%Äå\n".encodeToByteArray())
        for ((n, body) in objects) {
            offsets[n] = buf.size()
            buf.append("$n 0 obj\n$body\nendobj\n".encodeToByteArray())
        }
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
        buf.append("trailer\n<< /Size ${maxN + 1} /Root $trailerRoot 0 R >>\nstartxref\n$xref\n%%EOF\n".encodeToByteArray())
        return buf.toByteArray()
    }

    @Test
    fun a_page_reports_its_open_and_close_scripts() {
        val doc = PdfDocument.open(
            pdf(
                listOf(
                    1 to "<< /Type /Catalog /Pages 2 0 R >>",
                    2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                    3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] " +
                        "/AA << /O << /S /JavaScript /JS (start()) >> /C << /S /JavaScript /JS (stop()) >> >> >>",
                ),
            ),
        )
        assertEquals("start()", (doc.pages[0].openAction as PdfAction.JavaScript).script)
        assertEquals("stop()", (doc.pages[0].closeAction as PdfAction.JavaScript).script)
    }

    @Test
    fun the_document_reports_its_open_action_and_its_triggers() {
        val doc = PdfDocument.open(
            pdf(
                listOf(
                    1 to "<< /Type /Catalog /Pages 2 0 R /OpenAction << /S /JavaScript /JS (app.alert\\(1\\)) >> " +
                        "/AA << /WP << /S /JavaScript /JS (before()) >> /DP << /S /JavaScript /JS (after()) >> >> >>",
                    2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                    3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>",
                ),
            ),
        )
        assertEquals("app.alert(1)", (doc.openAction as PdfAction.JavaScript).script)
        val triggers = assertNotNull(doc.additionalActions)
        assertEquals("before()", (triggers.willPrint as PdfAction.JavaScript).script)
        assertEquals("after()", (triggers.didPrint as PdfAction.JavaScript).script)
        assertNull(triggers.willSave)
    }

    /** A destination in `/OpenAction` is reported as a GoTo, so one type covers both spellings. */
    @Test
    fun an_open_action_that_is_a_destination_reads_as_a_goto() {
        val doc = PdfDocument.open(
            pdf(
                listOf(
                    1 to "<< /Type /Catalog /Pages 2 0 R /OpenAction [3 0 R /Fit] >>",
                    2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                    3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>",
                ),
            ),
        )
        assertTrue(doc.openAction is PdfAction.GoTo, "expected a GoTo, got ${doc.openAction}")
    }

    @Test
    fun a_widget_reports_its_keystroke_and_mouse_scripts() {
        val doc = PdfDocument.open(
            pdf(
                listOf(
                    1 to "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R] >> >>",
                    2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                    3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [4 0 R 5 0 R] >>",
                    4 to "<< /Type /Annot /Subtype /Widget /FT /Tx /T (key_input) /Rect [10 10 90 30] " +
                        "/AA << /K << /S /JavaScript /JS (key_pressed\\(event.change\\)) >> >> >>",
                    5 to "<< /Type /Annot /Subtype /Widget /FT /Btn /Ff 65536 /T (fire_button) /Rect [100 10 140 30] " +
                        "/AA << /D << /S /JavaScript /JS (key_down\\(' '\\)) >> /U << /S /JavaScript /JS (key_up\\(' '\\)) >> >> >>",
                ),
            ),
        )
        val input = assertNotNull(doc.formField("key_input"))
        assertEquals("key_pressed(event.change)", (input.additionalActions?.keystroke as PdfAction.JavaScript).script)
        val button = assertNotNull(doc.formField("fire_button"))
        assertEquals("key_down(' ')", (button.additionalActions?.mouseDown as PdfAction.JavaScript).script)
        assertEquals("key_up(' ')", (button.additionalActions?.mouseUp as PdfAction.JavaScript).script)
        // The same scripts reach a caller that walks the page's annotations instead.
        assertEquals(
            "key_pressed(event.change)",
            (doc.pages[0].annotations[0].additionalActions?.keystroke as PdfAction.JavaScript).script,
        )
        assertNull(doc.pages[0].annotations[0].additionalActions?.calculate)
    }

    @Test
    fun a_widget_without_extra_actions_reports_none() {
        val doc = PdfDocument.open(
            pdf(
                listOf(
                    1 to "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> >>",
                    2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                    3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [4 0 R] >>",
                    4 to "<< /Type /Annot /Subtype /Widget /FT /Tx /T (plain) /Rect [10 10 90 30] >>",
                ),
            ),
        )
        assertNull(doc.formField("plain")?.additionalActions)
    }

    @Test
    fun a_radio_group_keeps_every_button() {
        val doc = PdfDocument.open(
            pdf(
                listOf(
                    1 to "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> >>",
                    2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                    3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [5 0 R 6 0 R 7 0 R] >>",
                    4 to "<< /FT /Btn /Ff 32768 /T (choice) /V /b /Kids [5 0 R 6 0 R 7 0 R] >>",
                    5 to "<< /Type /Annot /Subtype /Widget /Parent 4 0 R /Rect [30 30 50 50] " +
                        "/AP << /N << /a 8 0 R /Off 8 0 R >> >> /AS /Off >>",
                    6 to "<< /Type /Annot /Subtype /Widget /Parent 4 0 R /Rect [80 30 100 50] " +
                        "/AP << /N << /b 8 0 R /Off 8 0 R >> >> /AS /b >>",
                    7 to "<< /Type /Annot /Subtype /Widget /Parent 4 0 R /Rect [130 30 150 50] " +
                        "/AP << /N << /c 8 0 R /Off 8 0 R >> >> /AS /Off >>",
                    8 to "<< /Type /XObject /Subtype /Form /BBox [0 0 20 20] /Length 1 >>\nstream\n \nendstream",
                ),
            ),
        )
        val field = assertNotNull(doc.formField("choice"))
        assertEquals(3, field.widgets.size)
        assertEquals(listOf("a", "b", "c"), field.widgets.map { it.onStateName })
        assertEquals(listOf(30.0, 80.0, 130.0), field.widgets.map { it.rect!!.left })
        // The field keeps naming its first widget, so callers that knew only one do not change.
        assertEquals(30.0, field.rect!!.left)
    }

    @Test
    fun a_form_whose_widgets_are_only_on_the_page_still_reports_its_fields() {
        val doc = PdfDocument.open(
            pdf(
                listOf(
                    1 to "<< /Type /Catalog /Pages 2 0 R >>",
                    2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                    3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [4 0 R 5 0 R] >>",
                    4 to "<< /Type /Annot /Subtype /Widget /FT /Tx /T (field_0) /Ff 2 /V (hello) /Rect [10 10 90 30] >>",
                    5 to "<< /Type /Annot /Subtype /Widget /FT /Btn /Ff 65536 /T (fire) /Rect [100 10 140 30] >>",
                ),
            ),
        )
        assertNull(doc.acroForm, "this file has no /AcroForm at all")
        assertEquals(listOf("field_0", "fire"), doc.formFields.map { it.fullyQualifiedName })
        assertEquals("hello", doc.formField("field_0")?.value)
        assertEquals(PdfFormField.FieldType.Button, doc.formField("fire")?.type)
    }

    /** A widget the field tree already reached must not be reported twice. */
    @Test
    fun a_widget_inside_the_field_tree_is_not_collected_again() {
        val doc = PdfDocument.open(
            pdf(
                listOf(
                    1 to "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> >>",
                    2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                    3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [4 0 R 5 0 R] >>",
                    4 to "<< /Type /Annot /Subtype /Widget /FT /Tx /T (named) /Rect [10 10 90 30] >>",
                    5 to "<< /Type /Annot /Subtype /Widget /FT /Tx /T (stray) /Rect [10 40 90 60] >>",
                ),
            ),
        )
        assertEquals(listOf("named", "stray"), doc.formFields.map { it.fullyQualifiedName })
    }
}
