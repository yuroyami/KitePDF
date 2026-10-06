@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.test.TestResult

/** Whether this runs on Node with `KITEPDF_DOOM=true`. A browser has no `process`. */
private fun doomEnabled(): Boolean = js("typeof process !== 'undefined' && !!process.env && process.env.KITEPDF_DOOM === 'true'")

/** The repo's `corpus/pdf/doom.pdf`, found by walking up from the working folder, or null. */
private fun doomPath(): String? = js(
    """(function () {
        var fs = process.getBuiltinModule('fs'), path = process.getBuiltinModule('path');
        for (var d = process.cwd(); ; d = path.dirname(d)) {
            if (fs.existsSync(path.join(d, 'settings.gradle.kts'))) {
                var f = path.join(d, 'corpus', 'pdf', 'doom.pdf');
                return fs.existsSync(f) ? f : null;
            }
            if (path.dirname(d) === d) return null;
        }
    })()""",
)

/** The file at [path], one char per byte. */
private fun readLatin1(path: String): String = js("process.getBuiltinModule('fs').readFileSync(path, 'latin1')")

/**
 * DoomPDF on the web, as `DoomPdfBenchmark` runs it on the JVM, but through the calls a viewer
 * makes: the page-open script and every frame run with pausing on, so the event loop runs while
 * the game works (#489). Opt-in, on Node only: `KITEPDF_DOOM=true` plus the file
 * `corpus/pdf/doom.pdf` (https://doompdf.pages.dev/doom.pdf).
 */
class DoomPdfWebBenchmark {

    @Test
    fun doom_runs_on_the_web_with_pausing(): TestResult = scriptTest {
        if (!doomEnabled()) return@scriptTest println("Run with KITEPDF_DOOM=true to run DoomPDF.")
        val path = doomPath() ?: return@scriptTest println("corpus/pdf/doom.pdf is missing")
        val bytes = readLatin1(path).let { text -> ByteArray(text.length) { text[it].code.toByte() } }
        val doc = PdfDocument.open(bytes)
        assertTrue(doc.pages[0].openAction is PdfAction.JavaScript, "the page's open action is not a script")

        var clock = 1_000_000L
        val alerts = ArrayList<String>()
        val runner = PdfScriptRunner(
            doc,
            engine = KiteJsScriptEngine(
                instructionBudget = PdfScriptPolicy.LONG_RUNNING.instructionBudget,
                clock = { clock++ },
            ),
            policy = PdfScriptPolicy.LONG_RUNNING,
            onAlert = { alert -> alerts.add(alert.message); 1 },
            clock = { clock++ },
        )
        val ticker = startTicking()
        try {
            runner.prepare()
            val t0 = TimeSource.Monotonic.markNow()
            val ticksBefore = ticks()
            runner.pageOpened(0)
            println("start-up: ${t0.elapsedNow()}, ${ticks() - ticksBefore} timer ticks ran meanwhile")
            runner.failures.forEach { println("script failure: ${it.message?.take(200)}") }
            assertTrue(runner.hasTimers, "the game never set its loop timer; alerts: $alerts")

            // Past the title screen, which draws almost nothing.
            repeat((TITLE_SCREEN_MS / FRAME_STEP).toInt()) {
                clock += FRAME_STEP
                runner.pumpTimers(clock)
            }
            val times = ArrayList<Long>()
            val writes = ArrayList<Int>()
            val ticksBeforeFrames = ticks()
            repeat(FRAMES) {
                val before = runner.formState.revision
                val t = TimeSource.Monotonic.markNow()
                clock += FRAME_STEP
                runner.pumpTimers(clock)
                times += t.elapsedNow().inWholeMilliseconds
                writes += runner.formState.revision - before
            }
            val sorted = times.sorted()
            println(
                "frames: $FRAMES, median ${sorted[sorted.size / 2]} ms, min ${sorted.first()} ms, " +
                    "max ${sorted.last()} ms, median writes ${writes.sorted()[writes.size / 2]}, " +
                    "${ticks() - ticksBeforeFrames} timer ticks ran meanwhile",
            )
            assertTrue(writes.sorted()[writes.size / 2] > 0, "half the measured frames drew nothing")
            val drawn = (0 until 200).count { !runner.formState.value("field_$it").isNullOrBlank() }
            assertTrue(drawn > 100, "the game drew $drawn rows, so it never reached the screen")
            assertTrue(alerts.isEmpty(), "the script reported errors: $alerts")
        } finally {
            stopTicking(ticker)
            runner.close()
        }
    }

    private companion object {
        const val FRAME_STEP = 30L
        const val TITLE_SCREEN_MS = 15_000L
        const val FRAMES = 120
    }
}
