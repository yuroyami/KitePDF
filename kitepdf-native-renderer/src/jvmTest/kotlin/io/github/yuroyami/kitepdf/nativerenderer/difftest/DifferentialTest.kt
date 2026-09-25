package io.github.yuroyami.kitepdf.nativerenderer.difftest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The correctness scoreboard. Sweeps the corpus, renders KitePDF vs the MuPDF oracle,
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
 *   -Dkitepdf.diff.updateBaseline=true rewrite the per-page baseline, `difftest-baseline.txt`
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
        assertTrue(corpus.isNotEmpty(), "corpus is empty, expected synthetic fixtures at minimum")

        val report = DiffHarness.run(corpus, dpi, outDir)
        report.writeMarkdown()
        println(report.summary())
        assertTrue(
            report.results.isNotEmpty(),
            "differential sweep rendered zero pages: check corpus documents and kitepdf.diff.maxpages",
        )

        // KitePDF must not throw on any page.
        val failures = report.results.filter { !it.rendered }
        assertTrue(
            failures.isEmpty(),
            "KitePDF failed to render:\n" + failures.joinToString("\n") { "  ${it.doc} p${it.page}: ${it.error}" },
        )

        // No page may render blank where the reference paints it: fixtures always,
        // real documents whenever the oracle shows ink. A page blank in both is
        // a genuinely blank page (#43).
        val blank = report.results.filter { r ->
            r.rendered && !r.nonBlank && (r.synthetic || (r.referenceInk ?: 0L) > 20L)
        }
        assertTrue(
            blank.isEmpty(),
            "Blank render: " + blank.joinToString { "${it.doc} p${it.page}" },
        )

        // A discovered oracle must successfully score every page that
        // KitePDF rendered. A broken mutool must never look like a zero score.
        if (report.oracleAvailable) assertOracleComplete(report)

        // With the oracle present, no page may exceed the regression budget.
        // The default sits near 2x the observed worst page (0.026 as of
        // 2026-08-25), so a real regression fails instead of hiding under a
        // lenient ceiling. Loosen per run with -Dkitepdf.diff.budget if a
        // deliberate change moves the baseline.
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
                "[difftest] mutool not found, KitePDF-only smoke pass. " +
                    "Build mupdf-master (mujs=no) or pass -Dkitepdf.mutool to enable differential scoring.",
            )
        }

        // Each page against its own recorded score (#195). The scores hold at the default
        // density only, so a run at another density skips them.
        if (report.oracleAvailable && dpi == DiffHarness.DEFAULT_DPI) {
            checkBaseline(corpus, report, update = System.getProperty("kitepdf.diff.updateBaseline") == "true")
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
            if (raw == null) return 0.05
            val value = raw.toDoubleOrNull()
            require(value != null && value.isFinite() && value in 0.0..1.0) {
                "kitepdf.diff.budget must be a finite value from 0.0 to 1.0 (was '$raw')"
            }
            return value
        }

        /**
         * Fails when a page scores worse than its recorded baseline, or when a fixture has no
         * baseline. With [update], writes this run's scores instead.
         */
        internal fun checkBaseline(corpus: List<Corpus.Entry>, report: DiffHarness.Report, update: Boolean) {
            val current = LinkedHashMap<String, DiffBaseline.Score>()
            val names = HashMap<String, String>()
            for (r in report.results) {
                val entry = r.source ?: continue
                val score = DiffBaseline.Score(r.score ?: continue, r.diffFraction ?: continue, r.maxDelta ?: continue)
                val key = DiffBaseline.key(entry, r.page)
                current[key] = score
                names[key] = "${r.doc} p${r.page}"
                println("[difftest] page $key ${"%.5f".format(score.mae)} ${"%.5f".format(score.diffFraction)} ${score.maxDelta} (${r.doc})")
            }
            val file = DiffBaseline.file()
            val baseline = DiffBaseline.read(file)
            if (update) {
                DiffBaseline.write(file, baseline, current, corpus.filter { it.synthetic }.map { it.name }.toSet())
                println("[difftest] wrote ${current.size} page scores to ${file.path}")
                return
            }
            val worse = current.mapNotNull { (key, now) ->
                val shown = names[key].let { if (it == key) it else "$it [$key]" }
                baseline[key]?.let { base -> DiffBaseline.regression(base, now)?.let { "  $shown: $it" } }
            }
            val improved = current.filter { (key, now) -> baseline[key]?.let { DiffBaseline.improved(it, now) } == true }
            if (improved.isNotEmpty()) {
                println("[difftest] better than the baseline, record it with -Dkitepdf.diff.updateBaseline=true: " + improved.keys.joinToString { names[it]!! })
            }
            val missing = current.keys.filter { it !in baseline }
            val missingFixtures = missing.filter { !it.startsWith("sha1-") }
            if (missing.size > missingFixtures.size) {
                println("[difftest] corpus pages not in the baseline yet: " + missing.filter { it.startsWith("sha1-") }.joinToString { names[it]!! })
            }
            assertTrue(
                worse.isEmpty() && missingFixtures.isEmpty(),
                buildString {
                    if (worse.isNotEmpty()) append("Pages worse than their baseline:\n" + worse.joinToString("\n") + "\n")
                    if (missingFixtures.isNotEmpty()) append("Fixture pages with no baseline: " + missingFixtures.joinToString() + "\n")
                    append("If the change is intended, rerun with -Dkitepdf.diff.updateBaseline=true and explain it in the commit.")
                },
            )
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
