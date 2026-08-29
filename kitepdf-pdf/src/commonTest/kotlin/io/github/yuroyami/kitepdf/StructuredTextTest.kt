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

    /* ─── Effective font size (issue #22) ─────────────────────────────────── */

    @Test
    fun tf_size_with_unit_tm_reports_tf_size() {
        val doc = KitePDF.open(buildContentPdf("BT /F1 18 Tf 1 0 0 1 72 720 Tm (Hello) Tj ET\n"))
        assertEquals(18.0, doc.pages[0].structuredText.spans.single().fontSize, 1e-9)
    }

    @Test
    fun size_baked_into_tm_reports_effective_size() {
        // Issue #22: producers write `/F1 1 Tf` and scale via Tm.
        val doc = KitePDF.open(buildContentPdf("BT /F1 1 Tf 15 0 0 15 72 720 Tm (Hello) Tj ET\n"))
        assertEquals(15.0, doc.pages[0].structuredText.spans.single().fontSize, 1e-9)
    }

    @Test
    fun rotated_tm_keeps_effective_size() {
        // 90-degree rotation at scale 12: the Y basis is (-12, 0), length 12.
        val doc = KitePDF.open(buildContentPdf("BT /F1 1 Tf 0 12 -12 0 200 400 Tm (Rot) Tj ET\n"))
        assertEquals(12.0, doc.pages[0].structuredText.spans.single().fontSize, 1e-9)
    }

    @Test
    fun content_cm_scale_folds_into_size() {
        val doc = KitePDF.open(
            buildContentPdf("0.5 0 0 0.5 0 0 cm\nBT /F1 18 Tf 1 0 0 1 100 700 Tm (Half) Tj ET\n"),
        )
        assertEquals(9.0, doc.pages[0].structuredText.spans.single().fontSize, 1e-9)
    }

    @Test
    fun horizontal_scaling_tz_does_not_change_size() {
        val doc = KitePDF.open(buildContentPdf("BT /F1 12 Tf 200 Tz 1 0 0 1 72 720 Tm (Wide) Tj ET\n"))
        assertEquals(12.0, doc.pages[0].structuredText.spans.single().fontSize, 1e-9)
    }

    @Test
    fun y_flipped_tm_reports_positive_size() {
        val doc = KitePDF.open(buildContentPdf("BT /F1 1 Tf 12 0 0 -12 72 720 Tm (Flip) Tj ET\n"))
        assertEquals(12.0, doc.pages[0].structuredText.spans.single().fontSize, 1e-9)
    }

    @Test
    fun degenerate_tm_falls_back_to_tf_size() {
        val doc = KitePDF.open(buildContentPdf("BT /F1 8 Tf 0 0 0 0 100 700 Tm (Zero) Tj ET\n"))
        assertEquals(8.0, doc.pages[0].structuredText.spans.single().fontSize, 1e-9)
    }

    @Test
    fun tm_scaled_adjacent_runs_get_no_spurious_space() {
        // Two runs 1pt apart at effective 15pt. "Hel" in Helvetica is 1500
        // font units = 22.5pt here, so the second run starts at 72 + 22.5 + 1.
        // The joiner threshold is 0.25 x fontSize: against the old raw size
        // (1.0 -> 0.25pt) the 1pt gap faked a word break, "Hel lo".
        val doc = KitePDF.open(
            buildContentPdf(
                "BT /F1 1 Tf 15 0 0 15 72 720 Tm (Hel) Tj ET\n" +
                    "BT /F1 1 Tf 15 0 0 15 95.5 720 Tm (lo) Tj ET\n",
            ),
        )
        assertEquals("Hello", doc.pages[0].structuredText.blocks.single().lines.single().text)
    }

    /* ─── Character and word spacing (Tc / Tw) ────────────────────────────── */

    @Test
    fun char_spacing_widens_span_bounds() {
        // 2 Tc at 10pt: each glyph advances 6.67 + 2. Width = 17.34, not 13.34.
        val doc = KitePDF.open(buildContentPdf("BT /F1 10 Tf 2 Tc 1 0 0 1 72 720 Tm (AB) Tj ET\n"))
        val b = doc.pages[0].structuredText.spans.single().bounds
        assertEquals(17.34, b.right - b.left, 1e-6)
    }

    @Test
    fun word_spacing_widens_the_space_glyph() {
        // 5 Tw at 10pt: A(6.67) + space(2.78 + 5) + B(6.67) = 21.12.
        val doc = KitePDF.open(buildContentPdf("BT /F1 10 Tf 5 Tw 1 0 0 1 72 720 Tm (A B) Tj ET\n"))
        val b = doc.pages[0].structuredText.spans.single().bounds
        assertEquals(21.12, b.right - b.left, 1e-6)
    }

    @Test
    fun quote_operator_spacing_applies() {
        // " sets aw=5 (Tw) and ac=2 (Tc) then shows:
        // A(6.67+2) + space(2.78+2+5) + B(6.67+2) = 27.12.
        val doc = KitePDF.open(buildContentPdf("BT /F1 10 Tf 1 0 0 1 72 720 Tm 5 2 (A B) \" ET\n"))
        val b = doc.pages[0].structuredText.spans.single().bounds
        assertEquals(27.12, b.right - b.left, 1e-6)
    }

    @Test
    fun char_spacing_lands_in_char_edges() {
        val doc = KitePDF.open(buildContentPdf("BT /F1 10 Tf 2 Tc 1 0 0 1 72 720 Tm (AB) Tj ET\n"))
        val edges = doc.pages[0].structuredText.spans.single().charEdgePoints!!
        assertEquals(72.0, edges[0].first, 1e-6)
        assertEquals(80.67, edges[1].first, 1e-6)
        assertEquals(89.34, edges[2].first, 1e-6)
    }

    @Test
    fun spacing_keeps_bounds_and_tm_advance_consistent() {
        // Two Tj in one BT under 2 Tc: the second span must start exactly where
        // the first one's Tc-inclusive advance ends (72 + 17.34).
        val doc = KitePDF.open(buildContentPdf("BT /F1 10 Tf 2 Tc 1 0 0 1 72 720 Tm (AB) Tj (AB) Tj ET\n"))
        val spans = doc.pages[0].structuredText.blocks.single().lines.single().spans
        assertEquals(2, spans.size)
        assertEquals(89.34, spans[1].origin.first, 1e-6)
        assertEquals(89.34, spans[0].bounds.right, 1e-6)
    }

    /* ─── Builder ─────────────────────────────────────────────────────────── */

    /** Build a single-page PDF around one raw content stream (Helvetica as /F1). */
    private fun buildContentPdf(content: String): ByteArray {
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

        val bytes = content.encodeToByteArray()
        offsets.add(buf.size())
        w("4 0 obj\n<< /Length ${bytes.size} >>\nstream\n")
        buf.append(bytes)
        w("\nendstream\nendobj\n")

        offsets.add(buf.size())
        w("5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")

        val xref = buf.size()
        w("xref\n0 6\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }

    /** Build a single-page PDF whose content stream draws each (x, y, text) triple. */
    private fun buildTextPdf(runs: List<Pair<Pair<Double, Double>, String>>): ByteArray =
        buildContentPdf(
            runs.joinToString("") { entry ->
                "BT /F1 18 Tf 1 0 0 1 ${entry.first.first} ${entry.first.second} Tm (${entry.second}) Tj ET\n"
            },
        )
}
