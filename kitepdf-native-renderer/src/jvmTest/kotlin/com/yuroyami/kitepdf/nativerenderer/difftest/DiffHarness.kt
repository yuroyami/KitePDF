package com.yuroyami.kitepdf.nativerenderer.difftest

import com.yuroyami.kitepdf.KitePDF
import com.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.io.File
import javax.imageio.ImageIO

/**
 * Differential rendering harness. For every page of every corpus PDF it
 * rasterizes with KitePDF, rasterizes the same page with the MuPDF oracle
 * (when available), pixel-diffs the two, and emits a worst-first report plus
 * the kite / reference / heatmap PNGs for inspection.
 *
 * When the oracle is unavailable it still renders + writes the KitePDF rasters
 * and checks they aren't blank — a useful smoke pass on its own.
 */
object DiffHarness {

    const val DEFAULT_DPI = 96

    /** Pages scored per doc. Override with -Dkitepdf.diff.maxpages; default 6. */
    val MAX_PAGES_PER_DOC: Int get() = System.getProperty("kitepdf.diff.maxpages")?.toIntOrNull() ?: 6

    data class PageResult(
        val doc: String,
        val page: Int,            // 0-based
        val synthetic: Boolean,
        val rendered: Boolean,
        val error: String?,
        val nonBlank: Boolean,
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
                    "blank=${results.count { it.rendered && !it.nonBlank }}",
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
                else "**none** — KitePDF-only smoke (set `-Dkitepdf.mutool=…` or build `mupdf-master`)",
            )
            md.appendLine("- DPI: $dpi")
            md.appendLine(
                "- Pages: ${results.size} · Render failures: ${results.count { !it.rendered }} · " +
                    "Blank: ${results.count { it.rendered && !it.nonBlank }}",
            )
            meanScore?.let { md.appendLine("- Mean score (MAE vs MuPDF): ${"%.4f".format(it)}") }
            md.appendLine()
            md.appendLine("Worst-rendering pages first. Score = normalized mean abs error vs MuPDF, 0 = identical.")
            md.appendLine()
            md.appendLine("| Doc | Pg | OK | Non-blank | Score | Diff% | MaxΔ | KitePDF | Ref | Diff |")
            md.appendLine("|---|---:|:---:|:---:|---:|---:|---:|---|---|---|")
            for (r in worstFirst) {
                fun link(p: String?) = if (p != null) "[png]($p)" else "—"
                md.appendLine(
                    "| ${r.doc} | ${r.page} | ${if (r.rendered) "✅" else "❌"} | ${if (r.nonBlank) "✅" else "·"} | " +
                        "${r.score?.let { "%.4f".format(it) } ?: "—"} | " +
                        "${r.diffFraction?.let { "%.2f%%".format(it * 100) } ?: "—"} | ${r.maxDelta ?: "—"} | " +
                        "${link(r.kitePng)} | ${link(r.refPng)} | ${link(r.diffPng)} |",
                )
                if (!r.rendered && r.error != null) {
                    md.appendLine("| ↳ | | | | | | | _${r.error.take(140).replace("|", "/")}_ | | |")
                }
            }
            File(outDir, "report.md").writeText(md.toString())
        }
    }

    fun run(corpus: List<Corpus.Entry>, dpi: Int = DEFAULT_DPI, outDir: File): Report {
        val scale = dpi / 72.0
        val oracle = MuPdfOracle.available
        val results = mutableListOf<PageResult>()

        for (entry in corpus) {
            val doc = try {
                KitePDF.open(entry.pdf.readBytes())
            } catch (e: Exception) {
                results += fail(entry, 0, "open: ${e.message}")
                continue
            }

            val pageCount = doc.pages.size.coerceAtMost(MAX_PAGES_PER_DOC)
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

                    if (oracle) {
                        val ref = MuPdfOracle.render(entry.pdf, i + 1, dpi)
                        if (ref != null) {
                            val refPng = File(docOut, "p$i.ref.png")
                            ImageIO.write(ref, "png", refPng)
                            val d = ImageDiff.compare(kiteImg, ref)
                            val diffPng = File(docOut, "p$i.diff.png")
                            ImageIO.write(d.heatmap, "png", diffPng)
                            score = d.meanAbsError
                            diffFrac = d.diffFraction
                            maxDelta = d.maxChannelDelta
                            refRel = rel(outDir, refPng)
                            diffRel = rel(outDir, diffPng)
                        }
                    }

                    results += PageResult(
                        doc = entry.name, page = i, synthetic = entry.synthetic,
                        rendered = true, error = null, nonBlank = nonBlank,
                        score = score, diffFraction = diffFrac, maxDelta = maxDelta,
                        kitePng = rel(outDir, kitePng), refPng = refRel, diffPng = diffRel,
                    )
                } catch (e: Exception) {
                    results += fail(entry, i, "render: ${e.message}")
                }
            }
        }

        return Report(results, oracle, MuPdfOracle.describe(), dpi, outDir)
    }

    private fun fail(entry: Corpus.Entry, page: Int, error: String) = PageResult(
        doc = entry.name, page = page, synthetic = entry.synthetic,
        rendered = false, error = error, nonBlank = false,
        score = null, diffFraction = null, maxDelta = null,
        kitePng = null, refPng = null, diffPng = null,
    )

    private fun rel(base: File, f: File): String =
        base.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/')
}
