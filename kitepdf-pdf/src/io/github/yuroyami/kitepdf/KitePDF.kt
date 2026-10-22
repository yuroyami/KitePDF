package io.github.yuroyami.kitepdf

/**
 * Library-level constants.
 *
 * This used to carry a one-call `open`, which read as the entry point for the
 * whole library while only ever returning a [PdfDocument]. Use
 * [PdfDocument.open] for a PDF, `EpubDocument.open` for a book, or
 * `KiteDoc.open` from the `kitepdf` umbrella artifact when you do not know
 * which of the two you have.
 */
public object KitePDF {

    /** Library version, the same as the version of the published artifacts. */
    public const val VERSION: String = KITEPDF_VERSION

    @Deprecated(
        "KitePDF.open only ever opened PDFs. Use PdfDocument.open for a PDF, or " +
            "KiteDoc.open (io.github.yuroyami.kitepdf.document, in the kitepdf umbrella) " +
            "to open a PDF or an EPUB without knowing which it is.",
        ReplaceWith("PdfDocument.open(bytes)", "io.github.yuroyami.kitepdf.PdfDocument"),
    )
    public fun open(bytes: ByteArray): PdfDocument = PdfDocument.open(bytes)
}
