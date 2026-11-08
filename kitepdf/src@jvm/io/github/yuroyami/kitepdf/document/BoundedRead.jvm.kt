package io.github.yuroyami.kitepdf.document

import io.github.yuroyami.kitepdf.core.KiteFormatException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** The bytes of [stream], or a [KiteFormatException] once they pass [maxBytes], before memory runs out (#829). */
internal fun readBounded(stream: InputStream, maxBytes: Int, what: String): ByteArray {
    require(maxBytes >= 0) { "maxBytes is 0 or more, not $maxBytes" }
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = stream.read(buffer)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw tooLarge(what, maxBytes)
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** The bytes of [file], refused before a byte is read when its length passes [maxBytes]. */
internal fun readBounded(file: File, maxBytes: Int): ByteArray {
    if (file.length() > maxBytes) throw tooLarge("the file ${file.path} (${file.length()} bytes)", maxBytes)
    return file.inputStream().use { readBounded(it, maxBytes, "the file ${file.path}") }
}

internal fun tooLarge(what: String, maxBytes: Int): KiteFormatException =
    KiteFormatException("$what is larger than $maxBytes bytes, the limit of this call; pass a larger maxBytes to open it")
