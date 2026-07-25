package io.github.yuroyami.kitepdf.nativerenderer.difftest

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
        val dpi = parseDpi(System.getProperty("kitepdf.diff.dpi"))

        val corpus = Corpus.assemble(outDir)
        assertTrue(corpus.isNotEmpty(), "corpus is empty — expected synthetic fixtures at minimum")

        val report = DiffHarness.run(corpus, dpi, outDir)
        report.writeMarkdown()
        println(report.summary())
        assertTrue(
            report.results.isNotEmpty(),
            "differential sweep rendered zero pages — check corpus documents and kitepdf.diff.maxpages",
        )

        // KitePDF must not throw on any page.
        val failures = report.results.filter { !it.rendered }
        assertTrue(
            failures.isEmpty(),
            "KitePDF failed to render:\n" + failures.joinToString("\n") { "  ${it.doc} p${it.page}: ${it.error}" },
        )

        // Synthetic content fixtures must produce non-blank output.
        val blank = report.results.filter { it.synthetic && it.rendered && !it.nonBlank }
        assertTrue(
            blank.isEmpty(),
            "Blank render for fixtures: " + blank.joinToString { "${it.doc} p${it.page}" },
        )

        // A discovered oracle must successfully score every page that
        // KitePDF rendered. A broken mutool must never look like a zero score.
        if (report.oracleAvailable) assertOracleComplete(report)

        // With the oracle present, no page may exceed the regression budget.
        // Default budget is deliberately lenient: Phase 0's job is the scoreboard,
        // not a tight gate. Tighten with -Dkitepdf.diff.budget as correctness improves.
        if (report.oracleAvailable) {
            val budget = parseBudget(System.getProperty("kitepdf.diff.budget"))
            val over = report.results.filter { result ->
                result.score?.let { it > budget } == true
            }
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

    companion object {
        internal fun parseDpi(raw: String?): Int {
            if (raw == null) return DiffHarness.DEFAULT_DPI
            val value = raw.toIntOrNull()
            require(value != null && value >= 1) {
                "kitepdf.diff.dpi must be a positive integer (was '$raw')"
            }
            return value
        }

        internal fun parseBudget(raw: String?): Double {
            if (raw == null) return 0.50
            val value = raw.toDoubleOrNull()
            require(value != null && value.isFinite() && value in 0.0..1.0) {
                "kitepdf.diff.budget must be a finite value from 0.0 to 1.0 (was '$raw')"
            }
            return value
        }

        internal fun assertOracleComplete(report: DiffHarness.Report) {
            val unscored = report.results.filter { result ->
                result.rendered &&
                    (result.oracleError != null || result.score == null || !result.score.isFinite())
            }
            assertTrue(
                unscored.isEmpty(),
                "MuPDF oracle failed to score rendered pages:\n" +
                    unscored.joinToString("\n") {
                        "  ${it.doc} p${it.page}: ${it.oracleError ?: "no score or diagnostic returned"}"
                    },
            )
        }
    }
}
