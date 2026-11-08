package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A field appearance draws in the /DR font that its /DA names (ISO 32000-1, 12.7.3.3; #707). */
class FieldFontTest {

    /** A text field whose /DA names [fontName], with [drFont] as the /DR font of that name (object 5). */
    private fun pdf(fontName: String, drFont: String): ByteArray = TestPdf.build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] /DR << /Font << /$fontName 5 0 R >> >> >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Annots [4 0 R] >>",
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (Serif) /Rect [10 250 200 270] /DA (/$fontName 12 Tf 0 g) /P 3 0 R >>",
            drFont,
        ),
    )

    private fun fontOfAppearance(pdf: ByteArray, name: String): Pair<Any?, String> {
        val doc = KitePDF.open(pdf)
        val out = doc.edit().apply { setTextFieldValue(doc.formField("Serif")!!, "Serif text") }.saveIncremental()
        val ap = KitePDF.open(out).pages[0].annotations.first().appearanceStream!!
        val fonts = (ap.dict["Resources"] as PdfDictionary)["Font"] as PdfDictionary
        return fonts[name] to FilterChain.decode(ap).decodeToString()
    }

    @Test
    fun theAppearanceReferencesTheDrFont() {
        val (font, content) = fontOfAppearance(pdf("TiRo", "<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding /WinAnsiEncoding >>"), "TiRo")
        assertEquals(5L, (font as? PdfReference)?.objectNumber, "the Times font of /DR, not a new Helvetica")
        assertTrue("/TiRo 12 Tf" in content, content)
    }

    @Test
    fun aFontTheWriterCannotEncodeForFallsBackToHelvetica() {
        val type0 = "<< /Type /Font /Subtype /Type0 /BaseFont /SomeCJK /Encoding /Identity-H /DescendantFonts [] >>"
        val (font, _) = fontOfAppearance(pdf("CJK", type0), "CJK")
        val ref = font as PdfReference
        assertTrue(ref.objectNumber != 5L, "a new font object")
    }
}
