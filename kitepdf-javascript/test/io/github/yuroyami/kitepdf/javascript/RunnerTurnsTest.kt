package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.epub.EpubPage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.test.TestResult
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two runners open at once, as an app that shows two documents has them. On JavaScript and
 * WebAssembly every engine shares the page's one thread, which holds one open engine, so the
 * runners take turns there; elsewhere each has threads of its own. Either way each runner's
 * scripts run whenever it is called (#553).
 */
class RunnerTurnsTest {

    private val opened = ArrayList<AutoCloseable>()

    @AfterTest
    fun closeRunners() {
        opened.forEach { it.close() }
    }

    private fun <T : AutoCloseable> T.kept(): T = also { opened += it }

    private fun EpubPage.paintsBlue() = RecordingCanvas().also { renderTo(it) }.calls
        .filterIsInstance<RecordingCanvas.Call.Fill>().any { it.color.b > 0.9 && it.color.r < 0.1 && it.color.g < 0.1 }

    /** A one-page PDF whose document-level script is [script], with a link whose action is [linkScript]. */
    private fun pdf(script: String, linkScript: String): PdfDocument {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R /Names << /JavaScript << /Names [(lib) 5 0 R] >> >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Annots [4 0 R] >>",
            "<< /Type /Annot /Subtype /Link /Rect [0 0 50 50] /A << /S /JavaScript /JS ($linkScript) >> >>",
            "<< /S /JavaScript /JS ($script) >>",
        )
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body ->
            offsets += sb.length
            sb.append("${i + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (off in offsets) sb.append("${off.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    private fun PdfDocument.link(): PdfAction.JavaScript = pages[0].annotations.single().action as PdfAction.JavaScript

    @Test
    fun two_books_open_at_once_each_run_their_scripts(): TestResult = scriptTest {
        val first = ScriptBooks.buttonPage()
        val firstPage = first.page(KiteLocation(0, 0))
        val second = ScriptBooks.chapter("""<p>Second.</p><script>console.log('second ran');</script>""")
        val console = ArrayList<String>()
        val firstScripts = EpubScriptRunner(first).kept()
        val secondScripts = EpubScriptRunner(second, onConsole = { _, message -> console += message }).kept()

        firstScripts.chapterOpened(0)
        secondScripts.chapterOpened(0)
        assertEquals(listOf("second ran"), console)

        firstScripts.tap(firstPage, 52.5, 90.0)
        assertTrue(firstPage.paintsBlue(), "the first book's button works after the second book's scripts ran")
        secondScripts.chapterOpened(0)
        assertEquals(emptyList(), firstScripts.failures.map { it.message })
        assertEquals(emptyList(), secondScripts.failures.map { it.message })
    }

    @Test
    fun a_form_and_a_book_open_at_once_each_run_their_scripts(): TestResult = scriptTest {
        val form = pdf("function answer() { return 6 * 7; }", "answer()")
        val formScripts = PdfScriptRunner(form).kept()
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter("""<p>Book.</p><script>console.log('book ran');</script>""")
        val bookScripts = EpubScriptRunner(book, onConsole = { _, message -> console += message }).kept()

        formScripts.documentOpened()
        bookScripts.chapterOpened(0)
        assertEquals(listOf("book ran"), console)
        // The function comes from the document's own script, which runs again where the engine had to close.
        assertEquals("42", formScripts.run(form.link()))
        // Where they take turns, the book's chapter starts over and its script runs again.
        bookScripts.chapterOpened(0)
        assertEquals("42", formScripts.run(form.link()))
        assertEquals(emptyList(), formScripts.failures.map { it.message })
        assertEquals(emptyList(), bookScripts.failures.map { it.message })
    }

    @Test
    fun two_forms_open_at_once_each_run_their_scripts(): TestResult = scriptTest {
        val first = pdf("function name() { return 'first'; }", "name()")
        val second = pdf("function name() { return 'second'; }", "name()")
        val firstScripts = PdfScriptRunner(first).kept()
        val secondScripts = PdfScriptRunner(second).kept()
        firstScripts.documentOpened()
        secondScripts.documentOpened()
        assertEquals(
            listOf("first", "second", "first", "second"),
            listOf(firstScripts, secondScripts, firstScripts, secondScripts).zip(listOf(first, second, first, second))
                .map { (scripts, doc) -> scripts.run(doc.link()) },
        )
        assertEquals(emptyList(), firstScripts.failures.map { it.message })
        assertEquals(emptyList(), secondScripts.failures.map { it.message })
    }

    @Test
    fun a_closed_runner_leaves_the_thread_to_the_next(): TestResult = scriptTest {
        val book = ScriptBooks.chapter("""<p>Book.</p><script>console.log('ran');</script>""")
        val console = ArrayList<String>()
        EpubScriptRunner(book, onConsole = { _, message -> console += message }).use { it.chapterOpened(0) }
        val form = pdf("function answer() { return 42; }", "answer()")
        val formScripts = PdfScriptRunner(form).kept()
        formScripts.documentOpened()
        assertEquals("42", formScripts.run(form.link()))
        EpubScriptRunner(book, onConsole = { _, message -> console += message }).kept().chapterOpened(0)
        assertEquals(listOf("ran", "ran"), console)
    }
}
