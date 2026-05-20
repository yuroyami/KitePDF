package com.yuroyami.kitepdf.nativerenderer.difftest

import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Reference rasteriser backed by MuPDF's `mutool draw`. MuPDF is the in-repo
 * oracle (`mupdf-master/`) — the cleanest open-source PDF engine — so the
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
object MuPdfOracle {

    val binary: File? by lazy { locate() }
    val available: Boolean get() = binary != null

    fun describe(): String = binary?.absolutePath ?: "<not found>"

    /**
     * Render one [page] (1-based) of [pdf] at [dpi] to a [BufferedImage], or
     * null if the tool is unavailable or the render fails for any reason.
     */
    fun render(pdf: File, page: Int, dpi: Int): BufferedImage? {
        val tool = binary ?: return null
        val out = File.createTempFile("kite-ref-", ".png")
        return try {
            val proc = ProcessBuilder(
                tool.absolutePath,
                "draw",
                "-r", dpi.toString(),
                "-F", "png",
                "-o", out.absolutePath,
                pdf.absolutePath,
                page.toString(),
            ).redirectErrorStream(true).start()

            // Drain output so the process can't block on a full pipe, then wait.
            proc.inputStream.readBytes()
            if (!proc.waitFor(60, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return null
            }
            if (proc.exitValue() != 0 || !out.exists() || out.length() == 0L) return null
            ImageIO.read(out)
        } catch (_: Exception) {
            null
        } finally {
            out.delete()
        }
    }

    private fun locate(): File? {
        System.getProperty("kitepdf.mutool")?.let { p ->
            val f = File(p)
            if (f.canExecute()) return f
        }
        System.getenv("MUTOOL")?.let { p ->
            val f = File(p)
            if (f.canExecute()) return f
        }
        repoRoot()?.let { root ->
            for (variant in listOf("release", "debug")) {
                val f = File(root, "mupdf-master/build/$variant/mutool")
                if (f.canExecute()) return f
            }
        }
        val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        for (dir in pathDirs) {
            if (dir.isBlank()) continue
            val f = File(dir, "mutool")
            if (f.canExecute()) return f
        }
        return null
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
}
