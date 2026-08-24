package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.parser.PdfBoolean
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * `clearNeedAppearances` has to read the staged-or-base view and accept an
 * `/AcroForm` written straight into the catalog, the two shapes its
 * neighbours (ledger 14.8) already handle.
 */
class NeedAppearancesTest {

    private fun acroFormOf(doc: PdfDocument): PdfDictionary? =
        when (val a = doc.catalog["AcroForm"]) {
            is PdfDictionary -> a
            is PdfReference -> doc.resolve(a) as? PdfDictionary
            else -> null
        }

    @Test
    fun clears_need_appearances_written_straight_into_the_catalog() {
        val doc = KitePDF.open(buildFormPdf(inlineAcroForm = true))
        val out = doc.edit().apply {
            setTextFieldValue(doc.formField("FullName")!!, "Ada")
        }.saveIncremental()

        val acro = assertNotNull(acroFormOf(KitePDF.open(out)))
        assertEquals(false, (acro["NeedAppearances"] as? PdfBoolean)?.value)
    }

    @Test
    fun clearing_does_not_clobber_a_staged_acroform() {
        val doc = KitePDF.open(buildFormPdf(inlineAcroForm = false, secondField = true))
        val out = doc.edit().apply {
            // Redacting the second widget stages a pruned /AcroForm /Fields.
            redactRegion(doc.pages[0], KiteRectangle(90.0, 590.0, 410.0, 640.0))
            // The fill must not resurrect it by staging the base AcroForm wholesale.
            setTextFieldValue(doc.formField("FullName")!!, "Ada")
        }.saveRewritten()

        val reopened = KitePDF.open(out)
        assertEquals(listOf("FullName"), reopened.formFields.map { it.fullyQualifiedName })
        val acro = assertNotNull(acroFormOf(reopened))
        assertEquals(false, (acro["NeedAppearances"] as? PdfBoolean)?.value)
    }

    /**
     * One- or two-field AcroForm with `/NeedAppearances true`, either as its
     * own object or written inline into the catalog.
     */
    private fun buildFormPdf(inlineAcroForm: Boolean, secondField: Boolean = false): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())

        val fields = if (secondField) "[5 0 R 6 0 R]" else "[5 0 R]"
        val acro = "<< /Fields $fields /NeedAppearances true /DA (/Helv 12 Tf 0 g) /DR << /Font << /Helv 4 0 R >> >> >>"
        val annots = if (secondField) "[5 0 R 6 0 R]" else "[5 0 R]"

        val acroNum = if (secondField) 7 else 6
        write("%PDF-1.7\n%âãÏÓ\n")
        offsets.add(buf.size())
        val catalogAcro = if (inlineAcroForm) acro else "$acroNum 0 R"
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /AcroForm $catalogAcro >>\nendobj\n")
        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Annots $annots /Resources << >> >>\nendobj\n")
        offsets.add(buf.size())
        write("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        offsets.add(buf.size())
        write("5 0 obj\n<< /Type /Annot /Subtype /Widget /FT /Tx /T (FullName) /Rect [100 700 400 720] /DA (/Helv 12 Tf 0 g) /P 3 0 R /F 4 >>\nendobj\n")
        if (secondField) {
            offsets.add(buf.size())
            write("6 0 obj\n<< /Type /Annot /Subtype /Widget /FT /Tx /T (Secret) /Rect [100 600 400 620] /DA (/Helv 12 Tf 0 g) /P 3 0 R /F 4 >>\nendobj\n")
        }
        if (!inlineAcroForm) {
            offsets.add(buf.size())
            write("$acroNum 0 obj\n$acro\nendobj\n")
        }

        val size = offsets.size + 1
        val xref = buf.size()
        write("xref\n0 $size\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
