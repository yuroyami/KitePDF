package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-25: the format-neutral [KiteDocument] surface on [PdfDocument]. Every
 * assertion goes through a `KiteDocument`-typed variable: no downcast to
 * read a title or walk the outline with resolved page indices.
 */
class KiteSurfaceTest {

    @Test
    fun metadata_comes_from_info_and_lang_without_downcast() {
        val doc: KiteDocument = KitePDF.open(buildPdf())
        assertEquals("Kite Manual", doc.metadata.title)
        assertEquals(listOf("Ada Lovelace"), doc.metadata.authors)
        assertEquals("en-US", doc.metadata.language)
    }

    @Test
    fun outline_resolves_destinations_to_page_indices() {
        val doc: KiteDocument = KitePDF.open(buildPdf())
        val outline = doc.outline
        assertEquals(2, outline.size)

        val ch1 = outline[0]
        assertEquals("Chapter 1", ch1.title)
        assertEquals(0, ch1.pageIndex, "explicit XYZ destination resolves")

        val sec = ch1.children.single()
        assertEquals("Section 1.1", sec.title)
        assertEquals(1, sec.pageIndex, "named destination resolves through /Dests")

        val ch2 = outline[1]
        assertEquals("Chapter 2", ch2.title)
        assertEquals(2, ch2.pageIndex, "GoTo action destination resolves")
        assertTrue(ch2.children.isEmpty())
    }

    @Test
    fun document_without_outline_or_info_has_empty_surface() {
        val doc: KiteDocument = KitePDF.open(MetadataPdfBuilder.simpleTwoPagePdf())
        assertNull(doc.metadata.title)
        assertTrue(doc.metadata.authors.isEmpty())
        assertTrue(doc.outline.isEmpty())
    }

    /** The OutlinesTest fixture plus an /Info dict and a catalog /Lang. */
    private fun buildPdf(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())

        write("%PDF-1.5\n%Äå\n")

        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Outlines 3 0 R /Dests 4 0 R /Lang (en-US) >>\nendobj\n")

        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [7 0 R 8 0 R 9 0 R] /Count 3 /MediaBox [0 0 612 792] >>\nendobj\n")

        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Outlines /First 5 0 R /Last 10 0 R /Count 2 >>\nendobj\n")

        offsets.add(buf.size())
        write("4 0 obj\n<< /sec1.1 [8 0 R /Fit] >>\nendobj\n")

        offsets.add(buf.size())
        write(
            "5 0 obj\n<< /Title (Chapter 1) /Parent 3 0 R /First 6 0 R /Last 6 0 R " +
                "/Next 10 0 R /Count 1 /Dest [7 0 R /XYZ 0 792 null] >>\nendobj\n",
        )

        offsets.add(buf.size())
        write("6 0 obj\n<< /Title (Section 1.1) /Parent 5 0 R /Dest (sec1.1) >>\nendobj\n")

        offsets.add(buf.size())
        write("7 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")
        offsets.add(buf.size())
        write("8 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")
        offsets.add(buf.size())
        write("9 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")

        offsets.add(buf.size())
        write(
            "10 0 obj\n<< /Title (Chapter 2) /Parent 3 0 R /Prev 5 0 R " +
                "/A << /S /GoTo /D [9 0 R /Fit] >> >>\nendobj\n",
        )

        offsets.add(buf.size())
        write("11 0 obj\n<< /Title (Kite Manual) /Author (Ada Lovelace) >>\nendobj\n")

        val xref = buf.size()
        write("xref\n0 12\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size 12 /Root 1 0 R /Info 11 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
