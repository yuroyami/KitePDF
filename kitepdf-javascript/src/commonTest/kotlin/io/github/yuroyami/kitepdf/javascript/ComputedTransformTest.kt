package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * The computed value of transform in a book's scripts, as getComputedStyle answers it (#609). Each\n * expected line is what headless Chromium logs for the same chapter.
 */
class ComputedTransformTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private val script = """
        var el = document.getElementById('p'), out = [];
        ['rotate(30deg)', 'translate(10.123456789px, 2px) scale(1.5)', 'none', 'skewX(10deg)', 'rotate(45deg)', 'scale(0.0000012)'].forEach(function (t) {
          el.style.transform = t;
          out.push(t + ' => ' + getComputedStyle(el).transform);
        });
        console.log('C609\n' + out.join('\n'));
    """.trimIndent()

    private val chromium = listOf(
        """rotate(30deg) => matrix(0.866025, 0.5, -0.5, 0.866025, 0, 0)""",
        """translate(10.123456789px, 2px) scale(1.5) => matrix(1.5, 0, 0, 1.5, 10.1235, 2)""",
        """none => none""",
        """skewX(10deg) => matrix(1, 0, 0.176327, 1, 0, 0)""",
        """rotate(45deg) => matrix(0.707107, 0.707107, -0.707107, 0.707107, 0, 0)""",
        """scale(0.0000012) => matrix(1.2e-06, 0, 0, 1.2e-06, 0, 0)""",
    )

    private fun logged(html: Boolean): List<String> {
        val console = ArrayList<String>()
        var now = 0L
        val book = ScriptBooks.chapter("""<p id="p">x</p><script src="a.js"></script>""", extraFiles = mapOf("a.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }, clock = { now }).also { runners += it }
        runner.chapterOpened(0)
        while (console.none { it.startsWith("C609") } && now < 5_000) {
            now++
            runner.pumpTimers(now)
        }
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.last().lines().drop(1)
    }

    @Test
    fun an_xhtml_chapter_computed_transform_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium.joinToString("\n"), logged(html = false).joinToString("\n"))
    }

    @Test
    fun an_html_chapter_computed_transform_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium.joinToString("\n"), logged(html = true).joinToString("\n"))
    }
}
