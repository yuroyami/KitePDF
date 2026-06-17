package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder

/**
 * Encoders for the two distinct kinds of PDF string the writer produces.
 *
 * They are NOT interchangeable: a *text string* (metadata, outlines, form
 * values — §7.9.2.2) is decoded by the consumer via UTF-16BE/PDFDocEncoding
 * auto-detection, whereas a *content-stream show string* (the operand of `Tj`)
 * is decoded byte-by-byte through the current font's single-byte encoding, so
 * UTF-16BE there would render as garbage.
 */
internal object PdfText {

    /**
     * Encode a text string (§7.9.2.2): pure-ASCII stays single-byte
     * (PDFDocEncoding-compatible); anything else becomes UTF-16BE with a BOM,
     * which [io.github.yuroyami.kitepdf.parser.PdfString.asText] decodes back.
     */
    fun encodeTextString(s: String): ByteArray {
        if (s.all { it.code < 0x80 }) {
            return ByteArray(s.length) { s[it].code.toByte() }
        }
        val out = ByteArrayBuilder(2 + s.length * 2)
        out.append(0xFE.toByte())
        out.append(0xFF.toByte())
        for (ch in s) {
            out.append((ch.code ushr 8).toByte())
            out.append((ch.code and 0xFF).toByte())
        }
        return out.toByteArray()
    }

    /**
     * Encode a content-stream show string as single-byte codes (the common
     * Latin-1 overlap of WinAnsi/StandardEncoding). Code points above 0xFF
     * can't be represented by a non-embedded simple font, so they become '?'.
     * Showing arbitrary Unicode needs an embedded/Type0 font (a later feature).
     */
    fun encodeContentString(s: String): ByteArray =
        ByteArray(s.length) { (s[it].code.takeIf { c -> c <= 0xFF } ?: '?'.code).toByte() }
}
