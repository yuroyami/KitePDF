package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.difftest.CorpusSelection
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.awt.image.BufferedImage
import java.lang.management.ManagementFactory
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

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
 * synthetic fixtures always run. With `KITEPDF_BENCH=true`, the scanned books of [ScannedPdfs]
 * run under the same render budget as well.
 */
class RenderBenchmarkTest {

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
        CorpusSelection.configuredDocuments("pdf", docs.map { it.first }.toSet())
            .forEach { docs.add(it.name to it.file.readBytes()) }
        // Every page, so one slow page between the sampled ones still counts against the budget.
        val selection = CorpusSelection.Pages(allPages = true)
        assertTrue(docs.isNotEmpty())

        // Warm-up: JIT + font caches.
        for ((_, bytes) in docs) {
            val doc = KitePDF.open(bytes)
            for (i in selection.indices(doc.pages.size)) AwtPdfRasterizer.renderToImage(doc.pages[i])
        }
        kernelMs()

        val kernelBefore = kernelMs()
        var worstOpenMs = 0.0
        var worstOpenName = ""
        var worstRenderMs = 0.0
        var worstRenderName = ""
        var pageCount = 0
        var availablePages = 0
        for ((name, bytes) in docs) {
            var doc = KitePDF.open(bytes) // pre-warm anything file-global once
            val openMs = fastestMs { doc = KitePDF.open(bytes) }
            if (openMs > worstOpenMs) {
                worstOpenMs = openMs
                worstOpenName = name
            }
            val pages = selection.indices(doc.pages.size)
            availablePages += doc.pages.size
            assertTrue(pages.isNotEmpty(), "$name contains no renderable pages")
            // A fresh document in each run, so each run pays the first render of every selected page,
            // image decoding and font parsing included, as a viewer that opens the file does.
            val docMs = fastestMs {
                val fresh = KitePDF.open(bytes)
                for (i in pages) AwtPdfRasterizer.renderToImage(fresh.pages[i])
            } / pages.size
            pageCount += pages.size
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
            "[render bench] docs=${docs.size} selectedPages=$pageCount availablePages=$availablePages selection=${selection.describe()} " +
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

    /**
     * The scanned books of [ScannedPdfs], each under [RENDER_BUDGET_MS] per page as above (#462).
     * Here the budget applies to the thread's CPU time. A scanned page holds tens of megabytes, so
     * on a machine that is short of memory its wall time measures the swapping: one loaded run took
     * 3.1 s of wall time and 0.4 s of CPU time a page. The test prints both.
     *
     * Every page must draw its scan: a page that draws nothing is fast, and would hide a decoder
     * that stopped working.
     */
    @Test
    fun scanned_books_hold_the_render_budget() {
        val enabled = System.getenv("KITEPDF_BENCH") == "true" || System.getProperty("kitepdf.bench") == "true"
        // Skipped, not passed, when nobody asked for it (#191). Making and rendering the books takes a minute.
        assumeTrue("Run with KITEPDF_BENCH=true to render the scanned books.", enabled)
        val books = ScannedPdfs.all()
        // Warm-up, and the check that each page draws its scan.
        for (book in books) {
            val doc = KitePDF.open(book.bytes)
            for ((i, page) in doc.pages.withIndex()) {
                val dark = darkShare(AwtPdfRasterizer.renderToImage(page))
                assertTrue(dark > MIN_DARK_SHARE, "${book.name} page $i draws only $dark of its pixels dark")
            }
        }
        kernelMs()

        val kernelBefore = kernelMs()
        val threads = ManagementFactory.getThreadMXBean()
        var worstMs = 0.0
        var worstName = ""
        val lines = StringBuilder()
        for (book in books) {
            val pages = KitePDF.open(book.bytes).pages.size
            var wall = Long.MAX_VALUE
            var cpu = Long.MAX_VALUE
            // As above: a fresh document in each run, and the fastest run counts.
            repeat(RUNS) {
                val cpuStart = threads.currentThreadCpuTime
                val wallStart = System.nanoTime()
                val doc = KitePDF.open(book.bytes)
                for (page in doc.pages) AwtPdfRasterizer.renderToImage(page)
                wall = minOf(wall, System.nanoTime() - wallStart)
                cpu = minOf(cpu, threads.currentThreadCpuTime - cpuStart)
            }
            val cpuMs = cpu / 1_000_000.0 / pages
            lines.append(" ${book.name}=${round2(cpuMs)}ms/page cpu,${round2(wall / 1_000_000.0 / pages)}ms/page wall")
            if (cpuMs > worstMs) {
                worstMs = cpuMs
                worstName = book.name
            }
        }
        val kernel = maxOf(kernelBefore, kernelMs())
        val load = maxOf(1.0, kernel / KERNEL_REFERENCE_MS)
        val budget = RENDER_BUDGET_MS * load
        println("[scan bench] books=${books.size}$lines kernel=${round2(kernel)}ms load=${round2(load)}")
        assertTrue(worstMs < budget, "render budget: $worstName averages ${worstMs}ms/page of CPU time, must be < ${budget}ms")
    }

    /** The share of [image]'s pixels darker than mid grey. */
    private fun darkShare(image: BufferedImage): Double {
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val dark = pixels.count { p -> (p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF) < 3 * 160 }
        return dark.toDouble() / pixels.size
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

        /** A page of text draws about 12% of its pixels darker than mid grey at 1x. A blank page draws none. */
        const val MIN_DARK_SHARE = 0.05
    }
}
