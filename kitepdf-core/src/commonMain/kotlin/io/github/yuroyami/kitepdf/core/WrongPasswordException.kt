package io.github.yuroyami.kitepdf.core

/** Raised when a PDF is encrypted and the supplied password did not authenticate. */
class WrongPasswordException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
