package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An export of a filled form shows its fields with the values the reader typed: the rasterizer
 * draws them from the form state. The screen raster leaves them out for the form layer, so an
 * export through `onPageRendered` had no fields (#431).
 */
class FormExportTest {

    /** One 200 x 200 page with a text field `name` at display (20..180, 40..80), empty in the file and without an appearance. */
    private fun formPdf(): PdfDocument {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R] >>")
        add("<< /Type /Annot /Subtype /Widget /FT /Tx /T (name) /V () /Rect [20 120 180 160] /DA (/Helv 24 Tf 0 g) >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    private fun rasterizer(): KitePageRasterizer {
        val density = Density(1f)
        return KitePageRasterizer(density, LayoutDirection.Ltr, TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr))
    }

    /** Dark pixels inside the field's box. */
    private fun inkInField(bitmap: ImageBitmap): Int {
        val pixels = bitmap.toPixelMap()
        var ink = 0
        for (x in 20 until 180) for (y in 40 until 80) if (pixels[x, y].red < 0.5f) ink++
        return ink
    }

    @Test
    fun an_export_with_the_form_state_shows_what_the_reader_typed() {
        val doc = formPdf()
        val page = doc.pages[0]
        val form = PdfFormState(doc)
        form.setValue("name", "WWWW")
        val rasterizer = rasterizer()
        assertTrue(inkInField(rasterizer.rasterize(page, 200, 200, formState = form)) > 50, "the typed value is drawn")
        // The file's own empty value, with no appearance to draw.
        assertEquals(0, inkInField(rasterizer.rasterize(page, 200, 200)), "the file's value is empty")
        assertEquals(0, inkInField(rasterizer.rasterize(page, 200, 200, formState = null)), "no form state draws the file's value")
    }
}
