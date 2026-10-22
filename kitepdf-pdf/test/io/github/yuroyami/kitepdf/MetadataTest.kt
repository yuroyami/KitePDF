package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for PdfDocument.info, PdfDate parsing, and the Permissions API.
 */
class MetadataTest {

    @Test
    fun parses_info_dict_strings() {
        val bytes = buildPdfWithInfo(
            title = "KitePDF Test",
            author = "yuroyami",
            subject = "Metadata round-trip",
            keywords = "kotlin, pdf, kite",
            creator = "test-suite",
            producer = "kitepdf",
        )
        val doc = KitePDF.open(bytes)
        assertEquals("KitePDF Test", doc.info.title)
        assertEquals("yuroyami", doc.info.author)
        assertEquals("Metadata round-trip", doc.info.subject)
        assertEquals("kotlin, pdf, kite", doc.info.keywords)
        assertEquals("test-suite", doc.info.creator)
        assertEquals("kitepdf", doc.info.producer)
        assertEquals(PdfDocumentInfo.Trapped.Unknown, doc.info.trapped)
    }

    @Test
    fun missing_info_dict_returns_empty_info() {
        // DocumentTest.buildMinimalPdf has no /Info entry.
        val bytes = buildPdfWithInfo()  // no fields
        val doc = KitePDF.open(bytes)
        assertNull(doc.info.title)
        assertNull(doc.info.author)
        assertTrue(doc.info.custom.isEmpty())
    }

    @Test
    fun parses_pdf_date_string() {
        val d = PdfDate.parse("D:20260519143025+02'00'")
        assertNotNull(d)
        assertEquals(2026, d.year)
        assertEquals(5, d.month)
        assertEquals(19, d.day)
        assertEquals(14, d.hour)
        assertEquals(30, d.minute)
        assertEquals(25, d.second)
        assertEquals('+', d.tzSign)
        assertEquals(2, d.tzHour)
        assertEquals(0, d.tzMinute)
    }

    @Test
    fun pdf_date_round_trips() {
        val original = "D:20260519143025+02'00'"
        val parsed = PdfDate.parse(original)
        assertNotNull(parsed)
        assertEquals(original, parsed.toString())
    }

    @Test
    fun pdf_date_handles_z_marker() {
        val d = PdfDate.parse("D:20260101000000Z")
        assertNotNull(d)
        assertEquals('Z', d.tzSign)
        assertEquals(0, d.tzHour)
        assertEquals("D:20260101000000Z", d.toString())
    }

    @Test
    fun pdf_date_handles_partial_strings() {
        val d = PdfDate.parse("D:2026")
        assertNotNull(d)
        assertEquals(2026, d.year)
        assertEquals(1, d.month)
        assertEquals(1, d.day)
        assertEquals(0, d.hour)
    }

    @Test
    fun permissions_default_to_allow_all_for_unencrypted() {
        val bytes = buildPdfWithInfo()
        val doc = KitePDF.open(bytes)
        assertEquals(PdfPermissions.allowAll, doc.permissions)
        assertTrue(doc.permissions.canPrint)
        assertTrue(doc.permissions.canModifyContents)
        assertTrue(doc.permissions.canCopyContents)
        assertTrue(doc.permissions.canFillForms)
    }

    @Test
    fun permission_bit_masks_match_spec_table_22() {
        // Sanity-check the documented offsets against the 1-based bit positions.
        assertEquals(0x004, PdfPermissions.BIT_PRINT)
        assertEquals(0x008, PdfPermissions.BIT_MODIFY)
        assertEquals(0x010, PdfPermissions.BIT_COPY)
        assertEquals(0x020, PdfPermissions.BIT_ANNOTATE)
        assertEquals(0x100, PdfPermissions.BIT_FILL_FORMS)
        assertEquals(0x200, PdfPermissions.BIT_ACCESSIBILITY)
        assertEquals(0x400, PdfPermissions.BIT_ASSEMBLE)
        assertEquals(0x800, PdfPermissions.BIT_PRINT_HIGHRES)
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private fun buildPdfWithInfo(
        title: String? = null,
        author: String? = null,
        subject: String? = null,
        keywords: String? = null,
        creator: String? = null,
        producer: String? = null,
    ): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())

        write("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] >>\nendobj\n")
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> /Contents 4 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("4 0 obj\n<< /Length 0 >>\nstream\n\nendstream\nendobj\n")

        // /Info dict.
        offsets.add(buf.size())
        val hasInfo = listOf(title, author, subject, keywords, creator, producer).any { it != null }
        write("5 0 obj\n<<")
        if (title != null) write(" /Title ($title)")
        if (author != null) write(" /Author ($author)")
        if (subject != null) write(" /Subject ($subject)")
        if (keywords != null) write(" /Keywords ($keywords)")
        if (creator != null) write(" /Creator ($creator)")
        if (producer != null) write(" /Producer ($producer)")
        write(" >>\nendobj\n")

        val xref = buf.size()
        write("xref\n0 6\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        val infoEntry = if (hasInfo) " /Info 5 0 R" else ""
        write("trailer\n<< /Size 6 /Root 1 0 R$infoEntry >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
