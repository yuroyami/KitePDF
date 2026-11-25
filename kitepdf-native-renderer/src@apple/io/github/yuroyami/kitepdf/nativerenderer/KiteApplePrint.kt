package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.PdfViewerPreferences
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KitePage
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFDataCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGContextBeginPage
import platform.CoreGraphics.CGContextEndPage
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGDataConsumerCreateWithCFData
import platform.CoreGraphics.CGDataConsumerRelease
import platform.CoreGraphics.CGPDFContextClose
import platform.CoreGraphics.CGPDFContextCreate
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData

/**
 * Printing on Apple platforms (#951). [printData] draws each page through [CoreGraphicsCanvas]
 * into a PDF, one sheet a page at the page's own size, and the system's print dialog takes that
 * PDF. So text, paths and shadings stay vector, and only images go as pixels.
 *
 * A PDF page draws as it prints ([PdfPage.renderForPrint]): annotations by their Print flag, the
 * layers in their print states and the form from a [PdfFormState]. A page of another format, such
 * as a laid-out book, draws as it shows.
 *
 * `print` opens the dialog: on iOS through `UIPrintInteractionController`, on macOS through the
 * print operation of PDFKit. tvOS cannot print.
 *
 * ```kotlin
 * KiteApplePrint.print(doc, jobName = "Statement")
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
public object KiteApplePrint {

    /**
     * [document] as a PDF of the pages as they print, one sheet a page at its display size. Null when
     * CoreGraphics cannot make the PDF.
     */
    public fun printData(document: KiteDocument, formState: PdfFormState? = null): NSData? {
        // Core Foundation objects, not casts: Kotlin/Native checks a cast from an Objective-C
        // object to a C pointer at run time, and it throws (#288).
        val data = CFDataCreateMutable(null, 0) ?: return null
        var written = false
        try {
            val consumer = CGDataConsumerCreateWithCFData(data) ?: return null
            try {
                val pdf = CGPDFContextCreate(consumer, null, null) ?: return null
                try {
                    for (page in document.pages) drawSheet(pdf, page, formState)
                    CGPDFContextClose(pdf)
                } finally {
                    CGContextRelease(pdf)
                }
            } finally {
                CGDataConsumerRelease(consumer)
            }
            written = true
        } finally {
            if (!written) CFRelease(data)
        }
        // The NSData takes over the reference to the bytes.
        return CFBridgingRelease(data) as NSData
    }

    /** Whether [document] asks to print at 100% rather than fitted to the paper: `/PrintScaling /None` (ISO 32000-1, 12.2). */
    internal fun printsAtFullSize(document: KiteDocument): Boolean =
        (document as? PdfDocument)?.viewerPreferences?.printScaling == PdfViewerPreferences.PrintScaling.None

    /** One sheet of [pdf], the size of [page], with [page] drawn on it as it prints. */
    private fun drawSheet(pdf: CGContextRef, page: KitePage, formState: PdfFormState?) {
        // A sheet needs a size, so a page with none takes one point.
        val w = page.displayWidth.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
        val h = page.displayHeight.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
        CGContextBeginPage(pdf, CGRectMake(0.0, 0.0, w, h))
        CGContextSaveGState(pdf)
        try {
            // A page's device space runs down and a PDF context's runs up, as for a bitmap (#288).
            CGContextTranslateCTM(pdf, 0.0, h)
            CGContextScaleCTM(pdf, 1.0, -1.0)
            val canvas = CoreGraphicsCanvas(pdf)
            val ctm = page.displayToDeviceBase()
            if (page is PdfPage) page.renderForPrint(canvas, ctm, formState) else page.renderTo(canvas, ctm)
        } finally {
            CGContextRestoreGState(pdf)
            CGContextEndPage(pdf)
        }
    }
}
