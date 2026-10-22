package io.github.yuroyami.kitepdf.core

/**
 * Raised when a document is encrypted and the supplied password did not
 * authenticate. PDF standard security raises it today; the type is
 * format-neutral so a future DRM-aware handler can raise the same one.
 */
public class KiteWrongPasswordException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@Deprecated(
    "Renamed to KiteWrongPasswordException",
    ReplaceWith("KiteWrongPasswordException", "io.github.yuroyami.kitepdf.core.KiteWrongPasswordException"),
)
public typealias WrongPasswordException = KiteWrongPasswordException
