package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.render.Matrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertTrue

/** Tiling pattern (PatternType 1) rendering: the cell content stream is replayed
 *  across the filled region. */
class TilingPatternTest {

    private fun tilingPdf(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = LinkedHashMap<Int, Int>()
        fun obj(n: Int, body: String) { offsets[n] = buf.size(); buf.append("$n 0 obj\n$body\nendobj\n".encodeToByteArray()) }
        fun stream(n: Int, dict: String, content: String) {
            val c = content.encodeToByteArray()
            offsets[n] = buf.size()
            buf.append("$n 0 obj\n<< $dict /Length ${c.size} >>\nstream\n".encodeToByteArray())
            buf.append(c); buf.append("\nendstream\nendobj\n".encodeToByteArray())
        }
        buf.append("%PDF-1.5\n%Äå\n".encodeToByteArray())
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] " +
            "/Resources << /Pattern << /P1 6 0 R >> >> /Contents 5 0 R >>")
        stream(5, "", "/Pattern cs /P1 scn 0 0 200 200 re f")
        // Tiling pattern: 10x10 cell, paints a red 5x5 square.
        stream(6, "/Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 " +
            "/BBox [0 0 10 10] /XStep 10 /YStep 10 /Resources << >>",
            "1 0 0 rg 0 0 5 5 re f")
        val xref = buf.size()
        val maxN = offsets.keys.max()
        buf.append("xref\n0 ${maxN + 1}\n0000000000 65535 f \n".encodeToByteArray())
        for (n in 1..maxN) {
            val off = offsets[n]
            buf.append((if (off == null) "0000000000 65535 f \n"
                else "${off.toString().padStart(10, '0')} 00000 n \n").encodeToByteArray())
        }
        buf.append("trailer\n<< /Size ${maxN + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".encodeToByteArray())
        return buf.toByteArray()
    }

    @Test fun tiling_pattern_replays_cell_across_region() {
        val doc = KitePDF.open(tilingPdf())
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)
        val fills = canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        // 200x200 region, 10x10 cells → on the order of 400 tiles, each a red fill.
        assertTrue(fills.size > 100, "expected many tile fills, got ${fills.size}")
        assertTrue(fills.all { it.color.r > 0.9 && it.color.g < 0.1 && it.color.b < 0.1 },
            "all tile fills should be the pattern's red")
    }
}
