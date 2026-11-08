package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.script.KiteScriptEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A blocking method called while a suspend method's script is paused, as on the web, refuses
 * with an exception the host can tell apart, and stores nothing past the field's scripts (#879).
 * A caller's engine takes the path the web takes, so a fake engine that pauses shows it here.
 */
class PausedScriptCallTest {

    /** An engine whose pausing evaluation waits for [resume] before it runs the script. */
    private class PausingEngine(private val inner: KiteScriptEngine) : KiteScriptEngine by inner {
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        override suspend fun evaluatePausing(source: String, name: String): String? {
            paused.complete(Unit)
            resume.await()
            return inner.evaluate(source, name)
        }
    }

    /** A page with the field `amount`, whose validate script refuses every value. */
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
        add("<< /Type /Annot /Subtype /Widget /FT /Tx /T (amount) /V () /Rect [20 120 180 160] /AA << /V << /S /JavaScript /JS (event.rc = false;) >> >> >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    @Test
    fun a_blocking_call_during_a_paused_script_refuses_and_stores_nothing() = runBlocking {
        val engine = PausingEngine(KiteJsScriptEngine())
        val runner = PdfScriptRunner(PdfDocument.open(pdf()), engine = engine)
        val action = io.github.yuroyami.kitepdf.PdfAction.JavaScript("1", io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap()))
        val running = launch { runner.runAction(action) }
        engine.paused.await()
        val refusal = assertFailsWith<IllegalStateException> { runner.setFieldValue("amount", "12") }
        assertEquals("a script is paused; call the suspend method or wait for it", refusal.message)
        assertFailsWith<IllegalStateException> { runner.run(action) }
        assertEquals("", runner.formState.value("amount").orEmpty(), "a value stored past the validate script")
        assertTrue(runner.failures.isEmpty(), "the refusal is the caller's, not a script failure: ${runner.failures.map { it.message }}")
        engine.resume.complete(Unit)
        running.join()
        // Once the script ends, the same call runs, and the validate script refuses the value.
        runner.setFieldValue("amount", "12")
        assertEquals("", runner.formState.value("amount").orEmpty())
        runner.close()
        engine.close()
    }
}
