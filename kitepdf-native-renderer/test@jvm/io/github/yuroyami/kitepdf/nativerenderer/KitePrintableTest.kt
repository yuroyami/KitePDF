package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.print.PageFormat
import java.awt.print.Paper
import java.awt.print.Printable
import java.awt.print.PrinterJob
import java.io.ByteArrayOutputStream
import javax.print.DocFlavor
import javax.print.StreamPrintServiceFactory
import javax.print.attribute.standard.Copies
import javax.print.attribute.standard.PageRanges
import javax.print.attribute.standard.Sides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A document prints through `java.awt.print` as it should print, not as it shows (#951): a
 * print-only layer and annotation appear and a screen-only one does not (ISO 32000-1, 8.11.4.4 and
 * 12.5.3), the file's print preferences become the job's attributes (12.2), and a whole job reaches
 * a PostScript print service, one page each.
 */
class KitePrintableTest {

    /**
     * Page one, 200 by 200: a blue square on the layer that only prints, a red one on the layer that
     * only shows, and three square annotations, green that only prints (NoView and Print), magenta
     * that only shows (no Print flag) and black that does both. Page two is landscape.
     */
    private fun fixture(): ByteArray {
        fun square(r: Double, g: Double, b: Double) = "$r $g $b rg 0 0 40 40 re f"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R /OCProperties << /OCGs [5 0 R 6 0 R] /D << /Order [5 0 R 6 0 R] /OFF [5 0 R] " +
                "/AS [<< /Event /Print /OCGs [5 0 R 6 0 R] /Category [/Print] >>] >> >> " +
                "/ViewerPreferences << /NumCopies 3 /Duplex /DuplexFlipLongEdge /PrintPageRange [1 1] >> >>",
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

    private fun stream(content: String) = "<< /Length ${content.length} >>\nstream\n$content\nendstream"
    private fun formStream(content: String) = "<< /Type /XObject /Subtype /Form /BBox [0 0 40 40] /Length ${content.length} >>\nstream\n$content\nendstream"

    private fun format(size: Double) = PageFormat().apply {
        paper = Paper().apply { setSize(size, size); setImageableArea(0.0, 0.0, size, size) }
    }

    private fun printed(doc: PdfDocument): BufferedImage {
        val image = BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 200, 200)
        assertEquals(Printable.PAGE_EXISTS, KitePrintable(doc, format(200.0)).print(g, format(200.0), 0))
        g.dispose()
        return image
    }

    /** The colour at a point of the page, in PDF space with y up. */
    private fun BufferedImage.at(x: Int, y: Int): Int = getRGB(x, 199 - y) and 0xFFFFFF

    @Test
    fun a_page_prints_its_print_layers_and_annotations_and_shows_its_screen_ones() {
        val doc = PdfDocument.open(fixture())
        val screen = AwtPdfRasterizer.renderToImage(doc.pages[0], 1.0)
        val print = printed(doc)
        assertEquals(0xFFFFFF, screen.at(30, 30), "the print-only layer shows on screen")
        assertEquals(0x0000FF, print.at(30, 30), "the print-only layer prints")
        assertEquals(0xFF0000, screen.at(80, 30), "the screen-only layer shows")
        assertEquals(0xFFFFFF, print.at(80, 30), "the screen-only layer prints")
        assertEquals(0xFFFFFF, screen.at(130, 130), "the NoView annotation shows")
        assertEquals(0x00FF00, print.at(130, 130), "the NoView and Print annotation prints")
        assertEquals(0xFF00FF, screen.at(130, 30), "an annotation without the Print flag shows")
        assertEquals(0xFFFFFF, print.at(130, 30), "an annotation without the Print flag prints")
        assertEquals(0x000000, screen.at(30, 130), "the Print annotation shows")
        assertEquals(0x000000, print.at(30, 130), "the Print annotation prints")
        assertEquals(Printable.NO_SUCH_PAGE, KitePrintable(doc).print(BufferedImage(1, 1, 1).createGraphics(), format(200.0), 2))
    }

    @Test
    fun the_files_print_preferences_become_the_jobs_attributes() {
        val doc = PdfDocument.open(fixture())
        val set = KitePrintable.attributesOf(doc)
        assertEquals(3, (set.get(Copies::class.java) as Copies).value)
        assertEquals(Sides.TWO_SIDED_LONG_EDGE, set.get(Sides::class.java))
        assertEquals(PageRanges(1, 1), set.get(PageRanges::class.java))
        val pageable = KitePrintable(doc, format(400.0))
        assertEquals(2, pageable.numberOfPages)
        assertEquals(PageFormat.PORTRAIT, pageable.getPageFormat(0).orientation)
        assertEquals(PageFormat.LANDSCAPE, pageable.getPageFormat(1).orientation, "a wide page turns")
    }

    @Test
    fun a_job_prints_each_page_to_a_postscript_service() {
        val factory = StreamPrintServiceFactory.lookupStreamPrintServiceFactories(DocFlavor.SERVICE_FORMATTED.PAGEABLE, "application/postscript")
        assertTrue(factory.isNotEmpty(), "the JDK has a PostScript stream print service")
        val out = ByteArrayOutputStream()
        val job = PrinterJob.getPrinterJob()
        job.printService = factory[0].getPrintService(out)
        job.setPageable(KitePrintable(PdfDocument.open(fixture()), job.defaultPage()))
        job.print()
        val ps = out.toByteArray().decodeToString()
        assertTrue(ps.startsWith("%!PS"), ps.take(40))
        assertEquals(2, Regex("""(?m)^%%Page: """).findAll(ps).count(), "one PostScript page for each page")
    }
}
