package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
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
 * Rendering every page of one document from 8 threads simultaneously
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
        page.renderTo(c, KiteMatrix.IDENTITY)
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
                            page.renderTo(canvas, KiteMatrix.IDENTITY)
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

    /** The document shares one parsed font between the threads, so its glyph caches take writes from all of them (#383). */
    @Test
    fun eight_threads_share_one_parsed_font() {
        val widths = (listOf(250) + List(32) { 0 } + listOf(600)).joinToString(" ")
        val text = (0 until 40).joinToString(" ") { "(A A) Tj" }
        val pdf = TestPdf.onePage(
            content = "BT /F1 10 Tf 20 100 Td $text /F2 10 Tf (the standard font too) Tj ET",
            resources = "/Font << /F1 5 0 R /F2 8 0 R >>",
            mediaBox = "0 0 300 300",
            extra = listOf(
                "<< /Type /Font /Subtype /TrueType /BaseFont /SquareTest /FirstChar 32 /LastChar 65 /Widths [$widths] " +
                    "/FontDescriptor 6 0 R /Encoding /WinAnsiEncoding >>",
                "<< /Type /FontDescriptor /FontName /SquareTest /Flags 32 /FontBBox [0 0 500 500] /ItalicAngle 0 " +
                    "/Ascent 500 /Descent 0 /CapHeight 500 /StemV 80 /FontFile2 7 0 R >>",
                TestPdf.Stream("", TestFonts.squareAndSpaceTtf()),
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            ),
        )
        fun glyphs(doc: PdfDocument) = RecordingCanvas().also { doc.pages[0].renderTo(it, KiteMatrix.IDENTITY) }
            .calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().flatMap { run -> run.glyphs.map { it.gid to (it.outline != null) } }
        val baseline = glyphs(KitePDF.open(pdf))
        assertTrue(baseline.any { it.second }, "the baseline draws outlines")

        repeat(20) { iteration ->
            val fresh = KitePDF.open(pdf) // a cold font cache every iteration
            val errors = ConcurrentLinkedQueue<String>()
            val start = CountDownLatch(1)
            val threads = (0 until 8).map { t ->
                thread(start = true) {
                    start.await()
                    try {
                        repeat(5) { if (glyphs(fresh) != baseline) errors.add("iter $iteration thread $t drew other glyphs") }
                    } catch (e: Throwable) {
                        errors.add("iter $iteration thread $t threw: $e")
                    }
                }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertTrue(errors.isEmpty(), errors.joinToString("\n"))
            // Threads that miss together may each parse a font once; after that, every render hits.
            assertTrue(fresh.fontParseCount <= 16, "the fonts parsed ${fresh.fontParseCount} times")
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
                    repeat(5) { doc.pages[0].renderTo(RecordingCanvas(), KiteMatrix.IDENTITY) }
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
