package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream

/**
 * The `/Contents` concatenation rule (ISO 32000-1, 7.8.2), in one place for
 * [PdfPage.contentBytes] and the editor's staged-or-base view, which differ
 * only in how a reference resolves.
 */
internal object PageContents {

    /**
     * Decoded page content. An array's members concatenate with a newline
     * between them; a member that will not resolve or decode is skipped, so
     * one bad chunk cannot blank the page. Returns null for a `/Contents`
     * value of an illegal type, so the caller picks throw or empty.
     */
    fun concatenated(
        contents: PdfObject?,
        resolveStream: (PdfReference) -> PdfStream?,
    ): ByteArray? = when (contents) {
        null -> ByteArray(0)
        is PdfReference -> resolveStream(contents)?.let(::decodeOrNull) ?: ByteArray(0)
        is PdfStream -> decodeOrNull(contents) ?: ByteArray(0)
        is PdfArray -> {
            val buf = ByteArrayBuilder(4096)
            var first = true
            for (part in contents) {
                val ref = part as? PdfReference ?: continue
                val bytes = resolveStream(ref)?.let(::decodeOrNull) ?: continue
                if (!first) buf.append('\n'.code.toByte())
                buf.append(bytes)
                first = false
            }
            buf.toByteArray()
        }
        else -> null
    }

    private fun decodeOrNull(stream: PdfStream): ByteArray? =
        runCatching { FilterChain.decode(stream) }.getOrNull()
}
