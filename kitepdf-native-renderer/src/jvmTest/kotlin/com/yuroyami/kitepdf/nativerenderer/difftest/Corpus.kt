package com.yuroyami.kitepdf.nativerenderer.difftest

import java.io.File

/**
 * Assembles the set of PDFs the harness scores against:
 *
 *  - the deterministic [SyntheticPdfs] fixtures (always present), and
 *  - any real-world `.pdf` files in the drop-in corpus directory
 *    (default `./corpus`, overridable with `-Dkitepdf.corpus=/path`).
 *
 * Synthetic fixtures are materialized to `<outDir>/inputs` so that MuPDF —
 * which reads from disk — and KitePDF score the exact same bytes.
 */
object Corpus {

    data class Entry(val name: String, val pdf: File, val synthetic: Boolean)

    fun assemble(outDir: File): List<Entry> {
        val inputs = File(outDir, "inputs").apply { mkdirs() }
        val entries = mutableListOf<Entry>()

        for (fx in SyntheticPdfs.all()) {
            val f = File(inputs, "${fx.name}.pdf")
            f.writeBytes(fx.bytes)
            entries += Entry(fx.name, f, synthetic = true)
        }

        val dropIn = File(System.getProperty("kitepdf.corpus") ?: "corpus")
        if (dropIn.isDirectory) {
            dropIn.walkTopDown()
                .filter { it.isFile && it.extension.equals("pdf", ignoreCase = true) }
                .sortedBy { it.path }
                .forEach { entries += Entry(it.nameWithoutExtension, it, synthetic = false) }
        }
        return entries
    }
}
