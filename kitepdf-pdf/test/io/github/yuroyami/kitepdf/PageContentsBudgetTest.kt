package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.compression.Zlib
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The members of a page's content array share one budget, and a repeated member decodes once (#1018). */
class PageContentsBudgetTest {

    /** A page whose /Contents is [contents], with object 5 a Flate stream of [member] and object 6 a square. */
    private fun pdf(contents: String, member: ByteArray): ByteArray {
        val flate = Zlib.encode(member)
        val square = "0 0 1 rg 10 10 20 20 re f".encodeToByteArray()
        val out = ArrayList<Byte>()
        val offsets = ArrayList<Int>()
        fun add(s: String) = out.addAll(s.encodeToByteArray().toList())
        fun add(b: ByteArray) = out.addAll(b.toList())
        add("%PDF-1.4\n")
        offsets.add(out.size); add("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(out.size); add("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(out.size); add("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents $contents >>\nendobj\n")
        offsets.add(out.size); add("4 0 obj\nnull\nendobj\n")
        offsets.add(out.size); add("5 0 obj\n<< /Length ${flate.size} /Filter /FlateDecode >>\nstream\n"); add(flate); add("\nendstream\nendobj\n")
        offsets.add(out.size); add("6 0 obj\n<< /Length ${square.size} >>\nstream\n"); add(square); add("\nendstream\nendobj\n")
        val xref = out.size
        add("xref\n0 7\n0000000000 65535 f \n")
        for (o in offsets) add("${o.toString().padStart(10, '0')} 00000 n \n")
        add("trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    private fun fills(bytes: ByteArray): Int {
        val canvas = RecordingCanvas()
        PdfDocument.open(bytes).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.calls.count { it is RecordingCanvas.Call.Fill }
    }

    @Test
    fun aRepeatedMemberDrawsEachTime() {
        assertEquals(3, fills(pdf("[5 0 R 5 0 R 6 0 R]", "0 0 1 1 re f".encodeToByteArray())))
    }

    @Test
    fun manyCopiesOfALargeMemberStopAtThePageBudget() {
        // Sixteen names of 32 MiB of spaces would join 512 MiB.
        val spaces = ByteArray(32 shl 20) { ' '.code.toByte() }
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        fills(pdf("[" + "5 0 R ".repeat(16) + "6 0 R]", spaces))
        assertTrue(start.elapsedNow().inWholeSeconds < 60, "took ${start.elapsedNow()}")
    }
}
