package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A form that behaves the way Acrobat and Chrome make it behave: a total that recalculates, a
 * currency field that formats itself, and a keystroke script that refuses what it does not like
 * (ISO 32000-1 §12.6.3 for the triggers, §12.7.2 for the calculation order).
 */
class FormScriptTest {

    /**
     * Three fields: `a` and `b` are typed into, `total` calculates their sum with the helper
     * library and formats it as currency. The form declares the calculation order.
     */
    private fun invoicePdf(
        calculate: String = "AFSimple_Calculate\\('SUM', new Array\\('a', 'b'\\)\\)",
    ): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = LinkedHashMap<Int, Int>()
        fun obj(n: Int, body: String) {
            offsets[n] = buf.size()
            buf.append("$n 0 obj\n$body\nendobj\n".encodeToByteArray())
        }
        buf.append("%PDF-1.7\n%Äå\n".encodeToByteArray())
        obj(1, "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R 6 0 R 7 0 R] /CO [6 0 R] /DA (/Helv 0 Tf 0 g) >> >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 300] /Annots [4 0 R 5 0 R 6 0 R 7 0 R] >>")
        obj(4, "<< /Type /Annot /Subtype /Widget /FT /Tx /T (a) /V (0) /Rect [20 200 120 220] >>")
        obj(5, "<< /Type /Annot /Subtype /Widget /FT /Tx /T (b) /V (0) /Rect [20 160 120 180] >>")
        obj(
            6,
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (total) /V () /Rect [20 120 120 140] " +
                "/AA << /C << /S /JavaScript /JS ($calculate) >> " +
                "/F << /S /JavaScript /JS (AFNumber_Format\\(2, 0, 0, 0, '\\$', true\\)) >> >> >>",
        )
        obj(
            7,
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (digits) /V () /Rect [20 80 120 100] " +
                "/AA << /K << /S /JavaScript /JS (AFNumber_Keystroke\\(2, 0, 0, 0, '', true\\)) >> >> >>",
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

    @Test
    fun a_total_recalculates_when_a_field_changes() {
        val doc = PdfDocument.open(invoicePdf())
        PdfScriptRunner(doc).use { runner ->
            runner.setFieldValue("a", "2")
            runner.setFieldValue("b", "3")
            assertEquals("5", runner.formState.value("total"))
        }
    }

    /** The plain shape of a calculation, with no helper library: the example in the issue. */
    @Test
    fun a_calculate_script_that_adds_two_fields_by_hand() {
        val doc = PdfDocument.open(
            invoicePdf(
                calculate = "event.value = this.getField\\('a'\\).value + this.getField\\('b'\\).value",
            ),
        )
        PdfScriptRunner(doc).use { runner ->
            runner.setFieldValue("a", "2")
            runner.setFieldValue("b", "3")
            assertEquals("5", runner.formState.value("total"))
        }
    }

    @Test
    fun the_format_script_decides_what_the_field_shows() {
        val doc = PdfDocument.open(invoicePdf())
        PdfScriptRunner(doc).use { runner ->
            runner.setFieldValue("a", "1200")
            runner.setFieldValue("b", "34.5")
            // The stored value stays a number, so the next calculation still works with it.
            assertEquals("1234.5", runner.formState.value("total"))
            assertEquals("${'$'}1,234.50", runner.formattedValue("total"))
        }
    }

    @Test
    fun a_keystroke_script_can_refuse_what_is_typed() {
        val doc = PdfDocument.open(invoicePdf())
        PdfScriptRunner(doc).use { runner ->
            assertTrue(runner.keystroke("digits", "4").accepted)
            assertFalse(runner.keystroke("digits", "x").accepted, "a letter is not a number")
            assertFalse(runner.setFieldValue("digits", "abc"), "committing a bad value is refused")
            assertEquals("", runner.formState.value("digits"), "nothing was stored")
            assertTrue(runner.setFieldValue("digits", "42"))
            assertEquals("42", runner.formState.value("digits"))
        }
    }

    /** The script model itself: the document's members are globals, as they are in Chrome. */
    @Test
    fun a_script_reaches_the_document_and_its_fields() {
        val doc = PdfDocument.open(invoicePdf())
        val messages = mutableListOf<String>()
        PdfScriptRunner(doc, onAlert = { alert -> messages.add(alert.message); 1 }).use { runner ->
            runner.setFieldValue("a", "7")
            runner.run(
                io.github.yuroyami.kitepdf.PdfAction.JavaScript(
                    script = "app.alert(getField('a').value + ',' + this.getField('a').value + ',' + numPages + ',' + this.numFields)",
                    raw = io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap()),
                ),
            )
            assertEquals(listOf("7,7,1,4"), messages)
        }
    }

    @Test
    fun a_script_writes_a_field_and_hides_another() {
        val doc = PdfDocument.open(invoicePdf())
        PdfScriptRunner(doc).use { runner ->
            runner.run(
                io.github.yuroyami.kitepdf.PdfAction.JavaScript(
                    script = "getField('a').value = 12; getField('b').display = display.hidden;",
                    raw = io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap()),
                ),
            )
            assertEquals("12", runner.formState.value("a"))
            assertTrue(runner.formState.isHidden("b"))
        }
    }

    /** `app.setInterval` gives the host a timer to pump; nothing runs on its own. */
    @Test
    fun a_timer_runs_when_the_host_pumps_it() {
        val doc = PdfDocument.open(invoicePdf())
        var now = 0L
        PdfScriptRunner(doc, clock = { now }).use { runner ->
            runner.run(
                io.github.yuroyami.kitepdf.PdfAction.JavaScript(
                    script = "var ticks = 0; app.setInterval('ticks = ticks + 1; getField(\"a\").value = ticks', 10)",
                    raw = io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap()),
                ),
            )
            assertTrue(runner.hasTimers)
            now = 10
            runner.pumpTimers(now)
            assertEquals("1", runner.formState.value("a"))
            now = 20
            runner.pumpTimers(now)
            assertEquals("2", runner.formState.value("a"))
        }
    }

    @Test
    fun a_policy_that_denies_scripts_runs_none_of_them() {
        val doc = PdfDocument.open(invoicePdf())
        PdfScriptRunner(doc, policy = PdfScriptPolicy.DENY).use { runner ->
            runner.setFieldValue("a", "2")
            runner.setFieldValue("b", "3")
            // The values are still stored, because a reader may fill a form without its scripts.
            assertEquals("2", runner.formState.value("a"))
            // The total never calculated.
            assertEquals("", runner.formState.value("total"))
        }
    }

    /** What a script asks for outside the document reaches the host, and nothing else happens. */
    @Test
    fun a_request_to_leave_the_document_reaches_the_host() {
        val doc = PdfDocument.open(invoicePdf())
        val requests = mutableListOf<PdfScriptRequest>()
        PdfScriptRunner(doc, onRequest = { requests.add(it) }).use { runner ->
            runner.run(
                io.github.yuroyami.kitepdf.PdfAction.JavaScript(
                    script = "app.launchURL('https://example.org'); this.print(); this.submitForm('https://example.org/post')",
                    raw = io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap()),
                ),
            )
            assertEquals(
                listOf(
                    PdfScriptRequest.LaunchUrl("https://example.org"),
                    PdfScriptRequest.Print,
                    PdfScriptRequest.SubmitForm("https://example.org/post"),
                ),
                requests,
            )
        }
    }
}
