package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.filters.FilterChain
import com.yuroyami.kitepdf.parser.IndirectResolver
import com.yuroyami.kitepdf.parser.NameTreeWalker
import com.yuroyami.kitepdf.parser.PdfDictionary
import com.yuroyami.kitepdf.parser.PdfInt
import com.yuroyami.kitepdf.parser.PdfReference
import com.yuroyami.kitepdf.parser.PdfStream
import com.yuroyami.kitepdf.parser.PdfString

/**
 * One embedded file attachment from the document — ISO 32000-1 §7.11.4.
 *
 * Attachments live in the catalog's `/Names /EmbeddedFiles` name tree
 * (PDF 1.4+) and/or hang off file-attachment annotations. Each one is a
 * FileSpec dict pointing at one or more embedded-file streams; the
 * preferred one is keyed by `/UF` (Unicode filename) with `/F` as fallback.
 *
 * We resolve the bytes lazily — large attachments shouldn't be decoded
 * until the caller asks for them.
 */
data class PdfAttachment(
    /** Name-tree key (often the same as [filename] but not always). */
    val name: String,
    /** Author-supplied filename. */
    val filename: String,
    /** Optional user-readable description (/Desc). */
    val description: String?,
    /** MIME type (/EF /F /Subtype). null if not declared. */
    val mimeType: String?,
    /** Reported size in bytes. May be `null` if /Params /Size is absent. */
    val size: Long?,
    /** Pulls and decodes the embedded stream lazily. Empty array on failure. */
    val bytesProvider: () -> ByteArray,
) {
    /** Convenience for callers who want eager-but-cached bytes. */
    val bytes: ByteArray by lazy { bytesProvider() }

    companion object {
        internal fun parseAll(
            catalog: PdfDictionary,
            refs: IndirectResolver,
        ): List<PdfAttachment> {
            val names = catalog.getDict("Names", refs) ?: return emptyList()
            val embeddedFiles = names.getDict("EmbeddedFiles", refs) ?: return emptyList()
            val entries = NameTreeWalker.collect(embeddedFiles, refs)
            val out = mutableListOf<PdfAttachment>()
            for ((key, value) in entries) {
                val fileSpec = when (value) {
                    is PdfReference -> refs.resolve(value) as? PdfDictionary
                    is PdfDictionary -> value
                    else -> null
                } ?: continue
                out += build(key, fileSpec, refs)
            }
            return out
        }

        private fun build(
            nameKey: String,
            fileSpec: PdfDictionary,
            refs: IndirectResolver,
        ): PdfAttachment {
            val filename = (fileSpec["UF"] as? PdfString)?.asText()
                ?: (fileSpec["F"] as? PdfString)?.asText()
                ?: nameKey
            val description = (fileSpec["Desc"] as? PdfString)?.asText()

            // /EF carries the actual stream(s). Preferred order: UF, F, DOS, Mac, Unix.
            val efDict = fileSpec.getDict("EF", refs)
            val streamObj = efDict?.let {
                it["UF"] ?: it["F"] ?: it["DOS"] ?: it["Mac"] ?: it["Unix"]
            }
            val stream = when (streamObj) {
                is PdfReference -> refs.resolve(streamObj) as? PdfStream
                is PdfStream -> streamObj
                else -> null
            }
            val mimeType = stream?.dict?.getName("Subtype")?.replace("#2F", "/")
            val size = stream?.dict?.getDict("Params", refs)
                ?.let { it["Size"] as? PdfInt }?.value

            return PdfAttachment(
                name = nameKey,
                filename = filename,
                description = description,
                mimeType = mimeType,
                size = size,
                bytesProvider = {
                    if (stream == null) ByteArray(0) else FilterChain.decode(stream)
                },
            )
        }
    }
}
