package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.io.File
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Open and render budgets at 1x through the AWT rasterizer: the slowest open
 * under [OPEN_BUDGET_MS], and the slowest document under [RENDER_BUDGET_MS] per
 * page. The render budget is a per-document mean, not one mean over every page,
 * because many light synthetic pages would hide one slow real document (#46).
 *
 * A local check, not a CI gate: wall-clock time depends on the machine and its
 * load, so the build skips this test unless `-PslowTests` is given, and no CI
 * job passes it (#191). The numbers print so runs can be compared over time.
 * Corpus files are optional; the synthetic fixtures always run.
 */
class RenderBenchmarkTest {

    private fun corpusPdfs(): List<File> {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        val dir = d?.let { File(it, "corpus/pdf") } ?: return emptyList()
        return dir.listFiles { f -> f.extension == "pdf" }?.sortedBy { it.name } ?: emptyList()
    }

    @Test
    fun open_and_render_budgets_hold() {
        val docs = ArrayList<Pair<String, ByteArray>>()
        for (fx in SyntheticPdfs.all() + GeneratedPdfs.all()) docs.add(fx.name to fx.bytes)
        for (f in corpusPdfs()) docs.add(f.name to f.readBytes())
        assertTrue(docs.isNotEmpty())

        // Warm-up: JIT + font caches.
        for ((_, bytes) in docs) {
            val doc = KitePDF.open(bytes)
            for (p in doc.pages) AwtPdfRasterizer.renderToImage(p)
        }

        var worstOpenMs = 0.0
        var worstOpenName = ""
        var worstRenderMs = 0.0
        var worstRenderName = ""
        var pageCount = 0
        for ((name, bytes) in docs) {
            var doc = KitePDF.open(bytes) // pre-warm anything file-global once
            val openMs = measureNanoTime { doc = KitePDF.open(bytes) } / 1_000_000.0
            if (openMs > worstOpenMs) {
                worstOpenMs = openMs
                worstOpenName = name
            }
            if (doc.pages.isEmpty()) continue
            var docNs = 0L
            for (p in doc.pages) docNs += measureNanoTime { AwtPdfRasterizer.renderToImage(p) }
            pageCount += doc.pages.size
            val docMs = docNs / 1_000_000.0 / doc.pages.size
            if (docMs > worstRenderMs) {
                worstRenderMs = docMs
                worstRenderName = name
            }
        }
        println(
            "[render bench] docs=${docs.size} pages=$pageCount " +
                "worstOpen=${round2(worstOpenMs)}ms ($worstOpenName) " +
                "worstRender=${round2(worstRenderMs)}ms/page ($worstRenderName)",
        )
        assertTrue(worstOpenMs < OPEN_BUDGET_MS, "open budget: worst ${worstOpenMs}ms ($worstOpenName) must be < ${OPEN_BUDGET_MS}ms")
        assertTrue(
            worstRenderMs < RENDER_BUDGET_MS,
            "render budget: $worstRenderName averages ${worstRenderMs}ms/page, must be < ${RENDER_BUDGET_MS}ms",
        )
    }

    private fun round2(v: Double) = (v * 100).toInt() / 100.0

    private companion object {
        const val OPEN_BUDGET_MS = 50.0

        /** About twice the slowest corpus document (94 ms per page) on the machine that set it. */
        const val RENDER_BUDGET_MS = 200.0
    }
}
