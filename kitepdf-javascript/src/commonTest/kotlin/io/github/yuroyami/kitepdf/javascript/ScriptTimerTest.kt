package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import kotlin.test.Test
import kotlinx.coroutines.test.TestResult
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A script timer fires after its delay, measured on the runner's clock from the moment the script
 * set it, whatever time base the viewer pumps with (#367). A script that sets or clears a timer
 * tells the listeners, so a viewer pumps only while one waits (#368).
 */
class ScriptTimerTest {

    /** One text field `a`. */
    private fun pdf(): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R] >>")
        add("<< /Type /Annot /Subtype /Widget /FT /Tx /T (a) /V () /Rect [20 120 180 160] >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    private fun script(source: String) = PdfAction.JavaScript(source, PdfDictionary(emptyMap()))

    @Test
    fun a_timer_fires_after_its_delay_on_the_runner_clock(): TestResult = scriptTest {
        var now = 1_000L
        PdfScriptRunner(PdfDocument.open(pdf()), clock = { now }).use { runner ->
            runner.run(script("app.setTimeOut(\"getField('a').value = 'done'\", 5000)"))
            // A viewer pumps with its own frame time, which is far past the delay on another time base.
            assertEquals(5_000L, runner.pumpTimers(1_000_000))
            assertEquals("", runner.formState.value("a"))
            now = 5_999
            runner.pumpTimers(now)
            assertEquals("", runner.formState.value("a"), "one millisecond early")
            now = 6_000
            runner.pumpTimers(now)
            assertEquals("done", runner.formState.value("a"))
        }
    }

    @Test
    fun a_timer_set_after_an_idle_while_waits_its_full_delay(): TestResult = scriptTest {
        var now = 0L
        PdfScriptRunner(PdfDocument.open(pdf()), clock = { now }).use { runner ->
            runner.run(script("app.setTimeOut(\"getField('a').value = 'first'\", 10)"))
            now = 10
            runner.pumpTimers(now)
            assertEquals("first", runner.formState.value("a"))
            // A long while with no timer, so nothing pumps.
            now = 100_000
            runner.run(script("app.setTimeOut(\"getField('a').value = 'second'\", 5000)"))
            now = 100_016
            runner.pumpTimers(now)
            assertEquals("first", runner.formState.value("a"), "the new timer fired at once")
            now = 105_000
            runner.pumpTimers(now)
            assertEquals("second", runner.formState.value("a"))
        }
    }

    @Test
    fun setting_and_clearing_a_timer_tells_the_listeners(): TestResult = scriptTest {
        PdfScriptRunner(PdfDocument.open(pdf())).use { runner ->
            var told = 0
            val stop = runner.onTimersChanged { told++ }
            runner.run(script("var id = app.setInterval(\"getField('a').value = 'tick'\", 1000);"))
            assertTrue(told >= 1, "a new timer is reported")
            val afterSet = told
            runner.run(script("app.clearInterval(id);"))
            assertTrue(told > afterSet, "a cleared timer is reported")
            stop()
            val afterStop = told
            runner.run(script("app.setTimeOut(\"getField('a').value = 'late'\", 1000);"))
            assertEquals(afterStop, told, "a listener that stopped hears nothing")
        }
    }
}
