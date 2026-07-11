package io.github.yuroyami.kitepdf.core

/**
 * Common supertype for "these bytes are not a well-formed document of the
 * format this handler reads" failures. Format handlers throw a subtype
 * ([PdfFormatException], EpubFormatException, ...) with a precise message;
 * callers that prefer null-on-failure use the handler's `openOrNull`.
 */
public open class KiteFormatException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
