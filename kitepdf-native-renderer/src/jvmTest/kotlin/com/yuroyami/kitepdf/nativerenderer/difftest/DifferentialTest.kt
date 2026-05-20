package com.yuroyami.kitepdf.nativerenderer.difftest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 0 scoreboard. Sweeps the corpus, renders KitePDF vs the MuPDF oracle,
 * writes `build/difftest/report.md` (+ per-page PNGs), and gates CI.
 *
 * Run:
 *   ./gradlew :kitepdf-native-renderer:jvmTest
 *
 * Useful knobs (system properties):
 *   -Dkitepdf.mutool=/path/to/mutool   explicit oracle binary
 *   -Dkitepdf.corpus=/path/to/pdfs     extra real-world corpus dir (default ./corpus)
 *   -Dkitepdf.diff.dpi=150             render density (default 96)
 *   -Dkitepdf.diff.budget=0.15         max allowed per-page MAE before failing
 *   -Dkitepdf.difftest.out=build/difftest   output directory
 *
 * Without an oracle the test degrades to a KitePDF-only smoke pass (render
 * success + non-blank fixtures) and still emits the report.
 */
class DifferentialTest {

    @Test
    fun differential_sweep_against_mupdf() {
        val outDir = File(System.getProperty("kitepdf.difftest.out") ?: "build/difftest").apply { mkdirs() }
        val dpi = System.getProperty("kitepdf.diff.dpi")?.toIntOrNull() ?: DiffHarness.DEFAULT_DPI

        val corpus = Corpus.assemble(outDir)
        assertTrue(corpus.isNotEmpty(), "corpus is empty — expected synthetic fixtures at minimum")

        val report = DiffHarness.run(corpus, dpi, outDir)
        report.writeMarkdown()
        println(report.summary())

        // Gate 1 — KitePDF must not throw on any page.
        val failures = report.results.filter { !it.rendered }
        assertTrue(
            failures.isEmpty(),
            "KitePDF failed to render:\n" + failures.joinToString("\n") { "  ${it.doc} p${it.page}: ${it.error}" },
        )

        // Gate 2 — synthetic content fixtures must produce non-blank output.
        val blank = report.results.filter { it.synthetic && it.rendered && !it.nonBlank }
        assertTrue(
            blank.isEmpty(),
            "Blank render for fixtures: " + blank.joinToString { "${it.doc} p${it.page}" },
        )

        // Gate 3 — with the oracle present, no page may exceed the regression budget.
        // Default budget is deliberately lenient: Phase 0's job is the scoreboard,
        // not a tight gate. Tighten with -Dkitepdf.diff.budget as correctness improves.
        if (report.oracleAvailable) {
            val budget = System.getProperty("kitepdf.diff.budget")?.toDoubleOrNull() ?: 0.50
            val over = report.results.filter { (it.score ?: 0.0) > budget }
            assertTrue(
                over.isEmpty(),
                "Pages over diff budget ($budget):\n" +
                    over.joinToString("\n") { "  ${it.doc} p${it.page} = ${"%.4f".format(it.score)}" },
            )
        } else {
            println(
                "[difftest] mutool not found — KitePDF-only smoke pass. " +
                    "Build mupdf-master (mujs=no) or pass -Dkitepdf.mutool to enable differential scoring.",
            )
        }
    }
}
