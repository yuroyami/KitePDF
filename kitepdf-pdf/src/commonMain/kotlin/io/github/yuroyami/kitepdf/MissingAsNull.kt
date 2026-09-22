package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.PdfFormatException

/**
 * ISO 32000-1, 7.3.10: a reference to a missing object is the null object,
 * never an error. Reads through the throwing resolvers use this, so a broken
 * entry drops out instead of failing the page (#252).
 */
internal inline fun <T> missingAsNull(read: () -> T?): T? =
    try { read() } catch (_: PdfFormatException) { null }
