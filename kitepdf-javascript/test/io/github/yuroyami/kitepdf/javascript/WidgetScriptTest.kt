package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfString
import kotlin.test.Test
import kotlinx.coroutines.test.TestResult
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scripts a tap on a widget runs: the tapped button's own (#359), the widget's `/A` chain run
 * as the field's mouse up event (#361), and a reset that gives the default values back (#439).
 */
class WidgetScriptTest {

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

    private fun script(source: String) = PdfAction.JavaScript(source, PdfDictionary(emptyMap()))

    @Test
    fun each_button_of_a_group_runs_its_own_mouse_up_script(): TestResult = scriptTest {
        val doc = PdfDocument.open(
            pdf(
                "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 7 0 R] >> >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>",
                "<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [5 0 R 6 0 R 7 0 R] >>",
                "<< /FT /Btn /Ff 49152 /T (choice) /V /Off /Kids [5 0 R 6 0 R] >>",
                "<< /Type /Annot /Subtype /Widget /Parent 4 0 R /Rect [30 30 50 50] " +
                    "/AA << /U << /S /JavaScript /JS (this.getField\\('log'\\).value = 'a') >> >> >>",
                "<< /Type /Annot /Subtype /Widget /Parent 4 0 R /Rect [80 30 100 50] " +
                    "/AA << /U << /S /JavaScript /JS (this.getField\\('log'\\).value = 'b') >> >> >>",
                "<< /Type /Annot /Subtype /Widget /FT /Tx /T (log) /V () /Rect [30 100 150 120] >>",
            ),
        )
        PdfScriptRunner(doc).use { runner ->
            runner.mouseUp("choice", 1)
            assertEquals("b", runner.formState.value("log"))
            runner.mouseUp("choice", 0)
            assertEquals("a", runner.formState.value("log"))
        }
    }

    /** A push button whose `/A` script writes what event it ran in, and a reset button for `a`. */
    private fun buttonsPdf(): ByteArray = pdf(
        "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R 6 0 R 7 0 R 8 0 R] /CO [6 0 R] >> >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 300 300] >>",
        "<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R 5 0 R 6 0 R 7 0 R 8 0 R] >>",
        "<< /Type /Annot /Subtype /Widget /FT /Tx /T (a) /V (0) /DV (1) /Rect [20 250 120 270] >>",
        "<< /Type /Annot /Subtype /Widget /FT /Tx /T (b) /V (0) /DV (2) /Rect [20 220 120 240] >>",
        "<< /Type /Annot /Subtype /Widget /FT /Tx /T (total) /V () /Rect [20 190 120 210] " +
            "/AA << /C << /S /JavaScript /JS (AFSimple_Calculate\\('SUM', new Array\\('a', 'b'\\)\\)) >> >> >>",
        "<< /Type /Annot /Subtype /Widget /FT /Tx /T (log) /V () /Rect [20 160 120 180] >>",
        "<< /Type /Annot /Subtype /Widget /FT /Btn /Ff 65536 /T (go) /Rect [20 120 120 140] " +
            "/A << /S /JavaScript /JS (this.getField\\('log'\\).value = event.type + ' ' + event.name + ' ' + event.targetName) >> >>",
    )

    @Test
    fun a_widget_action_script_runs_as_the_mouse_up_event_of_its_field(): TestResult = scriptTest {
        val doc = PdfDocument.open(buttonsPdf())
        PdfScriptRunner(doc).use { runner ->
            val action = doc.formField("go")?.widgets?.first()?.action
            assertTrue(runner.runWidgetAction("go", action!!), "a script is the handler's to run")
            assertEquals("Field MouseUp go", runner.formState.value("log"))
        }
    }

    @Test
    fun a_reset_action_gives_back_the_defaults_and_recalculates(): TestResult = scriptTest {
        val doc = PdfDocument.open(buttonsPdf())
        PdfScriptRunner(doc).use { runner ->
            runner.setFieldValue("a", "5")
            runner.setFieldValue("b", "6")
            assertEquals("11", runner.formState.value("total"))
            val reset = PdfAction.ResetForm(listOf(PdfString("a".encodeToByteArray())), flags = 0, raw = PdfDictionary(emptyMap()))
            assertTrue(runner.runWidgetAction("clear", reset))
            assertEquals("1", runner.formState.value("a"), "the default value, not the saved 0")
            assertEquals("7", runner.formState.value("total"), "the total follows the reset")
        }
    }

    @Test
    fun a_script_reset_gives_back_the_defaults_and_keeps_what_it_hid(): TestResult = scriptTest {
        val doc = PdfDocument.open(
            pdf(
                "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R] >> >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>",
                "<< /Type /Page /Parent 2 0 R /Resources << >> >>",
                "<< /FT /Tx /T (name) /V (Ada) /Rect [10 10 90 30] >>",
                "<< /T (address) /Kids [6 0 R] >>",
                "<< /FT /Tx /T (street) /Parent 5 0 R /V (Main St) /DV (Street) /Rect [10 40 90 60] >>",
            ),
        )
        PdfScriptRunner(doc).use { runner ->
            runner.run(script("this.resetForm(['address']);"))
            assertEquals("Street", runner.formState.value("address.street"), "a parent name reaches the field below it")
            assertEquals("Ada", runner.formState.value("name"))
            runner.formState.setValue("address.street", "Elm St")
            runner.run(script("this.resetForm('address');"))
            assertEquals("Street", runner.formState.value("address.street"), "one name counts as a list of one")

            runner.run(script("this.getField('name').display = display.hidden; this.resetForm();"))
            assertEquals("", runner.formState.value("name"), "no default value means no value")
            assertTrue(runner.formState.isHidden("name"), "a reset changes values only")
        }
    }
}
