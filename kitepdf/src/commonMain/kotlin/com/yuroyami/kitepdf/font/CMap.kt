package com.yuroyami.kitepdf.font

import com.yuroyami.kitepdf.core.ByteReader
import com.yuroyami.kitepdf.core.PdfFormatException
import com.yuroyami.kitepdf.parser.Lexer
import com.yuroyami.kitepdf.parser.Token

/**
 * CMap parser for PDF `/ToUnicode` streams (ISO 32000-1 §9.10.3, Adobe Tech Note 5014).
 *
 * A ToUnicode CMap is a small PostScript-ish program that tells the reader
 * "given the source character codes I show in this content stream, here are
 * the unicode codepoints to use when extracting/copying text." It uses two
 * section types we care about:
 *
 *   begincodespacerange  …  endcodespacerange       — declares 1-byte vs 2-byte codes
 *   beginbfchar          …  endbfchar               — `<src> <utf16BE-bytes>` pairs
 *   beginbfrange         …  endbfrange              — `<srcLo> <srcHi> <utf16BE-start>`
 *                                                   — `<srcLo> <srcHi> [ <utf> <utf> … ]`
 *
 * Source codes are 1-byte (most simple fonts), 2-byte (CIDFonts, Identity-H),
 * or rarely 3/4-byte. Destination codes are always UTF-16BE byte strings,
 * possibly multi-character for ligatures.
 *
 * We mirror MuPDF's pdf-cmap-parse.c approach: tokenize with our regular PDF
 * [Lexer], then walk section keywords (`bfchar`, `bfrange`, `codespacerange`).
 * Anything outside known sections (CIDSystemInfo, /Registry strings, etc.) is
 * silently skipped — robustness over strictness, per the spec recommendation.
 */
class CMap private constructor(
    /** Maximum byte length of one source code (1–4). Inferred from codespacerange. */
    val codeWidth: Int,
    private val bfChars: Map<Int, String>,
    private val bfRanges: List<BfRange>,
) {

    /**
     * Decode a single character code starting at [offset] in [bytes]; returns
     * the (text, advance-in-bytes) pair, or null if no mapping exists.
     */
    fun decode(bytes: ByteArray, offset: Int): Pair<String, Int>? {
        // Try widest match first (some CMaps mix 1-byte and 2-byte codes via
        // codespacerange; we just try widest-to-narrowest).
        for (width in codeWidth downTo 1) {
            if (offset + width > bytes.size) continue
            var code = 0
            for (i in 0 until width) {
                code = (code shl 8) or (bytes[offset + i].toInt() and 0xFF)
            }
            val text = lookup(code) ?: continue
            return text to width
        }
        return null
    }

    /** Decode an entire byte string to a Kotlin String via repeated [decode]. */
    fun decodeAll(bytes: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val (text, n) = decode(bytes, i)
                ?: run {
                    // No mapping — emit replacement and advance by codeWidth.
                    out.append('�')
                    "" to codeWidth
                }
            out.append(text)
            i += n
        }
        return out.toString()
    }

    private fun lookup(code: Int): String? {
        bfChars[code]?.let { return it }
        for (r in bfRanges) {
            if (code !in r.lo..r.hi) continue
            if (r.replacements != null) return r.replacements.getOrNull(code - r.lo)
            val base = r.base ?: continue
            val cp = base + (code - r.lo)
            if (cp in 0..0x10FFFF) return charArrayOfCodepoint(cp).concatToString()
        }
        return null
    }

    private fun charArrayOfCodepoint(cp: Int): CharArray = when {
        cp < 0x10000 -> charArrayOf(cp.toChar())
        else -> {
            val s = cp - 0x10000
            charArrayOf((0xD800 + (s ushr 10)).toChar(), (0xDC00 + (s and 0x3FF)).toChar())
        }
    }

    internal data class BfRange(
        val lo: Int,
        val hi: Int,
        /** Base codepoint for sequential ranges (`bfrange <lo> <hi> <utf>`). */
        val base: Int?,
        /** Explicit per-code replacements for `bfrange <lo> <hi> [...]`. */
        val replacements: List<String>?,
    )

    companion object {

        /** Parse a CMap stream (decoded bytes). Robust to unknown sections. */
        fun parse(bytes: ByteArray): CMap {
            val lexer = Lexer(ByteReader(bytes))
            val bfChars = HashMap<Int, String>()
            val bfRanges = mutableListOf<BfRange>()
            var codeWidth = 1

            while (true) {
                val tok = lexer.nextToken()
                if (tok == Token.EndOfFile) break
                if (tok !is Token.Keyword) continue
                when (tok.value) {
                    "begincodespacerange" -> {
                        codeWidth = maxOf(codeWidth, parseCodeSpaceRange(lexer))
                    }
                    "beginbfchar" -> parseBfChar(lexer, bfChars)
                    "beginbfrange" -> parseBfRange(lexer, bfRanges)
                    // Ignore beginCIDChar/beginCIDRange/etc. for ToUnicode use-case.
                }
            }
            return CMap(codeWidth, bfChars, bfRanges)
        }

        private fun parseCodeSpaceRange(lexer: Lexer): Int {
            var maxWidth = 1
            while (true) {
                val tok = lexer.nextToken()
                if (tok is Token.Keyword && tok.value == "endcodespacerange") return maxWidth
                if (tok !is Token.StringLiteral) continue
                val hi = lexer.nextToken() as? Token.StringLiteral ?: continue
                maxWidth = maxOf(maxWidth, tok.bytes.size, hi.bytes.size)
            }
        }

        private fun parseBfChar(lexer: Lexer, out: MutableMap<Int, String>) {
            while (true) {
                val tok = lexer.nextToken()
                if (tok is Token.Keyword && tok.value == "endbfchar") return
                if (tok !is Token.StringLiteral) continue
                val dst = lexer.nextToken()
                val code = bytesToInt(tok.bytes)
                val text = when (dst) {
                    is Token.StringLiteral -> utf16BEToString(dst.bytes)
                    is Token.Name -> dst.value  // some CMaps use /name destinations
                    else -> continue
                }
                out[code] = text
            }
        }

        private fun parseBfRange(lexer: Lexer, out: MutableList<BfRange>) {
            while (true) {
                val loTok = lexer.nextToken()
                if (loTok is Token.Keyword && loTok.value == "endbfrange") return
                if (loTok !is Token.StringLiteral) continue
                val hiTok = lexer.nextToken() as? Token.StringLiteral ?: continue
                val lo = bytesToInt(loTok.bytes)
                val hi = bytesToInt(hiTok.bytes)
                when (val dst = lexer.nextToken()) {
                    is Token.StringLiteral -> {
                        // Sequential range: base codepoint from dst, increment with code.
                        val baseCp = if (dst.bytes.size >= 2) {
                            ((dst.bytes[0].toInt() and 0xFF) shl 8) or (dst.bytes[1].toInt() and 0xFF)
                        } else {
                            dst.bytes[0].toInt() and 0xFF
                        }
                        out.add(BfRange(lo, hi, base = baseCp, replacements = null))
                    }
                    Token.ArrayOpen -> {
                        // Per-code replacements: collect strings until ArrayClose.
                        val reps = mutableListOf<String>()
                        while (true) {
                            val item = lexer.nextToken()
                            if (item == Token.ArrayClose) break
                            if (item is Token.StringLiteral) reps.add(utf16BEToString(item.bytes))
                        }
                        out.add(BfRange(lo, hi, base = null, replacements = reps))
                    }
                    else -> { /* unrecognized destination — skip */ }
                }
            }
        }

        private fun bytesToInt(bytes: ByteArray): Int {
            var v = 0
            for (b in bytes) v = (v shl 8) or (b.toInt() and 0xFF)
            return v
        }

        private fun utf16BEToString(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            // Even-length: pure UTF-16BE codepoints. Odd-length (rare): treat as latin-1.
            if (bytes.size % 2 == 0) {
                val sb = StringBuilder(bytes.size / 2)
                var i = 0
                while (i < bytes.size) {
                    val hi = bytes[i].toInt() and 0xFF
                    val lo = bytes[i + 1].toInt() and 0xFF
                    sb.append(((hi shl 8) or lo).toChar())
                    i += 2
                }
                return sb.toString()
            }
            // Treat odd-length destinations as a single byte → unicode (latin-1 ish).
            return bytes.joinToString("") { (it.toInt() and 0xFF).toChar().toString() }
        }
    }
}
