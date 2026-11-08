package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A runner runs a document's own scripts once, however often a viewer reports the document open,
 * and closing it stops a script that is still running (#365).
 */
class RunnerLifecycleTest {

    /** A text field `log`, and a document open action that runs [openScript]. */
    private fun pdf(openScript: String): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> /OpenAction << /S /JavaScript /JS ($openScript) >> >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R] >>")
        add("<< /Type /Annot /Subtype /Widget /FT /Tx /T (log) /V () /Rect [20 120 180 160] >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    @Test
    fun the_document_scripts_run_once_however_often_the_document_opens() {
        val doc = PdfDocument.open(pdf("var f = this.getField\\('log'\\); f.value = f.value + 'x';"))
        PdfScriptRunner(doc).use { runner ->
            runBlocking { runner.documentOpened() }
            runBlocking { runner.documentOpened() }
            assertEquals("x", runner.formState.value("log"))
        }
    }

    @Test
    fun closing_the_runner_stops_a_script_that_never_ends() {
        val doc = PdfDocument.open(pdf("while \\(true\\) {}"))
        val runner = PdfScriptRunner(doc, policy = PdfScriptPolicy.LONG_RUNNING)
        val opener = thread(isDaemon = true, name = "opener") { runCatching { runBlocking { runner.documentOpened() } } }
        // Long enough for the script to be well inside its loop.
        Thread.sleep(500)
        val closer = thread(isDaemon = true, name = "closer") { runner.close() }
        closer.join(10_000)
        assertFalse(closer.isAlive, "close did not return")
        opener.join(10_000)
        assertFalse(opener.isAlive, "the script is still running")
    }

    @Test
    fun closing_the_runner_does_not_wait_for_a_built_in_call_that_never_checks_its_deadline() {
        // join loops in the engine's C code with no interrupt poll, so neither the budget nor a
        // close stops it until it returns. The close must not wait for that (#996).
        val doc = PdfDocument.open(pdf("Array.prototype.join.call\\({length: 1073741824}, ''\\)"))
        val runner = PdfScriptRunner(doc)
        val opener = thread(isDaemon = true, name = "opener") { runCatching { runBlocking { runner.documentOpened() } } }
        Thread.sleep(500)
        val started = System.nanoTime()
        val closer = thread(isDaemon = true, name = "closer") { runner.close() }
        closer.join(10_000)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertFalse(closer.isAlive, "close did not return")
        assertTrue(millis < CLOSE_WAIT_MILLIS + 1_500, "close took $millis ms")
        assertTrue(opener.isAlive, "the call ended before the close, so this test shows nothing: make it longer")
        // Leave no busy thread behind for the next tests.
        opener.join(300_000)
    }

    @Test
    fun closing_on_the_thread_a_script_dialog_waits_for_returns_at_once() {
        // The host answers an alert from its UI thread. A close there, as Compose's onDispose
        // makes it while the dialog is up, must not wait for the script that waits for the UI (#862).
        val ui = java.util.concurrent.Executors.newSingleThreadExecutor()
        val dialogUp = java.util.concurrent.CountDownLatch(1)
        val closing = java.util.concurrent.CountDownLatch(1)
        val runner = PdfScriptRunner(PdfDocument.open(pdf("0")), onAlert = {
            dialogUp.countDown()
            // The reader answers once the UI thread is in the close, as when the screen turns with the dialog up.
            closing.await(10, java.util.concurrent.TimeUnit.SECONDS)
            ui.submit(java.util.concurrent.Callable { 1 }).get()
        })
        val script = thread(isDaemon = true, name = "script caller") {
            runCatching { runner.run(io.github.yuroyami.kitepdf.PdfAction.JavaScript("app.alert('hi')", io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap()))) }
        }
        assertTrue(dialogUp.await(10, java.util.concurrent.TimeUnit.SECONDS), "the dialog came up")
        val started = System.nanoTime()
        ui.submit { closing.countDown(); Thread.sleep(100); runner.close() }.get(10, java.util.concurrent.TimeUnit.SECONDS)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(millis < 600, "close waited $millis ms for the script")
        // The UI is free again, so the dialog answers, and the script call ends.
        script.join(10_000)
        assertFalse(script.isAlive, "the script call ended")
        ui.shutdown()
    }

    /** The engines KiteJS's QuickJS host has open in this process, which a closed engine leaves. */
    private fun registeredEngines(): Int {
        val host = Class.forName("io.github.yuroyami.kitejs.quickjs.bridge.QuickJsHost")
        val field = host.getDeclaredField("engines").apply { isAccessible = true }
        val ref = field.get(host.getField("INSTANCE").get(null))
        return ((ref as java.util.concurrent.atomic.AtomicReference<*>).get() as Map<*, *>).size
    }

    @Test
    fun a_close_from_a_callback_of_the_runner_closes_its_engine_when_the_call_ends() {
        // closeDoc() asks the host, which closes the runner from onRequest, on the script thread (#858).
        val before = registeredEngines()
        lateinit var runner: PdfScriptRunner
        var thrown: Throwable? = null
        runner = PdfScriptRunner(PdfDocument.open(pdf("0")), onRequest = { thrown = runCatching { runner.close() }.exceptionOrNull() })
        runner.run(io.github.yuroyami.kitepdf.PdfAction.JavaScript("this.closeDoc(); 'after'", io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap())))
        assertEquals(null, thrown, "close threw in the callback")
        assertTrue(runner.failures.none { "running a script" in it.message.orEmpty() }, "${runner.failures.map { it.message }}")
        assertEquals(before, registeredEngines(), "the engine stayed open")
        runner.close()
    }

    @Test
    fun a_close_from_a_book_script_console_returns_and_closes_the_session() {
        // onConsole runs on the chapter's own thread while the runner's thread waits for it (#858).
        val before = registeredEngines()
        lateinit var runner: EpubScriptRunner
        runner = EpubScriptRunner(ScriptBooks.chapter("<p>text</p><script>console.log('hello')</script>"), onConsole = { _, _ -> runner.close() })
        val opener = thread(isDaemon = true, name = "opener") { runCatching { runBlocking { runner.chapterOpened(0) } } }
        opener.join(15_000)
        assertFalse(opener.isAlive, "chapterOpened never returned")
        assertEquals(before, registeredEngines(), "an engine of the book stayed open")
    }

    @Test
    fun a_call_that_races_close_opens_no_engine_and_fails_as_closed() {
        val doc = PdfDocument.open(pdf("0"))
        val before = registeredEngines()
        val odd = ArrayList<String>()
        repeat(200) { trial ->
            val runner = PdfScriptRunner(doc)
            runner.run(io.github.yuroyami.kitepdf.PdfAction.JavaScript("1", io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap())))
            var last: Throwable? = null
            val caller = thread(isDaemon = true) {
                while (true) {
                    val e = runCatching { runner.run(io.github.yuroyami.kitepdf.PdfAction.JavaScript("1", io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap()))) }.exceptionOrNull()
                    if (e != null) { last = e; break }
                }
            }
            Thread.sleep((trial % 5).toLong())
            val closers = List(2) { thread(isDaemon = true) { runner.close() } }
            closers.forEach { it.join(10_000) }
            caller.join(10_000)
            val e = last
            if (e !is IllegalStateException || e.message != "the script runner is closed") odd += "trial $trial: $e"
        }
        assertEquals(emptyList(), odd, "a late call failed in another way")
        assertEquals(before, registeredEngines(), "a late call left an engine open")
    }

    @Test
    fun cancelling_the_caller_of_an_event_method_stops_its_script() {
        // The suspend methods are cancellable: the script stops at its next check, and writes nothing after (#865).
        val runner = PdfScriptRunner(PdfDocument.open(pdf("0")), policy = PdfScriptPolicy.LONG_RUNNING)
        val action = io.github.yuroyami.kitepdf.PdfAction.JavaScript(
            "var s = Date.now(); while (Date.now() - s < 1500) {} getField('log').value = 'written after cancel';",
            io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap()),
        )
        runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.Default) { runner.runAction(action) }
            kotlinx.coroutines.delay(200)
            val started = System.nanoTime()
            job.cancelAndJoin()
            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue(millis < 700, "cancelAndJoin took $millis ms")
        }
        assertEquals("", runner.formState.value("log").orEmpty(), "the script wrote after its caller was cancelled")
        // A script that never ends stops too, though the policy has no budget.
        runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                runner.runAction(io.github.yuroyami.kitepdf.PdfAction.JavaScript("while (true) {}", io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap())))
            }
            kotlinx.coroutines.delay(200)
            kotlinx.coroutines.withTimeout(3_000) { job.cancelAndJoin() }
        }
        // The runner still works for the next call.
        assertEquals("2", runner.run(io.github.yuroyami.kitepdf.PdfAction.JavaScript("1 + 1", io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap()))))
        runner.close()
    }
}
