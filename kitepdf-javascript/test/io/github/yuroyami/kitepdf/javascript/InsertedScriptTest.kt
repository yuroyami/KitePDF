package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestResult

/**
 * A script element that a book's script inserts runs, and fires load or error after it, and
 * defer and async move the parser's scripts after the parse (HTML, 4.12.1.1; #935).
 */
class InsertedScriptTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    /** The lines the window's load handler logs, and the failures of the chapter. */
    private suspend fun run(body: String, files: Map<String, String>, html: Boolean = false): Pair<List<String>, List<String>> {
        val console = ArrayList<String>()
        val prologue = "<script>var out = []; function log(s) { out.push(s); } " +
            "window.addEventListener('load', function () { console.log(out.join('\\n')); });</script>"
        val book = ScriptBooks.chapter(prologue + body, extraFiles = files, html = html)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }).also { runners += it }
        runner.chapterOpened(0)
        return console.flatMap { it.lines() } to runner.failures.mapNotNull { it.message }
    }

    @Test
    fun an_inserted_script_runs_and_then_its_load_handler(): TestResult = scriptTest {
        val (log, failures) = run(
            """<p id="p">before</p><script>
                var s = document.createElement('script');
                s.src = 'b.js';
                s.onload = function (e) { log('onload ' + window.fromB + ' ' + e.type + ' ' + e.isTrusted); };
                document.body.appendChild(s);
                log('appended ' + window.fromB);
            </script>""",
            mapOf("b.js" to "window.fromB = 'b ran'; log('b runs, current is the element: ' + (document.currentScript === s)); document.getElementById('p').textContent = 'changed by b';"),
        )
        assertEquals(emptyList(), failures)
        assertEquals(listOf("appended undefined", "b runs, current is the element: true", "onload b ran load true"), log)
    }

    @Test
    fun an_inserted_script_whose_file_is_not_in_the_book_fires_error(): TestResult = scriptTest {
        val (log, failures) = run(
            """<script>
                var s = document.createElement('script');
                s.onload = function () { log('onload'); };
                s.onerror = function (e) { log('onerror ' + e.type); };
                s.src = 'missing.js';
                document.head.appendChild(s);
            </script>""",
            emptyMap(),
        )
        assertEquals(listOf("onerror error"), log)
        assertTrue(failures.any { "missing.js" in it }, "the failure list names the file: $failures")
    }

    @Test
    fun an_inline_script_runs_when_it_is_inserted_and_markup_scripts_never_run(): TestResult = scriptTest {
        val (log, failures) = run(
            """<div id="d"></div><script>
                var s = document.createElement('script');
                s.textContent = "log('inline inserted runs')";
                document.body.appendChild(s);
                log('after append');
                document.getElementById('d').innerHTML = '<script>log("innerHTML script runs")<\/script>';
                var moved = document.getElementById('d').firstChild;
                document.body.appendChild(moved);
                var later = document.createElement('script');
                document.body.appendChild(later);
                log('empty did not run');
                later.src = 'c.js';
            </script>""",
            mapOf("c.js" to "log('src set later runs')"),
        )
        assertEquals(emptyList(), failures)
        assertEquals(listOf("inline inserted runs", "after append", "empty did not run", "src set later runs"), log)
    }

    @Test
    fun a_written_script_runs(): TestResult = scriptTest {
        val (log, failures) = run("""<script>document.write('<script>log("written runs")<\/script>'); log('wrote');</script>""", emptyMap(), html = true)
        assertEquals(emptyList(), failures)
        assertEquals(setOf("written runs", "wrote"), log.toSet())
    }

    @Test
    fun a_deferred_script_runs_after_the_parse_before_dom_content_loaded(): TestResult = scriptTest {
        val (log, failures) = run(
            """<script defer src="a.js"></script><script async src="async.js"></script><script>
                log('inline');
                document.addEventListener('DOMContentLoaded', function () { log('DOMContentLoaded'); });
            </script>""",
            mapOf("a.js" to "log('deferred')", "async.js" to "log('async')"),
        )
        assertEquals(emptyList(), failures)
        assertEquals(listOf("inline", "deferred", "async", "DOMContentLoaded"), log)
    }

    @Test
    fun a_module_and_a_data_block_still_do_not_run(): TestResult = scriptTest {
        val (log, _) = run(
            """<script>
                var m = document.createElement('script'); m.type = 'module'; m.textContent = "log('module')"; document.body.appendChild(m);
                var d = document.createElement('script'); d.type = 'text/plain'; d.textContent = "log('data')"; document.body.appendChild(d);
                log('end');
            </script>""",
            emptyMap(),
        )
        assertEquals(listOf("end"), log)
    }
}
