package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What a tap on a form reaches: the widget under the point, found by its reference and with the
 * live visibility (#359, #360), the action chain it performs (#361), and the reset it can ask for
 * (#439).
 */
class WidgetTargetTest {

    /** A PDF whose objects are [bodies], numbered from 1. Object 1 is the catalog. */
    private fun pdf(vararg bodies: String): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        for ((index, body) in bodies.withIndex()) {
            offsets.add(sb.length)
            sb.append("${index + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** A form XObject that fills its 20 x 20 box, so a drawn widget records one fill. */
    private val box: String = "0 0 20 20 re f".let { "<< /Type /XObject /Subtype /Form /BBox [0 0 20 20] /Length ${it.length} >>\nstream\n$it\nendstream" }

    @Test
    fun a_tap_reaches_the_field_of_the_page_it_lands_on() {
        val doc = PdfDocument.open(
            pdf(
                "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [5 0 R 6 0 R] >> >>",
                "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 200 200] >>",
                "<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [5 0 R] >>",
                "<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [6 0 R] >>",
                "<< /Type /Annot /Subtype /Widget /FT /Tx /T (first) /Rect [30 30 50 50] >>",
                "<< /Type /Annot /Subtype /Widget /FT /Tx /T (second) /Rect [30 30 50 50] >>",
            ),
        )
        assertEquals("first", doc.pages[0].widgetAt(40.0, 40.0)?.field?.fullyQualifiedName)
        assertEquals("second", doc.pages[1].widgetAt(40.0, 40.0)?.field?.fullyQualifiedName)
        assertEquals("second", doc.pages[1].formFieldAt(40.0, 40.0)?.fullyQualifiedName)
    }

    /** A radio group of three buttons, each with its own on state and its own mouse up script. */
    private fun radioPdf(flags: Int = 49152, fileFlags: String = ""): ByteArray = pdf(
        "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>",
        "<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [5 0 R 6 0 R 7 0 R] >>",
        "<< /FT /Btn /Ff $flags /T (choice) /V /Off /Kids [5 0 R 6 0 R 7 0 R] >>",
        "<< /Type /Annot /Subtype /Widget /Parent 4 0 R /Rect [30 30 50 50] $fileFlags /AS /Off " +
            "/AP << /N << /a 8 0 R /Off 8 0 R >> >> /AA << /U << /S /JavaScript /JS (up a) >> >> >>",
        "<< /Type /Annot /Subtype /Widget /Parent 4 0 R /Rect [80 30 100 50] /AS /Off " +
            "/AP << /N << /b 8 0 R /Off 8 0 R >> >> /AA << /U << /S /JavaScript /JS (up b) >> >> >>",
        "<< /Type /Annot /Subtype /Widget /Parent 4 0 R /Rect [130 30 150 50] /AS /Off " +
            "/AP << /N << /c 8 0 R /Off 8 0 R >> >> /AA << /U << /S /JavaScript /JS (up c) >> >> >>",
        box,
    )

    @Test
    fun a_tap_reaches_the_button_under_the_point_with_its_own_state_and_scripts() {
        val doc = PdfDocument.open(radioPdf())
        val hit = assertNotNull(doc.pages[0].widgetAt(90.0, 40.0))
        assertEquals("choice", hit.field.fullyQualifiedName)
        assertEquals(1, hit.widgetIndex)
        assertEquals("b", hit.widget.onStateName)
        assertEquals("up b", (hit.widget.additionalActions?.mouseUp as? PdfAction.JavaScript)?.script)
        assertEquals(2, doc.pages[0].widgetAt(140.0, 40.0)?.widgetIndex)
        assertNull(doc.pages[0].widgetAt(65.0, 40.0), "between two buttons")
    }

    @Test
    fun a_widget_a_script_hid_lets_the_tap_through_to_the_widget_below() {
        val doc = PdfDocument.open(
            pdf(
                "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R] >> >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>",
                "<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R 5 0 R] >>",
                "<< /Type /Annot /Subtype /Widget /FT /Tx /T (below) /Rect [20 20 60 60] >>",
                "<< /Type /Annot /Subtype /Widget /FT /Btn /T (top) /Rect [30 30 50 50] >>",
            ),
        )
        val state = PdfFormState(doc)
        val page = doc.pages[0]
        assertEquals("top", page.widgetAt(40.0, 40.0, state)?.field?.fullyQualifiedName)
        state.setHidden("top", true)
        assertEquals("below", page.widgetAt(40.0, 40.0, state)?.field?.fullyQualifiedName)
        state.setHidden("below", true)
        assertNull(page.widgetAt(40.0, 40.0, state))
        // Without the live state the file decides, and the file shows both.
        assertEquals("top", page.widgetAt(40.0, 40.0)?.field?.fullyQualifiedName)
    }

    @Test
    fun a_widget_the_file_hides_takes_a_tap_and_draws_once_a_script_shows_it() {
        val doc = PdfDocument.open(radioPdf(fileFlags = "/F 2"))
        val state = PdfFormState(doc)
        val page = doc.pages[0]
        assertNull(page.widgetAt(40.0, 40.0, state), "the file hides button a")
        assertEquals(0, fills(page, state), "the hidden button a draws nothing")
        state.setHidden("choice", false)
        assertEquals(0, page.widgetAt(40.0, 40.0, state)?.widgetIndex)
        assertEquals(1, fills(page, state), "button a draws once a script shows it")
        state.setHidden("choice", true)
        assertNull(page.widgetAt(90.0, 40.0, state))
        assertEquals(0, fills(page, state) + fills(page, state, x = 90), "a script hides every button")
    }

    @Test
    fun each_widget_keeps_its_own_hidden_flag_while_a_form_is_filled() {
        // Button a is hidden by the file and b is not. Button b must still draw when the live form
        // says nothing about the field.
        val doc = PdfDocument.open(radioPdf(fileFlags = "/F 2"))
        val state = PdfFormState(doc)
        assertEquals(1, fills(doc.pages[0], state, x = 90), "button b draws")
        assertEquals(1, fills(doc.pages[0], null, x = 90), "and draws without a form state too")
    }

    /** The fills that land on the button whose box starts at [x], drawn with [state]. */
    private fun fills(page: PdfPage, state: PdfFormState?, x: Int = 40): Int {
        val canvas = RecordingCanvas()
        page.renderAnnotationsTo(canvas, KiteMatrix.IDENTITY, state)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().count { call ->
            val left = call.ctm.transformX(0.0, 0.0)
            left in (x - 10.0)..(x + 10.0)
        }
    }

    @Test
    fun an_action_chain_runs_depth_first_and_stops_at_a_loop() {
        val doc = PdfDocument.open(
            pdf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>",
                "<< /Type /Page /Parent 2 0 R /Resources << >> >>",
                "<< /S /JavaScript /JS (a) /Next [5 0 R << /S /JavaScript /JS (d) >>] >>",
                "<< /S /JavaScript /JS (b) /Next 6 0 R >>",
                // Leads back to b, which already ran.
                "<< /S /JavaScript /JS (c) /Next 5 0 R >>",
            ),
        )
        val first = assertNotNull(PdfAction.parse(doc.resolve(io.github.yuroyami.kitepdf.core.parser.PdfReference(4, 0)) as? io.github.yuroyami.kitepdf.core.parser.PdfDictionary, doc))
        val scripts = first.withNext(doc).map { (it as PdfAction.JavaScript).script }
        assertEquals(listOf("a", "b", "c", "d"), scripts)
    }

    @Test
    fun a_reset_gives_back_the_default_values_and_keeps_what_a_script_hid() {
        val doc = PdfDocument.open(
            pdf(
                "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R 7 0 R 8 0 R] >> >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>",
                "<< /Type /Page /Parent 2 0 R /Resources << >> >>",
                "<< /FT /Tx /T (name) /V (Ada) /Rect [10 10 90 30] >>",
                "<< /T (address) /Kids [6 0 R] >>",
                "<< /FT /Tx /T (street) /Parent 5 0 R /V (Main St) /DV (Street) /Rect [10 40 90 60] >>",
                "<< /FT /Btn /T (agree) /V /Yes /Rect [10 70 30 90] /AP << /N << /Yes 9 0 R /Off 9 0 R >> >> >>",
                "<< /FT /Btn /Ff 65536 /T (go) /Rect [10 100 60 120] >>",
                box,
            ),
        )
        val state = PdfFormState(doc)
        state.setValue("go", "pressed")
        state.setHidden("name", true)

        state.resetForm(listOf("address"))
        assertEquals("Street", state.value("address.street"), "a parent name reaches the field below it")
        assertEquals("Ada", state.value("name"), "a field not named keeps its value")

        state.resetForm()
        assertEquals("", state.value("name"), "no default value means no value")
        assertEquals("Off", state.value("agree"), "a check box with no default turns off")
        assertEquals("pressed", state.value("go"), "a push button holds no value to reset")
        assertEquals(true, state.isHidden("name"), "a reset changes values only")
    }

    @Test
    fun a_reset_action_names_fields_by_name_or_reference_and_can_exclude_them() {
        val doc = PdfDocument.open(
            pdf(
                "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R 6 0 R] >> >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>",
                "<< /Type /Page /Parent 2 0 R /Resources << >> >>",
                "<< /FT /Tx /T (one) /V (1) /DV (x) /Rect [10 10 90 30] >>",
                "<< /FT /Tx /T (two) /V (2) /DV (y) /Rect [10 40 90 60] >>",
                "<< /FT /Tx /T (three) /V (3) /DV (z) /Rect [10 70 90 90] >>",
            ),
        )
        val ref = io.github.yuroyami.kitepdf.core.parser.PdfReference(5, 0)
        val named = listOf(io.github.yuroyami.kitepdf.core.parser.PdfString("one".encodeToByteArray()), ref)
        val empty = io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap())

        val include = PdfFormState(doc)
        include.resetForm(PdfAction.ResetForm(named, flags = 0, raw = empty))
        assertEquals(listOf("x", "y", "3"), listOf("one", "two", "three").map { include.value(it) })

        val exclude = PdfFormState(doc)
        exclude.resetForm(PdfAction.ResetForm(named, flags = 1, raw = empty))
        assertEquals(listOf("1", "2", "z"), listOf("one", "two", "three").map { exclude.value(it) })

        val all = PdfFormState(doc)
        all.resetForm(PdfAction.ResetForm(null, flags = 1, raw = empty))
        assertEquals(listOf("x", "y", "z"), listOf("one", "two", "three").map { all.value(it) }, "no names means every field")
    }
}
