package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.epub.EpubDocument
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * EPUB render sweep — the robustness gate for the reflow engine. Renders a set of
 * synthetic books (plus any external corpus) through the real AWT raster path and
 * asserts the engine never throws and never blanks a content page.
 *
 * Run:  ./gradlew :kitepdf-native-renderer:jvmTest
 * Knobs (system properties):
 *   -Dkitepdf.epub.corpus=/path/to/epubs   extra real-world books
 *   -Dkitepdf.epub.dpi=96                   render density
 *   -Dkitepdf.epub.difftest.out=build/epub-difftest   report dir
 *
 * When `mutool` is available it also renders each book's first page and records a
 * mean-absolute-error against ours. That number is **informational only**: EPUB
 * reflow and pagination legitimately differ between engines, so per-page pixel
 * MAE is a trend to watch (and a genuine parity gate only for fixed-layout books,
 * which are a later phase), not a pass/fail threshold.
 */
class EpubDifferentialTest {

    @Test
    fun epub_render_sweep() {
        val outDir = File(System.getProperty("kitepdf.epub.difftest.out") ?: "build/epub-difftest").apply { mkdirs() }
        val dpi = System.getProperty("kitepdf.epub.dpi")?.toIntOrNull() ?: 96
        val scale = dpi / 72.0

        val corpus = ArrayList<Pair<String, ByteArray>>().apply {
            addAll(EpubCorpus.synthetic())
            System.getProperty("kitepdf.epub.corpus")?.let { dir ->
                File(dir).listFiles { f -> f.isFile && f.extension.equals("epub", ignoreCase = true) }
                    ?.sortedBy { it.name }?.forEach { add(it.nameWithoutExtension to it.readBytes()) }
            }
        }

        val lines = ArrayList<String>()
        var failures = 0
        var blanks = 0
        var pages = 0
        var worstMae = 0.0

        for ((name, bytes) in corpus) {
            val doc = try {
                EpubDocument.open(bytes)
            } catch (e: Throwable) {
                failures++; lines.add("- $name: open() THREW ${e.message}"); continue
            }
            if (doc == null) { failures++; lines.add("- $name: open() returned null"); continue }

            for ((i, page) in doc.pages.withIndex()) {
                pages++
                val img = try {
                    EpubCorpus.rasterize(page, scale)
                } catch (e: Throwable) {
                    failures++; lines.add("- $name p$i: render() THREW ${e.message}"); continue
                }
                if (ImageDiff.nonBackgroundPixels(img) == 0L) { blanks++; lines.add("- $name p$i: BLANK") }
                if (i == 0 && MuPdfOracle.available) {
                    oracleRef(bytes, dpi)?.let { ref ->
                        val mae = ImageDiff.compare(img, ref).score
                        worstMae = maxOf(worstMae, mae)
                        lines.add("- $name p0: MAE=%.4f vs mutool (informational; reflow differs)".format(mae))
                    }
                }
            }
        }

        val report = buildString {
            append("# EPUB render sweep\n\n")
            append("corpus: ${corpus.size} books, $pages pages\n")
            append("oracle (mutool): ${MuPdfOracle.available} (${MuPdfOracle.describe()})\n")
            append("render failures: $failures, blank pages: $blanks\n")
            append("worst page-0 MAE vs mutool (informational only): %.4f\n\n".format(worstMae))
            append(lines.joinToString("\n"))
        }
        File(outDir, "report.md").writeText(report)
        println("[epub-sweep] ${corpus.size} books, $pages pages, $failures failures, $blanks blanks, oracle=${MuPdfOracle.available}, worstMAE=%.3f".format(worstMae))

        // Gate 1 — every page of every book renders without throwing.
        assertEquals(0, failures, "EPUB render failures:\n" + lines.filter { "THREW" in it || "null" in it }.joinToString("\n"))
        // Gate 2 — synthetic content pages are never blank.
        assertEquals(0, blanks, "blank EPUB pages:\n" + lines.filter { "BLANK" in it }.joinToString("\n"))
    }

    private fun oracleRef(epubBytes: ByteArray, dpi: Int): BufferedImage? {
        val tmp = File.createTempFile("kite-epub-", ".epub")
        return try {
            tmp.writeBytes(epubBytes)
            MuPdfOracle.render(tmp, 0, dpi)
        } catch (e: Throwable) {
            null
        } finally {
            tmp.delete()
        }
    }
}
