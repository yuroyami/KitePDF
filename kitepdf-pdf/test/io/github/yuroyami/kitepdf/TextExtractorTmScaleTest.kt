package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `extractText` heuristics must scale with the effective (Tm-scaled) size.
 * Producers that write `/F1 1 Tf` and carry the size in Tm otherwise get a
 * sub-point line-break tolerance and the wrong word-gap rule (issue #22).
 */
class TextExtractorTmScaleTest {

    @Test
    fun same_line_jitter_at_tm_scale_does_not_break_line() {
        // Effective 15pt; 1pt baseline jitter is intra-line. The old raw
        // tolerance (1.0 x 0.3) sprayed a newline: "Hel\nlo".
        val doc = KitePDF.open(
            buildContentPdf(
                "BT /F1 1 Tf 15 0 0 15 72 720 Tm (Hel) Tj 15 0 0 15 95 719 Tm (lo) Tj ET\n",
            ),
        )
        assertEquals("Hello", doc.pages[0].extractText())
    }

    @Test
    fun real_line_break_at_tm_scale_still_breaks() {
        // Td moves one full 1.2em line: 1.2 x 15 = 18pt effective.
        val doc = KitePDF.open(
            buildContentPdf(
                "BT /F1 1 Tf 15 0 0 15 72 720 Tm (a) Tj 0 -1.2 Td (b) Tj ET\n",
            ),
        )
        assertEquals("a\nb", doc.pages[0].extractText())
    }

    @Test
    fun tj_gap_nudge_uses_effective_size() {
        // Effective 20pt takes the large-font branch: threshold -240, so a
        // -210 kern is NOT a word gap. The old raw size (1.0) took the
        // tiny-font branch (-150) and inserted a space.
        val doc = KitePDF.open(
            buildContentPdf("BT /F1 1 Tf 20 0 0 20 72 720 Tm [(A) -210 (B)] TJ ET\n"),
        )
        assertEquals("AB", doc.pages[0].extractText())
    }

    @Test
    fun tj_word_gap_at_effective_size_still_spaces() {
        val doc = KitePDF.open(
            buildContentPdf("BT /F1 1 Tf 20 0 0 20 72 720 Tm [(A) -300 (B)] TJ ET\n"),
        )
        assertEquals("A B", doc.pages[0].extractText())
    }

    /** Same builder as StructuredTextTest (test sources share no helpers). */
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
}
