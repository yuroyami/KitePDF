package io.github.yuroyami.kitepdf.difftest

import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Small seam around the reference renderer so the harness can test oracle
 * failures without replacing the process-wide `mutool` discovery state.
 */
public interface PdfRenderOracle {
    val available: Boolean
    fun describe(): String
    fun pageCountDetailed(pdf: File): MuPdfOracle.PageCountResult
    fun renderDetailed(pdf: File, page: Int, dpi: Int): MuPdfOracle.RenderResult
}

/**
 * Reference rasteriser backed by MuPDF's `mutool draw`. MuPDF is the in-repo
 * oracle (`mupdf-master/`), the cleanest open-source PDF engine, so the
 * differential harness scores KitePDF against "what MuPDF would draw."
 *
 * The tool is located, in priority order, from:
 *   1. `-Dkitepdf.mutool=/path/to/mutool`
 *   2. `$MUTOOL`
 *   3. the in-repo build: `mupdf-master/build/{release,debug}/mutool`
 *   4. anything named `mutool` on `$PATH`
 *
 * If none is found the oracle is simply [available] == false, and the harness
 * degrades to a KitePDF-only smoke pass instead of failing.
 */
public object MuPdfOracle : PdfRenderOracle {

    sealed interface RenderResult {
        data class Success(val image: BufferedImage) : RenderResult

        data class Failure(
            val reason: String,
            val exitCode: Int? = null,
            val timedOut: Boolean = false,
            val output: String = "",
        ) : RenderResult {
            fun describe(): String = buildString {
                append(reason)
                exitCode?.let { append(" (exit ").append(it).append(')') }
                if (output.isNotBlank()) append(": ").append(output)
            }
        }
    }

    sealed interface PageCountResult {
        data class Success(val count: Int) : PageCountResult

        data class Failure(
            val reason: String,
            val exitCode: Int? = null,
            val timedOut: Boolean = false,
            val output: String = "",
        ) : PageCountResult {
            fun describe(): String = buildString {
                append(reason)
                exitCode?.let { append(" (exit ").append(it).append(')') }
                if (output.isNotBlank()) append(": ").append(output)
            }
        }
    }

    val binary: File? by lazy { locate() }
    override val available: Boolean get() = binary != null

    override fun describe(): String = binary?.absolutePath ?: "<not found>"

    /**
     * Render one [page] (1-based) of [pdf] at [dpi] to a [BufferedImage], or
     * null if the tool is unavailable or the render fails for any reason.
     */
    fun render(pdf: File, page: Int, dpi: Int): BufferedImage? =
        (renderDetailed(pdf, page, dpi) as? RenderResult.Success)?.image

    override fun pageCountDetailed(pdf: File): PageCountResult {
        val tool = binary ?: return PageCountResult.Failure("mutool is unavailable")
        return pageCountWith(tool, pdf)
    }

    /**
     * The diagnostic form used by the differential gate. Unlike [render], it
     * distinguishes an unavailable oracle from a discovered oracle that failed
     * to render a page.
     */
    override fun renderDetailed(pdf: File, page: Int, dpi: Int): RenderResult {
        val tool = binary ?: return RenderResult.Failure("mutool is unavailable")
        return renderWith(tool, pdf, page, dpi)
    }

    /**
     * Invoke a specific binary. Kept internal both as a test seam and so all
     * process/error handling lives in one place.
     */
    public fun renderWith(
        tool: File,
        pdf: File,
        page: Int,
        dpi: Int,
        timeoutMillis: Long = 60_000L,
    ): RenderResult {
        var out: File? = null
        var log: File? = null
        var proc: Process? = null
        return try {
            val outFile = File.createTempFile("kite-ref-", ".png").also { out = it }
            val logFile = File.createTempFile("kite-ref-", ".log").also { log = it }
            val process = ProcessBuilder(
                tool.absolutePath,
                "draw",
                "-r", dpi.toString(),
                "-F", "png",
                "-o", outFile.absolutePath,
                pdf.absolutePath,
                page.toString(),
            )
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .start()
                .also { proc = it }

            // Redirecting output to disk avoids a full pipe without blocking
            // this thread before the timeout has a chance to run.
            if (!process.waitFor(timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)) {
                process.destroy()
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
                RenderResult.Failure(
                    reason = "mutool timed out after $timeoutMillis ms",
                    timedOut = true,
                    output = readDiagnostic(logFile),
                )
            } else {
                val exit = process.exitValue()
                val output = readDiagnostic(logFile)
                when {
                    exit != 0 -> RenderResult.Failure(
                        reason = "mutool draw failed",
                        exitCode = exit,
                        output = output,
                    )

                    !outFile.isFile || outFile.length() == 0L -> RenderResult.Failure(
                        reason = "mutool draw produced no PNG",
                        exitCode = exit,
                        output = output,
                    )

                    else -> {
                        val image = ImageIO.read(outFile)
                        if (image != null) RenderResult.Success(image)
                        else RenderResult.Failure(
                            reason = "mutool draw produced an unreadable PNG",
                            exitCode = exit,
                            output = output,
                        )
                    }
                }
            }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            RenderResult.Failure(
                reason = "mutool invocation failed: ${e.message ?: e::class.simpleName}",
                output = readDiagnostic(log),
            )
        } finally {
            if (proc?.isAlive == true) proc.destroyForcibly()
            out?.delete()
            log?.delete()
        }
    }

    public fun pageCountWith(
        tool: File,
        pdf: File,
        timeoutMillis: Long = 60_000L,
    ): PageCountResult {
        var log: File? = null
        var proc: Process? = null
        return try {
            val logFile = File.createTempFile("kite-ref-pages-", ".log").also { log = it }
            val process = ProcessBuilder(
                tool.absolutePath,
                "show",
                pdf.absolutePath,
                "trailer/Root/Pages/Count",
            )
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .start()
                .also { proc = it }

            if (!process.waitFor(timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)) {
                process.destroy()
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
                PageCountResult.Failure(
                    reason = "mutool page-count query timed out after $timeoutMillis ms",
                    timedOut = true,
                    output = readDiagnostic(logFile),
                )
            } else {
                val exit = process.exitValue()
                val rawOutput = readOutput(logFile)
                val output = normalizeDiagnostic(rawOutput)
                when {
                    exit != 0 -> PageCountResult.Failure(
                        reason = "mutool page-count query failed",
                        exitCode = exit,
                        output = output,
                    )

                    else -> {
                        val count = parsePageCount(rawOutput)
                        if (count != null) PageCountResult.Success(count)
                        else PageCountResult.Failure(
                            reason = "mutool returned no readable page count",
                            exitCode = exit,
                            output = output,
                        )
                    }
                }
            }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            PageCountResult.Failure(
                reason = "mutool page-count invocation failed: ${e.message ?: e::class.simpleName}",
                output = readDiagnostic(log),
            )
        } finally {
            if (proc?.isAlive == true) proc.destroyForcibly()
            log?.delete()
        }
    }

    internal fun parsePageCount(output: String): Int? =
        output.lineSequence()
            .map(String::trim)
            .mapNotNull(String::toIntOrNull)
            .lastOrNull()

    private fun readDiagnostic(file: File?, limit: Int = 4_096): String =
        normalizeDiagnostic(readOutput(file, limit))

    private fun readOutput(file: File?, limit: Int = 4_096): String {
        if (file == null || !file.isFile || file.length() == 0L) return ""
        return try {
            file.inputStream().use { input ->
                val bytes = input.readNBytes(limit + 1)
                val truncated = bytes.size > limit
                bytes.copyOf(minOf(bytes.size, limit))
                    .toString(Charsets.UTF_8)
                    .trim()
                    .let { if (truncated) "$it…" else it }
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun normalizeDiagnostic(output: String): String =
        output.replace(Regex("\\s+"), " ").trim()

    private fun locate(): File? {
        System.getProperty("kitepdf.mutool")?.let { p ->
            return requireExecutable(p, "kitepdf.mutool")
        }
        System.getenv("MUTOOL")?.let { p ->
            return requireExecutable(p, "MUTOOL")
        }
        repoRoot()?.let { root ->
            for (variant in listOf("release", "debug")) {
                for (name in executableNames) {
                    val f = File(root, "mupdf-master/build/$variant/$name")
                    if (f.canExecute()) return f
                }
            }
        }
        val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        for (dir in pathDirs) {
            if (dir.isBlank()) continue
            for (name in executableNames) {
                val f = File(dir, name)
                if (f.canExecute()) return f
            }
        }
        return null
    }

    public fun requireExecutable(path: String, source: String): File {
        val file = File(path)
        require(file.isFile && file.canExecute()) {
            "$source points to a missing or non-executable mutool binary: ${file.absolutePath}"
        }
        return file
    }

    /** Walk up from the test working directory to the repo root (has settings.gradle.kts + mupdf-master/). */
    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val hasSettings =
                File(dir, "settings.gradle.kts").exists() || File(dir, "settings.gradle").exists()
            if (hasSettings && File(dir, "mupdf-master").isDirectory) return dir
            dir = dir.parentFile
        }
        return null
    }

    private val executableNames = listOf("mutool", "mutool.exe")
}
