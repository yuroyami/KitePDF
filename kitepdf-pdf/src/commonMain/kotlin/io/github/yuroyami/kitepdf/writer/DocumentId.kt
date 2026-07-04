package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.crypto.Md5
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfString

/**
 * Generates a document `/ID` (ISO 32000-1 §14.4) — a two-element array of
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
}
