package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.filters.FilterChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A choice field set by the editor shows the option's text, not its export value, and a list
 * box draws its rows (ISO 32000-1, 12.7.4.4; #700).
 */
class ChoiceValueAppearanceTest {

    private fun pdf(field: String): ByteArray = TestPdf.build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] /DA (/Helv 10 Tf 0 g) >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Annots [4 0 R] >>",
            "<< /Type /Annot /Subtype /Widget /FT /Ch /P 3 0 R /DA (/Helv 10 Tf 0 g) $field >>",
        ),
    )

    private fun shown(pdf: ByteArray): String =
        FilterChain.decode(KitePDF.open(pdf).pages[0].annotations.first().appearanceStream!!).decodeToString()

    @Test
    fun comboShowsTheLabelOfTheExportValue() {
        val doc = KitePDF.open(pdf("/T (Country) /Ff 131072 /Rect [10 10 160 30] /Opt [[(US) (United States)] [(FR) (France)]]"))
        val out = doc.edit().apply { setChoiceValue(doc.formField("Country")!!, "FR") }.saveIncremental()
        val content = shown(out)
        assertTrue("(France) Tj" in content && "(FR)" !in content, content)
        assertEquals("FR", KitePDF.open(out).formField("Country")!!.value)
    }

    @Test
    fun listBoxDrawsEveryRowAndHighlightsTheSelection() {
        val doc = KitePDF.open(pdf("/T (Fruit) /Rect [10 10 160 70] /Opt [(Apple) (Banana) (Cherry)]"))
        val out = doc.edit().apply { setChoiceValue(doc.formField("Fruit")!!, "Banana") }.saveIncremental()
        val content = shown(out)
        for (row in listOf("Apple", "Banana", "Cherry")) assertTrue("($row) Tj" in content, content)
        assertTrue(" re f" in content, "the selected row is highlighted: $content")
    }
}
