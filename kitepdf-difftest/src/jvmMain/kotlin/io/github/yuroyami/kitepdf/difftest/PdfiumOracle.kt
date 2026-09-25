package io.github.yuroyami.kitepdf.difftest

import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Second reference renderer, backed by PDFium, the PDF engine of Chrome, through the
 * Python binding pypdfium2. The driver script `pdfium_oracle.py` runs in its own process,
 * so a crash in PDFium cannot take the test JVM down.
 *
 * PDFium and MuPDF render the same page differently in known ways, such as
 * anti-aliasing, text rasterization and the fonts that replace the standard 14 fonts. So
 * PDFium is a second opinion, not a second gate: see `PdfiumParityTest`.
 *
 * The interpreter is located, in priority order, from:
 *   1. `-Dkitepdf.pdfium.python=/path/to/python`
 *   2. `$PDFIUM_PYTHON`
 *   3. `~/.cache/kitepdf/pdfium-venv/bin/python`
 *   4. `python3` on `$PATH`
 *
 * A candidate counts only when it imports pypdfium2. Without one, [available] is false
 * and [unavailableReason] says why.
 */
public object PdfiumOracle : PdfRenderOracle {

    /** Pages that one render call covers, so a document costs one process for each run of pages. */
    private const val BATCH_PAGES = 8

    private data class Probe(val python: File?, val version: String?, val reason: String)

    private val probe: Probe by lazy { locate() }

    /** The Python interpreter that runs the driver, or null. */
    public val python: File? get() = probe.python

    /** The PDFium and pypdfium2 versions, such as "PDFium 153.0.7999.0, pypdfium2 5.13.0", or null. */
    public val version: String? get() = probe.version

    public val unavailableReason: String get() = probe.reason

    override val available: Boolean get() = probe.version != null

    override fun describe(): String = probe.version?.let { "$it (${probe.python?.absolutePath})" } ?: "<not found: ${probe.reason}>"

    private val script: File by lazy {
        val source = PdfiumOracle::class.java.getResourceAsStream("pdfium_oracle.py")
            ?: error("pdfium_oracle.py is missing from the classpath")
        File.createTempFile("kite-pdfium-", ".py").apply {
            deleteOnExit()
            source.use { input -> outputStream().use { input.copyTo(it) } }
        }
    }

    private class Batch(val key: String, val dpi: Int, val pages: Map<Int, MuPdfOracle.RenderResult>)

    private var batch: Batch? = null

    override fun pageCountDetailed(pdf: File): MuPdfOracle.PageCountResult {
        val python = probe.python ?: return MuPdfOracle.PageCountResult.Failure("PDFium is unavailable: ${probe.reason}")
        val run = run(python, listOf("pages", pdf.absolutePath), timeoutMillis = 60_000L)
        val count = run.lines.firstOrNull { it.firstOrNull() == "pages" }?.getOrNull(1)?.toIntOrNull()
        return if (run.exitCode == 0 && count != null) MuPdfOracle.PageCountResult.Success(count)
        else MuPdfOracle.PageCountResult.Failure(
            reason = run.failure ?: "PDFium returned no readable page count",
            exitCode = run.exitCode,
            timedOut = run.timedOut,
            output = run.diagnostic,
        )
    }

    /** Renders [page] (1-based) and the next pages of its batch, then answers later pages from the batch. */
    @Synchronized
    override fun renderDetailed(pdf: File, page: Int, dpi: Int): MuPdfOracle.RenderResult {
        val python = probe.python ?: return MuPdfOracle.RenderResult.Failure("PDFium is unavailable: ${probe.reason}")
        val key = keyOf(pdf)
        batch?.takeIf { it.key == key && it.dpi == dpi }?.pages?.get(page)?.let { return it }
        val pages = renderRange(python, pdf, dpi, page, page + BATCH_PAGES - 1)
        batch = Batch(key, dpi, pages)
        return pages[page] ?: MuPdfOracle.RenderResult.Failure("PDFium rendered no page $page")
    }

    /** The text that PDFium extracts from each page in [first] to [last] (1-based), inside the page box. */
    public fun extractText(pdf: File, first: Int, last: Int): Map<Int, TextResult> {
        val python = probe.python ?: return (first..last).associateWith { TextResult.Failure("PDFium is unavailable: ${probe.reason}") }
        val dir = createTempDir("kite-pdfium-text-")
        try {
            val run = run(python, listOf("text", pdf.absolutePath, dir.absolutePath, "$first", "$last"), timeoutMillis = 120_000L)
            val results = HashMap<Int, TextResult>()
            for (fields in run.lines) {
                val number = fields.getOrNull(1)?.toIntOrNull() ?: continue
                results[number] = when (fields[0]) {
                    "ok" -> TextResult.Success(File(fields[2]).readText())
                    else -> TextResult.Failure(fields.getOrNull(2) ?: "PDFium failed to extract text")
                }
            }
            if (results.isEmpty() && run.failure != null) {
                return (first..last).associateWith { TextResult.Failure(run.failure + ": " + run.diagnostic) }
            }
            return results
        } finally {
            dir.deleteRecursively()
        }
    }

    public sealed interface TextResult {
        public data class Success(val text: String) : TextResult
        public data class Failure(val reason: String) : TextResult
    }

    private fun renderRange(python: File, pdf: File, dpi: Int, first: Int, last: Int): Map<Int, MuPdfOracle.RenderResult> {
        val dir = createTempDir("kite-pdfium-render-")
        try {
            val run = run(python, listOf("render", pdf.absolutePath, dir.absolutePath, "$dpi", "$first", "$last"), timeoutMillis = 120_000L)
            val results = HashMap<Int, MuPdfOracle.RenderResult>()
            for (fields in run.lines) {
                val number = fields.getOrNull(1)?.toIntOrNull() ?: continue
                results[number] = when (fields[0]) {
                    "ok" -> ImageIO.read(File(fields[2]))?.let { MuPdfOracle.RenderResult.Success(it, run.diagnostic) }
                        ?: MuPdfOracle.RenderResult.Failure("PDFium wrote an unreadable PNG", output = run.diagnostic)

                    else -> MuPdfOracle.RenderResult.Failure(
                        reason = "PDFium failed to render: " + (fields.getOrNull(2) ?: "no reason"),
                        output = run.diagnostic,
                    )
                }
            }
            if (results.isEmpty()) {
                results[first] = MuPdfOracle.RenderResult.Failure(
                    reason = run.failure ?: "PDFium rendered no page",
                    exitCode = run.exitCode,
                    timedOut = run.timedOut,
                    output = run.diagnostic,
                )
            }
            return results
        } finally {
            dir.deleteRecursively()
        }
    }

    private class Run(
        val exitCode: Int?,
        val timedOut: Boolean,
        /** Each stdout line split at tabs. */
        val lines: List<List<String>>,
        /** Up to 4 KB of stderr, and the "error" line of a document that did not open. */
        val diagnostic: String,
        val failure: String?,
    )

    private fun run(python: File, arguments: List<String>, timeoutMillis: Long): Run {
        var out: File? = null
        var err: File? = null
        var process: Process? = null
        try {
            val outFile = File.createTempFile("kite-pdfium-", ".out").also { out = it }
            val errFile = File.createTempFile("kite-pdfium-", ".err").also { err = it }
            val builder = ProcessBuilder(listOf(python.absolutePath, script.absolutePath) + arguments)
                .redirectOutput(outFile)
                .redirectError(errFile)
            // The unsupported-feature log of pypdfium2 goes to stderr and is not a failure.
            builder.environment()["DEBUG_UNSUPPORTED"] = "0"
            val started = builder.start().also { process = it }
            if (!started.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                started.destroyForcibly()
                started.waitFor(5, TimeUnit.SECONDS)
                return Run(null, true, emptyList(), readLimited(errFile), "PDFium timed out after $timeoutMillis ms")
            }
            val lines = outFile.readLines().filter { it.isNotBlank() }.map { it.split('\t') }
            val documentError = lines.firstOrNull { it.firstOrNull() == "error" }?.getOrNull(1)
            val diagnostic = listOfNotNull(documentError, readLimited(errFile).takeIf { it.isNotBlank() }).joinToString(": ")
            val exit = started.exitValue()
            val failure = when {
                documentError != null -> "PDFium could not open the document"
                exit != 0 -> "PDFium driver failed"
                else -> null
            }
            return Run(exit, false, lines, diagnostic, failure)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            return Run(null, false, emptyList(), readLimited(err), "PDFium invocation failed: ${e.message ?: e::class.simpleName}")
        } finally {
            if (process?.isAlive == true) process?.destroyForcibly()
            out?.delete()
            err?.delete()
        }
    }

    private fun readLimited(file: File?, limit: Int = 4_096): String {
        if (file == null || !file.isFile) return ""
        val text = file.readText()
        return (if (text.length > limit) text.take(limit) + "…" else text).replace(Regex("\\s+"), " ").trim()
    }

    private fun createTempDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()

    private fun keyOf(pdf: File): String = "${pdf.absolutePath}:${pdf.length()}:${pdf.lastModified()}"

    private fun locate(): Probe {
        System.getProperty("kitepdf.pdfium.python")?.let { return probeOf(File(it), "kitepdf.pdfium.python") }
        System.getenv("PDFIUM_PYTHON")?.let { return probeOf(File(it), "PDFIUM_PYTHON") }
        val home = System.getProperty("user.home")
        val defaultVenv = File(home, ".cache/kitepdf/pdfium-venv/bin/python")
        if (defaultVenv.canExecute()) {
            val probe = probeOf(defaultVenv, defaultVenv.path)
            if (probe.version != null) return probe
        }
        for (dir in (System.getenv("PATH") ?: "").split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            val candidate = File(dir, "python3")
            if (candidate.canExecute()) {
                val probe = probeOf(candidate, candidate.path)
                if (probe.version != null) return probe
            }
        }
        return Probe(null, null, "no Python with pypdfium2 (set -Dkitepdf.pdfium.python or PDFIUM_PYTHON)")
    }

    private fun probeOf(python: File, source: String): Probe {
        if (!python.canExecute()) return Probe(null, null, "$source is not an executable: ${python.absolutePath}")
        val run = run(python, listOf("version"), timeoutMillis = 60_000L)
        val fields = run.lines.firstOrNull { it.firstOrNull() == "pdfium" }
        return if (run.exitCode == 0 && fields != null && fields.size >= 4) {
            Probe(python, "PDFium ${fields[1]}, pypdfium2 ${fields[3]}", "")
        } else {
            Probe(null, null, "$source does not run pypdfium2: ${run.failure ?: "no version"} ${run.diagnostic}".trim())
        }
    }
}
