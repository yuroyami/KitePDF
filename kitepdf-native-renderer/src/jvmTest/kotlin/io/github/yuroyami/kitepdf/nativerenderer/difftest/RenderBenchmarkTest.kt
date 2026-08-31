package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.io.File
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M3 exit gate: measured open and render budgets on this machine
 * (open < 50 ms, corpus mean render < 35 ms/page at 1x through the AWT
 * rasterizer). The numbers print for the progress ledger. Corpus files are
 * optional; the synthetic fixtures always run.
 */
class M3BenchmarkTest {

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
        var renderTotalNs = 0L
        var pageCount = 0
        for ((name, bytes) in docs) {
            var doc = KitePDF.open(bytes) // pre-warm anything file-global once
            val openNs = measureNanoTime { doc = KitePDF.open(bytes) }
            val openMs = openNs / 1_000_000.0
            if (openMs > worstOpenMs) {
                worstOpenMs = openMs
                worstOpenName = name
            }
            for (p in doc.pages) {
                renderTotalNs += measureNanoTime { AwtPdfRasterizer.renderToImage(p) }
                pageCount++
            }
        }
        val meanRenderMs = renderTotalNs / 1_000_000.0 / pageCount
        println(
            "[M3 bench] docs=${docs.size} pages=$pageCount " +
                "worstOpen=${(worstOpenMs * 100).toInt() / 100.0}ms ($worstOpenName) " +
                "meanRender=${(meanRenderMs * 100).toInt() / 100.0}ms/page",
        )
        assertTrue(worstOpenMs < 50.0, "open budget: worst ${worstOpenMs}ms ($worstOpenName) must be < 50ms")
        assertTrue(meanRenderMs < 35.0, "render budget: mean ${meanRenderMs}ms/page must be < 35ms")
    }
}
