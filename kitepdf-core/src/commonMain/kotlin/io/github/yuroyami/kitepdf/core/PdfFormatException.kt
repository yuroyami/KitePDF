package io.github.yuroyami.kitepdf.core

/** Raised for any structural PDF error: bad header, truncated stream, malformed xref, etc. */
public class PdfFormatException(
    message: String,
    cause: Throwable? = null,
) : KiteFormatException(message, cause)
