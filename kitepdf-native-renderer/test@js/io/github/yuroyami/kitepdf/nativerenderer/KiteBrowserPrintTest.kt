package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The element the browser prints (#951): a canvas for each page, sized in points and drawn at the
 * resolution asked, with the page drawn as it prints. A print-only layer and annotation appear,
 * and a screen-only one does not (ISO 32000-1, 8.11.4.4 and 12.5.3).
 */
class KiteBrowserPrintTest {

    /** Page one, 200 by 200, as in KitePrintableTest; page two, 300 by 150. */
    private fun fixture(): ByteArray {
        fun square(r: Double, g: Double, b: Double) = "$r $g $b rg 0 0 40 40 re f"
        fun stream(content: String) = "<< /Length ${content.length} >>\nstream\n$content\nendstream"
        fun form(content: String) = "<< /Type /XObject /Subtype /Form /BBox [0 0 40 40] /Length ${content.length} >>\nstream\n$content\nendstream"
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
            form(square(0.0, 1.0, 0.0)),
            form(square(1.0, 0.0, 1.0)),
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 150] /Contents 14 0 R /Resources << >> >>",
            form(square(0.0, 0.0, 0.0)),
            stream("0 0 0 rg 10 10 280 130 re f"),
        )
        var out = "%PDF-1.7\n"
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body ->
            offsets += out.length
            out += "${i + 1} 0 obj\n$body\nendobj\n"
        }
        val xref = out.length
        out += "xref\n0 ${objects.size + 1}\n0000000000 65535 f \n"
        for (o in offsets) out += "${o.toString().padStart(10, '0')} 00000 n \n"
        out += "trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n"
        return out.encodeToByteArray()
    }

    private fun HTMLCanvasElement.context() = getContext("2d") as CanvasRenderingContext2D

    @Test
    fun each_page_gets_a_canvas_drawn_as_it_prints() {
        val element = KiteBrowserPrint.printableElement(PdfDocument.open(fixture()), dpi = 72.0)
        assertEquals(KiteBrowserPrint.CONTAINER_CLASS, element.className)
        assertEquals(2, element.children.length)
        val first = element.children.item(0) as HTMLCanvasElement
        val second = element.children.item(1) as HTMLCanvasElement
        assertEquals(200 to 200, first.width to first.height)
        assertEquals(300 to 150, second.width to second.height)
        assertEquals("200pt", first.style.width)
        assertEquals("page", first.style.getPropertyValue("break-after"))
        val ctx = first.context()
        ctx.assertPixel(30, 30, listOf(0, 0, 255))      // the print-only layer prints
        ctx.assertPixel(80, 30, listOf(255, 255, 255))  // the screen-only layer does not
        ctx.assertPixel(130, 130, listOf(0, 255, 0))    // the NoView and Print annotation prints
        ctx.assertPixel(130, 30, listOf(255, 255, 255)) // an annotation without the Print flag does not
        ctx.assertPixel(30, 130, listOf(0, 0, 0))       // the Print annotation prints
    }

    @Test
    fun the_resolution_sets_the_canvas_size_but_not_its_size_on_paper() {
        val element = KiteBrowserPrint.printableElement(PdfDocument.open(fixture()), dpi = 144.0)
        val first = element.children.item(0) as HTMLCanvasElement
        assertEquals(400 to 400, first.width to first.height)
        assertEquals("200pt", first.style.width)
    }
}
