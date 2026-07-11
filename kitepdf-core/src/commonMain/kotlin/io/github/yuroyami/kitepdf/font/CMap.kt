package io.github.yuroyami.kitepdf.font

import io.github.yuroyami.kitepdf.core.ByteReader
import io.github.yuroyami.kitepdf.core.PdfFormatException
import io.github.yuroyami.kitepdf.parser.Lexer
import io.github.yuroyami.kitepdf.parser.Token

/**
 * CMap parser for PDF `/ToUnicode` streams (ISO 32000-1 §9.10.3, Adobe Tech Note 5014).
 *
 * A ToUnicode CMap is a small PostScript-ish program that tells the reader
 * "given the source character codes I show in this content stream, here are
 * the unicode codepoints to use when extracting/copying text." It uses these
 * section types:
 *
 *   begincodespacerange  …  endcodespacerange       — declares the valid byte patterns
 *   beginbfchar          …  endbfchar               — `<src> <utf16BE-bytes>` pairs
 *   beginbfrange         …  endbfrange              — `<srcLo> <srcHi> <utf16BE-start>`
 *                                                   — `<srcLo> <srcHi> [ <utf> <utf> … ]`
 *   begincidchar         …  endcidchar              — `<src> cid`   (embedded /Encoding CMaps)
 *   begincidrange        …  endcidrange             — `<srcLo> <srcHi> cid`
 *
 * Source codes are 1-byte (most simple fonts), 2-byte (CIDFonts, Identity-H),
 * or a *mix* (many CJK CMaps: ASCII stays 1-byte, kanji goes 2-byte). The mix
 * is disambiguated by the codespace ranges, which give the exact byte-prefix
 * pattern for each width (see [Codespace]). Destination codes in ToUnicode are
 * UTF-16BE byte strings, possibly multi-character (ligatures) or non-BMP
 * (surrogate pairs).
 *
 * We mirror MuPDF's pdf-cmap-parse.c approach: tokenize with our regular PDF
 * [Lexer], then walk section keywords. Anything outside known sections
 * (CIDSystemInfo, /Registry strings, etc.) is silently skipped — robustness
 * over strictness, per the spec recommendation.
 *
 * Besides ToUnicode, this class doubles as a decoder for an EMBEDDED /Encoding
 * CMap stream on a Type 0 font: [codeUnits] performs codespace-correct byte
 * segmentation and maps each code to a CID via the cidchar/cidrange sections.
 */
public class CMap private constructor(
    /**
     * Maximum byte length of one source code (1–4). Inferred from
     * codespacerange; used only as a hint / legacy accessor. Segmentation
     * itself is driven by [codespaces], not this width.
     */
    public val codeWidth: Int,
    /**
     * Codespace ranges (low/high byte patterns per width) in declaration
     * order. Empty when the CMap declared none (then we behave as if a single
     * [codeWidth]-wide range covering everything existed).
     */
    private val codespaces: List<Codespace>,
    private val bfChars: Map<Int, String>,
    private val bfRanges: List<BfRange>,
    /** CID mappings for an embedded /Encoding CMap (code → CID). */
    private val cidChars: Map<Int, Int>,
    private val cidRanges: List<CidRange>,
) {

    /**
     * A single `begincodespacerange` entry — a per-byte low/high pattern of
     * width [width]. A byte sequence matches when, for every byte position, the
     * input byte is within `[low[i], high[i]]`.
     */
    internal class Codespace(
        val width: Int,
        val low: IntArray,   // width bytes, each 0..255
        val high: IntArray,  // width bytes, each 0..255
    ) {
        /** True iff [bytes] starting at [offset] has [width] bytes matching this range. */
        fun matches(bytes: ByteArray, offset: Int): Boolean {
            if (offset + width > bytes.size) return false
            for (i in 0 until width) {
                val b = bytes[offset + i].toInt() and 0xFF
                if (b < low[i] || b > high[i]) return false
            }
            return true
        }
    }

    /**
     * Segment the input at [offset] into a single code, using the codespace
     * ranges. Returns (integerCode, width). Follows Adobe's rule: prefer the
     * codespace range that matches; when several widths could match, the one
     * whose byte pattern actually matches the input wins. On a total miss we
     * return the SHORTEST declared width (advancing minimally so we resync),
     * or width 1 if no codespaces were declared.
     */
    private fun segment(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        if (codespaces.isNotEmpty()) {
            // Prefer the longest matching codespace (a 2-byte match is more
            // specific than a 1-byte one for the same prefix).
            var best: Codespace? = null
            for (cs in codespaces) {
                if (cs.matches(bytes, offset)) {
                    if (best == null || cs.width > best.width) best = cs
                }
            }
            if (best != null) return readCode(bytes, offset, best.width) to best.width
            // No codespace matched. Advance by the shortest declared width so a
            // single bad byte doesn't swallow following (valid) codes.
            val minWidth = codespaces.minOf { it.width }
            val w = minWidth.coerceAtMost(bytes.size - offset).coerceAtLeast(1)
            return readCode(bytes, offset, w) to w
        }
        // No codespaces declared — fall back to the inferred fixed width.
        val w = codeWidth.coerceAtMost(bytes.size - offset).coerceAtLeast(1)
        return readCode(bytes, offset, w) to w
    }

    private fun readCode(bytes: ByteArray, offset: Int, width: Int): Int {
        var code = 0
        val n = width.coerceAtMost(bytes.size - offset)
        for (i in 0 until n) code = (code shl 8) or (bytes[offset + i].toInt() and 0xFF)
        return code
    }

    /**
     * Decode a single character code starting at [offset] in [bytes]; returns
     * the (text, advance-in-bytes) pair, or null if no ToUnicode mapping exists
     * for the segmented code. Segmentation is codespace-driven so mixed
     * 1-/2-byte streams are split correctly.
     */
    public fun decode(bytes: ByteArray, offset: Int): Pair<String, Int>? {
        if (offset >= bytes.size) return null
        val (code, width) = segment(bytes, offset)
        val text = lookup(code) ?: return null
        return text to width
    }

    /** Decode an entire byte string to a Kotlin String via repeated [decode]. */
    public fun decodeAll(bytes: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val (code, width) = segment(bytes, i)
            val text = lookup(code)
            if (text != null) {
                out.append(text)
                i += width
            } else {
                // No mapping — emit replacement and advance by ONE byte so we
                // don't desync a mixed-width stream on an unmapped code.
                out.append('�')
                i += 1
            }
        }
        return out.toString()
    }

    /* ─── Embedded /Encoding CMap support (code → CID) ────────────────────── */

    /**
     * Segment [bytes] into (CID, byteOffset, byteCount) code units for use as a
     * Type 0 `/Encoding` CMap. Codespace-correct: mixed 1-/2-byte streams are
     * split by matching codespace ranges. When no cidchar/cidrange maps a code
     * we fall back to CID == code (last resort; see [PredefinedCMaps]).
     */
    public fun codeUnits(bytes: ByteArray): List<CodeUnit> {
        val out = ArrayList<CodeUnit>(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val (code, width) = segment(bytes, i)
            out.add(CodeUnit(cid = lookupCid(code) ?: code, code = code, byteOffset = i, byteCount = width))
            i += width
        }
        return out
    }

    /**
     * Segment ONE code unit at [offset] (no allocation of the whole list).
     * Returns (cid, byteCount) or null at/after EOF. Used on the hot advance
     * path so we don't re-scan the tail of the byte string per glyph.
     */
    public fun codeUnitAt(bytes: ByteArray, offset: Int): Pair<Int, Int>? {
        if (offset >= bytes.size) return null
        val (code, width) = segment(bytes, offset)
        return (lookupCid(code) ?: code) to width
    }

    /** True iff this CMap carries CID mappings (embedded /Encoding), not just ToUnicode. */
    public val hasCidMappings: Boolean get() = cidChars.isNotEmpty() || cidRanges.isNotEmpty()

    public data class CodeUnit(val cid: Int, val code: Int, val byteOffset: Int, val byteCount: Int)

    private fun lookupCid(code: Int): Int? {
        cidChars[code]?.let { return it }
        for (r in cidRanges) {
            if (code in r.lo..r.hi) return r.baseCid + (code - r.lo)
        }
        return null
    }

    private fun lookup(code: Int): String? {
        bfChars[code]?.let { return it }
        for (r in bfRanges) {
            if (code !in r.lo..r.hi) continue
            if (r.replacements != null) return r.replacements.getOrNull(code - r.lo)
            val base = r.base ?: continue
            // Increment the LAST code unit of the destination across the range.
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

    internal data class CidRange(val lo: Int, val hi: Int, val baseCid: Int)

    public companion object {

        /** Parse a CMap stream (decoded bytes). Robust to unknown sections. */
        public fun parse(bytes: ByteArray): CMap {
            val lexer = Lexer(ByteReader(bytes))
            val bfChars = HashMap<Int, String>()
            val bfRanges = mutableListOf<BfRange>()
            val cidChars = HashMap<Int, Int>()
            val cidRanges = mutableListOf<CidRange>()
            val codespaces = mutableListOf<Codespace>()
            var codeWidth = 1

            while (true) {
                val tok = lexer.nextToken()
                if (tok == Token.EndOfFile) break
                if (tok !is Token.Keyword) continue
                when (tok.value) {
                    "begincodespacerange" -> {
                        parseCodeSpaceRange(lexer, codespaces)
                        codeWidth = codespaces.maxOfOrNull { it.width } ?: codeWidth
                    }
                    "beginbfchar" -> parseBfChar(lexer, bfChars)
                    "beginbfrange" -> parseBfRange(lexer, bfRanges)
                    "begincidchar" -> parseCidChar(lexer, cidChars)
                    "begincidrange" -> parseCidRange(lexer, cidRanges)
                }
            }
            return CMap(codeWidth, codespaces, bfChars, bfRanges, cidChars, cidRanges)
        }

        private fun parseCodeSpaceRange(lexer: Lexer, out: MutableList<Codespace>) {
            while (true) {
                val tok = lexer.nextToken()
                if (tok is Token.Keyword && tok.value == "endcodespacerange") return
                if (tok !is Token.StringLiteral) continue
                val hi = lexer.nextToken() as? Token.StringLiteral ?: continue
                // Width is the byte-length of the pattern. Low/high are padded to
                // the same width so per-byte comparison is well-defined.
                val width = maxOf(tok.bytes.size, hi.bytes.size).coerceIn(1, 4)
                out.add(Codespace(width, padBytes(tok.bytes, width), padBytes(hi.bytes, width)))
            }
        }

        /** Left-pad (or truncate to last [width]) a byte pattern to [width] ints. */
        private fun padBytes(bytes: ByteArray, width: Int): IntArray {
            val out = IntArray(width)
            // Right-align: the declared bytes fill the low-order positions.
            val start = width - bytes.size
            for (i in 0 until width) {
                out[i] = if (i < start) 0 else (bytes[i - start].toInt() and 0xFF)
            }
            return out
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
                        // Sequential range. Per Adobe TN 5411, the destination's
                        // LAST code unit is incremented across the range. For a
                        // 4-byte UTF-16BE destination (a surrogate pair / non-BMP
                        // codepoint) we decode it to a full codepoint and use that
                        // as the base so surrogate math is done in codepoint space.
                        val baseCp = utf16BEToBaseCodepoint(dst.bytes)
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

        private fun parseCidChar(lexer: Lexer, out: MutableMap<Int, Int>) {
            while (true) {
                val tok = lexer.nextToken()
                if (tok is Token.Keyword && tok.value == "endcidchar") return
                if (tok !is Token.StringLiteral) continue
                val cidTok = lexer.nextToken()
                val cid = intValueOf(cidTok) ?: continue
                out[bytesToInt(tok.bytes)] = cid
            }
        }

        private fun parseCidRange(lexer: Lexer, out: MutableList<CidRange>) {
            while (true) {
                val loTok = lexer.nextToken()
                if (loTok is Token.Keyword && loTok.value == "endcidrange") return
                if (loTok !is Token.StringLiteral) continue
                val hiTok = lexer.nextToken() as? Token.StringLiteral ?: continue
                val cidTok = lexer.nextToken()
                val cid = intValueOf(cidTok) ?: continue
                out.add(CidRange(bytesToInt(loTok.bytes), bytesToInt(hiTok.bytes), cid))
            }
        }

        /** CID destinations are plain integers in the CMap; parse the token. */
        private fun intValueOf(tok: Token?): Int? = when (tok) {
            is Token.Integer -> tok.value.toInt()
            is Token.StringLiteral -> bytesToInt(tok.bytes)
            else -> null
        }

        private fun bytesToInt(bytes: ByteArray): Int {
            var v = 0
            for (b in bytes) v = (v shl 8) or (b.toInt() and 0xFF)
            return v
        }

        /**
         * Decode a UTF-16BE destination byte string to its FIRST codepoint,
         * combining a leading surrogate pair (4 bytes) into one non-BMP
         * codepoint. Used as the increment base for bfrange sequential mappings.
         */
        private fun utf16BEToBaseCodepoint(bytes: ByteArray): Int {
            if (bytes.isEmpty()) return 0
            if (bytes.size == 1) return bytes[0].toInt() and 0xFF
            val u0 = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            if (u0 in 0xD800..0xDBFF && bytes.size >= 4) {
                val u1 = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
                if (u1 in 0xDC00..0xDFFF) {
                    return 0x10000 + ((u0 - 0xD800) shl 10) + (u1 - 0xDC00)
                }
            }
            return u0
        }

        private fun utf16BEToString(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            // Even-length: pure UTF-16BE code units (may include surrogate pairs
            // for non-BMP codepoints; toChar()/String round-trips them correctly).
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
