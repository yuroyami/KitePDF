package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.content.ContentStreamParser
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.render.Matrix
import io.github.yuroyami.kitepdf.core.render.NoopCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Content-stream operation budget (T-02): a stream with millions of operators
 * (adversarial, or produced by the repair path from garbage) must not allocate
 * an unbounded operation list or render forever. The parser stops at 5M ops
 * per stream; the renderer additionally budgets 20M dispatched ops per page
 * (covering tiling and form replays, which this parse cap alone cannot bound).
 */
class OpBudgetTest {

    private companion object {
        const val PARSE_CAP = 5_000_000
        // (cap + 1000) tiny ops; "1 0 0 1 0 0 cm\n" is 15 bytes -> ~75 MB.
        val bombOps: ByteArray by lazy {
            val op = "1 0 0 1 0 0 cm\n".encodeToByteArray()
            val out = ByteArrayBuilder(op.size * (PARSE_CAP + 1000))
            repeat(PARSE_CAP + 1000) { out.append(op) }
            out.toByteArray()
        }
    }

    @Test
    fun parser_stops_at_the_op_cap() {
        val ops = ContentStreamParser.parse(bombOps)
        assertEquals(PARSE_CAP, ops.size)
    }

    @Test
    fun op_bomb_page_renders_and_terminates() {
        val doc = KitePDF.open(pdfWithRawContent(bombOps))
        assertEquals(1, doc.pageCount)
        // Must terminate (parse cap) and not throw; nothing paints, which is fine.
        doc.pages[0].renderTo(NoopCanvas, Matrix.IDENTITY)
    }

    private fun pdfWithRawContent(content: ByteArray): ByteArray {
        val buf = ByteArrayBuilder(content.size + 1024)
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())

        write("%PDF-1.4\n")
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("4 0 obj\n<< /Length ${content.size} >>\nstream\n")
        buf.append(content)
        write("\nendstream\nendobj\n")
        val xref = buf.size()
        write("xref\n0 5\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
