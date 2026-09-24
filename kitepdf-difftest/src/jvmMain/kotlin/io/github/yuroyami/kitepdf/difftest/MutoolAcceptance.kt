package io.github.yuroyami.kitepdf.difftest

import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit

/** What one `mutool` run printed. mutool writes its warnings and errors to [stderr]. */
public data class MutoolRun(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Checks that `mutool` reads a file that KitePDF wrote without a complaint.
 *
 * An exit code of 0 is not enough. mutool repairs a broken cross-reference table while it
 * reads, prints `warning: repairing PDF document`, and still exits with 0. Use this check
 * only for files that KitePDF wrote: many corpus files need a repair, and that is expected.
 */
public object MutoolAcceptance {

    // MuPDF prints a warning as "warning: <message>" and an error as "<type> error: <message>",
    // for example "syntax error:" or "format error:". The "page <file> 1" line of draw is progress.
    private val complaint = Regex("^(warning|([a-z]+ )?error): ")

    /** Runs [tool] with [args]. stderr stays apart from stdout, so no warning hides in the output. */
    public fun run(tool: File, vararg args: String, timeoutSeconds: Long = 60): MutoolRun {
        val out = File.createTempFile("kite-mutool-", ".out")
        val err = File.createTempFile("kite-mutool-", ".err")
        try {
            // Files instead of pipes, so a full pipe cannot block the process before the timeout.
            val process = ProcessBuilder(listOf(tool.path) + args)
                .redirectOutput(out)
                .redirectError(err)
                .start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("mutool ${args.joinToString(" ")} timed out after $timeoutSeconds s")
            }
            return MutoolRun(process.exitValue(), out.readText(), err.readText())
        } finally {
            out.delete()
            err.delete()
        }
    }

    /** The lines of [stderr] in which mutool reports a warning or an error. */
    public fun complaints(stderr: String): List<String> =
        stderr.lineSequence().map(String::trim).filter(complaint::containsMatchIn).toList()

    /** Fails unless [run] exited with 0 and printed no warning or error. [what] names the file in the message. */
    public fun assertAccepted(run: MutoolRun, what: String) {
        val complaints = complaints(run.stderr)
        if (run.exitCode == 0 && complaints.isEmpty()) return
        throw AssertionError(
            buildString {
                append("mutool did not accept $what cleanly (exit ${run.exitCode})")
                complaints.forEach { append("\n  ").append(it) }
                if (complaints.isEmpty()) append('\n').append(run.stderr.trim())
            },
        )
    }

    /** Page [page] (1-based) of [pdf] as mutool draws it, after the same check as [assertAccepted]. */
    public fun render(pdf: File, page: Int, dpi: Int): BufferedImage =
        when (val result = MuPdfOracle.renderDetailed(pdf, page, dpi)) {
            is MuPdfOracle.RenderResult.Failure ->
                throw AssertionError("mutool could not draw page $page of ${pdf.name}: ${result.describe()}")

            is MuPdfOracle.RenderResult.Success -> {
                assertAccepted(MutoolRun(0, "", result.output), "page $page of ${pdf.name}")
                result.image
            }
        }
}
