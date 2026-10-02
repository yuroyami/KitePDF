package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.difftest.CorpusSelection
import java.io.File

/**
 * Assembles the set of PDFs the harness scores against:
 *
 *  - the deterministic [SyntheticPdfs] fixtures (always present), and
 *  - any real-world `.pdf` files in the drop-in corpus directory
 *    (default the git-ignored repo-root `corpus/pdf`, overridable with
 *    `-Dkitepdf.corpus=/path`).
 *
 * Synthetic fixtures are materialized to `<outDir>/inputs` so that MuPDF,
 * which reads from disk, and KitePDF score the exact same bytes.
 */
object Corpus {

    data class Entry(val name: String, val pdf: File, val synthetic: Boolean)

    /** The repo-root `corpus/<sub>` directory (found by walking up to settings.gradle.kts). */
    fun repoCorpus(sub: String): File? = CorpusSelection.repoCorpus(sub)

    fun assemble(outDir: File, reservedNames: Set<String> = emptySet()): List<Entry> {
        val inputs = File(outDir, "inputs").apply { mkdirs() }
        val entries = mutableListOf<Entry>()

        for (fx in SyntheticPdfs.all() + GeneratedPdfs.all()) {
            val f = File(inputs, "${fx.name}.pdf")
            f.writeBytes(fx.bytes)
            entries += Entry(fx.name, f, synthetic = true)
        }

        CorpusSelection.configuredDocuments("pdf", reservedNames + entries.map { it.name })
            .forEach { entries += Entry(it.name, it.file, synthetic = false) }
        return entries
    }

    internal fun resolveCorpusDirectory(
        propertyName: String,
        configuredPath: String?,
        fallback: File?,
    ): File? = CorpusSelection.resolveDirectory(propertyName, configuredPath, fallback)
}
