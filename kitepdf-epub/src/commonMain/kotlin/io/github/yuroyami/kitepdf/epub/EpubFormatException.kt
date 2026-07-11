package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteFormatException

/** Raised when bytes are not a readable EPUB: no container, no OPF, empty spine. */
public class EpubFormatException(
    message: String,
    cause: Throwable? = null,
) : KiteFormatException(message, cause)
