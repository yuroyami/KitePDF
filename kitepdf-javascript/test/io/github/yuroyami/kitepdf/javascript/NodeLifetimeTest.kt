package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * A node's wrapper lives as long as a browser keeps it (#885): while the document holds the node,
 * or while the script holds a wrapper of any node in its tree. Each chapter makes thousands of
 * nodes and garbage first, so that the DOM's sweeps and the engine's collections run, and then
 * reads what a browser keeps.
 */
class NodeLifetimeTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private suspend fun logged(script: String): List<String> {
        val console = ArrayList<String>()
        var now = 0L
        val book = ScriptBooks.chapter("""<p id="p">x</p><script src="a.js"></script>""", extraFiles = mapOf("a.js" to script), html = true)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }, clock = { now }).also { runners += it }
        runner.chapterOpened(0)
        while (console.isEmpty() && now < 5_000) {
            now += 16
            runner.pumpTimers(now)
        }
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.flatMap { it.lines() }
    }

    @Test
    fun a_detached_tree_keeps_its_expandos_and_listeners_while_the_script_holds_one_node(): TestResult = scriptTest {
        val out = logged(
            """
            var clicks = 0;
            var held = document.createElement('div');
            held.innerHTML = '<span>s</span><b>b</b>';
            held.querySelector('span').mark = 'kept';
            held.querySelector('b').addEventListener('click', function () { clicks++; });
            var first = held.firstChild;
            document.getElementById('p').mark = 'connected';
            var rounds = 0;
            function churn() {
              for (var i = 0; i < 3000; i++) document.createElement('i');
              var junk = [];
              for (var j = 0; j < 20000; j++) junk.push({ j: j, s: 'x' + j });
              junk = null;
              if (++rounds < 8) { setTimeout(churn, 0); return; }
              document.body.appendChild(held);
              for (var k = 0; k < 3000; k++) document.createElement('i');
              document.querySelector('b').click();
              console.log([
                document.querySelector('span').mark,
                document.getElementById('p').mark,
                clicks,
                held.firstChild === first,
                document.querySelector('span') === held.firstChild
              ].join(' '));
            }
            setTimeout(churn, 0);
            """.trimIndent(),
        )
        assertEquals(listOf("kept connected 1 true true"), out)
    }
}
