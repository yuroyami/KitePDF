package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.font.Encodings
import io.github.yuroyami.kitepdf.core.font.GlyphList

/**
 * Encoders for the two distinct kinds of PDF string the writer produces.
 *
 * They are NOT interchangeable: a *text string* (metadata, outlines, form
 * values, per §7.9.2.2) is decoded by the consumer via UTF-16BE/PDFDocEncoding
 * auto-detection, whereas a *content-stream show string* (the operand of `Tj`)
 * is decoded byte-by-byte through the current font's single-byte encoding, so
 * UTF-16BE there would render as garbage.
 */
internal object PdfText {

    /**
     * Encode a text string (§7.9.2.2): pure-ASCII stays single-byte
     * (PDFDocEncoding-compatible); anything else becomes UTF-16BE with a BOM,
     * which [io.github.yuroyami.kitepdf.core.parser.PdfString.asText] decodes back.
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
     * Encode a content-stream show string for a standard font through WinAnsiEncoding, which the
     * writer's font dictionaries name (ISO 32000-1, Annex D.2): U+2019 is 0x92 and U+20AC 0x80. A
     * character that WinAnsi lacks becomes '?', and so does a C1 control, which WinAnsi would draw
     * as one of its symbols (#670). Showing other Unicode needs an embedded font.
     */
    fun encodeContentString(s: String): ByteArray =
        ByteArray(s.length) { (winAnsiCode(s[it]) ?: '?'.code).toByte() }

    /** The WinAnsiEncoding byte that draws [ch], or null when the encoding has none (#670). */
    fun winAnsiCode(ch: Char): Int? = WIN_ANSI[ch.code]

    private val WIN_ANSI: Map<Int, Int> by lazy {
        val m = HashMap<Int, Int>()
        for (code in 0..255) {
            val name = Encodings.winAnsiEncoding[code] ?: continue
            val cp = GlyphList.unicodeFor(name) ?: continue
            m.getOrPut(cp) { code }
        }
        // Latin-1 and WinAnsi agree from 0xA0 on, where WinAnsi also names a no-break space and a
        // soft hyphen by the glyphs of a space and a hyphen.
        for (code in 0xA0..0xFF) m.getOrPut(code) { code }
        m
    }
}
