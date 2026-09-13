package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.difftest.ImageDiff
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.PdfRenderOracle

import io.github.yuroyami.kitepdf.epub.EpubDocument
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EPUB render sweep: the robustness gate for the reflow engine. Renders a set of
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
            // Real books from the git-ignored repo-root corpus/epub (or -Dkitepdf.epub.corpus).
            val dir = Corpus.resolveCorpusDirectory(
                propertyName = "kitepdf.epub.corpus",
                configuredPath = System.getProperty("kitepdf.epub.corpus"),
                fallback = Corpus.repoCorpus("epub"),
            )
            dir?.listFiles { f -> f.isFile && f.extension.equals("epub", ignoreCase = true) }
                ?.sortedBy { it.name }?.forEach { add(it.nameWithoutExtension to it.readBytes()) }
        }

        val lines = ArrayList<String>()
        // Per-book page counts, so a sweep-total delta is attributable to one book.
        val pagesPerBook = LinkedHashMap<String, Int>()
        var failures = 0
        var blanks = 0
        var syntheticBlanks = 0
        var pages = 0
        var worstMae = 0.0

        // Only the synthetic fixtures are authored to always paint; real corpus books
        // legitimately contain blank pages (part dividers, verso blanks).
        val syntheticNames = EpubCorpus.synthetic().map { it.first }.toSet()

        for ((name, bytes) in corpus) {
            val doc = try {
                EpubDocument.open(bytes)
            } catch (e: Throwable) {
                failures++; lines.add("- $name: open() THREW ${e.message}"); continue
            }

            pagesPerBook[name] = doc.pages.size
            for ((i, page) in doc.pages.withIndex()) {
                pages++
                val img = try {
                    EpubCorpus.rasterize(page, scale)
                } catch (e: Throwable) {
                    failures++; lines.add("- $name p$i: render() THREW ${e.message}"); continue
                }
                if (ImageDiff.nonBackgroundPixels(img) == 0L) {
                    blanks++; if (name in syntheticNames) syntheticBlanks++
                    lines.add("- $name p$i: BLANK" + if (name in syntheticNames) " (SYNTHETIC, gated)" else " (corpus, informational)")
                }
                if (i == 0 && MuPdfOracle.available) {
                    oracleRef(bytes, dpi)?.let { ref ->
                        val mae = ImageDiff.compare(img, ref, maxDimensionDelta = null).score
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
            append("## Pages per book\n\n")
            pagesPerBook.forEach { (name, count) -> append("- $name: $count\n") }
            append("\n")
            append(lines.joinToString("\n"))
        }
        File(outDir, "report.md").writeText(report)
        println("[epub-sweep] ${corpus.size} books, $pages pages, $failures failures, $blanks blanks, oracle=${MuPdfOracle.available}, worstMAE=%.3f".format(worstMae))
        println("[epub-sweep] pages per book: " + pagesPerBook.entries.joinToString(", ") { "${it.key}=${it.value}" })

        // Page counts are the gate (#45): a book whose count moved fails until the
        // baseline moves with it, in the commit that explains why.
        val baselineFile = pageBaselineFile()
        val baseline = readBaseline(baselineFile)
        val current = LinkedHashMap<String, Pair<String, Int>>() // hash -> (name, pages)
        for ((name, bytes) in corpus) pagesPerBook[name]?.let { current[sha1(bytes)] = name to it }
        if (System.getProperty("kitepdf.epub.updatePages") == "true") {
            writeBaseline(baselineFile, baseline, current)
            println("[epub-sweep] wrote ${current.size} page counts to ${baselineFile.path}")
        } else {
            val unknown = current.filterKeys { it !in baseline }.values.map { it.first }
            if (unknown.isNotEmpty()) println("[epub-sweep] not in the page-count baseline yet: ${unknown.joinToString()}")
            val moved = current.filter { (hash, now) -> baseline[hash]?.let { it.first != now.second } == true }
            assertTrue(
                moved.isEmpty(),
                "EPUB page counts moved:\n" +
                    moved.entries.joinToString("\n") { (hash, now) -> "  ${now.first}: ${baseline[hash]?.first} -> ${now.second}" } +
                    "\nIf the change is intended, rerun with -Dkitepdf.epub.updatePages=true and explain it in the commit.",
            )
        }

        // Every page of every book renders without throwing.
        assertEquals(0, failures, "EPUB render failures:\n" + lines.filter { "THREW" in it }.joinToString("\n"))
        // Synthetic content pages are never blank (real corpus books may
        // legitimately have blank pages, reported informationally above).
        assertEquals(0, syntheticBlanks, "blank SYNTHETIC EPUB pages:\n" + lines.filter { "SYNTHETIC" in it }.joinToString("\n"))
    }

    /** The tracked page-count baseline, found from the repo root so any working directory works. */
    private fun pageBaselineFile(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return File(d ?: File("."), "kitepdf-native-renderer/src/jvmTest/resources/epub-sweep-pages.txt")
    }

    /** `hash pages name` per line, keyed by hash; `#` starts a comment. */
    private fun readBaseline(f: File): Map<String, Pair<Int, String>> {
        if (!f.exists()) return emptyMap()
        val out = LinkedHashMap<String, Pair<Int, String>>()
        for (line in f.readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.trim().split(Regex("\\s+"), limit = 3)
            val pages = parts.getOrNull(1)?.toIntOrNull() ?: continue
            out[parts[0]] = pages to parts.getOrElse(2) { "" }
        }
        return out
    }

    /** Rewrites the books this machine has and keeps the ones it does not. */
    private fun writeBaseline(f: File, old: Map<String, Pair<Int, String>>, current: Map<String, Pair<String, Int>>) {
        val merged = LinkedHashMap(old)
        for ((hash, now) in current) merged[hash] = now.second to now.first
        f.parentFile.mkdirs()
        f.writeText(
            buildString {
                append("# EPUB sweep page counts, checked by EpubDifferentialTest.\n")
                append("# Book hash (SHA-1 prefix), page count, book name. Rewrite with -Dkitepdf.epub.updatePages=true.\n")
                for ((hash, v) in merged.entries.sortedBy { it.value.second }) append("$hash ${v.first} ${v.second}\n")
            },
        )
    }

    private fun sha1(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(12)

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
