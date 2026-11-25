package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A document goes to Apple's print dialog as a PDF of the pages as they print (#951): a
 * print-only layer and annotation appear and a screen-only one does not (ISO 32000-1, 8.11.4.4 and
 * 12.5.3), and each page keeps its size. The fixture is `KitePrintableTest`'s.
 */
@OptIn(ExperimentalForeignApi::class)
class KiteApplePrintTest {

    /**
     * Page one, 200 by 200: a blue square on the layer that only prints, a red one on the layer that
     * only shows, and three square annotations, green that only prints (NoView and Print), magenta
     * that only shows (no Print flag) and black that does both. Page two is landscape.
     */
    private fun fixture(): ByteArray {
        fun square(r: Double, g: Double, b: Double) = "$r $g $b rg 0 0 40 40 re f"
        fun stream(content: String) = "<< /Length ${content.length} >>\nstream\n$content\nendstream"
        fun formStream(content: String) = "<< /Type /XObject /Subtype /Form /BBox [0 0 40 40] /Length ${content.length} >>\nstream\n$content\nendstream"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R /OCProperties << /OCGs [5 0 R 6 0 R] /D << /Order [5 0 R 6 0 R] /OFF [5 0 R] " +
                "/AS [<< /Event /Print /OCGs [5 0 R 6 0 R] /Category [/Print] >>] >> >> >>",
            "<< /Type /Pages /Kids [3 0 R 12 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R " +
                "/Resources << /Properties << /P 5 0 R /S 6 0 R >> >> /Annots [7 0 R 8 0 R 9 0 R] >>",
            stream("/OC /P BDC 0 0 1 rg 10 10 40 40 re f EMC /OC /S BDC 1 0 0 rg 60 10 40 40 re f EMC"),
            "<< /Type /OCG /Name (Print only) /Usage << /Print << /PrintState /ON >> >> >>",
            "<< /Type /OCG /Name (Screen only) /Usage << /Print << /PrintState /OFF >> >> >>",
            "<< /Type /Annot /Subtype /Square /Rect [110 110 150 150] /F 36 /AP << /N 10 0 R >> >>",
            "<< /Type /Annot /Subtype /Square /Rect [110 10 150 50] /F 0 /AP << /N 11 0 R >> >>",
            "<< /Type /Annot /Subtype /Square /Rect [10 110 50 150] /F 4 /AP << /N 13 0 R >> >>",
            formStream(square(0.0, 1.0, 0.0)),
            formStream(square(1.0, 0.0, 1.0)),
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 150] /Contents 14 0 R /Resources << >> >>",
            formStream(square(0.0, 0.0, 0.0)),
            stream("0 0 0 rg 10 10 280 130 re f"),
        )
        val out = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        for ((i, body) in objects.withIndex()) {
            offsets += out.length
            out.append("${i + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) out.append(o.toString().padStart(10, '0')).append(" 00000 n \n")
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().encodeToByteArray()
    }

    @Test
    fun the_print_pdf_holds_each_page_as_it_prints() {
        val data = assertNotNull(KiteApplePrint.printData(PdfDocument.open(fixture())))
        val printed = PdfDocument.open(data.bytes!!.readBytes(data.length.toInt()))
        assertEquals(2, printed.pageCount)
        assertEquals(200.0 to 200.0, printed.pages[0].width to printed.pages[0].height)
        assertEquals(300.0 to 150.0, printed.pages[1].width to printed.pages[1].height)
        val pixels = cgRender(data.bytes!!.readBytes(data.length.toInt()))
        val white = listOf(255, 255, 255)
        pixels.assertPixel(30, 30, listOf(0, 0, 255))   // the print-only layer
        pixels.assertPixel(80, 30, white)               // the screen-only layer
        pixels.assertPixel(130, 130, listOf(0, 255, 0)) // the print-only annotation
        pixels.assertPixel(130, 30, white)              // the screen-only annotation
        pixels.assertPixel(30, 130, listOf(0, 0, 0))    // the annotation that shows and prints
    }
}
