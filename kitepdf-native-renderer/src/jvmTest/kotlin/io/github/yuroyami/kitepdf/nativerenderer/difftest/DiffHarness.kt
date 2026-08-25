package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.difftest.ImageDiff
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.PdfRenderOracle

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Differential rendering harness. For every page of every corpus PDF it
 * rasterizes with KitePDF, rasterizes the same page with the MuPDF oracle
 * (when available), pixel-diffs the two, and emits a worst-first report plus
 * the kite / reference / heatmap PNGs for inspection.
 *
 * When the oracle is unavailable it still renders + writes the KitePDF rasters
 * and checks they aren't blank, a useful smoke pass on its own.
 */
object DiffHarness {

    const val DEFAULT_DPI = 96

    /** Pages scored per doc. Override with -Dkitepdf.diff.maxpages; default 6. */
    val MAX_PAGES_PER_DOC: Int
        get() = parseMaxPages(System.getProperty("kitepdf.diff.maxpages"))

    internal fun parseMaxPages(raw: String?): Int {
        if (raw == null) return 6
        val value = raw.toIntOrNull()
        require(value != null && value >= 1) {
            "kitepdf.diff.maxpages must be a positive integer (was '$raw')"
        }
        return value
    }

    data class PageResult(
        val doc: String,
        val page: Int,            // 0-based
        val synthetic: Boolean,
        val rendered: Boolean,
        val error: String?,
        val nonBlank: Boolean,
        val oracleError: String?,
        val score: Double?,       // null when oracle unavailable / ref render failed
        val diffFraction: Double?,
        val maxDelta: Int?,
        val kitePng: String?,     // paths relative to outDir
        val refPng: String?,
        val diffPng: String?,
    )

    data class Report(
        val results: List<PageResult>,
        val oracleAvailable: Boolean,
        val oraclePath: String,
        val dpi: Int,
        val outDir: File,
    ) {
        private val scored get() = results.mapNotNull { it.score }
        val meanScore: Double? get() = scored.takeIf { it.isNotEmpty() }?.average()
        val oracleFailures: List<PageResult>
            get() = results.filter { it.rendered && it.oracleError != null }

        val worstFirst: List<PageResult>
            get() = results.sortedByDescending {
                when {
                    !it.rendered -> Double.MAX_VALUE
                    it.score != null -> it.score
                    else -> -1.0
                }
            }

        fun summary(): String = buildString {
            appendLine("[difftest] oracle=${if (oracleAvailable) oraclePath else "none (KitePDF-only smoke)"} dpi=$dpi")
            appendLine(
                "[difftest] pages=${results.size} " +
                    "renderFailures=${results.count { !it.rendered }} " +
                    "blank=${results.count { it.rendered && !it.nonBlank }} " +
                    "oracleComparisonFailures=${oracleFailures.size}",
            )
            if (oracleAvailable) {
                val worst = worstFirst.firstOrNull { it.score != null }
                appendLine(
                    "[difftest] meanScore=${meanScore?.let { "%.4f".format(it) } ?: "n/a"} " +
                        "worst=${worst?.let { "${it.doc} p${it.page}=${"%.4f".format(it.score)}" } ?: "n/a"}",
                )
            }
            append("[difftest] report: ${File(outDir, "report.md").absolutePath}")
        }

        fun writeMarkdown() {
            val md = StringBuilder()
            md.appendLine("# KitePDF differential report")
            md.appendLine()
            md.appendLine(
                "- Oracle: " + if (oracleAvailable) "`$oraclePath`"
                else "**none**: KitePDF-only smoke (set `-Dkitepdf.mutool=…` or build `mupdf-master`)",
            )
            md.appendLine("- DPI: $dpi")
            md.appendLine(
                "- Pages: ${results.size} · Render failures: ${results.count { !it.rendered }} · " +
                    "Blank: ${results.count { it.rendered && !it.nonBlank }}",
            )
            if (oracleAvailable) md.appendLine("- Oracle/comparison failures: ${oracleFailures.size}")
            meanScore?.let { md.appendLine("- Mean score (MAE vs MuPDF): ${"%.4f".format(it)}") }
            md.appendLine()
            md.appendLine("Worst-rendering pages first. Score = normalized mean abs error vs MuPDF, 0 = identical.")
            md.appendLine()
            md.appendLine("| Doc | Pg | OK | Non-blank | Score | Diff% | MaxΔ | KitePDF | Ref | Diff |")
            md.appendLine("|---|---:|:---:|:---:|---:|---:|---:|---|---|---|")
            for (r in worstFirst) {
                fun link(p: String?) = if (p != null) "[png]($p)" else "n/a"
                md.appendLine(
                    "| ${r.doc} | ${r.page} | ${if (r.rendered) "✅" else "❌"} | ${if (r.nonBlank) "✅" else "·"} | " +
                        "${r.score?.let { "%.4f".format(it) } ?: "n/a"} | " +
                        "${r.diffFraction?.let { "%.2f%%".format(it * 100) } ?: "n/a"} | ${r.maxDelta ?: "n/a"} | " +
                        "${link(r.kitePng)} | ${link(r.refPng)} | ${link(r.diffPng)} |",
                )
                if (!r.rendered && r.error != null) {
                    md.appendLine("| ↳ | | | | | | | _${r.error.take(140).replace("|", "/")}_ | | |")
                }
            }
            if (oracleFailures.isNotEmpty()) {
                md.appendLine()
                md.appendLine("## Oracle/comparison failures")
                md.appendLine()
                for (r in oracleFailures) {
                    md.appendLine(
                        "- `${r.doc} p${r.page}`: " +
                            (r.oracleError ?: "unknown oracle failure").replace("\n", " "),
                    )
                }
            }
            File(outDir, "report.md").writeText(md.toString())
        }
    }

    internal fun run(
        corpus: List<Corpus.Entry>,
        dpi: Int = DEFAULT_DPI,
        outDir: File,
        oracle: PdfRenderOracle = MuPdfOracle,
    ): Report {
        require(dpi >= 1) { "dpi must be positive (was $dpi)" }
        val scale = dpi / 72.0
        val oracleAvailable = oracle.available
        val results = mutableListOf<PageResult>()

        for (entry in corpus) {
            val doc = try {
                KitePDF.open(entry.pdf.readBytes())
            } catch (e: Exception) {
                results += fail(entry, 0, "open: ${e.message}")
                continue
            }

            val kitePageCount = doc.pages.size
            val pageCount = kitePageCount.coerceAtMost(MAX_PAGES_PER_DOC)
            if (pageCount == 0) {
                results += fail(entry, 0, "document contains no renderable pages")
                continue
            }
            val documentOracleError = if (oracleAvailable) {
                when (val countResult = oracle.pageCountDetailed(entry.pdf)) {
                    is MuPdfOracle.PageCountResult.Success ->
                        if (countResult.count == kitePageCount) null
                        else "page count differs: KitePDF=$kitePageCount, MuPDF=${countResult.count}"

                    is MuPdfOracle.PageCountResult.Failure ->
                        countResult.describe()
                }
            } else {
                null
            }
            val docOut = File(outDir, "out/${entry.name}").apply { mkdirs() }

            for (i in 0 until pageCount) {
                try {
                    val kiteImg = AwtPdfRasterizer.renderToImage(doc.pages[i], scale = scale)
                    val kitePng = File(docOut, "p$i.kite.png")
                    ImageIO.write(kiteImg, "png", kitePng)
                    val nonBlank = ImageDiff.nonBackgroundPixels(kiteImg) > 20

                    var score: Double? = null
                    var diffFrac: Double? = null
                    var maxDelta: Int? = null
                    var refRel: String? = null
                    var diffRel: String? = null
                    var oracleError: String? = documentOracleError.takeIf { i == 0 }

                    if (oracleAvailable) {
                        when (val oracleResult = oracle.renderDetailed(entry.pdf, i + 1, dpi)) {
                            is MuPdfOracle.RenderResult.Success -> {
                                val refPng = File(docOut, "p$i.ref.png")
                                ImageIO.write(oracleResult.image, "png", refPng)
                                refRel = rel(outDir, refPng)
                                if (
                                    abs(kiteImg.width - oracleResult.image.width) > 1 ||
                                    abs(kiteImg.height - oracleResult.image.height) > 1
                                ) {
                                    oracleError = combineOracleErrors(
                                        oracleError,
                                        "page dimensions differ: KitePDF=${kiteImg.width}x${kiteImg.height}, " +
                                            "MuPDF=${oracleResult.image.width}x${oracleResult.image.height}",
                                    )
                                } else {
                                    val d = ImageDiff.compare(
                                        kiteImg,
                                        oracleResult.image,
                                        maxDimensionDelta = 1,
                                    )
                                    val diffPng = File(docOut, "p$i.diff.png")
                                    ImageIO.write(d.heatmap, "png", diffPng)
                                    score = d.meanAbsError
                                    diffFrac = d.diffFraction
                                    maxDelta = d.maxChannelDelta
                                    diffRel = rel(outDir, diffPng)
                                }
                            }

                            is MuPdfOracle.RenderResult.Failure -> {
                                oracleError = combineOracleErrors(oracleError, oracleResult.describe())
                            }
                        }
                    }

                    results += PageResult(
                        doc = entry.name, page = i, synthetic = entry.synthetic,
                        rendered = true, error = null, nonBlank = nonBlank,
                        oracleError = oracleError,
                        score = score, diffFraction = diffFrac, maxDelta = maxDelta,
                        kitePng = rel(outDir, kitePng), refPng = refRel, diffPng = diffRel,
                    )
                } catch (e: Exception) {
                    results += fail(entry, i, "render: ${e.message}")
                }
            }
        }

        return Report(results, oracleAvailable, oracle.describe(), dpi, outDir)
    }

    private fun fail(entry: Corpus.Entry, page: Int, error: String) = PageResult(
        doc = entry.name, page = page, synthetic = entry.synthetic,
        rendered = false, error = error, nonBlank = false,
        oracleError = null,
        score = null, diffFraction = null, maxDelta = null,
        kitePng = null, refPng = null, diffPng = null,
    )

    private fun rel(base: File, f: File): String =
        base.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/')

    private fun combineOracleErrors(first: String?, second: String): String =
        if (first == null) second else "$first; $second"
}
