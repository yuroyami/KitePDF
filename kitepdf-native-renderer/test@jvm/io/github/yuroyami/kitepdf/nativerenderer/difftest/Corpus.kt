package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.difftest.CorpusSelection
import java.io.File

/**
 * Assembles the set of PDFs the harness scores against:
 *
 *  - the deterministic [SyntheticPdfs] fixtures (always present),
 *  - the encrypted fixtures of kitepdf-pdf's `testResources@jvm/encrypted`, which qpdf and
 *    `make.py` wrote, each opened with its user password by both renderers (#908), and
 *  - any real-world `.pdf` files in the drop-in corpus directory
 *    (default the git-ignored repo-root `corpus/pdf`, overridable with
 *    `-Dkitepdf.corpus=/path`).
 *
 * Synthetic fixtures are materialized to `<outDir>/inputs` so that MuPDF,
 * which reads from disk, and KitePDF score the exact same bytes.
 */
object Corpus {

    /** One document; [password] opens an encrypted one, in KitePDF and in the oracle. */
    data class Entry(val name: String, val pdf: File, val synthetic: Boolean, val password: String? = null)

    /** The user password of every encrypted fixture but `owner-only.pdf`, whose user password is empty (make.py). */
    private const val ENCRYPTED_USER_PASSWORD = "user"

    /** The encrypted fixtures as sweep entries named `enc-<file>`, or none outside the repository. */
    fun encrypted(): List<Entry> {
        val dir = repoCorpus("pdf")?.parentFile?.parentFile?.let { File(it, "kitepdf-pdf/testResources@jvm/encrypted") }
            ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".pdf") }.orEmpty().sortedBy { it.name }
        return files.map { f ->
            val name = "enc-" + f.name.removeSuffix(".pdf")
            Entry(name, f, synthetic = true, password = if (f.name == "owner-only.pdf") null else ENCRYPTED_USER_PASSWORD)
        }
    }

    /** The repo-root `corpus/<sub>` directory (found by walking up to project.yaml). */
    fun repoCorpus(sub: String): File? = CorpusSelection.repoCorpus(sub)

    fun assemble(outDir: File, reservedNames: Set<String> = emptySet()): List<Entry> {
        val inputs = File(outDir, "inputs").apply { mkdirs() }
        val entries = mutableListOf<Entry>()

        for (fx in SyntheticPdfs.all() + GeneratedPdfs.all()) {
            val f = File(inputs, "${fx.name}.pdf")
            f.writeBytes(fx.bytes)
            entries += Entry(fx.name, f, synthetic = true)
        }
        entries += encrypted()

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
