package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import kotlin.math.ceil

/**
 * Prints a document from a web page (#951): each page drawn onto a canvas of its own through
 * [Canvas2dCanvas], in an element that only the print shows, and then the browser's
 * `window.print()`. A PDF page draws as it prints ([PdfPage.renderForPrint]); a page of another
 * format draws as it shows.
 *
 * The browser's print dialog sets the paper, the copies and the pages. Each canvas is laid out at
 * its page's size in points, with a page break after it, and drawn at [dpi] so that text prints
 * sharp: 150 dots per inch by default, which is about 4 MB of canvas for a Letter page.
 *
 * ```kotlin
 * KiteBrowserPrint.print(doc)
 * ```
 */
public object KiteBrowserPrint {

    /** The class of the element [print] adds to the page, which a stylesheet can style further. */
    public const val CONTAINER_CLASS: String = "kitepdf-print"

    /**
     * An element with one canvas for each page of [document], drawn at [dpi], each sized in points
     * and followed by a page break. [print] adds it to the page; a host that prints in its own way
     * can do so too.
     */
    public fun printableElement(document: KiteDocument, dpi: Double = 150.0, formState: PdfFormState? = null): HTMLElement {
        require(dpi > 0.0) { "dpi must be positive, not $dpi" }
        val container = kotlinx.browser.document.createElement("div") as HTMLElement
        container.className = CONTAINER_CLASS
        val scale = dpi / 72.0
        for (page in document.pages) {
            val w = page.displayWidth
            val h = page.displayHeight
            val canvas = kotlinx.browser.document.createElement("canvas") as HTMLCanvasElement
            canvas.width = maxOf(1, ceil(w * scale).toInt())
            canvas.height = maxOf(1, ceil(h * scale).toInt())
            canvas.style.width = "${w}pt"
            canvas.style.height = "${h}pt"
            canvas.style.display = "block"
            canvas.style.setProperty("break-after", "page")
            canvas.style.setProperty("page-break-after", "always")
            val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
            ctx.fillStyle = "white"
            ctx.fillRect(0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble())
            val ctm = KiteMatrix.scaling(scale, scale).concat(page.displayToDeviceBase())
            val target = Canvas2dCanvas(ctx)
            if (page is PdfPage) page.renderForPrint(target, ctm, formState) else page.renderTo(target, ctm)
            container.appendChild(canvas)
        }
        return container
    }

    /**
     * Prints [document] with the browser's print dialog. While the dialog is open the page shows
     * only the document's pages, which a print stylesheet hides on screen; both go when the
     * print ends, or at once if the browser never says it ended.
     */
    public fun print(document: KiteDocument, dpi: Double = 150.0, formState: PdfFormState? = null) {
        val container = printableElement(document, dpi, formState)
        val style = kotlinx.browser.document.createElement("style") as HTMLElement
        style.textContent = PRINT_STYLE
        val body = kotlinx.browser.document.body ?: return
        body.appendChild(style)
        body.appendChild(container)
        var cleaned = false
        fun cleanUp() {
            if (cleaned) return
            cleaned = true
            container.parentNode?.removeChild(container)
            style.parentNode?.removeChild(style)
        }
        window.addEventListener("afterprint", { cleanUp() })
        try {
            window.print()
        } finally {
            // Browsers whose print() returns once the dialog closes get no afterprint the page can wait for.
            window.setTimeout({ cleanUp() }, 1000)
        }
    }

    private val PRINT_STYLE = """
        @media screen { .$CONTAINER_CLASS { display: none !important; } }
        @media print {
          body > *:not(.$CONTAINER_CLASS) { display: none !important; }
          .$CONTAINER_CLASS { display: block !important; }
          @page { margin: 0; }
        }
    """.trimIndent()
}
