package io.github.yuroyami.kitepdf.nativerenderer.difftest

import java.io.File

/**
 * Assembles the set of PDFs the harness scores against:
 *
 *  - the deterministic [SyntheticPdfs] fixtures (always present), and
 *  - any real-world `.pdf` files in the drop-in corpus directory
 *    (default the git-ignored repo-root `corpus/pdf`, overridable with
 *    `-Dkitepdf.corpus=/path`).
 *
 * Synthetic fixtures are materialized to `<outDir>/inputs` so that MuPDF —
 * which reads from disk — and KitePDF score the exact same bytes.
 */
object Corpus {

    data class Entry(val name: String, val pdf: File, val synthetic: Boolean)

    /** The repo-root `corpus/<sub>` directory (found by walking up to settings.gradle.kts). */
    fun repoCorpus(sub: String): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            if (File(d, "settings.gradle.kts").exists()) return File(d, "corpus/$sub")
            d = d.parentFile
        }
        return null
    }

    fun assemble(outDir: File): List<Entry> {
        val inputs = File(outDir, "inputs").apply { mkdirs() }
        val entries = mutableListOf<Entry>()

        for (fx in SyntheticPdfs.all() + GeneratedPdfs.all()) {
            val f = File(inputs, "${fx.name}.pdf")
            f.writeBytes(fx.bytes)
            entries += Entry(fx.name, f, synthetic = true)
        }

        val dropIn = System.getProperty("kitepdf.corpus")?.let { File(it) } ?: repoCorpus("pdf")
        if (dropIn != null && dropIn.isDirectory) {
            dropIn.walkTopDown()
                .filter { it.isFile && it.extension.equals("pdf", ignoreCase = true) }
                .sortedBy { it.path }
                .forEach { entries += Entry(it.nameWithoutExtension, it, synthetic = false) }
        }
        return entries
    }
}
