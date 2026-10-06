@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestResult

/** A promise, as far as waiting for one goes. */
private external interface Waitable : JsAny {
    fun then(onFulfilled: (JsAny?) -> JsAny?): JsAny?
}

private fun timeout(ms: Int): Waitable = js("new Promise(function (resolve) { setTimeout(resolve, ms); })")

private fun startTicking(): JsAny = js("(globalThis.kitePdfTicks = 0, setInterval(function () { globalThis.kitePdfTicks++; }, 1))")

private fun stopTicking(timer: JsAny): Unit = js("clearInterval(timer)")

private fun ticks(): Int = js("globalThis.kitePdfTicks")

/** Waits [ms] of real time, which the test's virtual clock would skip. */
private suspend fun realWait(ms: Int) = suspendCoroutine { continuation ->
    timeout(ms).then {
        continuation.resume(Unit)
        null
    }
}

/** Script source that keeps busy for [ms] of real time. */
private fun busy(ms: Int) = "var t0 = Date.now(); while (Date.now() - t0 < $ms) {}"

/**
 * On the web a long document script pauses now and then, so the page goes on drawing and its
 * timers go on running while the script works (#489). Node 26 has the WebAssembly stack
 * switching this needs, as Chrome does.
 */
class WebPauseTest {

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
    fun a_long_book_script_lets_the_timers_of_the_page_run(): TestResult = scriptTest {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter("<p>Busy.</p><script>${busy(300)} console.log('done');</script>")
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message })
        val timer = startTicking()
        try {
            val before = ticks()
            runner.chapterOpened(0)
            val during = ticks() - before
            assertTrue(during > 3, "only $during timer ticks ran while the chapter's script ran")
            assertEquals(listOf("done"), console)
            assertEquals(emptyList(), runner.failures.map { it.message })
        } finally {
            stopTicking(timer)
            runner.close()
        }
    }

    @Test
    fun a_long_pdf_action_lets_the_timers_run_and_the_next_call_waits_for_it(): TestResult = scriptTest {
        PdfScriptRunner(PdfDocument.open(pdf())).use { runner ->
            val timer = startTicking()
            try {
                val before = ticks()
                val action = async { runner.runAction(script("${busy(300)} this.getField('a').value = 'action';")) }
                realWait(30)
                // The reader types while the action is paused: the commit waits for the action.
                val commit = async { runner.commit("a", "typed") }
                action.await()
                assertTrue(commit.await())
                val during = ticks() - before
                assertTrue(during > 3, "only $during timer ticks ran while the action ran")
                assertEquals("typed", runner.formState.value("a"))
                assertEquals(emptyList(), runner.failures.map { it.message })
            } finally {
                stopTicking(timer)
            }
        }
    }

    @Test
    fun a_call_outside_the_viewer_runs_the_script_in_one_go(): TestResult = scriptTest {
        PdfScriptRunner(PdfDocument.open(pdf())).use { runner ->
            val timer = startTicking()
            try {
                val before = ticks()
                runner.run(script("${busy(50)} this.getField('a').value = 'done';"))
                assertEquals(before, ticks())
                assertEquals("done", runner.formState.value("a"))
                assertEquals(emptyList(), runner.failures.map { it.message })
            } finally {
                stopTicking(timer)
            }
        }
    }

    @Test
    fun closing_the_runner_stops_a_paused_script(): TestResult = scriptTest {
        val book = ScriptBooks.chapter("<p>Forever.</p><script>for (;;) {}</script>")
        val runner = EpubScriptRunner(book)
        val opening = async { runner.chapterOpened(0) }
        realWait(30)
        runner.close()
        opening.await()
        assertTrue(runner.failures.any { "interrupted" in it.message.orEmpty() }, "${runner.failures.map { it.message }}")
    }
}
