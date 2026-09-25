package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.difftest.ColorFixtures
import io.github.yuroyami.kitepdf.difftest.GradientFixtures
import io.github.yuroyami.kitepdf.difftest.GroupFixtures
import io.github.yuroyami.kitepdf.difftest.ImageDiff
import io.github.yuroyami.kitepdf.difftest.ImageFixtures
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.PdfRenderOracle
import io.github.yuroyami.kitepdf.difftest.PdfiumOracle
import io.github.yuroyami.kitepdf.difftest.ThreeWayDiff
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.awt.image.BufferedImage
import java.io.File
import java.text.Normalizer
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Looks for anything that PDFium does better than KitePDF. For each page of the corpus
 * and of the oracle fixtures, it renders KitePDF on AWT, MuPDF and PDFium, compares the
 * three renders with [ThreeWayDiff], and compares the text that PDFium and KitePDF extract.
 *
 * PDFium does better on a page when:
 *  - KitePDF fails to open the document, or finds fewer pages, and PDFium does not.
 *  - KitePDF fails to render the page, and PDFium renders it.
 *  - MuPDF and PDFium agree on the page size, and KitePDF does not.
 *  - MuPDF and PDFium agree on the pixels of a tile or of the whole page, and KitePDF differs from both.
 *  - PDFium extracts a character from the page that KitePDF does not extract.
 *
 * A difference that only PDFium shows, or only MuPDF shows, is recorded and never fails.
 * A page where each of the three engines alone differs somewhere has no consensus: every
 * engine resamples or rounds in its own way there, so it is listed for review and never fails.
 */
object ParityHarness {

    data class Document(val name: String, val pdf: File)

    enum class Verdict(val label: String) {
        PDFIUM_BETTER("PDFium does better"),
        AGREE("KitePDF agrees"),
        PDFIUM_DIFFERS("PDFium alone differs"),
        MUPDF_DIFFERS("MuPDF alone differs"),
        NO_CONSENSUS("no consensus"),
        NO_REFERENCE("no reference render"),
    }

    data class PageResult(
        val doc: String,
        /** 0-based; -1 for a finding about the whole document. */
        val page: Int,
        val verdict: Verdict,
        /** Why PDFium does better, one line each. Empty unless the verdict is [Verdict.PDFIUM_BETTER]. */
        val findings: List<String>,
        /** Anything else worth reading: a failed reference, a difference only one reference shows. */
        val notes: List<String>,
        val diff: ThreeWayDiff.Result? = null,
        val images: String? = null,
    ) {
        val key: String get() = if (page < 0) doc else "$doc p$page"
    }

    class Report(
        val results: List<PageResult>,
        val knownGaps: Map<String, Int>,
        val dpi: Int,
        val outDir: File,
    ) {
        /** Pages where PDFium does better and no open issue records it. */
        val unexplained: List<PageResult> get() = results.filter { it.verdict == Verdict.PDFIUM_BETTER && it.key !in knownGaps }

        /** Known gaps whose page ran and no longer shows PDFium doing better. */
        val stale: List<String>
            get() = knownGaps.keys.filter { key ->
                val ran = results.filter { it.key == key }
                ran.isNotEmpty() && ran.none { it.verdict == Verdict.PDFIUM_BETTER }
            }

        fun summary(): String = buildString {
            appendLine("[parity] mupdf=${MuPdfOracle.describe()} pdfium=${PdfiumOracle.describe()} dpi=$dpi")
            appendLine("[parity] " + Verdict.entries.joinToString(" ") { v -> "${v.name}=${results.count { it.verdict == v }}" })
            append("[parity] report: ${File(outDir, "parity.md").absolutePath}")
        }

        fun writeMarkdown() {
            val md = StringBuilder()
            md.appendLine("# PDFium parity report")
            md.appendLine()
            md.appendLine("- MuPDF: `${MuPdfOracle.describe()}`")
            md.appendLine("- PDFium: `${PdfiumOracle.describe()}`")
            md.appendLine("- DPI: $dpi")
            md.appendLine("- " + Verdict.entries.joinToString(" · ") { v -> "${v.label}: ${results.count { it.verdict == v }}" })
            md.appendLine()
            md.appendLine(
                "Distances are the mean absolute error over RGB. A tile is ${ThreeWayDiff.TILE} pixels. " +
                    "The map is red where KitePDF alone differs, blue where PDFium alone differs, and green where MuPDF alone differs.",
            )
            md.appendLine()
            md.appendLine("| Doc | Pg | Verdict | K-M | K-P | M-P | Tiles K/P/M | Why | Renders |")
            md.appendLine("|---|---:|---|---:|---:|---:|---|---|---|")
            for (r in results.sortedBy { it.verdict.ordinal }) {
                val d = r.diff
                fun f(v: Double?) = v?.let { "%.4f".format(it) } ?: "n/a"
                val tiles = d?.let { t ->
                    listOf(ThreeWayDiff.Engine.KITE, ThreeWayDiff.Engine.PDFIUM, ThreeWayDiff.Engine.MUPDF)
                        .joinToString("/") { "${t.outlierTiles.getValue(it)}" }
                } ?: "n/a"
                val gap = knownGaps[r.key]?.let { " (known gap, #$it)" } ?: ""
                val why = (r.findings + r.notes).joinToString("; ").replace("|", "/").take(300)
                val renders = r.images?.let { "[K]($it.kite.png) [M]($it.mupdf.png) [P]($it.pdfium.png) [map]($it.map.png)" } ?: ""
                md.appendLine(
                    "| ${r.doc} | ${if (r.page < 0) "all" else r.page} | ${r.verdict.label}$gap | ${f(d?.kiteMupdf)} | ${f(d?.kitePdfium)} | " +
                        "${f(d?.mupdfPdfium)} | $tiles | $why | $renders |",
                )
            }
            File(outDir, "parity.md").writeText(md.toString())
        }
    }

    /** The drop-in and synthetic corpus, and every shared oracle fixture. */
    fun documents(outDir: File): List<Document> {
        val docs = Corpus.assemble(outDir).map { Document(it.name, it.pdf) }.toMutableList()
        val inputs = File(outDir, "parity-inputs").apply { mkdirs() }
        for (f in ColorFixtures.all() + GradientFixtures.all() + GroupFixtures.all() + ImageFixtures.all()) {
            val pdf = File(inputs, "${f.name}.pdf").apply { writeBytes(f.bytes) }
            docs += Document("fixture-${f.name}", pdf)
        }
        return docs
    }

    fun run(
        documents: List<Document>,
        dpi: Int,
        outDir: File,
        knownGaps: Map<String, Int>,
        maxPages: Int = DiffHarness.MAX_PAGES_PER_DOC,
        mupdf: PdfRenderOracle = MuPdfOracle,
        pdfium: PdfiumOracle = PdfiumOracle,
    ): Report {
        val results = ArrayList<PageResult>()
        for (document in documents) results += runDocument(document, dpi, outDir, maxPages, mupdf, pdfium)
        return Report(results, knownGaps, dpi, outDir)
    }

    private fun runDocument(
        document: Document,
        dpi: Int,
        outDir: File,
        maxPages: Int,
        mupdf: PdfRenderOracle,
        pdfium: PdfiumOracle,
    ): List<PageResult> {
        val pdfiumPages = (pdfium.pageCountDetailed(document.pdf) as? MuPdfOracle.PageCountResult.Success)?.count
        val kiteDoc = try {
            KitePDF.open(document.pdf.readBytes())
        } catch (e: Exception) {
            val finding = if ((pdfiumPages ?: 0) > 0) "KitePDF cannot open a document that PDFium opens: ${e.message}" else null
            return listOf(
                PageResult(
                    doc = document.name,
                    page = -1,
                    verdict = if (finding != null) Verdict.PDFIUM_BETTER else Verdict.NO_REFERENCE,
                    findings = listOfNotNull(finding),
                    notes = if (finding == null) listOf("neither KitePDF nor PDFium opens the document") else emptyList(),
                ),
            )
        }
        val results = ArrayList<PageResult>()
        val kitePages = kiteDoc.pages.size
        if (pdfiumPages != null && pdfiumPages > kitePages) {
            results += PageResult(
                doc = document.name,
                page = -1,
                verdict = Verdict.PDFIUM_BETTER,
                findings = listOf("PDFium finds $pdfiumPages pages and KitePDF finds $kitePages"),
                notes = emptyList(),
            )
        }
        val pages = kitePages.coerceAtMost(maxPages)
        val pdfiumText = if (pages > 0) pdfium.extractText(document.pdf, 1, pages) else emptyMap()
        val docOut = File(outDir, "parity/${document.name}").apply { mkdirs() }
        for (i in 0 until pages) {
            results += runPage(document, kiteDoc, i, dpi, outDir, docOut, mupdf, pdfium, pdfiumText[i + 1])
        }
        return results
    }

    private fun runPage(
        document: Document,
        kiteDoc: io.github.yuroyami.kitepdf.PdfDocument,
        i: Int,
        dpi: Int,
        outDir: File,
        docOut: File,
        mupdf: PdfRenderOracle,
        pdfium: PdfiumOracle,
        pdfiumText: PdfiumOracle.TextResult?,
    ): PageResult {
        val findings = ArrayList<String>()
        val notes = ArrayList<String>()
        val kite = try {
            AwtPdfRasterizer.renderToImage(kiteDoc.pages[i], scale = dpi / 72.0)
        } catch (e: Exception) {
            notes += "KitePDF failed to render: ${e.message}"
            null
        }
        val m = when (val r = mupdf.renderDetailed(document.pdf, i + 1, dpi)) {
            is MuPdfOracle.RenderResult.Success -> r.image
            is MuPdfOracle.RenderResult.Failure -> null.also { notes += "MuPDF failed: ${r.describe()}" }
        }
        val p = when (val r = pdfium.renderDetailed(document.pdf, i + 1, dpi)) {
            is MuPdfOracle.RenderResult.Success -> r.image
            is MuPdfOracle.RenderResult.Failure -> null.also { notes += "PDFium failed: ${r.describe()}" }
        }

        // Text: every character that PDFium extracts must be in the KitePDF text as well.
        if (pdfiumText is PdfiumOracle.TextResult.Success) {
            val kiteText = runCatching { kiteDoc.pages[i].extractText() }.getOrElse { "" }
            val missing = missingCharacters(pdfiumText.text, kiteText)
            if (missing.isNotEmpty()) {
                findings += "PDFium extracts ${missing.length} characters that KitePDF does not: \"${missing.take(40)}\""
            }
        }

        if (kite == null) {
            if (p != null && ImageDiff.nonBackgroundPixels(p) > 0) findings += "PDFium renders the page and KitePDF fails"
            return result(document, i, findings, notes, null, null)
        }
        val base = "parity/${document.name}/p$i"
        ImageIO.write(kite, "png", File(outDir, "$base.kite.png"))
        m?.let { ImageIO.write(it, "png", File(outDir, "$base.mupdf.png")) }
        p?.let { ImageIO.write(it, "png", File(outDir, "$base.pdfium.png")) }
        if (m == null || p == null) return result(document, i, findings, notes, null, base)

        val kiteFitsMupdf = fits(kite, m)
        val kiteFitsPdfium = fits(kite, p)
        if (!kiteFitsMupdf || !kiteFitsPdfium) {
            val referencesAgree = abs(m.width - p.width) <= 1 && abs(m.height - p.height) <= 1
            val sizes = "KitePDF ${kite.width}x${kite.height}, MuPDF ${m.width}x${m.height}, PDFium ${p.width}x${p.height}"
            if (referencesAgree) findings += "the page size differs from both references: $sizes" else notes += "page sizes differ: $sizes"
            return result(document, i, findings, notes, null, base)
        }
        val diff = ThreeWayDiff.compare(kite, m, p)
        ImageIO.write(diff.map, "png", File(outDir, "$base.map.png"))
        val everyEngineAlone = ThreeWayDiff.Engine.entries.all { diff.outlierTiles.getValue(it) > 0 }
        if (diff.kiteIsOutlier && everyEngineAlone && diff.pageOutlier != ThreeWayDiff.Engine.KITE) {
            notes += "each engine alone differs in some tiles, so the tiles where KitePDF alone differs prove nothing"
            return result(document, i, findings, notes, diff, base, noConsensus = true)
        }
        if (diff.kiteIsOutlier) {
            val tiles = diff.outlierTiles.getValue(ThreeWayDiff.Engine.KITE)
            val worst = diff.worstKiteTile?.let { t ->
                " The worst tile, at x ${t.x} and y ${t.y}, has K-M ${"%.3f".format(t.kiteMupdf)}, K-P ${"%.3f".format(t.kitePdfium)}, M-P ${"%.3f".format(t.mupdfPdfium)}."
            } ?: ""
            findings += when {
                diff.pageOutlier == ThreeWayDiff.Engine.KITE && tiles > 0 -> "MuPDF and PDFium agree and KitePDF differs, on the page and in $tiles tiles.$worst"
                diff.pageOutlier == ThreeWayDiff.Engine.KITE -> "MuPDF and PDFium agree and KitePDF differs across the page"
                else -> "MuPDF and PDFium agree and KitePDF differs in $tiles tiles.$worst"
            }
        }
        if (diff.outlierTiles.getValue(ThreeWayDiff.Engine.PDFIUM) > 0 || diff.pageOutlier == ThreeWayDiff.Engine.PDFIUM) {
            notes += "PDFium alone differs in ${diff.outlierTiles.getValue(ThreeWayDiff.Engine.PDFIUM)} tiles"
        }
        if (diff.outlierTiles.getValue(ThreeWayDiff.Engine.MUPDF) > 0 || diff.pageOutlier == ThreeWayDiff.Engine.MUPDF) {
            notes += "MuPDF alone differs in ${diff.outlierTiles.getValue(ThreeWayDiff.Engine.MUPDF)} tiles"
        }
        return result(document, i, findings, notes, diff, base)
    }

    private fun result(
        document: Document,
        page: Int,
        findings: List<String>,
        notes: List<String>,
        diff: ThreeWayDiff.Result?,
        images: String?,
        noConsensus: Boolean = false,
    ): PageResult {
        val verdict = when {
            findings.isNotEmpty() -> Verdict.PDFIUM_BETTER
            diff == null -> Verdict.NO_REFERENCE
            noConsensus -> Verdict.NO_CONSENSUS
            diff.pageOutlier == ThreeWayDiff.Engine.PDFIUM || diff.outlierTiles.getValue(ThreeWayDiff.Engine.PDFIUM) > 0 -> Verdict.PDFIUM_DIFFERS
            diff.pageOutlier == ThreeWayDiff.Engine.MUPDF || diff.outlierTiles.getValue(ThreeWayDiff.Engine.MUPDF) > 0 -> Verdict.MUPDF_DIFFERS
            else -> Verdict.AGREE
        }
        return PageResult(document.name, page, verdict, findings, notes, diff, images)
    }

    private fun fits(kite: BufferedImage, reference: BufferedImage): Boolean =
        abs(kite.width - reference.width) <= 1 && abs(kite.height - reference.height) <= 1

    /**
     * The characters of [pdfium] that [kite] lacks, counted with repeats. Both sides are
     * NFKC-normalized and lose their whitespace, because the engines insert spaces and line
     * breaks differently.
     */
    internal fun missingCharacters(pdfium: String, kite: String): String {
        fun bag(text: String): Map<Int, Int> =
            Normalizer.normalize(text, Normalizer.Form.NFKC).codePoints().toArray()
                .filter { !Character.isWhitespace(it) && it != 0xFFFE && it != 0xFFFD }
                .groupingBy { it }.eachCount()
        val kiteBag = bag(kite)
        return buildString {
            for ((cp, count) in bag(pdfium)) {
                repeat((count - (kiteBag[cp] ?: 0)).coerceAtLeast(0)) { appendCodePoint(cp) }
            }
        }
    }
}
