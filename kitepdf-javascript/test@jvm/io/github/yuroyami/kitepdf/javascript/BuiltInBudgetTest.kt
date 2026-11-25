package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * One call of a built-in that loops in the engine, such as `join` on an object whose length is
 * 2^53 - 1, stops at the budget like any other script, in a document and in a book (#996).
 */
class BuiltInBudgetTest {

    private val calls = listOf(
        "Array.prototype.join.call({length: 2**53 - 1}, '')",
        "Array.prototype.reverse.call({length: 2**53 - 1})",
        "Array.prototype.copyWithin.call({length: 2**53 - 1}, 0, 1)",
    )

    /** A document whose open action runs [script]. */
    private fun pdf(script: String): ByteArray {
        val js = script.replace("(", "\\(").replace(")", "\\)")
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R /OpenAction << /S /JavaScript /JS ($js) >> >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** Runs [block] on a thread of its own and fails when it takes longer than [limitMillis]. */
    private fun within(limitMillis: Long, what: String, block: () -> Unit) {
        var error: Throwable? = null
        val worker = thread(isDaemon = true, name = "budget") { runCatching(block).onFailure { error = it } }
        worker.join(limitMillis)
        assertFalse(worker.isAlive, "$what ran past ${limitMillis} ms")
        error?.let { throw it }
    }

    @Test
    fun a_document_script_of_one_long_built_in_call_stops_at_the_budget() {
        for (call in calls) {
            val runner = PdfScriptRunner(PdfDocument.open(pdf(call)), policy = PdfScriptPolicy(budgetMillis = BUDGET))
            within(BUDGET + SLACK, call) { runBlocking { runner.documentOpened() } }
            assertTrue(runner.failures.any { "interrupted" in it.message.orEmpty() }, "$call: ${runner.failures.map { it.message }}")
            runner.close()
        }
    }

    @Test
    fun a_book_script_of_one_long_built_in_call_stops_at_the_budget() {
        for (call in calls) {
            val book = ScriptBooks.chapter("<p>text</p><script>$call</script>")
            val runner = EpubScriptRunner(book, EpubScriptPolicy(budgetMillis = BUDGET))
            within(BUDGET + SLACK, call) { runBlocking { runner.chapterOpened(0) } }
            assertTrue(runner.failures.any { "interrupted" in it.message.orEmpty() }, "$call: ${runner.failures.map { it.message }}")
            runner.close()
        }
    }

    private companion object {
        const val BUDGET = 500L

        /** Time for the engine to start and for a chapter to lay out, on a loaded machine. */
        const val SLACK = 4_500L
    }
}
