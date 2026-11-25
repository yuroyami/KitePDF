package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfViewerPreferences
import io.github.yuroyami.kitepdf.core.KiteDocument
import platform.UIKit.UIPrintInfo
import platform.UIKit.UIPrintInfoDuplex
import platform.UIKit.UIPrintInfoOutputType
import platform.UIKit.UIPrintInteractionController

/**
 * Opens the print dialog of iOS for [document], through `UIPrintInteractionController` with
 * [KiteApplePrint.printData] as its item (#951). The reader picks the printer, the paper, the
 * pages and the copies, and can cancel. `/Duplex` of the file sets the default sides (ISO
 * 32000-1, 12.2). Returns false when the device cannot print or the PDF cannot be made.
 *
 * Call it on the main thread.
 */
public fun KiteApplePrint.print(document: KiteDocument, jobName: String = "document", formState: PdfFormState? = null): Boolean {
    if (!UIPrintInteractionController.isPrintingAvailable()) return false
    val data = printData(document, formState) ?: return false
    val info = UIPrintInfo.printInfo()
    info.jobName = jobName
    info.outputType = UIPrintInfoOutputType.UIPrintInfoOutputGeneral
    info.duplex = when ((document as? PdfDocument)?.viewerPreferences?.duplex) {
        PdfViewerPreferences.Duplex.DuplexFlipLongEdge -> UIPrintInfoDuplex.UIPrintInfoDuplexLongEdge
        PdfViewerPreferences.Duplex.DuplexFlipShortEdge -> UIPrintInfoDuplex.UIPrintInfoDuplexShortEdge
        else -> UIPrintInfoDuplex.UIPrintInfoDuplexNone
    }
    val controller = UIPrintInteractionController.sharedPrintController()
    controller.printInfo = info
    controller.printingItem = data
    return controller.presentAnimated(true, completionHandler = null)
}
