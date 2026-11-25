package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.core.KiteDocument
import platform.AppKit.NSPrintCopies
import platform.AppKit.NSPrintInfo
import platform.Foundation.NSNumber
import platform.Foundation.setValue
import platform.PDFKit.PDFDocument as KitPdfDocument
import platform.PDFKit.kPDFPrintPageScaleNone
import platform.PDFKit.kPDFPrintPageScaleToFit

/**
 * Opens the print dialog of macOS for [document], through the print operation of PDFKit over
 * [KiteApplePrint.printData] (#951). Each page is scaled to fit the paper and turned to its
 * orientation, unless the file says `/PrintScaling /None`, and `/NumCopies` sets the default
 * copies (ISO 32000-1, 12.2). Returns false when the PDF cannot be made or the reader cancels.
 *
 * Call it on the main thread.
 */
public fun KiteApplePrint.print(document: KiteDocument, jobName: String = "document", formState: PdfFormState? = null): Boolean {
    val data = printData(document, formState) ?: return false
    val pdf = KitPdfDocument(data = data)
    val info = NSPrintInfo.sharedPrintInfo().copy() as NSPrintInfo
    val copies = (document as? PdfDocument)?.viewerPreferences?.numCopies ?: 1
    val copiesKey = NSPrintCopies
    if (copies > 1 && copiesKey != null) info.dictionary().setValue(NSNumber(int = copies), forKey = copiesKey)
    val scaling = if (printsAtFullSize(document)) kPDFPrintPageScaleNone else kPDFPrintPageScaleToFit
    val operation = pdf.printOperationForPrintInfo(info, scaling, autoRotate = true) ?: return false
    operation.jobTitle = jobName
    operation.showsPrintPanel = true
    return operation.runOperation()
}
