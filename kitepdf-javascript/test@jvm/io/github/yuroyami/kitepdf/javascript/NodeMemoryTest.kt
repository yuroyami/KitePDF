package io.github.yuroyami.kitepdf.javascript

import java.lang.management.ManagementFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.test.TestResult
import kotlin.test.assertTrue

/**
 * A chapter that makes elements on a timer and drops them keeps no memory for them (#885). The
 * script makes 1,000 elements each tick for 60 ticks; before the fix the host kept every one, about
 * 450 bytes of Java heap each, 22 MB for 50,000.
 */
class NodeMemoryTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private fun heapAfterGc(): Long {
        val memory = ManagementFactory.getMemoryMXBean()
        repeat(3) { System.gc(); Thread.sleep(50) }
        return memory.heapMemoryUsage.used
    }

    @Test
    fun elements_that_no_script_holds_are_let_go(): TestResult = scriptTest {
        val script = "setInterval(function () { for (var i = 0; i < 1000; i++) document.createElement('div'); }, 0);"
        val book = ScriptBooks.chapter("""<p>x</p><script src="a.js"></script>""", extraFiles = mapOf("a.js" to script), html = true)
        var now = 0L
        val runner = EpubScriptRunner(book, clock = { now }).also { runners += it }
        runner.chapterOpened(0)
        // KiteJS lets go of the handles the collector found when the engine next runs, so each
        // measure collects, runs the engine once, and collects again.
        suspend fun settled(): Long { heapAfterGc(); now += 16; runner.pumpTimers(now); return heapAfterGc() }
        repeat(5) { now += 16; runner.pumpTimers(now) }
        val before = settled()
        repeat(60) { now += 16; runner.pumpTimers(now) }
        val grown = settled() - before
        assertTrue(runner.failures.isEmpty(), runner.failures.joinToString { it.message.orEmpty() })
        assertTrue(grown < 6_000_000, "60,000 dropped elements kept ${grown / 1_000_000} MB of heap")
    }
}
