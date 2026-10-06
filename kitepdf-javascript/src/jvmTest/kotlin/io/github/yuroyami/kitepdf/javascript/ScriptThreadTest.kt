package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A runner answers on any thread. A KiteJS engine refuses a call from a thread other than the one
 * that opened it, and a viewer calls from a pool, so the runner keeps its engine on a thread of its
 * own (#355).
 */
class ScriptThreadTest {

    /** A text field `t`, and a push button `b` whose mouse up script adds an x to `t` through the host API. */
    private fun buttonPdf(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = LinkedHashMap<Int, Int>()
        fun obj(n: Int, body: String) {
            offsets[n] = buf.size()
            buf.append("$n 0 obj\n$body\nendobj\n".encodeToByteArray())
        }
        buf.append("%PDF-1.7\n".encodeToByteArray())
        obj(1, "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R] >> >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R 5 0 R] >>")
        obj(4, "<< /Type /Annot /Subtype /Widget /FT /Tx /T (t) /V () /Rect [20 120 180 160] >>")
        obj(
            5,
            "<< /Type /Annot /Subtype /Widget /FT /Btn /Ff 65536 /T (b) /Rect [20 20 90 60] " +
                "/AA << /U << /S /JavaScript /JS (var f = this.getField\\('t'\\); f.value = f.value + 'x';) >> >> >>",
        )
        val xref = buf.size()
        buf.append("xref\n0 6\n0000000000 65535 f \n".encodeToByteArray())
        for (n in 1..5) buf.append("${offsets.getValue(n).toString().padStart(10, '0')} 00000 n \n".encodeToByteArray())
        buf.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".encodeToByteArray())
        return buf.toByteArray()
    }

    private fun onThread(name: String, block: () -> Unit): Throwable? {
        var failure: Throwable? = null
        thread(name = name) {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            }
        }.join()
        return failure
    }

    @Test
    fun calls_from_two_threads_reach_the_one_engine() {
        PdfScriptRunner(PdfDocument.open(buttonPdf())).use { runner ->
            assertNull(onThread("A") { runBlocking { runner.mouseUp("b") } })
            assertNull(onThread("B") { runBlocking { runner.mouseUp("b") } })
            runBlocking { runner.mouseUp("b") }
            assertTrue(runner.failures.isEmpty(), "scripts failed: ${runner.failures.map { it.message }}")
            assertEquals("xxx", runner.formState.value("t"))
        }
    }

    @Test
    fun two_runners_called_from_one_thread_each_keep_an_engine() {
        val one = PdfScriptRunner(PdfDocument.open(buttonPdf()))
        val two = PdfScriptRunner(PdfDocument.open(buttonPdf()))
        try {
            assertNull(onThread("C") {
                runBlocking { one.mouseUp("b") }
                runBlocking { two.mouseUp("b") }
                runBlocking { two.mouseUp("b") }
            })
            assertTrue(one.failures.isEmpty() && two.failures.isEmpty())
            assertEquals("x", one.formState.value("t"))
            assertEquals("xx", two.formState.value("t"))
        } finally {
            one.close()
            two.close()
        }
    }

    /** The engine runs on a thread of its own, never on the caller's. */
    @Test
    fun the_engine_runs_on_the_runner_thread() {
        var seen: String? = null
        PdfScriptRunner(PdfDocument.open(buttonPdf()), onConsole = { seen = Thread.currentThread().name }).use { runner ->
            runner.run(io.github.yuroyami.kitepdf.PdfAction.JavaScript("console.println('here')", raw = emptyRaw()))
        }
        assertEquals("kitepdf-scripts", seen)
    }

    @Test
    fun a_closed_runner_refuses_calls_instead_of_hanging() {
        val runner = PdfScriptRunner(PdfDocument.open(buttonPdf()))
        runBlocking { runner.mouseUp("b") }
        runner.close()
        val failure = runCatching { runBlocking { runner.mouseUp("b") } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "got $failure")
    }

    private fun emptyRaw() = io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap())
}
