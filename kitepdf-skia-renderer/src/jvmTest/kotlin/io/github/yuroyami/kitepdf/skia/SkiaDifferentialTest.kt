package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.PdfPage
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
 * ledger D-6's false-green list:
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

    private val dpi = 96
    private val scale = dpi / 72.0

    private data class Result(
        val name: String,
        val page: Int,
        val rendered: Boolean,
        val nonBlank: Boolean,
        val score: Double?,
        val oracleFailure: String?,
    )

    @Test
    fun skia_backend_differential_sweep() {
        val outDir = File(System.getProperty("kitepdf.skia.difftest.out") ?: "build/skia-difftest").apply { mkdirs() }
        val budget = parseBudget(System.getProperty("kitepdf.diff.budget"))
        val results = mutableListOf<Result>()

        for ((name, bytes) in inputs()) {
            val doc = runCatching { KitePDF.open(bytes) }.getOrNull() ?: run {
                results += Result(name, 0, rendered = false, nonBlank = false, score = null, oracleFailure = null)
                continue
            }
            val pageCount = doc.pages.size.coerceAtMost(if (name.startsWith("gen-")) 5 else 2)
            val pdfFile = File(outDir, "$name.pdf").apply { writeBytes(bytes) }
            for (i in 0 until pageCount) {
                results += scorePage(name, doc.pages[i], i, pdfFile)
            }
        }

        writeReport(outDir, results)
        val mean = results.mapNotNull { it.score }.takeIf { it.isNotEmpty() }?.average()
        println(
            "[skia-difftest] oracle=${MuPdfOracle.describe()} pages=${results.size} " +
                "mean=${mean?.let { "%.4f".format(it) } ?: "n/a"} report=${File(outDir, "report.md").path}",
        )

        // Skia must render (not throw on) every page.
        assertTrue(
            results.all { it.rendered },
            "Skia failed to render: " + results.filter { !it.rendered }.map { "${it.name} p${it.page}" },
        )
        // Every generated fixture must paint something; no text exemption.
        val blank = results.filter { it.name.startsWith("gen-") && !it.nonBlank }
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

        val nonBlank = ImageDiff.nonBackgroundPixels(skiaImg) > 20
        if (!MuPdfOracle.available) {
            return Result(name, i, rendered = true, nonBlank = nonBlank, score = null, oracleFailure = null)
        }
        return when (val ref = MuPdfOracle.renderDetailed(pdfFile, i + 1, dpi)) {
            is MuPdfOracle.RenderResult.Success -> {
                val outcome = runCatching { ImageDiff.compare(skiaImg, ref.image).score }
                Result(
                    name, i, rendered = true, nonBlank = nonBlank,
                    score = outcome.getOrNull(),
                    oracleFailure = outcome.exceptionOrNull()?.message,
                )
            }
            is MuPdfOracle.RenderResult.Failure ->
                Result(name, i, rendered = true, nonBlank = nonBlank, score = null, oracleFailure = ref.describe())
        }
    }

    /* ─── Inputs: writer-generated pages + a few repo-corpus docs ─────────── */

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

        corpusDir()?.walkTopDown()
            ?.filter { it.isFile && it.extension.equals("pdf", ignoreCase = true) }
            ?.sortedBy { it.path }
            ?.take(3)
            ?.forEach { list += it.nameWithoutExtension to it.readBytes() }
        return list
    }

    /** Repo `corpus/pdf`, same as the AWT harness; an explicit property must exist. */
    private fun corpusDir(): File? {
        System.getProperty("kitepdf.corpus")?.let { configured ->
            val dir = File(configured)
            require(dir.isDirectory) {
                "kitepdf.corpus points to a missing or non-directory corpus: ${dir.absolutePath}"
            }
            return dir
        }
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            if (File(d, "settings.gradle.kts").exists()) {
                return File(d, "corpus/pdf").takeIf { it.isDirectory }
            }
            d = d.parentFile
        }
        return null
    }

    private fun parseBudget(raw: String?): Double {
        if (raw == null) return 0.05
        val value = raw.toDoubleOrNull()
        require(value != null && value.isFinite() && value in 0.0..1.0) {
            "kitepdf.diff.budget must be a finite value from 0.0 to 1.0 (was '$raw')"
        }
        return value
    }

    private fun writeReport(outDir: File, results: List<Result>) {
        val mean = results.mapNotNull { it.score }.takeIf { it.isNotEmpty() }?.average()
        val md = StringBuilder()
        md.appendLine("# KitePDF Skia-backend differential report").appendLine()
        md.appendLine("- Oracle: ${MuPdfOracle.describe()}")
        md.appendLine("- DPI: $dpi · Pages: ${results.size} · Render failures: ${results.count { !it.rendered }}")
        mean?.let { md.appendLine("- Mean score (MAE vs MuPDF): ${"%.4f".format(it)}") }
        md.appendLine().appendLine("| Doc | Pg | OK | Non-blank | Score |").appendLine("|---|---:|:---:|:---:|---:|")
        for (r in results.sortedByDescending { it.score ?: -1.0 }) {
            val score = r.score?.let { "%.4f".format(it) } ?: (r.oracleFailure ?: "n/a")
            md.appendLine("| ${r.name} | ${r.page} | ${if (r.rendered) "OK" else "FAIL"} | ${if (r.nonBlank) "yes" else "no"} | $score |")
        }
        File(outDir, "report.md").writeText(md.toString())
    }
}
