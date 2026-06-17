package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the structured-text pipeline:
 *   - text runs are captured with positions
 *   - runs sharing a Y baseline cluster into one line
 *   - large vertical gaps open a new block
 */
class StructuredTextTest {

    @Test
    fun single_run_produces_one_block_one_line_one_span() {
        val doc = KitePDF.open(buildTextPdf(listOf(72.0 to 720.0 to "Hello")))
        val st = doc.pages[0].structuredText
        assertEquals(1, st.blocks.size)
        assertEquals(1, st.blocks[0].lines.size)
        assertEquals(1, st.blocks[0].lines[0].spans.size)
        assertEquals("Hello", st.blocks[0].lines[0].spans[0].text)
    }

    @Test
    fun three_runs_on_same_y_form_one_line() {
        val doc = KitePDF.open(
            buildTextPdf(
                listOf(
                    72.0 to 720.0 to "Hello",
                    160.0 to 720.0 to "structured",
                    280.0 to 720.0 to "world",
                ),
            ),
        )
        val st = doc.pages[0].structuredText
        assertEquals(1, st.blocks.size)
        val line = st.blocks[0].lines.single()
        assertEquals(3, line.spans.size)
        // Spans inside a line are stored left-to-right.
        assertEquals("Hello", line.spans[0].text)
        assertEquals("structured", line.spans[1].text)
        assertEquals("world", line.spans[2].text)
        // Joined text inserts synthesised whitespace between visible-gap spans.
        assertTrue(line.text.contains("Hello"))
        assertTrue(line.text.contains("structured"))
        assertTrue(line.text.contains("world"))
    }

    @Test
    fun lines_on_distinct_baselines_cluster_separately() {
        val doc = KitePDF.open(
            buildTextPdf(
                listOf(
                    72.0 to 720.0 to "line one",
                    72.0 to 700.0 to "line two",
                    72.0 to 680.0 to "line three",
                ),
            ),
        )
        val st = doc.pages[0].structuredText
        // 20pt baseline gaps + 18pt font ⇒ separate lines, single block.
        assertEquals(1, st.blocks.size)
        assertEquals(3, st.blocks[0].lines.size)
        assertEquals("line one", st.blocks[0].lines[0].spans[0].text)
        assertEquals("line two", st.blocks[0].lines[1].spans[0].text)
        assertEquals("line three", st.blocks[0].lines[2].spans[0].text)
    }

    @Test
    fun large_vertical_gap_opens_a_new_block() {
        val doc = KitePDF.open(
            buildTextPdf(
                listOf(
                    72.0 to 720.0 to "para one line a",
                    72.0 to 700.0 to "para one line b",
                    // Big gap ⇒ block break.
                    72.0 to 500.0 to "para two line a",
                    72.0 to 480.0 to "para two line b",
                ),
            ),
        )
        val st = doc.pages[0].structuredText
        assertEquals(2, st.blocks.size)
        assertEquals(2, st.blocks[0].lines.size)
        assertEquals(2, st.blocks[1].lines.size)
        assertTrue(st.blocks[0].lines[0].spans[0].text.startsWith("para one"))
        assertTrue(st.blocks[1].lines[0].spans[0].text.startsWith("para two"))
    }

    @Test
    fun plain_text_joins_blocks_with_double_newline() {
        val doc = KitePDF.open(
            buildTextPdf(
                listOf(
                    72.0 to 720.0 to "first",
                    72.0 to 500.0 to "second",
                ),
            ),
        )
        val txt = doc.pages[0].structuredText.plainText
        assertEquals("first\n\nsecond", txt)
    }

    @Test
    fun reading_order_is_top_to_bottom() {
        // Place runs in non-reading order in the content stream; result must
        // still be top-to-bottom because we sort by Y.
        val doc = KitePDF.open(
            buildTextPdf(
                listOf(
                    72.0 to 500.0 to "bottom",
                    72.0 to 700.0 to "middle",
                    72.0 to 720.0 to "top",
                ),
            ),
        )
        val lines = doc.pages[0].structuredText.spans.map { it.text }
        // After sort by Y descending: top (720), middle (700), bottom (500).
        assertEquals(listOf("top", "middle", "bottom"), lines)
    }

    /* ─── Builder ─────────────────────────────────────────────────────────── */

    /** Build a single-page PDF whose content stream draws each (x, y, text) triple. */
    private fun buildTextPdf(runs: List<Pair<Pair<Double, Double>, String>>): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())

        w("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        w("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] >>\nendobj\n")
        offsets.add(buf.size())
        w("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>\nendobj\n")

        // Build content stream — one BT/ET per run for clarity.
        val sb = StringBuilder()
        for (entry in runs) {
            val x = entry.first.first
            val y = entry.first.second
            val text = entry.second
            sb.append("BT /F1 18 Tf 1 0 0 1 $x $y Tm ($text) Tj ET\n")
        }
        val content = sb.toString().encodeToByteArray()
        offsets.add(buf.size())
        w("4 0 obj\n<< /Length ${content.size} >>\nstream\n")
        buf.append(content)
        w("\nendstream\nendobj\n")

        offsets.add(buf.size())
        w("5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")

        val xref = buf.size()
        w("xref\n0 6\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
