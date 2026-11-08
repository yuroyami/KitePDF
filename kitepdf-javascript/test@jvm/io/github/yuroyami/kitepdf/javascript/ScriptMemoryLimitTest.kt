package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfDocument
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A host sets how much native memory a document's or a chapter's engine may take, and the default
 * is a few tens of MiB, not the 512 MiB a compiled program needs (#958).
 */
class ScriptMemoryLimitTest {

    private val closeables = ArrayList<AutoCloseable>()

    @AfterTest
    fun close() {
        closeables.forEach { it.close() }
    }

    private fun pdf(): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** About [mib] MiB of arrays kept alive, then "done". */
    private fun allocate(mib: Int) = "var keep = []; for (var i = 0; i < ${mib * 16}; i++) keep.push(new Array(8192).fill(i)); 'done'"

    private fun runPdf(policy: PdfScriptPolicy, script: String): Pair<String?, List<String>> {
        val runner = PdfScriptRunner(PdfDocument.open(pdf()), policy = policy).also { closeables += it }
        val result = runner.run(io.github.yuroyami.kitepdf.PdfAction.JavaScript(script, io.github.yuroyami.kitepdf.core.parser.PdfDictionary(emptyMap())))
        return result to runner.failures.mapNotNull { it.message }
    }

    @Test
    fun a_document_engine_stops_at_the_limit_of_its_policy() {
        val (small, failures) = runPdf(PdfScriptPolicy(memoryLimitBytes = 16L shl 20), allocate(32))
        assertEquals(null, small)
        assertTrue(failures.any { "out of memory" in it }, "$failures")
        assertEquals("done", runPdf(PdfScriptPolicy(memoryLimitBytes = 128L shl 20), allocate(32)).first)
    }

    @Test
    fun the_default_limit_is_a_few_tens_of_mib_and_a_long_running_program_keeps_512() {
        assertEquals(64L shl 20, PdfScriptPolicy().memoryLimitBytes)
        assertEquals(64L shl 20, EpubScriptPolicy().memoryLimitBytes)
        assertEquals(KiteJsScriptEngine.MEMORY_LIMIT, PdfScriptPolicy.LONG_RUNNING.memoryLimitBytes)
        assertTrue(runPdf(PdfScriptPolicy(), allocate(128)).second.any { "out of memory" in it })
    }

    @Test
    fun a_chapter_engine_stops_at_the_limit_of_its_policy() {
        val failures = ArrayList<String>()
        val book = ScriptBooks.chapter("<p id='p'>text</p><script>${allocate(128)}; document.getElementById('p').textContent = 'filled';</script>")
        val runner = EpubScriptRunner(book).also { closeables += it }
        runBlocking { runner.chapterOpened(0) }
        failures += runner.failures.mapNotNull { it.message }
        assertTrue(failures.any { "out of memory" in it }, "$failures")
    }
}
