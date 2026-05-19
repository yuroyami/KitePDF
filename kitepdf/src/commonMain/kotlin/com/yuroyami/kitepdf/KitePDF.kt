package com.yuroyami.kitepdf

/**
 * KitePDF — a pure-Kotlin Multiplatform PDF library.
 *
 * Convenience facade. The full API lives on [PdfDocument] and [PdfPage].
 *
 *   val doc = KitePDF.open(bytes)
 *   for (page in doc.pages) println(page.extractText())
 *
 * No external runtime dependencies — only kotlin-stdlib.
 */
object KitePDF {

    /** Library version (kept in sync with the Gradle group/version). */
    const val VERSION = "0.0.1-SNAPSHOT"

    fun open(bytes: ByteArray): PdfDocument = PdfDocument.open(bytes)
}
