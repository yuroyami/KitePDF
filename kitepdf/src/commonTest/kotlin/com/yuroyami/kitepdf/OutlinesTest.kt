package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the /Outlines tree parser and named-destination resolution.
 *
 * Builds a 3-page PDF with two top-level bookmarks:
 *   - "Chapter 1" → page 0 via explicit XYZ destination
 *      - "Section 1.1" (child) → page 1 via named destination "sec1.1"
 *   - "Chapter 2" → page 2 via /A /S /GoTo /D action
 */
class OutlinesTest {

    @Test
    fun parses_outlines_tree() {
        val doc = KitePDF.open(buildPdfWithOutlines())
        assertEquals(2, doc.outlines.size)

        val ch1 = doc.outlines[0]
        assertEquals("Chapter 1", ch1.title)
        assertEquals(1, ch1.children.size)
        assertTrue(ch1.isOpen)

        val sec = ch1.children[0]
        assertEquals("Section 1.1", sec.title)
        assertEquals(0, sec.children.size)

        val ch2 = doc.outlines[1]
        assertEquals("Chapter 2", ch2.title)
        // Chapter 2 uses /A /S /GoTo — rawDestination should be promoted.
        assertNotNull(ch2.rawDestination)
    }

    @Test
    fun explicit_destination_resolves_to_page_index() {
        val doc = KitePDF.open(buildPdfWithOutlines())
        val ch1Dest = doc.resolveDestination(doc.outlines[0].rawDestination)
        assertNotNull(ch1Dest)
        assertEquals(0, ch1Dest.pageIndex)
        val view = ch1Dest.view
        assertTrue(view is PdfDestination.ViewFit.XYZ)
        assertEquals(0.0, view.left)
        assertEquals(792.0, view.top)
    }

    @Test
    fun named_destination_resolves_through_dests_dict() {
        val doc = KitePDF.open(buildPdfWithOutlines())
        val sec = doc.outlines[0].children[0]
        val dest = doc.resolveDestination(sec.rawDestination)
        assertNotNull(dest)
        assertEquals(1, dest.pageIndex)
        assertTrue(dest.view is PdfDestination.ViewFit.Fit)
    }

    @Test
    fun goto_action_destination_resolves() {
        val doc = KitePDF.open(buildPdfWithOutlines())
        val ch2 = doc.outlines[1]
        val dest = doc.resolveDestination(ch2.rawDestination)
        assertNotNull(dest)
        assertEquals(2, dest.pageIndex)
    }

    @Test
    fun document_without_outlines_returns_empty_list() {
        val doc = KitePDF.open(MetadataPdfBuilder.simpleTwoPagePdf())
        assertTrue(doc.outlines.isEmpty())
    }

    @Test
    fun resolving_null_destination_returns_null() {
        val doc = KitePDF.open(MetadataPdfBuilder.simpleTwoPagePdf())
        assertNull(doc.resolveDestination(null))
    }

    /* ─── PDF builder ─────────────────────────────────────────────────────── */

    private fun buildPdfWithOutlines(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())

        // Object plan:
        //   1: Catalog          (refs Pages=2, Outlines=3, Dests=4)
        //   2: Pages            (3 kids: 10, 11, 12)
        //   3: Outlines root    (First=5, Last=8, Count=2)
        //   4: /Dests           (named-destination lookup table)
        //   5: Chapter 1        (First=6, Last=6, Next=8)
        //   6: Section 1.1      (Parent=5)
        //   7: (unused — keeps numbering simple if needed; we'll skip)
        //   8: Chapter 2        (Prev=5)
        //   10/11/12: page leaves
        // We renumber to keep contiguous: 1..9.

        write("%PDF-1.5\n%Äå\n")

        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Outlines 3 0 R /Dests 4 0 R >>\nendobj\n")

        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [7 0 R 8 0 R 9 0 R] /Count 3 /MediaBox [0 0 612 792] >>\nendobj\n")

        // /Outlines root.
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Outlines /First 5 0 R /Last 10 0 R /Count 2 >>\nendobj\n")

        // /Dests: maps "sec1.1" → [page-2-ref /Fit].
        offsets.add(buf.size())
        write("4 0 obj\n<< /sec1.1 [8 0 R /Fit] >>\nendobj\n")

        // Chapter 1 outline node — explicit XYZ destination at top-left of page 0.
        offsets.add(buf.size())
        write(
            "5 0 obj\n<< /Title (Chapter 1) /Parent 3 0 R /First 6 0 R /Last 6 0 R " +
                "/Next 10 0 R /Count 1 /Dest [7 0 R /XYZ 0 792 null] >>\nendobj\n",
        )

        // Section 1.1 — named destination (the string form).
        offsets.add(buf.size())
        write("6 0 obj\n<< /Title (Section 1.1) /Parent 5 0 R /Dest (sec1.1) >>\nendobj\n")

        // Page leaves: 7, 8, 9.
        offsets.add(buf.size())
        write("7 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")
        offsets.add(buf.size())
        write("8 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")
        offsets.add(buf.size())
        write("9 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")

        // Chapter 2 — uses /A /GoTo action instead of /Dest directly.
        offsets.add(buf.size())
        write(
            "10 0 obj\n<< /Title (Chapter 2) /Parent 3 0 R /Prev 5 0 R " +
                "/A << /S /GoTo /D [9 0 R /Fit] >> >>\nendobj\n",
        )

        val xref = buf.size()
        write("xref\n0 11\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size 11 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
