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
 * Wall-clock time depends on the machine and its load, so the test measures in two
 * ways that load does not move much (#213):
 *
 * - Each open and each page render counts its fastest of [RUNS] runs. A pause of the
 *   collector or a burst of other work slows one run, not all of them.
 * - A fixed piece of work that does not use the renderer, a sort, is timed in the same
 *   run. When it takes longer than [KERNEL_REFERENCE_MS], the machine that set the
 *   budgets, the budgets grow by the same ratio. A faster machine keeps them.
 *
 * The numbers print so runs can be compared over time. Corpus files are optional; the
 * synthetic fixtures always run.
 */
class RenderBenchmarkTest {

    private fun corpusPdfs(): List<File> {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        val dir = d?.let { File(it, "corpus/pdf") } ?: return emptyList()
        return dir.listFiles { f -> f.extension == "pdf" }?.sortedBy { it.name } ?: emptyList()
    }

    /** The fastest of [RUNS] runs of [block], in milliseconds. */
    private inline fun fastestMs(block: () -> Unit): Double {
        var best = Long.MAX_VALUE
        repeat(RUNS) { best = minOf(best, measureNanoTime(block)) }
        return best / 1_000_000.0
    }

    /** Keeps the sort's result, so the JIT cannot drop the work. */
    private var sink = 0

    /** A fixed piece of plain CPU and memory work: sorting [KERNEL_SIZE] numbers, the fastest of five runs. */
    private fun kernelMs(): Double {
        var best = Double.MAX_VALUE
        repeat(5) {
            val ms = measureNanoTime {
                var seed = 42
                val values = IntArray(KERNEL_SIZE) { seed = seed * 1103515245 + 12345; seed }
                values.sort()
                sink += values[KERNEL_SIZE / 2]
            } / 1_000_000.0
            best = minOf(best, ms)
        }
        return best
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
        kernelMs()

        val kernelBefore = kernelMs()
        var worstOpenMs = 0.0
        var worstOpenName = ""
        var worstRenderMs = 0.0
        var worstRenderName = ""
        var pageCount = 0
        for ((name, bytes) in docs) {
            var doc = KitePDF.open(bytes) // pre-warm anything file-global once
            val openMs = fastestMs { doc = KitePDF.open(bytes) }
            if (openMs > worstOpenMs) {
                worstOpenMs = openMs
                worstOpenName = name
            }
            val pages = doc.pages.size
            if (pages == 0) continue
            // A fresh document in each run, so each run pays the first render of every page,
            // image decoding and font parsing included, as a viewer that opens the file does.
            val docMs = fastestMs { for (p in KitePDF.open(bytes).pages) AwtPdfRasterizer.renderToImage(p) } / pages
            pageCount += pages
            if (docMs > worstRenderMs) {
                worstRenderMs = docMs
                worstRenderName = name
            }
        }
        // The load of the whole run: the slower of the two kernel runs around it.
        val kernel = maxOf(kernelBefore, kernelMs())
        val load = maxOf(1.0, kernel / KERNEL_REFERENCE_MS)
        val openBudget = OPEN_BUDGET_MS * load
        val renderBudget = RENDER_BUDGET_MS * load
        println(
            "[render bench] docs=${docs.size} pages=$pageCount " +
                "worstOpen=${round2(worstOpenMs)}ms ($worstOpenName) " +
                "worstRender=${round2(worstRenderMs)}ms/page ($worstRenderName) " +
                "kernel=${round2(kernel)}ms load=${round2(load)}",
        )
        assertTrue(worstOpenMs < openBudget, "open budget: worst ${worstOpenMs}ms ($worstOpenName) must be < ${openBudget}ms")
        assertTrue(
            worstRenderMs < renderBudget,
            "render budget: $worstRenderName averages ${worstRenderMs}ms/page, must be < ${renderBudget}ms",
        )
    }

    private fun round2(v: Double) = (v * 100).toInt() / 100.0

    private companion object {
        /** How many times each open and each page render runs. The fastest run counts. */
        const val RUNS = 3

        const val OPEN_BUDGET_MS = 50.0

        /** About twice the slowest corpus document (94 ms per page) on the machine that set it. */
        const val RENDER_BUDGET_MS = 200.0

        const val KERNEL_SIZE = 1 shl 18

        /** The kernel on the machine that set the budgets, idle: 12.9 to 13.6 ms in three runs. */
        const val KERNEL_REFERENCE_MS = 13.0
    }
}
