package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A long page content stream is drawn as it is parsed, one operation at a time, and never becomes
 * a list of operations (#978): a 4 MB plot needed 220 MB for its list and parsed again at every
 * render. It draws what the list draws.
 */
class StreamedContentTest {

    /** A page of [lines] strokes, a colour every hundred, with the line endings of a plotting program. */
    private fun doc(lines: Int): PdfDocument {
        val content = StringBuilder()
        for (i in 0 until lines) {
            if (i % 100 == 0) content.append("${i / 100 % 10 / 10.0} 0 ${1 - i / 100 % 10 / 10.0} RG\n")
            val x = i % 500
            val y = i / 500 % 500
            content.append("$x $y m ${x + 1} ${y + 1} l S\n")
        }
        return doc(content.toString())
    }

    private fun doc(page: String): PdfDocument {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 500 500] /Contents 4 0 R >>",
            "<< /Length ${page.length} >>\nstream\n$page\nendstream",
        )
        val out = StringBuilder("%PDF-1.7\n")
        val offsets = objects.mapIndexed { i, body -> out.length.also { out.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) out.append("${o.toString().padStart(10, '0')} 00000 n \n")
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(out.toString().encodeToByteArray())
    }

    private fun PdfDocument.render(): List<RecordingCanvas.Call> =
        RecordingCanvas().also { pages[0].renderTo(it, KiteMatrix.IDENTITY) }.calls

    @Test
    fun a_long_page_is_drawn_without_a_list_of_its_operations() {
        val doc = doc(70_000)
        assertTrue(doc.pages[0].contentBytes.size > doc.streamedContentBytes, "the content is longer than the streaming bound")
        val streamed = doc.render()
        assertEquals(0, doc.operationParseCount, "the page was parsed into a list")
        assertEquals(0L, doc.cachedOperationBytes)
        assertEquals(70_000, streamed.count { it is RecordingCanvas.Call.Stroke })
        doc.render()
        assertEquals(0, doc.operationParseCount, "a second render made a list")

        // The same page through the list draws the same calls.
        val listed = doc(70_000).apply { streamedContentBytes = Int.MAX_VALUE }
        val drawn = listed.render()
        assertEquals(1, listed.operationParseCount)
        assertEquals(drawn, streamed)
    }

    @Test
    fun a_short_page_still_goes_through_the_cache() {
        val doc = doc(200)
        doc.render()
        doc.render()
        assertEquals(1, doc.operationParseCount)
        assertTrue(doc.cachedOperationBytes > 0)
    }

    @Test
    fun moves_with_nothing_drawn_between_them_keep_one_segment() {
        // The 44 KB file of the issue: `0 0 m` again and again, which kept every move in the current path.
        val doc = doc("0 0 m\n".repeat(300_000) + "3 3 m 10 10 l S\n")
        val strokes = doc.render().filterIsInstance<RecordingCanvas.Call.Stroke>()
        assertEquals(1, strokes.size)
        assertEquals(2, strokes[0].path.segments.size, "the moves before the last one stayed in the path")
    }
}
