package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Regression tests for the Session-2 foundation fixes — these PDFs would have
 * blown up in Session 1.
 */
class FoundationFixesTest {

    @Test
    fun parses_pdf_with_indirect_length_in_stream() {
        // The stream's /Length is an indirect reference to object 6 (= 38 bytes).
        // Session 1's parser would throw on the indirect ref; Session 2 resolves it.
        val payload = "BT /F1 18 Tf 72 720 Td (Indirect length) Tj ET".encodeToByteArray()
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())

        w("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        w("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        w("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n")
        offsets.add(buf.size())
        w("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        offsets.add(buf.size())
        // Stream object with /Length 6 0 R (indirect ref to object 6).
        w("5 0 obj\n<< /Length 6 0 R >>\nstream\n")
        buf.append(payload)
        w("\nendstream\nendobj\n")
        offsets.add(buf.size())
        w("6 0 obj\n${payload.size}\nendobj\n")

        val xref = buf.size()
        w("xref\n0 7\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")

        val doc = KitePDF.open(buf.toByteArray())
        assertEquals(1, doc.pageCount)
        assertContains(doc.pages[0].extractText(), "Indirect length")
    }

    /**
     * Two xref sections chained via /Prev — simulates an incremental update.
     * The newer section replaces obj 5's content stream; older sections still
     * provide objs 1–4.
     */
    @Test
    fun prev_xref_chain_merges_newer_objects_over_older() {
        // We build this in two passes: first the "original" PDF, then we append
        // an updated content stream + new xref + new trailer that points back
        // to the original via /Prev.
        val buf = ByteArrayBuilder()
        val oldOffsets = mutableListOf<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())

        // ─── Original section ──────────────────────────────────────────
        w("%PDF-1.4\n%Äå\n")
        oldOffsets.add(buf.size())
        w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        oldOffsets.add(buf.size())
        w("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        oldOffsets.add(buf.size())
        w("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n")
        oldOffsets.add(buf.size())
        w("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        oldOffsets.add(buf.size())
        val oldPayload = "BT /F1 18 Tf 72 720 Td (original) Tj ET".encodeToByteArray()
        w("5 0 obj\n<< /Length ${oldPayload.size} >>\nstream\n")
        buf.append(oldPayload); w("\nendstream\nendobj\n")
        val oldXref = buf.size()
        w("xref\n0 6\n0000000000 65535 f \n")
        for (o in oldOffsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$oldXref\n%%EOF\n")

        // ─── Incremental update ────────────────────────────────────────
        // Replace obj 5 with a new content stream.
        val newPayloadOffset = buf.size()
        val newPayload = "BT /F1 18 Tf 72 720 Td (updated) Tj ET".encodeToByteArray()
        w("5 0 obj\n<< /Length ${newPayload.size} >>\nstream\n")
        buf.append(newPayload); w("\nendstream\nendobj\n")
        val newXref = buf.size()
        // Section header: subsection "5 1" rewriting obj 5 only.
        w("xref\n5 1\n${newPayloadOffset.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size 6 /Root 1 0 R /Prev $oldXref >>\nstartxref\n$newXref\n%%EOF\n")

        val doc = KitePDF.open(buf.toByteArray())
        assertEquals(1, doc.pageCount)
        // Should pick up the NEW content stream via /Prev merge.
        assertContains(doc.pages[0].extractText(), "updated")
    }
}
