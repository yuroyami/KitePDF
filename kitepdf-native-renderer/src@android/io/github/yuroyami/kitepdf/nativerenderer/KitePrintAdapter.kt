package io.github.yuroyami.kitepdf.nativerenderer

import android.content.Context
import android.graphics.pdf.PdfDocument as AndroidPdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.PdfViewerPreferences
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Prints a document with Android's print framework (#951): a [PrintDocumentAdapter] that draws
 * each page through [AndroidNativeCanvas] onto the canvas of a PDF page the print service
 * receives, so text, paths and shadings stay vector and only images go as pixels.
 *
 * Each page is centred on the paper the reader picked, inside its margins, and scaled down to fit,
 * unless [fit] is false, which prints at 100%. A PDF page draws as it prints
 * ([PdfPage.renderForPrint]): annotations by their Print flag, the layers in their print states
 * and the form from [formState]. A page of another format draws as it shows.
 *
 * ```kotlin
 * KitePrintAdapter.print(context, doc, jobName = "Statement")
 * ```
 */
public class KitePrintAdapter(
    private val document: KiteDocument,
    private val formState: PdfFormState? = null,
    /** False prints each page at 100%, as `/PrintScaling /None` asks; the default follows the file (ISO 32000-1, 12.2). */
    private val fit: Boolean = (document as? PdfDocument)?.viewerPreferences?.printScaling != PdfViewerPreferences.PrintScaling.None,
    private val jobName: String = "document",
) : PrintDocumentAdapter() {

    private var attributes: PrintAttributes? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) return callback.onLayoutCancelled()
        attributes = newAttributes
        val info = PrintDocumentInfo.Builder(fileName(jobName))
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(document.pageCount)
            .build()
        callback.onLayoutFinished(info, newAttributes != oldAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        val media = attributes?.mediaSize ?: PrintAttributes.MediaSize.ISO_A4
        val margins = attributes?.minMargins ?: PrintAttributes.Margins.NO_MARGINS
        val wanted = (0 until document.pageCount).filter { index -> pages.any { it.contains(index) } }
        try {
            FileOutputStream(destination.fileDescriptor).use { out ->
                writePages(document, wanted, Paper.of(media, margins), fit, formState, out) { cancellationSignal.isCanceled }
            }
        } catch (_: CancelledPrint) {
            return callback.onWriteCancelled()
        } catch (e: Exception) {
            return callback.onWriteFailed(e.message)
        }
        callback.onWriteFinished(ranges(wanted))
    }

    /** A sheet in points: its size and the printable area inside its margins. */
    internal class Paper(val width: Int, val height: Int, val left: Double, val top: Double, val right: Double, val bottom: Double) {
        companion object {
            /** [media] in points, 72 to the inch, as Android's mils are thousandths of an inch. */
            fun of(media: PrintAttributes.MediaSize, margins: PrintAttributes.Margins): Paper = Paper(
                mils(media.widthMils).toInt(), mils(media.heightMils).toInt(),
                mils(margins.leftMils), mils(margins.topMils), mils(margins.rightMils), mils(margins.bottomMils),
            )

            private fun mils(value: Int): Double = value * 72.0 / 1000.0
        }
    }

    private class CancelledPrint : Exception()

    public companion object {
        /**
         * Opens Android's print dialog for [document] through [PrintManager]. The reader picks the
         * printer, the paper and the pages, and can cancel.
         */
        public fun print(context: Context, document: KiteDocument, jobName: String = "document", formState: PdfFormState? = null) {
            val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            manager.print(jobName, KitePrintAdapter(document, formState, jobName = jobName), null)
        }

        /**
         * Draws pages [indices] of [document] onto sheets of [paper], one page a sheet, and writes the
         * PDF that the print service takes to [out]. [cancelled] is asked before each page.
         */
        internal fun writePages(
            document: KiteDocument,
            indices: List<Int>,
            paper: Paper,
            fit: Boolean,
            formState: PdfFormState?,
            out: OutputStream,
            cancelled: () -> Boolean = { false },
        ) {
            val pdf = AndroidPdfDocument()
            try {
                for ((sheet, index) in indices.withIndex()) {
                    if (cancelled()) throw CancelledPrint()
                    val page = document.pages[index]
                    val printed = pdf.startPage(AndroidPdfDocument.PageInfo.Builder(paper.width, paper.height, sheet + 1).create())
                    drawSheet(printed.canvas, page, paper, fit, formState)
                    pdf.finishPage(printed)
                }
                pdf.writeTo(out)
            } finally {
                pdf.close()
            }
        }

        /**
         * Draws [page] on [canvas], a sheet of [paper] in points: centred inside the margins, and
         * scaled to fit them when [fit] is true.
         */
        internal fun drawSheet(canvas: android.graphics.Canvas, page: io.github.yuroyami.kitepdf.core.KitePage, paper: Paper, fit: Boolean, formState: PdfFormState?) {
            val w = page.displayWidth
            val h = page.displayHeight
            if (w <= 0.0 || h <= 0.0) return
            val areaW = paper.width - paper.left - paper.right
            val areaH = paper.height - paper.top - paper.bottom
            val scale = if (fit) minOf(areaW / w, areaH / h) else 1.0
            val dx = paper.left + (areaW - w * scale) / 2
            val dy = paper.top + (areaH - h * scale) / 2
            val ctm = KiteMatrix.translation(dx, dy).concat(KiteMatrix.scaling(scale, scale).concat(page.displayToDeviceBase()))
            val kite = AndroidNativeCanvas(canvas)
            if (page is PdfPage) page.renderForPrint(kite, ctm, formState) else page.renderTo(kite, ctm)
        }

        /** [indices], ascending, as the fewest page ranges. */
        internal fun ranges(indices: List<Int>): Array<PageRange> {
            val out = ArrayList<PageRange>()
            var start = -1
            var last = -1
            for (i in indices) {
                if (start >= 0 && i == last + 1) { last = i; continue }
                if (start >= 0) out += PageRange(start, last)
                start = i; last = i
            }
            if (start >= 0) out += PageRange(start, last)
            return out.toTypedArray()
        }

        private fun fileName(job: String): String = job.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "document" } + ".pdf"
    }
}

private fun PageRange.contains(index: Int): Boolean = this == PageRange.ALL_PAGES || index in start..end
