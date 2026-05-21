package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.core.ByteArrayBuilder
import com.yuroyami.kitepdf.parser.Parser
import com.yuroyami.kitepdf.parser.PdfArray
import com.yuroyami.kitepdf.parser.PdfBoolean
import com.yuroyami.kitepdf.parser.PdfDictionary
import com.yuroyami.kitepdf.parser.PdfInt
import com.yuroyami.kitepdf.parser.PdfName
import com.yuroyami.kitepdf.parser.PdfNull
import com.yuroyami.kitepdf.parser.PdfObject
import com.yuroyami.kitepdf.parser.PdfReal
import com.yuroyami.kitepdf.parser.PdfReference
import com.yuroyami.kitepdf.parser.PdfString
import com.yuroyami.kitepdf.writer.PdfObjectWriter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the write engine: the [PdfObjectWriter] serializer (round-trips
 * through the parser) and the [com.yuroyami.kitepdf.writer.PdfEditor]
 * incremental-update writer (edits re-open correctly and preserve the
 * original bytes verbatim).
 */
class WriterTest {

    /* ─── Serializer round-trips ─────────────────────────────────────────── */

    private fun roundTrip(obj: PdfObject): PdfObject =
        Parser(PdfObjectWriter.toBytes(obj)).readObject()

    @Test fun serializes_primitives() {
        assertEquals(PdfNull, roundTrip(PdfNull))
        assertEquals(PdfBoolean(true), roundTrip(PdfBoolean(true)))
        assertEquals(PdfBoolean(false), roundTrip(PdfBoolean(false)))
        assertEquals(PdfInt(42), roundTrip(PdfInt(42)))
        assertEquals(PdfInt(-17), roundTrip(PdfInt(-17)))
        assertEquals(PdfReal(3.14), roundTrip(PdfReal(3.14)))
        assertEquals(PdfReal(-2.5), roundTrip(PdfReal(-2.5)))
        assertEquals(PdfReference(7, 0), roundTrip(PdfReference(7, 0)))
    }

    @Test fun reals_never_use_scientific_notation() {
        assertEquals("0", PdfObjectWriter.formatReal(0.0))
        assertEquals("3.14", PdfObjectWriter.formatReal(3.14))
        assertEquals("0.1", PdfObjectWriter.formatReal(0.1))
        assertEquals("-2.5", PdfObjectWriter.formatReal(-2.5))
        assertEquals("0.00001", PdfObjectWriter.formatReal(1.0e-5))
        // A magnitude that Kotlin's Double.toString would render as "1.0E11".
        val big = PdfObjectWriter.formatReal(1.0e11)
        assertFalse(big.contains('E') || big.contains('e'), "got: $big")
        assertEquals("100000000000", big)
    }

    @Test fun serializes_names_with_escapes() {
        assertEquals(PdfName("Hello"), roundTrip(PdfName("Hello")))
        // Space and slash are not name-legal raw; must come back through #XX.
        assertEquals(PdfName("A B"), roundTrip(PdfName("A B")))
        assertEquals(PdfName("a/b#c"), roundTrip(PdfName("a/b#c")))
        assertTrue(PdfObjectWriter.toBytes(PdfName("A B")).decodeToString().contains("#20"))
    }

    @Test fun serializes_strings_literal_and_hex() {
        // Printable ASCII → literal form with paren/backslash escaping.
        assertEquals(PdfString("a(b)c\\d".enc()), roundTrip(PdfString("a(b)c\\d".enc())))
        assertEquals("(a\\(b\\)c\\\\d)", PdfObjectWriter.toBytes(PdfString("a(b)c\\d".enc())).decodeToString())
        // Binary (UTF-16BE w/ BOM) → hex form, byte-exact.
        val binary = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x00, 0x41)
        val rt = roundTrip(PdfString(binary))
        assertIs<PdfString>(rt)
        assertTrue(binary.contentEquals(rt.bytes))
        assertEquals("<FEFF0041>", PdfObjectWriter.toBytes(PdfString(binary)).decodeToString())
    }

    @Test fun serializes_nested_array_and_dict() {
        val obj = PdfDictionary(
            linkedMapOf(
                "Type" to PdfName("Catalog"),
                "Count" to PdfInt(3),
                "Kids" to PdfArray(listOf(PdfReference(2, 0), PdfReference(4, 0))),
                "Box" to PdfArray(listOf(PdfInt(0), PdfInt(0), PdfReal(612.0), PdfReal(792.5))),
                "Nested" to PdfDictionary(linkedMapOf("Flag" to PdfBoolean(true))),
            ),
        )
        val rt = roundTrip(obj) as PdfDictionary
        assertEquals(PdfName("Catalog"), rt["Type"])
        assertEquals(PdfInt(3), rt["Count"])
        assertEquals(PdfArray(listOf(PdfReference(2, 0), PdfReference(4, 0))), rt["Kids"])
        assertEquals(PdfReal(792.5), (rt["Box"] as PdfArray)[3])
        assertEquals(PdfBoolean(true), (rt["Nested"] as PdfDictionary)["Flag"])
    }

    /* ─── Incremental writer ─────────────────────────────────────────────── */

    @Test fun incremental_update_preserves_original_bytes_verbatim() {
        val original = buildMinimalPdf(withInfo = true)
        val doc = KitePDF.open(original)
        val out = doc.edit().apply { setInfo(title = "Edited Title") }.saveIncremental()

        assertTrue(out.size > original.size, "incremental save should append, not shrink")
        // The original file must be an exact byte prefix of the updated file.
        assertTrue(original.contentEquals(out.copyOf(original.size)), "original bytes not preserved verbatim")
    }

    @Test fun incremental_update_of_existing_info_reopens_with_change() {
        val original = buildMinimalPdf(withInfo = true)
        val doc = KitePDF.open(original)
        assertEquals("Original Producer", doc.info.producer)

        val out = doc.edit().apply { setInfo(title = "Edited Title") }.saveIncremental()
        val reopened = KitePDF.open(out)

        assertEquals("Edited Title", reopened.info.title)
        // Untouched /Info fields survive the merge.
        assertEquals("Original Producer", reopened.info.producer)
        // The rest of the document is intact.
        assertEquals(1, reopened.pageCount)
        assertContains(reopened.pages[0].extractText(), "Hello")
    }

    @Test fun creating_info_when_absent_points_trailer_at_new_object() {
        val original = buildMinimalPdf(withInfo = false)
        val doc = KitePDF.open(original)
        assertEquals(null, doc.info.author)

        val out = doc.edit().apply { setInfo(author = "Ada Lovelace") }.saveIncremental()
        val reopened = KitePDF.open(out)

        assertEquals("Ada Lovelace", reopened.info.author)
        assertEquals(1, reopened.pageCount)
    }

    @Test fun added_object_is_resolvable_after_reopen() {
        val original = buildMinimalPdf(withInfo = true)
        val doc = KitePDF.open(original)

        val editor = doc.edit()
        val ref = editor.addObject(
            PdfDictionary(linkedMapOf("Type" to PdfName("KiteMarker"), "N" to PdfInt(99))),
        )
        val out = editor.saveIncremental()
        val reopened = KitePDF.open(out)

        val resolved = reopened.resolve(ref)
        assertIs<PdfDictionary>(resolved)
        assertEquals(PdfName("KiteMarker"), resolved["Type"])
        assertEquals(PdfInt(99), resolved["N"])
    }

    @Test fun unicode_title_round_trips_via_utf16be() {
        val original = buildMinimalPdf(withInfo = true)
        val doc = KitePDF.open(original)
        val out = doc.edit().apply { setInfo(title = "Café — 日本語") }.saveIncremental()
        val reopened = KitePDF.open(out)
        assertEquals("Café — 日本語", reopened.info.title)
    }

    @Test fun no_op_save_returns_equivalent_bytes() {
        val original = buildMinimalPdf(withInfo = true)
        val doc = KitePDF.open(original)
        val out = doc.edit().saveIncremental()
        assertTrue(original.contentEquals(out))
    }

    @Test fun two_successive_incremental_updates_chain_via_prev() {
        val original = buildMinimalPdf(withInfo = true)
        val first = KitePDF.open(original).edit().apply { setInfo(title = "First") }.saveIncremental()
        val second = KitePDF.open(first).edit().apply { setInfo(subject = "Second") }.saveIncremental()

        val reopened = KitePDF.open(second)
        // Both updates are visible: the latest title and the newly added subject.
        assertEquals("First", reopened.info.title)
        assertEquals("Second", reopened.info.subject)
        assertEquals("Original Producer", reopened.info.producer)
        // First update's bytes remain a verbatim prefix of the second.
        assertTrue(first.contentEquals(second.copyOf(first.size)))
        assertNotNull(reopened.catalog)
    }

    /* ─── Fixture ────────────────────────────────────────────────────────── */

    private fun String.enc(): ByteArray = encodeToByteArray()

    /**
     * Minimal one-page PDF, optionally with a trailer-referenced /Info dict.
     * Mirrors the offset-tracking emitter used by [DocumentTest].
     */
    private fun buildMinimalPdf(withInfo: Boolean): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())

        write("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        offsets.add(buf.size())
        val payload = "BT /F1 18 Tf 72 720 Td (Hello, KitePDF!) Tj ET".encodeToByteArray()
        write("5 0 obj\n<< /Length ${payload.size} >>\nstream\n")
        buf.append(payload)
        write("\nendstream\nendobj\n")
        if (withInfo) {
            offsets.add(buf.size())
            write("6 0 obj\n<< /Producer (Original Producer) >>\nendobj\n")
        }

        val size = offsets.size + 1
        val xref = buf.size()
        write("xref\n0 $size\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        val infoEntry = if (withInfo) " /Info 6 0 R" else ""
        write("trailer\n<< /Size $size /Root 1 0 R$infoEntry >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
