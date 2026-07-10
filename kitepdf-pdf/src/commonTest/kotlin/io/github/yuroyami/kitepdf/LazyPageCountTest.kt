package io.github.yuroyami.kitepdf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T-17: `pageCount` answers from the root `/Count` without materializing the
 * page list; a lying `/Count` self-corrects once `pages` is walked.
 */
class LazyPageCountTest {

    /** A 2-page raw PDF whose /Pages /Count claims [declaredCount]. */
    private fun pdf(declaredCount: Int): ByteArray {
        val sb = StringBuilder()
        val offsets = ArrayList<Int>()
        fun add(s: String) {
            offsets.add(sb.length)
            sb.append(s)
        }
        sb.append("%PDF-1.4\n")
        add("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        add("2 0 obj\n<< /Type /Pages /Kids [3 0 R 4 0 R] /Count $declaredCount /MediaBox [0 0 100 100] >>\nendobj\n")
        add("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")
        add("4 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")
        val xref = sb.length
        sb.append("xref\n0 5\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    @Test
    fun valid_count_answers_without_materializing_pages() {
        val doc = KitePDF.open(pdf(declaredCount = 2))
        assertEquals(2, doc.pageCount)
        // The open() validation probe may touch the tree, but the pageCount
        // read itself must not have built the page list: the internal index
        // built alongside pages is still absent.
        assertEquals(2, doc.pages.size)
        assertEquals(2, doc.pageCount, "after materializing, sizes agree")
    }

    @Test
    fun lying_count_self_corrects_once_pages_materialize() {
        val doc = KitePDF.open(pdf(declaredCount = 3))
        // Before the walk the declared (wrong) count is what /Count says.
        assertEquals(3, doc.pageCount, "declared count answers first")
        assertEquals(2, doc.pages.size, "the real tree walk finds 2 pages")
        assertEquals(2, doc.pageCount, "pages.size is authoritative from then on")
    }

    @Test
    fun negative_count_falls_through_to_the_walk() {
        val doc = KitePDF.open(pdf(declaredCount = -5))
        assertEquals(2, doc.pageCount, "implausible /Count walks the tree instead")
    }
}
