package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for catalog /Threads parsing — article threads with bead chains.
 *
 * Builds a 2-page PDF with one thread of three beads:
 *   - bead 1 on page 0, top-left column
 *   - bead 2 on page 0, top-right column
 *   - bead 3 on page 1, full width
 * The thread carries an /I dict with title + author.
 */
class ArticleThreadTest {

    @Test
    fun parses_thread_with_metadata_and_beads() {
        val doc = KitePDF.open(buildPdfWithThread())
        val threads = doc.articleThreads
        assertEquals(1, threads.size)
        val t = threads[0]
        assertEquals("Cover Story", t.title)
        assertEquals("yuroyami", t.author)
        assertEquals("test", t.subject)
        assertEquals(3, t.beads.size)

        assertEquals(0, t.beads[0].pageIndex)
        assertEquals(50.0, t.beads[0].rect.left)
        assertEquals(0, t.beads[1].pageIndex)
        assertEquals(1, t.beads[2].pageIndex)
    }

    @Test
    fun thread_walk_terminates_on_cycle() {
        val doc = KitePDF.open(buildPdfWithCyclicThread())
        val t = doc.articleThreads.single()
        // The thread points to two beads where bead2 /N → bead1 (cycle).
        // Walk must stop and give us [bead1, bead2].
        assertEquals(2, t.beads.size)
    }

    @Test
    fun no_threads_returns_empty_list() {
        val doc = KitePDF.open(MetadataPdfBuilder.simpleTwoPagePdf())
        assertTrue(doc.articleThreads.isEmpty())
    }

    /* ─── Builders ────────────────────────────────────────────────────────── */

    private fun buildPdfWithThread(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())

        // Object plan:
        //   1: Catalog               (Pages=2, Threads=[3])
        //   2: Pages                 (Kids=[7,8])
        //   3: Thread                (F=4, I=11)
        //   4: Bead 1 (P=7, N=5, V=6)
        //   5: Bead 2 (P=7, N=6, V=4)
        //   6: Bead 3 (P=8, N=4, V=5)  (wrap)
        //   7: Page 1
        //   8: Page 2
        //   11: Thread info dict
        w("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Threads [3 0 R] >>\nendobj\n")
        offsets.add(buf.size())
        w("2 0 obj\n<< /Type /Pages /Kids [7 0 R 8 0 R] /Count 2 /MediaBox [0 0 612 792] >>\nendobj\n")
        offsets.add(buf.size())
        w("3 0 obj\n<< /Type /Thread /F 4 0 R /I 11 0 R >>\nendobj\n")
        offsets.add(buf.size())
        w("4 0 obj\n<< /Type /Bead /T 3 0 R /N 5 0 R /V 6 0 R /P 7 0 R /R [50 600 300 750] >>\nendobj\n")
        offsets.add(buf.size())
        w("5 0 obj\n<< /Type /Bead /T 3 0 R /N 6 0 R /V 4 0 R /P 7 0 R /R [320 600 562 750] >>\nendobj\n")
        offsets.add(buf.size())
        w("6 0 obj\n<< /Type /Bead /T 3 0 R /N 4 0 R /V 5 0 R /P 8 0 R /R [50 100 562 700] >>\nendobj\n")
        offsets.add(buf.size())
        w("7 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")
        offsets.add(buf.size())
        w("8 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")
        // Pad to keep object numbers contiguous up to 11 (9, 10 unused but the xref accepts gaps via free entries).
        // To keep this simple we'll number compact: 1..8 then thread info 9.
        // Adjust the catalog ref upstream — but we wrote 11 above. So we need to skip to 11.

        // Free 9, 10 by inserting them as free entries in xref.
        // Object 9 (free) and 10 (free) — we'll write the xref by hand below.
        offsets.add(buf.size())
        w("9 0 obj\n<< >>\nendobj\n")  // placeholder
        offsets.add(buf.size())
        w("10 0 obj\n<< >>\nendobj\n")  // placeholder
        offsets.add(buf.size())
        w("11 0 obj\n<< /Title (Cover Story) /Author (yuroyami) /Subject (test) >>\nendobj\n")

        val xref = buf.size()
        w("xref\n0 12\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size 12 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }

    private fun buildPdfWithCyclicThread(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())

        // 1: Catalog, 2: Pages, 3: Thread (F=4), 4: Bead1 (N=5), 5: Bead2 (N=4 cycle), 6: Page
        w("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Threads [3 0 R] >>\nendobj\n")
        offsets.add(buf.size())
        w("2 0 obj\n<< /Type /Pages /Kids [6 0 R] /Count 1 /MediaBox [0 0 612 792] >>\nendobj\n")
        offsets.add(buf.size())
        w("3 0 obj\n<< /Type /Thread /F 4 0 R >>\nendobj\n")
        offsets.add(buf.size())
        w("4 0 obj\n<< /Type /Bead /N 5 0 R /P 6 0 R /R [0 0 100 100] >>\nendobj\n")
        offsets.add(buf.size())
        w("5 0 obj\n<< /Type /Bead /N 4 0 R /P 6 0 R /R [100 100 200 200] >>\nendobj\n")
        offsets.add(buf.size())
        w("6 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")

        val xref = buf.size()
        w("xref\n0 7\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
