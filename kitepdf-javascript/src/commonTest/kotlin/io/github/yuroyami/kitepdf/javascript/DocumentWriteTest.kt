package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * `document.write` writes into an HTML chapter and throws in an XHTML one, which is an XML
 * document (#603). Each expected line is what headless Chromium logs for the same chapter.
 */
class DocumentWriteTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private val script = """
        var out = [];
        function n(f) { try { f(); return 'ok'; } catch (e) { return e.name + ': ' + e.message; } }
        out.push('write ' + n(function () { document.write('<i id="w">w</i>'); }));
        out.push('writeln ' + n(function () { document.writeln('<i id="w2">w</i>'); }));
        out.push('written ' + (document.getElementById('w') !== null) + ' ' + (document.getElementById('w2') !== null));
        var made = document.implementation.createHTMLDocument('');
        out.push('made ' + n(function () { made.open(); made.write('<p id="m">m</p>'); made.close(); }) + ' ' + (made.getElementById('m') !== null));
        console.log(out.join('\n'));
    """.trimIndent()

    private suspend fun logged(html: Boolean): List<String> {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter("<p>x</p><script src=\"a.js\"></script>", extraFiles = mapOf("a.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }).also { runners += it }
        runner.chapterOpened(0)
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.flatMap { it.lines() }
    }

    @Test
    fun an_xhtml_chapter_refuses_to_be_written(): TestResult = scriptTest {
        assertEquals(
            listOf(
                "write InvalidStateError: Failed to execute 'write' on 'Document': Only HTML documents support write().",
                "writeln InvalidStateError: Failed to execute 'writeln' on 'Document': Only HTML documents support write().",
                "written false false",
                "made ok true",
            ),
            logged(html = false),
        )
    }

    @Test
    fun an_html_chapter_is_written(): TestResult = scriptTest {
        assertEquals(listOf("write ok", "writeln ok", "written true true", "made ok true"), logged(html = true))
    }
}
