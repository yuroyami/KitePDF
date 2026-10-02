package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.difftest.CorpusSelection
import io.github.yuroyami.kitepdf.difftest.ImageDiff
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Differential harness for the **Skia** backend, on the shared hardened
 * infrastructure ([MuPdfOracle], [ImageDiff] in `:kitepdf-difftest`), closing
 * the known false-green cases:
 *
 *  - a failed oracle render is a test FAILURE, never a perfect score;
 *  - dimensions must match within one pixel ([ImageDiff.compare] throws);
 *  - the oracle's process handling reads output concurrently with the
 *    timeout, so a full pipe cannot defeat it;
 *  - an explicitly configured mutool path that is not executable throws
 *    instead of being silently ignored (the oracle's own lookup rule);
 *  - the drop-in corpus is the repo `corpus/pdf`, same as the AWT harness;
 *  - no blank-text exemption: the Skia backend grew its system-font
 *    fallback long ago, so base-14 text must render like everything else.
 *
 * Degrades to a render-success + non-blank smoke pass when no mutool exists.
 */
class SkiaDifferentialTest {

    private val dpi = System.getProperty("kitepdf.diff.dpi")?.let { raw ->
        val value = raw.toIntOrNull()
        require(value != null && value > 0) { "kitepdf.diff.dpi must be a positive integer (was '$raw')" }
        value
    } ?: 96
    private val syntheticNames = setOf("gen-text-base14", "gen-rgb", "gen-vector", "gen-transform")
    private val scale = dpi / 72.0

    private data class Result(
        val name: String,
        val page: Int,
        val rendered: Boolean,
        val nonBlank: Boolean,
        val score: Double?,
        val oracleFailure: String?,
        val referenceInk: Long? = null,
        val error: String? = null,
    )

    @Test
    fun skia_backend_differential_sweep() {
        val outDir = File(System.getProperty("kitepdf.skia.difftest.out") ?: "build/skia-difftest").apply { mkdirs() }
        val budget = parseBudget(System.getProperty("kitepdf.diff.budget"))
        val results = mutableListOf<Result>()
        val selection = CorpusSelection.configuredPages()
        val coverage = linkedMapOf<String, Pair<Int, List<Int>>>()
        val corpus = inputs()

        for ((name, bytes) in corpus) {
            val doc = try {
                KitePDF.open(bytes)
            } catch (e: Exception) {
                results += Result(name, -1, rendered = false, nonBlank = false, score = null, oracleFailure = null, error = "open: ${e.message}")
                continue
            }
            val pages = selection.indices(doc.pages.size)
            coverage[name] = doc.pages.size to pages
            if (pages.isEmpty()) {
                results += Result(name, -1, rendered = false, nonBlank = false, score = null, oracleFailure = null, error = "document contains no renderable pages")
                continue
            }
            val pdfFile = File(outDir, "$name.pdf").apply { writeBytes(bytes) }
            for (i in pages) {
                results += scorePage(name, doc.pages[i], i, pdfFile)
            }
        }

        writeReport(outDir, results, coverage, selection.describe(), corpus.size)
        val mean = results.mapNotNull { it.score }.takeIf { it.isNotEmpty() }?.average()
        println(
            "[skia-difftest] oracle=${MuPdfOracle.describe()} documents=${corpus.size} " +
                "selectedPages=${coverage.values.sumOf { it.second.size }} availablePages=${coverage.values.sumOf { it.first }} " +
                "selection=${selection.describe()} " +
                "mean=${mean?.let { "%.4f".format(it) } ?: "n/a"} report=${File(outDir, "report.md").path}",
        )

        // Skia must render (not throw on) every page.
        assertTrue(
            results.all { it.rendered },
            "Skia failed to render: " + results.filter { !it.rendered }.map { "${it.name} p${it.page}" },
        )
        // Every generated fixture must paint something, and so must every corpus
        // page the reference paints (#43).
        val blank = results.filter {
            it.rendered && !it.nonBlank && (it.name in syntheticNames || (it.referenceInk ?: 0L) > 20L)
        }
        assertTrue(blank.isEmpty(), "blank Skia render: " + blank.map { "${it.name} p${it.page}" })

        if (MuPdfOracle.available) {
            // A discovered oracle must score every rendered page. A broken
            // mutool run or a dimension mismatch is a failure, not a zero.
            val oracleFailures = results.filter { it.rendered && it.oracleFailure != null }
            assertTrue(
                oracleFailures.isEmpty(),
                "oracle could not score:\n" +
                    oracleFailures.joinToString("\n") { "  ${it.name} p${it.page}: ${it.oracleFailure}" },
            )
            val over = results.filter { (it.score ?: 0.0) > budget }
            assertTrue(
                over.isEmpty(),
                "Skia pages over budget ($budget): " + over.map { "${it.name} p${it.page}=${"%.4f".format(it.score)}" },
            )
        } else {
            println("[skia-difftest] mutool not found, KitePDF-only smoke pass.")
        }
    }

    private fun scorePage(name: String, page: PdfPage, i: Int, pdfFile: File): Result {
        val skiaImg = runCatching {
            ImageIO.read(ByteArrayInputStream(PdfPageRasterizer.encodeToPng(page, scale)))
        }.getOrNull() ?: return Result(name, i, rendered = false, nonBlank = false, score = null, oracleFailure = null)

        val ink = ImageDiff.nonBackgroundPixels(skiaImg)
        val nonBlank = ink > 20
        if (!MuPdfOracle.available) {
            return Result(name, i, rendered = true, nonBlank = nonBlank, score = null, oracleFailure = null)
        }
        return when (val ref = MuPdfOracle.renderDetailed(pdfFile, i + 1, dpi)) {
            is MuPdfOracle.RenderResult.Success -> {
                val outcome = runCatching { ImageDiff.compare(skiaImg, ref.image).score }
                val refInk = ImageDiff.nonBackgroundPixels(ref.image)
                Result(
                    name, i, rendered = true,
                    // Blank: next to no ink, or under a tenth of what the reference paints (#43).
                    nonBlank = nonBlank && ink * 10 >= refInk,
                    score = outcome.getOrNull(),
                    oracleFailure = outcome.exceptionOrNull()?.message,
                    referenceInk = refInk,
                )
            }
            is MuPdfOracle.RenderResult.Failure ->
                Result(name, i, rendered = true, nonBlank = nonBlank, score = null, oracleFailure = ref.describe())
        }
    }

    /* Inputs: writer-generated pages and every recursively discovered corpus PDF. */

    private fun inputs(): List<Pair<String, ByteArray>> {
        val list = mutableListOf<Pair<String, ByteArray>>()
        list += "gen-text-base14" to PdfBuilder()
            .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "Skia backend differential") }
            .build()
        list += "gen-rgb" to PdfBuilder().page {
            setFillRgb(1.0, 0.0, 0.0); rectangle(56.0, 600.0, 120.0, 120.0); fill()
            setFillRgb(0.0, 0.0, 1.0); rectangle(200.0, 600.0, 120.0, 120.0); fill()
        }.build()
        list += "gen-vector" to PdfBuilder().page {
            setStrokeRgb(0.1, 0.4, 0.9); setLineWidth(6.0)
            moveTo(72.0, 200.0); lineTo(400.0, 240.0); lineTo(300.0, 420.0); closePath(); stroke()
            setFillGray(0.3); rectangle(72.0, 500.0, 300.0, 120.0); fill()
        }.build()
        list += "gen-transform" to PdfBuilder().page {
            save(); transform(0.94, 0.34, -0.34, 0.94, 200.0, 300.0)
            setFillRgb(0.2, 0.7, 0.4); rectangle(0.0, 0.0, 200.0, 40.0); fill(); restore()
        }.build()

        CorpusSelection.configuredDocuments("pdf", syntheticNames)
            .forEach { list += it.name to it.file.readBytes() }
        return list
    }

    private fun parseBudget(raw: String?): Double {
        if (raw == null) return 0.05
        val value = raw.toDoubleOrNull()
        require(value != null && value.isFinite() && value in 0.0..1.0) {
            "kitepdf.diff.budget must be a finite value from 0.0 to 1.0 (was '$raw')"
        }
        return value
    }

    private fun writeReport(
        outDir: File,
        results: List<Result>,
        coverage: Map<String, Pair<Int, List<Int>>>,
        selection: String,
        documentCount: Int,
    ) {
        val mean = results.mapNotNull { it.score }.takeIf { it.isNotEmpty() }?.average()
        val md = StringBuilder()
        md.appendLine("# KitePDF Skia-backend differential report").appendLine()
        md.appendLine("- Oracle: ${MuPdfOracle.describe()}")
        md.appendLine("- DPI: $dpi · Documents: $documentCount · Render failures: ${results.count { !it.rendered }}")
        md.appendLine("- Selection: $selection")
        md.appendLine("- Selected pages: ${coverage.values.sumOf { it.second.size }} / ${coverage.values.sumOf { it.first }} available in opened documents")
        mean?.let { md.appendLine("- Mean score (MAE vs MuPDF): ${"%.4f".format(it)}") }
        md.appendLine().appendLine("## Coverage").appendLine()
        md.appendLine("Page indices are zero-based. Documents that fail to open remain failures below.")
        md.appendLine()
        md.appendLine("| Doc | Available | Selected | Page indices |")
        md.appendLine("|---|---:|---:|---|")
        coverage.forEach { (name, pages) ->
            md.appendLine("| $name | ${pages.first} | ${pages.second.size} | ${pages.second.joinToString()} |")
        }
        md.appendLine().appendLine("| Doc | Pg | OK | Non-blank | Score |").appendLine("|---|---:|:---:|:---:|---:|")
        for (r in results.sortedByDescending { it.score ?: -1.0 }) {
            val score = r.score?.let { "%.4f".format(it) } ?: (r.error ?: r.oracleFailure ?: "n/a")
            md.appendLine("| ${r.name} | ${r.page} | ${if (r.rendered) "OK" else "FAIL"} | ${if (r.nonBlank) "yes" else "no"} | $score |")
        }
        File(outDir, "report.md").writeText(md.toString())
    }
}
