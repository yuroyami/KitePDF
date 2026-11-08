package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlinx.coroutines.test.TestResult
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
        format: String = "AFNumber_Format\\(2, 0, 0, 0, '\\$', true\\)",
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
                "/F << /S /JavaScript /JS ($format) >> >> >>",
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
    fun an_empty_field_counts_as_zero_and_the_result_is_rounded(): TestResult = scriptTest {
        // PDFium counts an empty field as 0 and stores six decimals (#772).
        for ((function, a, b, expected) in listOf(
            listOf("SUM", "0.1", "0.2", "0.3"), listOf("PRD", "1.1", "1.1", "1.21"),
            listOf("AVG", "10", "", "5"), listOf("MIN", "10", "", "0"), listOf("MAX", "-5", "", "0"),
        )) {
            val doc = PdfDocument.open(invoicePdf(calculate = "AFSimple_Calculate\\('$function', new Array\\('a', 'b'\\)\\)"))
            PdfScriptRunner(doc).use { runner ->
                runner.setFieldValue("a", a)
                runner.setFieldValue("b", b)
                assertEquals(expected, runner.formState.value("total"), "$function of '$a' and '$b'")
            }
        }
    }

    @Test
    fun the_negative_styles_write_a_sign_or_parentheses_and_two_of_them_turn_the_text_red(): TestResult = scriptTest {
        // PDFium, AFNumber_Format: the sign for style 0 only, and red text for styles 1 and 3 (#766).
        for ((style, text, red) in listOf(
            Triple(0, "-$1,234.50", false), Triple(1, "$1,234.50", true),
            Triple(2, "($1,234.50)", false), Triple(3, "($1,234.50)", true),
        )) {
            val doc = PdfDocument.open(
                invoicePdf(calculate = "event.value = -1234.5", format = "AFNumber_Format\\(2, 0, $style, 0, '\\$', true\\)"),
            )
            PdfScriptRunner(doc).use { runner ->
                runner.setFieldValue("a", "1")
                assertEquals(text, runner.formattedValue("total"), "style $style")
                assertEquals(if (red) listOf(1.0, 0.0, 0.0) else null, runner.formState.textColor("total"), "style $style")
            }
        }
    }

    @Test
    fun a_number_keystroke_refuses_an_exponent_and_the_other_decimal_mark(): TestResult = scriptTest {
        // PDFium and pdf.js take digits and the style's mark only (#785).
        val doc = PdfDocument.open(invoicePdf())
        PdfScriptRunner(doc).use { runner ->
            assertFalse(runner.keystroke("digits", "1e5").accepted)
            assertFalse(runner.keystroke("digits", "1,5").accepted, "style 0 takes a period")
            assertTrue(runner.keystroke("digits", "1.5").accepted)
        }
    }

    @Test
    fun calculate_false_stops_the_calculations(): TestResult = scriptTest {
        // The Acrobat reference, Doc.calculate (#781).
        for (flag in listOf("this.calculate", "app.calculate")) {
            val doc = PdfDocument.open(invoicePdf())
            PdfScriptRunner(doc).use { runner ->
                runner.run(io.github.yuroyami.kitepdf.PdfAction.JavaScript("$flag = false;", io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap())))
                runner.setFieldValue("a", "2")
                assertEquals("", runner.formState.value("total").orEmpty(), flag)
                runner.run(io.github.yuroyami.kitepdf.PdfAction.JavaScript("$flag = true; this.calculateNow();", io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap())))
                assertEquals("2", runner.formState.value("total"), flag)
            }
        }
    }

    @Test
    fun a_calculate_script_that_does_not_compile_keeps_the_fields_own_value(): TestResult = scriptTest {
        // It answered with the result of the event before it, another field's value (#751).
        val doc = PdfDocument.open(invoicePdf(calculate = "event.value = \\(;"))
        PdfScriptRunner(doc).use { runner ->
            // The keystroke script of digits runs first, and leaves its result behind.
            runner.setFieldValue("digits", "42")
            runner.setFieldValue("a", "2")
            assertEquals("", runner.formState.value("total").orEmpty())
            assertEquals("2", runner.formState.value("a"))
        }
    }

    /** A parent field amount with kids amount.0 and amount.1, and a total that sums the parent. */
    private fun parentFieldPdf(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = LinkedHashMap<Int, Int>()
        fun obj(n: Int, body: String) {
            offsets[n] = buf.size()
            buf.append("$n 0 obj\n$body\nendobj\n".encodeToByteArray())
        }
        buf.append("%PDF-1.7\n".encodeToByteArray())
        obj(1, "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 7 0 R] /CO [7 0 R] >> >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 300] /Annots [5 0 R 6 0 R 7 0 R] >>")
        obj(4, "<< /FT /Tx /T (amount) /Kids [5 0 R 6 0 R] >>")
        obj(5, "<< /Type /Annot /Subtype /Widget /Parent 4 0 R /T (0) /V (0) /Rect [20 200 120 220] >>")
        obj(6, "<< /Type /Annot /Subtype /Widget /Parent 4 0 R /T (1) /V (0) /Rect [20 160 120 180] >>")
        obj(7, "<< /Type /Annot /Subtype /Widget /FT /Tx /T (total) /V () /Rect [20 120 120 140] " +
            "/AA << /C << /S /JavaScript /JS (AFSimple_Calculate\\('SUM', 'amount'\\)) >> >> >>")
        val xref = buf.size()
        val maxN = offsets.keys.max()
        buf.append("xref\n0 ${maxN + 1}\n0000000000 65535 f \n".encodeToByteArray())
        for (n in 1..maxN) buf.append("${offsets[n]!!.toString().padStart(10, '0')} 00000 n \n".encodeToByteArray())
        buf.append("trailer\n<< /Size ${maxN + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".encodeToByteArray())
        return buf.toByteArray()
    }

    @Test
    fun a_calculation_over_a_parent_field_sums_its_kids(): TestResult = scriptTest {
        // ISO 32000-1, 12.7.3.2: getField of a parent, and getArray of its terminal fields (#753).
        val doc = PdfDocument.open(parentFieldPdf())
        PdfScriptRunner(doc).use { runner ->
            runner.setFieldValue("amount.0", "2")
            runner.setFieldValue("amount.1", "3")
            assertEquals("5", runner.formState.value("total"))
        }
    }

    @Test
    fun a_total_recalculates_when_a_field_changes(): TestResult = scriptTest {
        val doc = PdfDocument.open(invoicePdf())
        PdfScriptRunner(doc).use { runner ->
            runner.setFieldValue("a", "2")
            runner.setFieldValue("b", "3")
            assertEquals("5", runner.formState.value("total"))
        }
    }

    /** The plain shape of a calculation, with no helper library: the example in the issue. */
    @Test
    fun a_calculate_script_that_adds_two_fields_by_hand(): TestResult = scriptTest {
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
    fun a_calculate_script_that_calls_calculate_now_does_not_nest_rounds(): TestResult = scriptTest {
        // Each calculateNow() started a round inside the running one, without end (#962).
        val doc = PdfDocument.open(
            invoicePdf(
                calculate = "this.calculateNow\\(\\); event.value = this.getField\\('a'\\).value + this.getField\\('b'\\).value",
            ),
        )
        PdfScriptRunner(doc).use { runner ->
            val start = kotlin.time.TimeSource.Monotonic.markNow()
            runner.setFieldValue("a", "2")
            runner.setFieldValue("b", "3")
            assertEquals("5", runner.formState.value("total"))
            kotlin.test.assertTrue(start.elapsedNow().inWholeSeconds < 20, "took ${start.elapsedNow()}")
        }
    }

    @Test
    fun the_format_script_decides_what_the_field_shows(): TestResult = scriptTest {
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
    fun the_page_draws_the_formatted_text_and_formats_only_what_changed(): TestResult = scriptTest {
        // ISO 32000-1, 12.6.3, Table 196: the F action decides the text a field displays (#758).
        val counting = "globalThis.runs = \\(globalThis.runs || 0\\) + 1; AFNumber_Format\\(2, 0, 0, 0, '\\$', true\\)"
        val doc = PdfDocument.open(invoicePdf(format = counting))
        PdfScriptRunner(doc).use { runner ->
            fun runs(): String {
                runner.run(io.github.yuroyami.kitepdf.PdfAction.JavaScript(
                    "this.getField('digits').value = String(globalThis.runs);", io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap()),
                ))
                return runner.formState.value("digits").orEmpty()
            }
            fun drawn(): String {
                val canvas = io.github.yuroyami.kitepdf.core.render.RecordingCanvas()
                doc.pages[0].renderAnnotationsTo(canvas, io.github.yuroyami.kitepdf.core.render.KiteMatrix.IDENTITY, runner.formState)
                return canvas.calls.filterIsInstance<io.github.yuroyami.kitepdf.core.render.RecordingCanvas.Call.Glyphs>().joinToString(" ") { it.text }
            }
            runner.setFieldValue("a", "1200")
            runner.setFieldValue("b", "34.5")
            assertEquals("1234.5", runner.formState.value("total"))
            assertEquals("${'$'}1,234.50", runner.formState.formattedValue("total"))
            assertTrue("${'$'}1,234.50" in drawn(), drawn())
            assertEquals("2", runs(), "one format run for each change of the total")
            // A commit that leaves the total as it was formats nothing.
            runner.setFieldValue("digits", "7")
            assertEquals("2", runs())
            // With the caret, the field shows the value the reader edits; without it, the format again.
            runner.focus("total", 0)
            assertTrue("1234.5" in drawn() && "${'$'}1,234.50" !in drawn(), drawn())
            runner.blur("total", 0)
            assertTrue("${'$'}1,234.50" in drawn(), drawn())
        }
    }

    @Test
    fun a_keystroke_script_can_refuse_what_is_typed(): TestResult = scriptTest {
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
    fun a_script_reaches_the_document_and_its_fields(): TestResult = scriptTest {
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
    fun a_script_writes_a_field_and_hides_another(): TestResult = scriptTest {
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
    fun a_timer_runs_when_the_host_pumps_it(): TestResult = scriptTest {
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
    fun a_policy_that_denies_scripts_runs_none_of_them(): TestResult = scriptTest {
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

    @Test
    fun a_policy_that_denies_scripts_opens_no_engine(): TestResult = scriptTest {
        // The engine's setup ran under the event budget, so five seconds after the runner was made
        // the first value of a DENY runner was refused as an interrupted script (#894).
        var now = 0L
        val doc = PdfDocument.open(invoicePdf())
        PdfScriptRunner(doc, policy = PdfScriptPolicy.DENY, clock = { now }).use { runner ->
            now = 60_000
            assertTrue(runner.setFieldValue("a", "2"))
            assertEquals("2", runner.formState.value("a"))
            assertEquals(emptyList(), runner.failures, "no engine opened, so nothing failed")
        }
    }

    @Test
    fun the_first_script_long_after_the_runner_was_made_runs(): TestResult = scriptTest {
        // The API's setup ran under the clock of an event that had not started (#869).
        var now = 0L
        val doc = PdfDocument.open(invoicePdf())
        PdfScriptRunner(doc, clock = { now }).use { runner ->
            now = 60_000
            runner.setFieldValue("a", "2")
            runner.setFieldValue("b", "3")
            assertEquals("5", runner.formState.value("total"))
            assertEquals(emptyList(), runner.failures)
        }
    }

    @Test
    fun the_time_a_dialog_waits_for_the_reader_is_no_script_time(): TestResult = scriptTest {
        // PdfScriptPolicy.budgetMillis measures the script's own work (#872).
        var now = 0L
        val doc = PdfDocument.open(invoicePdf())
        PdfScriptRunner(doc, clock = { now }, onResponse = { now += 6_000; "Ada" }).use { runner ->
            runner.run(io.github.yuroyami.kitepdf.PdfAction.JavaScript(
                "var r = app.response('Name?'); for (var i = 0; i < 5000; i++) {} getField('a').value = r;",
                io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap()),
            ))
            assertEquals("Ada", runner.formState.value("a"))
            assertEquals(emptyList(), runner.failures)
        }
    }

    /** What a script asks for outside the document reaches the host, and nothing else happens. */
    @Test
    fun a_request_to_leave_the_document_reaches_the_host(): TestResult = scriptTest {
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
