package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.PdfFormatException

/**
 * A read that a broken entry can fail, as null, so the entry drops out instead
 * of failing the page (#252). A reference to a missing object already resolves
 * to the null object (ISO 32000-1, 7.3.10; #581).
 */
internal inline fun <T> missingAsNull(read: () -> T?): T? =
    try { read() } catch (_: PdfFormatException) { null }
