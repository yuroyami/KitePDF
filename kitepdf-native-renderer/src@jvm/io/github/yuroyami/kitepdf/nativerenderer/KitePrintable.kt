package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.PdfViewerPreferences
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import java.awt.print.PageFormat
import java.awt.print.Pageable
import java.awt.print.Printable
import java.awt.print.PrinterJob
import javax.print.attribute.HashPrintRequestAttributeSet
import javax.print.attribute.PrintRequestAttributeSet
import javax.print.attribute.standard.Copies
import javax.print.attribute.standard.PageRanges
import javax.print.attribute.standard.Sides

/**
 * Prints a document with `java.awt.print` (#951): a [Pageable] whose pages draw through
 * [AwtCanvas] onto the printer's own `Graphics2D`, so text, paths and shadings stay vector and
 * only images go as pixels.
 *
 * Each page is centred in the imageable area of [pageFormat], turned to landscape when the page is
 * wider than it is tall, and scaled down to fit it, unless [fit] is false, which prints at 100%.
 * A PDF page draws as it prints ([PdfPage.renderForPrint]): annotations by their Print flag, the
 * layers in their print states and the form from [formState]. A page of another format draws as
 * it shows.
 *
 * ```kotlin
 * val job = PrinterJob.getPrinterJob()
 * job.setPageable(KitePrintable(doc, job.defaultPage()))
 * val attributes = KitePrintable.attributesOf(doc)
 * if (job.printDialog(attributes)) job.print(attributes)
 * ```
 */
public class KitePrintable(
    private val document: KiteDocument,
    private val pageFormat: PageFormat = PageFormat(),
    private val formState: PdfFormState? = null,
    /** False prints each page at 100%, as `/PrintScaling /None` asks; the default follows the file (ISO 32000-1, 12.2). */
    private val fit: Boolean = (document as? PdfDocument)?.viewerPreferences?.printScaling != PdfViewerPreferences.PrintScaling.None,
) : Pageable, Printable {

    override fun getNumberOfPages(): Int = document.pageCount

    /** [pageFormat], in landscape for a page that is wider than it is tall. */
    override fun getPageFormat(pageIndex: Int): PageFormat {
        if (pageIndex !in 0 until numberOfPages) throw IndexOutOfBoundsException("page $pageIndex of $numberOfPages")
        val page = document.pages[pageIndex]
        val format = pageFormat.clone() as PageFormat
        val wide = page.displayWidth > page.displayHeight
        format.orientation = if (wide) PageFormat.LANDSCAPE else PageFormat.PORTRAIT
        return format
    }

    override fun getPrintable(pageIndex: Int): Printable = this

    override fun print(graphics: Graphics, format: PageFormat, pageIndex: Int): Int {
        if (pageIndex !in 0 until numberOfPages) return Printable.NO_SUCH_PAGE
        val page = document.pages[pageIndex]
        val w = page.displayWidth
        val h = page.displayHeight
        if (w <= 0.0 || h <= 0.0) return Printable.PAGE_EXISTS
        val g = graphics.create() as Graphics2D
        try {
            val area = Rectangle2D.Double(format.imageableX, format.imageableY, format.imageableWidth, format.imageableHeight)
            val scale = if (fit) minOf(area.width / w, area.height / h) else 1.0
            val dx = area.x + (area.width - w * scale) / 2
            val dy = area.y + (area.height - h * scale) / 2
            g.clip(area)
            val ctm = KiteMatrix.translation(dx, dy).concat(KiteMatrix.scaling(scale, scale).concat(page.displayToDeviceBase()))
            val canvas = AwtCanvas(g)
            if (page is PdfPage) page.renderForPrint(canvas, ctm, formState) else page.renderTo(canvas, ctm)
        } finally {
            g.dispose()
        }
        return Printable.PAGE_EXISTS
    }

    public companion object {
        /**
         * The print request attributes [document] asks for in its viewer preferences (ISO 32000-1,
         * 12.2, Table 150): `/NumCopies` as [Copies], `/Duplex` as [Sides] and `/PrintPageRange`
         * as [PageRanges], each only when the file sets it. A print dialog shows them as its
         * defaults, and the reader can change them.
         */
        public fun attributesOf(document: KiteDocument): PrintRequestAttributeSet {
            val set = HashPrintRequestAttributeSet()
            val prefs = (document as? PdfDocument)?.viewerPreferences ?: return set
            if (prefs.numCopies > 1) set.add(Copies(prefs.numCopies))
            when (prefs.duplex) {
                PdfViewerPreferences.Duplex.DuplexFlipLongEdge -> set.add(Sides.TWO_SIDED_LONG_EDGE)
                PdfViewerPreferences.Duplex.DuplexFlipShortEdge -> set.add(Sides.TWO_SIDED_SHORT_EDGE)
                PdfViewerPreferences.Duplex.Simplex -> {}
            }
            // Both count pages from 1.
            val ranges = prefs.printPageRange.filter { it.first >= 1 && it.last >= it.first }
            if (ranges.isNotEmpty()) set.add(PageRanges(Array(ranges.size) { intArrayOf(ranges[it].first, ranges[it].last) }))
            return set
        }

        /**
         * Prints [document] on [job]: with its print dialog first when [dialog] is true, which
         * the reader can cancel, else at once on the job's printer. Returns false when the reader
         * cancelled. The file's own print preferences are the dialog's defaults.
         */
        public fun print(document: KiteDocument, job: PrinterJob = PrinterJob.getPrinterJob(), dialog: Boolean = true, formState: PdfFormState? = null): Boolean {
            val attributes = attributesOf(document)
            job.setPageable(KitePrintable(document, job.defaultPage(), formState))
            if (dialog && !job.printDialog(attributes)) return false
            job.print(attributes)
            return true
        }
    }
}
