package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the /PageLabels number-tree parser.
 *
 * Builds a 6-page PDF whose label scheme is:
 *  - pages 0-2 → lowercase roman (i, ii, iii)
 *  - page 3 → decimal starting at 1, prefix "Ch-" → "Ch-1"
 *  - pages 4-5 → uppercase letters starting at 1 → "A", "B"
 */
class PageLabelsTest {

    @Test
    fun mixed_label_schemes_resolve_correctly() {
        val doc = KitePDF.open(buildPdfWithLabels(pageCount = 6))
        assertEquals(6, doc.pageCount)
        assertEquals("i", doc.pages[0].label)
        assertEquals("ii", doc.pages[1].label)
        assertEquals("iii", doc.pages[2].label)
        assertEquals("Ch-1", doc.pages[3].label)
        assertEquals("A", doc.pages[4].label)
        assertEquals("B", doc.pages[5].label)
    }

    @Test
    fun missing_page_labels_falls_back_to_one_based_index() {
        // Build a 2-page PDF without /PageLabels.
        val bytes = MetadataPdfBuilder.simpleTwoPagePdf()
        val doc = KitePDF.open(bytes)
        assertEquals("1", doc.pages[0].label)
        assertEquals("2", doc.pages[1].label)
    }

    @Test
    fun letter_labels_repeat_past_z() {
        // Direct algorithm sanity-check: 1=A, 26=Z, 27=AA, 52=ZZ, 53=AAA.
        val ranges = listOf(
            PageLabelRange(firstPageIndex = 0, style = PageLabelRange.NumberStyle.UppercaseLetters,
                prefix = "", start = 1),
        )
        val tree = PageLabelTreeReflection.of(ranges)
        assertEquals("A", tree.labelOf(0))
        assertEquals("Z", tree.labelOf(25))
        assertEquals("AA", tree.labelOf(26))
        assertEquals("ZZ", tree.labelOf(51))
        assertEquals("AAA", tree.labelOf(52))
    }

    @Test
    fun roman_labels_format_correctly() {
        val ranges = listOf(
            PageLabelRange(firstPageIndex = 0, style = PageLabelRange.NumberStyle.LowercaseRoman,
                prefix = "", start = 1),
        )
        val tree = PageLabelTreeReflection.of(ranges)
        assertEquals("i", tree.labelOf(0))
        assertEquals("iv", tree.labelOf(3))
        assertEquals("ix", tree.labelOf(8))
        assertEquals("xiv", tree.labelOf(13))
        assertEquals("xl", tree.labelOf(39))
    }

    @Test
    fun start_offset_is_honoured() {
        val ranges = listOf(
            PageLabelRange(firstPageIndex = 0, style = PageLabelRange.NumberStyle.Decimal,
                prefix = "p", start = 100),
        )
        val tree = PageLabelTreeReflection.of(ranges)
        assertEquals("p100", tree.labelOf(0))
        assertEquals("p101", tree.labelOf(1))
    }

    private fun buildPdfWithLabels(pageCount: Int): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())

        write("%PDF-1.5\n%Äå\n")
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /PageLabels 3 0 R >>\nendobj\n")

        offsets.add(buf.size())
        val kids = (0 until pageCount).joinToString(" ") { "${4 + it} 0 R" }
        write("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count $pageCount /MediaBox [0 0 612 792] >>\nendobj\n")

        // /PageLabels number tree:
        //  0 → /S /r  (roman lowercase, start 1)
        //  3 → /S /D /P (Ch-) /St 1
        //  4 → /S /A
        offsets.add(buf.size())
        write(
            "3 0 obj\n<< /Nums " +
                "[0 << /S /r >> " +
                "3 << /S /D /P (Ch-) /St 1 >> " +
                "4 << /S /A >>] >>\nendobj\n",
        )

        // Page leaves (4 0 R, 5 0 R, …).
        for (i in 0 until pageCount) {
            offsets.add(buf.size())
            write("${4 + i} 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")
        }

        val xref = buf.size()
        val total = offsets.size + 1
        write("xref\n0 $total\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size $total /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}

/**
 * Tiny accessor shim so tests in the same package can construct a
 * PageLabelTree from a list of internal ranges. Kept out of the production
 * surface.
 */
internal object PageLabelTreeReflection {
    fun of(ranges: List<PageLabelRange>): PageLabelTree = PageLabelTree(ranges)
}

/** Shared minimal-PDF helper used by a couple of metadata tests. */
internal object MetadataPdfBuilder {
    fun simpleTwoPagePdf(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())

        write("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 612 792] >>\nendobj\n")
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")
        offsets.add(buf.size())
        write("4 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")

        val xref = buf.size()
        write("xref\n0 5\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
