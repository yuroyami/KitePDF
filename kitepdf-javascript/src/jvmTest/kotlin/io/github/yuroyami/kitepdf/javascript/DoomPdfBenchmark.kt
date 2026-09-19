package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfDocument
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * DoomPDF, the Doom port that lives in a PDF's page-open script, run through the real script
 * runner: the page's own trigger starts it, the game reads and writes its 200 screen rows through
 * `getField`, and its loop runs on `app.setInterval` timers the host pumps.
 *
 * It is the end-to-end test of the scripting stack, and a benchmark: it prints start-up time,
 * time per frame and how many fields one frame changes.
 *
 * Opt-in, because one run takes minutes: `KITEPDF_DOOM=true` plus the file `corpus/pdf/doom.pdf`
 * (https://doompdf.pages.dev/doom.pdf). Writes the last frame and the game's own console under
 * `build/doom/`.
 *
 * The clock is the harness's own, advancing a millisecond per read plus a fixed step per frame.
 * The game waits for its next tic by reading the clock in a loop, so a harness that lets real
 * time pass measures the waiting rather than the work. A clock the harness owns also makes the
 * run repeatable: the same tick draws the same frame every time, and the frames can be compared
 * with another engine's.
 */
class DoomPdfBenchmark {

    @Test
    fun doom_starts_and_renders_frames() {
        val enabled = System.getenv("KITEPDF_DOOM") == "true" || System.getProperty("kitepdf.doom") == "true"
        assumeTrue("Run with KITEPDF_DOOM=true to run DoomPDF.", enabled)
        val pdf = repoCorpus("pdf")?.let { File(it, "doom.pdf") }
        assumeTrue("corpus/pdf/doom.pdf is missing", pdf != null && pdf.exists())

        val out = File("build/doom").apply { mkdirs() }
        val log = File(out, "progress.log").also { it.writeText("") }
        fun note(line: String) { println(line); log.appendText(line + "\n") }
        fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

        val t0 = System.nanoTime()
        val doc = PdfDocument.open(pdf!!.readBytes())
        val script = (doc.pages[0].openAction as? io.github.yuroyami.kitepdf.PdfAction.JavaScript)?.script
        note(
            "open: ${ms(t0)} ms, page script ${script?.length ?: 0} chars, " +
                "${doc.formFields.size} fields, acroForm=${doc.acroForm != null}",
        )
        assertTrue(script != null, "the page's open action is not a script")

        // A millisecond per read, plus a step per frame: enough for the game to see time moving
        // inside one frame, which its wait loops need, and no more.
        var clock = 1_000_000L
        val alerts = ArrayList<String>()
        val console = ArrayList<String>()
        PdfScriptRunner(
            doc,
            policy = PdfScriptPolicy.LONG_RUNNING,
            onAlert = { alert -> alerts.add(alert.message); note("alert: ${alert.message}"); 1 },
            onConsole = { text -> console.add(text) },
            clock = { clock++ },
        ).use { runner ->
            val t1 = System.nanoTime()
            runner.runPageOpen(0)
            val startupMs = ms(t1)
            val rows = { (199 downTo 0).joinToString("\n") { runner.formState.value("field_$it") ?: "" } }
            val consoleRows = { (24 downTo 0).mapNotNull { runner.formState.value("console_$it") }.filter { it.isNotBlank() } }
            note("start-up: ${startupMs / 1000.0} s, ${runner.formState.changedFields.size} fields written, timers=${runner.hasTimers}")
            for (line in consoleRows().takeLast(6)) note("game console: $line")
            runner.failures.forEach { note("script failure: ${it.message?.take(200)}") }
            File(out, "console.txt").writeText(consoleRows().joinToString("\n"))
            assertTrue(runner.hasTimers, "the game never set its loop timer; alerts: $alerts")

            val frames = 120
            val times = ArrayList<Long>()
            val writes = ArrayList<Int>()
            for (frame in 1..frames) {
                val before = runner.formState.revision
                val t = System.nanoTime()
                clock += FRAME_STEP
                runner.pumpTimers(clock)
                times += ms(t)
                writes += runner.formState.revision - before
                if (frame <= 3 || frame % 20 == 0) note("frame $frame: ${times.last()} ms, ${writes.last()} field writes")
            }
            File(out, "frame.txt").writeText(rows())
            val sorted = times.sorted()
            note(
                "frames: ${times.size}, median ${sorted[sorted.size / 2]} ms, " +
                    "min ${sorted.first()} ms, max ${sorted.last()} ms, " +
                    "median writes ${writes.sorted()[writes.size / 2]}",
            )
            val drawn = (0 until 200).count { !runner.formState.value("field_$it").isNullOrBlank() }
            note("screen rows with content: $drawn of 200")
            assertTrue(alerts.isEmpty(), "the script reported errors: $alerts")
            assertTrue(drawn > 100, "the game drew $drawn rows, so it never reached the screen")
        }
    }

    /** The repo-root `corpus/<sub>` directory, found by walking up to settings.gradle.kts. */
    private fun repoCorpus(sub: String): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            if (File(d, "settings.gradle.kts").exists()) return File(d, "corpus/$sub")
            d = d.parentFile
        }
        return null
    }

    private companion object {
        /** Doom runs at 35 tics a second, so a frame is worth about this many milliseconds. */
        const val FRAME_STEP = 20L
    }
}
