package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.crypto.Md5
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfString

/**
 * Generates a document `/ID` (ISO 32000-1 §14.4), a two-element array of
 * 16-byte strings (permanent + changing). Many validators warn when it is
 * absent, and it is required for encryption key derivation. We derive it
 * deterministically from a content seed via MD5 (its only use here is as a
 * fingerprint, not for security).
 */
internal object DocumentId {
    /** `[<md5(seed)> <md5(seed)>]`. */
    fun generate(seed: ByteArray): PdfArray {
        val h = Md5.hash(seed)
        val s = PdfString(h)
        return PdfArray(listOf(s, s))
    }

    /**
     * The `/ID` of a file that an edit wrote: the first string of [existing], or one made from
     * [original] when there is none, and a second string from [contents], the file as written.
     * ISO 32000-1, 14.4: the first is permanent, and the file key of an encrypted document is made
     * from it, while the second changes with each update (#738). [original] reads the original
     * file, which a document opened from a source fetches again, so it runs only when needed.
     */
    fun updated(existing: PdfObject?, original: () -> ByteArray, contents: ByteArray): PdfArray {
        val first = ((existing as? PdfArray)?.getOrNull(0) as? PdfString) ?: PdfString(Md5.hash(original()))
        return PdfArray(listOf(first, PdfString(Md5.hash(first.bytes + contents))))
    }
}
