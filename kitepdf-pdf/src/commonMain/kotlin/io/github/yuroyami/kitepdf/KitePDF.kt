package io.github.yuroyami.kitepdf

/**
 * One-call entry point. Equivalent to [PdfDocument.open] — exists so
 * `KitePDF.open(bytes)` reads nicely at call sites. For passwords, encryption
 * checks, editing, etc. use [PdfDocument] directly.
 *
 * ```
 * val doc = KitePDF.open(bytes)
 * for (page in doc.pages) println(page.extractText())
 * ```
 */
public object KitePDF {

    /** Library version (kept in sync with the Gradle group/version). */
    public const val VERSION: String = "0.1.1"

    /** Parse [bytes] as a PDF document. See [PdfDocument.open] for the password overload. */
    public fun open(bytes: ByteArray): PdfDocument = PdfDocument.open(bytes)
}
