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

    /** Library version. Generated from the Gradle project version at build time. */
    public const val VERSION: String = KITEPDF_VERSION

    /** Parse [bytes] as a PDF document. See [PdfDocument.open] for the password overload. */
    public fun open(bytes: ByteArray): PdfDocument = PdfDocument.open(bytes)
}
