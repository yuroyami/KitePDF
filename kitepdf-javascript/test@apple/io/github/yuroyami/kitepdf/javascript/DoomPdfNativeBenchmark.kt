@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getcwd
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * DoomPDF on Kotlin/Native, as `DoomPdfBenchmarkTest` runs it on the JVM: QuickJS as an Apple app
 * links it, through the same script runner and the same clock. Opt-in: kotlin-test on Kotlin/Native
 * has no assumption, and a test that returns early reads as passed (#44), so the benchmark is
 * [Ignore]d and a run reports it as ignored (#726). Take [Ignore] off, with the file
 * `corpus/pdf/doom.pdf` (https://doompdf.pages.dev/doom.pdf), to run it.
 */
class DoomPdfNativeBenchmark {

    @Ignore
    @Test
    fun doom_runs_on_native() {
        val bytes = doomPath()?.let(::readFile) ?: error("corpus/pdf/doom.pdf is missing: download it to run DoomPDF")
        val doc = PdfDocument.open(bytes)
        assertTrue(doc.pages[0].openAction is PdfAction.JavaScript, "the page's open action is not a script")

        var clock = 1_000_000L
        val alerts = ArrayList<String>()
        PdfScriptRunner(
            doc,
            engine = KiteJsScriptEngine(
                instructionBudget = PdfScriptPolicy.LONG_RUNNING.instructionBudget,
                clock = { clock++ },
            ),
            policy = PdfScriptPolicy.LONG_RUNNING,
            onAlert = { alert -> alerts.add(alert.message); 1 },
            clock = { clock++ },
        ).use { runner ->
            val t0 = TimeSource.Monotonic.markNow()
            runner.runPageOpen(0)
            println("start-up: ${t0.elapsedNow()}")
            runner.failures.forEach { println("script failure: ${it.message?.take(200)}") }
            assertTrue(runner.hasTimers, "the game never set its loop timer; alerts: $alerts")

            // Past the title screen, which draws almost nothing.
            repeat((TITLE_SCREEN_MS / FRAME_STEP).toInt()) {
                clock += FRAME_STEP
                runBlocking { runner.pumpTimers(clock) }
            }
            val times = ArrayList<Long>()
            val writes = ArrayList<Int>()
            repeat(FRAMES) {
                val before = runner.formState.revision
                val t = TimeSource.Monotonic.markNow()
                clock += FRAME_STEP
                runBlocking { runner.pumpTimers(clock) }
                times += t.elapsedNow().inWholeMilliseconds
                writes += runner.formState.revision - before
            }
            val sorted = times.sorted()
            println(
                "frames: $FRAMES, median ${sorted[sorted.size / 2]} ms, min ${sorted.first()} ms, " +
                    "max ${sorted.last()} ms, median writes ${writes.sorted()[writes.size / 2]}",
            )
            assertTrue(writes.sorted()[writes.size / 2] > 0, "half the measured frames drew nothing")
            val drawn = (0 until 200).count { !runner.formState.value("field_$it").isNullOrBlank() }
            assertTrue(drawn > 100, "the game drew $drawn rows, so it never reached the screen")
            assertTrue(alerts.isEmpty(), "the script reported errors: $alerts")
        }
    }

    /** The repo's `corpus/pdf/doom.pdf`, found by walking up from the working folder, or null. */
    private fun doomPath(): String? {
        val buffer = ByteArray(4096)
        var dir = getcwd(buffer.refTo(0), buffer.size.convert())?.toKString() ?: return null
        while (true) {
            if (readable("$dir/project.yaml")) return "$dir/corpus/pdf/doom.pdf".takeIf(::readable)
            if (dir.length <= 1) return null
            dir = dir.substringBeforeLast('/').ifEmpty { "/" }
        }
    }

    private fun readable(path: String): Boolean = fopen(path, "rb")?.also { fclose(it) } != null

    private fun readFile(path: String): ByteArray? {
        val file = fopen(path, "rb") ?: return null
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file).toInt()
            fseek(file, 0, SEEK_SET)
            val out = ByteArray(size)
            if (size > 0) out.usePinned { fread(it.addressOf(0), 1u, size.convert(), file) }
            return out
        } finally {
            fclose(file)
        }
    }

    private companion object {
        const val FRAME_STEP = 30L
        const val TITLE_SCREEN_MS = 15_000L
        const val FRAMES = 120
    }
}
