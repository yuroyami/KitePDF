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
object KitePDF {

    /** Library version (kept in sync with the Gradle group/version). */
    const val VERSION = "0.1.0"

    /** Parse [bytes] as a PDF document. See [PdfDocument.open] for the password overload. */
    fun open(bytes: ByteArray): PdfDocument = PdfDocument.open(bytes)
}
