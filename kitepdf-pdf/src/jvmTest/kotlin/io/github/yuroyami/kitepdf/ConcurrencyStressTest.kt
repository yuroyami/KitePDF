package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.RecordingCanvas
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.PdfImage
import io.github.yuroyami.kitepdf.writer.StandardFont
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-16: rendering every page of one document from 8 threads simultaneously
 * (including all threads on the SAME page) produces exactly the serial
 * baseline's draw calls, across 20 iterations. Before the per-call readers
 * and locked caches, the shared seek-based reader interleaved positions and
 * produced garbage parses under this load.
 */
class ConcurrencyStressTest {

    private fun buildDoc(): PdfDocument {
        val pixels = ByteArray(16 * 16 * 3) { (it * 31).toByte() }
        val img = PdfImage.rgb(pixels, 16, 16)
        val b = PdfBuilder()
        repeat(6) { i ->
            b.page(width = 300.0, height = 300.0) {
                setFillRgb(0.1 * i, 0.5, 1.0 - 0.1 * i)
                rectangle(10.0, 10.0, 280.0, 280.0)
                fill()
                drawImage(img, 40.0, 40.0, 100.0, 100.0)
                text(StandardFont.Helvetica, 14.0, 30.0, 250.0, "page $i of the stress fixture")
                text(StandardFont.TimesRoman, 10.0, 30.0, 230.0, "second run keeps the font cache busy")
            }
        }
        return KitePDF.open(b.build())
    }

    private fun callCounts(doc: PdfDocument): List<Int> = doc.pages.map { page ->
        val c = RecordingCanvas()
        page.renderTo(c, Matrix.IDENTITY)
        c.calls.size
    }

    @Test
    fun eight_threads_render_identically_to_the_serial_baseline() {
        val doc = buildDoc()
        val baseline = callCounts(doc)
        assertTrue(baseline.all { it > 3 }, "baseline renders real content: $baseline")

        repeat(20) { iteration ->
            val fresh = KitePDF.open(buildDoc().bytes) // cold caches every iteration
            val errors = ConcurrentLinkedQueue<String>()
            val start = CountDownLatch(1)
            val threads = (0 until 8).map { t ->
                thread(start = true) {
                    start.await()
                    try {
                        for ((i, page) in fresh.pages.withIndex()) {
                            val canvas = RecordingCanvas()
                            page.renderTo(canvas, Matrix.IDENTITY)
                            if (canvas.calls.size != baseline[i]) {
                                errors.add("iter $iteration thread $t page $i: ${canvas.calls.size} != ${baseline[i]}")
                            }
                        }
                    } catch (e: Throwable) {
                        errors.add("iter $iteration thread $t threw: $e")
                    }
                }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertTrue(errors.isEmpty(), errors.joinToString("\n"))
        }
    }

    @Test
    fun all_threads_on_the_same_page_share_one_image_decode() {
        val doc = buildDoc()
        val errors = ConcurrentLinkedQueue<String>()
        val start = CountDownLatch(1)
        val threads = (0 until 8).map { t ->
            thread(start = true) {
                start.await()
                try {
                    repeat(5) { doc.pages[0].renderTo(RecordingCanvas(), Matrix.IDENTITY) }
                } catch (e: Throwable) {
                    errors.add("thread $t threw: $e")
                }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
        // Racing threads may each decode once before the first write lands,
        // but the count must stay far below the 40 renders.
        assertTrue(doc.imageDecodeCount <= 8, "at most one decode per racing thread (got ${doc.imageDecodeCount})")
    }
}
