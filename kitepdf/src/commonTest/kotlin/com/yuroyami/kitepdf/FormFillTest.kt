package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.core.ByteArrayBuilder
import com.yuroyami.kitepdf.filters.FilterChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests the AcroForm field-tree reader ([PdfDocument.formFields]) and text
 * form-filling ([com.yuroyami.kitepdf.writer.PdfEditor.setTextFieldValue]):
 * fill a field, re-open, and confirm both the value (`/V`) and a generated
 * appearance stream (`/AP /N`) that contains the value.
 */
class FormFillTest {

    @Test fun reads_text_field_from_acroform() {
        val doc = KitePDF.open(buildFormPdf())
        assertEquals(1, doc.formFields.size)
        val field = doc.formField("FullName")
        assertNotNull(field)
        assertEquals(PdfFormField.FieldType.Text, field.type)
        assertEquals(null, field.value)
        assertNotNull(field.rect)
        assertEquals(300.0, field.rect.width)
    }

    @Test fun filling_sets_value_and_generates_appearance() {
        val original = buildFormPdf()
        val doc = KitePDF.open(original)
        val out = doc.edit().apply {
            setTextFieldValue(doc.formField("FullName")!!, "Ada Lovelace")
        }.saveIncremental()

        // Original bytes preserved (incremental invariant).
        assertTrue(original.contentEquals(out.copyOf(original.size)))

        val reopened = KitePDF.open(out)
        val field = reopened.formField("FullName")
        assertNotNull(field)
        assertEquals("Ada Lovelace", field.value)

        // The widget now has a generated /AP /N whose content draws the value.
        val ap = reopened.pages[0].annotations.firstOrNull()?.appearanceStream
        assertNotNull(ap, "expected a generated appearance stream on the widget")
        val apText = FilterChain.decode(ap).decodeToString()
        assertTrue(apText.contains("Ada Lovelace"), "appearance content didn't draw the value: $apText")
    }

    @Test fun filling_unicode_value_round_trips() {
        val doc = KitePDF.open(buildFormPdf())
        val out = doc.edit().apply {
            setTextFieldValue(doc.formField("FullName")!!, "Ådá — 名前")
        }.saveIncremental()
        assertEquals("Ådá — 名前", KitePDF.open(out).formField("FullName")?.value)
    }

    /* ─── Fixture: a one-field AcroForm ──────────────────────────────────── */

    /**
     * Minimal PDF with a single merged field+widget text field "FullName".
     * Object map: 1 Catalog, 2 Pages, 3 Page, 4 Helv font, 5 widget/field,
     * 6 AcroForm.
     */
    private fun buildFormPdf(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())

        write("%PDF-1.7\n%âãÏÓ\n")
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /AcroForm 6 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Annots [5 0 R] /Resources << >> >>\nendobj\n")
        offsets.add(buf.size())
        write("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        offsets.add(buf.size())
        write("5 0 obj\n<< /Type /Annot /Subtype /Widget /FT /Tx /T (FullName) /Rect [100 700 400 720] /DA (/Helv 12 Tf 0 g) /P 3 0 R /F 4 >>\nendobj\n")
        offsets.add(buf.size())
        write("6 0 obj\n<< /Fields [5 0 R] /DA (/Helv 12 Tf 0 g) /DR << /Font << /Helv 4 0 R >> >> >>\nendobj\n")

        val size = offsets.size + 1
        val xref = buf.size()
        write("xref\n0 $size\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
