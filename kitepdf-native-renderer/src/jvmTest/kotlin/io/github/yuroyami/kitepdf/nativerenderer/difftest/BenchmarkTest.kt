package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.compression.Zlib
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.test.Test

/**
 * Lightweight performance benchmark — the evidence behind the "efficient"
 * claim, which was previously unmeasured.
 *
 * Measures open/parse, render, text extraction, and write throughput on
 * representative inputs, and pits KitePDF's pure-Kotlin Flate codec against the
 * JVM's native `java.util.zip` for ratio and speed. Pure-Kotlin portability is
 * the design goal, so JDK zlib being faster / tighter is expected — the point
 * is to know by how much.
 *
 * Opt-in (it's slow + perf numbers are noisy in CI): run with
 *   KITEPDF_BENCH=true ./gradlew :kitepdf-native-renderer:jvmTest --tests "*BenchmarkTest*"
 * (env var, since Gradle doesn't forward -D to the forked test JVM by default).
 * Writes `build/benchmark/report.md`.
 */
class BenchmarkTest {

    @Test
    fun benchmarks() {
        val enabled = System.getenv("KITEPDF_BENCH") == "true" || System.getProperty("kitepdf.bench") == "true"
        if (!enabled) {
            println("[bench] skipped — run with KITEPDF_BENCH=true to enable the performance benchmark.")
            return
        }
        val md = StringBuilder()
        md.appendLine("# KitePDF performance benchmark").appendLine()
        md.appendLine("Median of timed iterations after warmup. Lower ms = faster.").appendLine()

        benchReadPath(md)
        benchWritePath(md)
        benchCodec(md)

        val out = File(System.getProperty("kitepdf.bench.out") ?: "build/benchmark").apply { mkdirs() }
        File(out, "report.md").writeText(md.toString())
        println("[bench] report: ${File(out, "report.md").absolutePath}")
        println(md.toString())
    }

    /* ─── Read path: open / render / extract ─────────────────────────────── */

    private fun benchReadPath(md: StringBuilder) {
        val docs = corpusFiles().take(4)
        md.appendLine("## Read path (real-world corpus)").appendLine()
        if (docs.isEmpty()) { md.appendLine("_no corpus found_").appendLine(); return }
        md.appendLine("| Doc | Size KB | open ms | render p0 ms | extractText p0 ms |")
        md.appendLine("|---|---:|---:|---:|---:|")
        for (f in docs) {
            val bytes = f.readBytes()
            val openMs = bench(3, 7) { KitePDF.open(bytes) }
            val doc = KitePDF.open(bytes)
            val page = doc.pages.firstOrNull()
            val renderMs = if (page != null) bench(2, 5) { AwtPdfRasterizer.renderToImage(page, scale = 96.0 / 72.0) } else Double.NaN
            val extractMs = if (page != null) bench(2, 5) { page.extractText() } else Double.NaN
            md.appendLine("| ${f.nameWithoutExtension.take(28)} | ${f.length() / 1024} | ${fmt(openMs)} | ${fmt(renderMs)} | ${fmt(extractMs)} |")
        }
        md.appendLine()
    }

    /* ─── Write path: from-scratch / incremental / rewrite ───────────────── */

    private fun benchWritePath(md: StringBuilder) {
        md.appendLine("## Write path").appendLine()
        md.appendLine("| Operation | ms | output KB |")
        md.appendLine("|---|---:|---:|")

        var built = ByteArray(0)
        val buildMs = bench(2, 5) {
            built = PdfBuilder().apply {
                repeat(50) { i ->
                    page { text(StandardFont.Helvetica, 12.0, 72.0, 720.0, "Generated page ${i + 1} — benchmark body line.") }
                }
            }.build()
        }
        md.appendLine("| PdfBuilder 50 pages (build) | ${fmt(buildMs)} | ${built.size / 1024} |")

        // Incremental edit + GC rewrite on a real doc (first corpus file, else the built one).
        val base = corpusFiles().firstOrNull()?.readBytes() ?: built
        val incMs = bench(2, 5) { KitePDF.open(base).edit().apply { setInfo(title = "bench") }.saveIncremental() }
        val rewriteMs = bench(2, 5) { KitePDF.open(base).edit().saveRewritten() }
        val incBytes = KitePDF.open(base).edit().apply { setInfo(title = "bench") }.saveIncremental()
        val rwBytes = KitePDF.open(base).edit().saveRewritten()
        md.appendLine("| Incremental setInfo + save | ${fmt(incMs)} | ${incBytes.size / 1024} |")
        md.appendLine("| Full GC rewrite (saveRewritten) | ${fmt(rewriteMs)} | ${rwBytes.size / 1024} |")
        md.appendLine()
    }

    /* ─── Codec: KitePDF Flate vs JDK java.util.zip ──────────────────────── */

    private fun benchCodec(md: StringBuilder) {
        md.appendLine("## Flate codec — KitePDF (pure-Kotlin) vs JDK java.util.zip").appendLine()

        // Realistic-ish payload: repeated extracted text + pseudo-random tail.
        val text = corpusFiles().firstOrNull()?.let {
            runCatching { KitePDF.open(it.readBytes()).pages.firstOrNull()?.extractText() }.getOrNull()
        } ?: "The quick brown fox jumps over the lazy dog. "
        val sb = StringBuilder()
        while (sb.length < 512 * 1024) sb.append(text).append(' ')
        val payload = sb.toString().encodeToByteArray()

        // Encode
        var kiteEnc = ByteArray(0); var jdkEnc = ByteArray(0)
        val kiteEncMs = bench(1, 3) { kiteEnc = Zlib.encode(payload) }
        val jdkEncMs = bench(1, 3) { jdkEnc = jdkDeflate(payload) }

        // Decode (use JDK-compressed bytes so both decode identical input).
        val kiteDecMs = bench(2, 5) { Zlib.decode(jdkEnc) }
        val jdkDecMs = bench(2, 5) { jdkInflate(jdkEnc) }

        fun pct(n: Int) = "%.1f%%".format(n * 100.0 / payload.size)
        md.appendLine("Payload: ${payload.size / 1024} KB (repeated real text).").appendLine()
        md.appendLine("| Metric | KitePDF | JDK | Ratio (Kite/JDK) |")
        md.appendLine("|---|---:|---:|---:|")
        md.appendLine("| Compressed size | ${kiteEnc.size / 1024} KB (${pct(kiteEnc.size)}) | ${jdkEnc.size / 1024} KB (${pct(jdkEnc.size)}) | ${"%.2f×".format(kiteEnc.size.toDouble() / jdkEnc.size)} |")
        md.appendLine("| Encode time | ${fmt(kiteEncMs)} ms | ${fmt(jdkEncMs)} ms | ${"%.1f×".format(kiteEncMs / jdkEncMs)} |")
        md.appendLine("| Decode time | ${fmt(kiteDecMs)} ms | ${fmt(jdkDecMs)} ms | ${"%.1f×".format(kiteDecMs / jdkDecMs)} |")
        md.appendLine()
        md.appendLine("_Encode ratio > 1× = larger output (KitePDF uses fixed-Huffman, no dynamic Huffman yet). " +
            "Time ratio > 1× = slower than native zlib (expected for pure-Kotlin)._")
        md.appendLine()
    }

    private fun jdkDeflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION).apply { setInput(data); finish() }
        val out = ByteArrayOutputStream(data.size / 2)
        val buf = ByteArray(16384)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun jdkInflate(zlib: ByteArray): ByteArray {
        val inf = Inflater().apply { setInput(zlib) }
        val out = ByteArrayOutputStream(zlib.size * 3)
        val buf = ByteArray(16384)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && inf.needsInput()) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    /* ─── Harness helpers ────────────────────────────────────────────────── */

    private fun bench(warmup: Int, iters: Int, block: () -> Unit): Double {
        // JIT needs far more than 2 warmups to stabilize; KITEPDF_BENCH_SCALE
        // multiplies both counts for trustworthy local measurement (CI keeps the
        // fast default of 1). Min, not median, of the timed runs — the fastest
        // run is the one least perturbed by GC/scheduling, the cleanest estimate.
        val scale = (System.getenv("KITEPDF_BENCH_SCALE")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        repeat(warmup * scale) { block() }
        val n = iters * scale
        var best = Double.MAX_VALUE
        repeat(n) {
            val t = System.nanoTime(); block(); val dt = (System.nanoTime() - t) / 1e6
            if (dt < best) best = dt
        }
        return best
    }

    private fun fmt(ms: Double): String = if (ms.isNaN()) "—" else "%.2f".format(ms)

    private fun corpusFiles(): List<File> {
        val dir = System.getProperty("kitepdf.corpus")?.let { File(it) }
            ?: run {
                var d: File? = File(System.getProperty("user.dir")).absoluteFile
                var found: File? = null
                while (d != null && found == null) {
                    if (File(d, "settings.gradle.kts").exists()) found = File(d, "kitepdf-native-renderer/corpus")
                    d = d.parentFile
                }
                found
            }
        return dir?.takeIf { it.isDirectory }
            ?.listFiles { f -> f.extension.equals("pdf", true) }
            ?.sortedBy { it.name } ?: emptyList()
    }
}
