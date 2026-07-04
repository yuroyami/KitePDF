package io.github.yuroyami.kitepdf.font

/**
 * Built-in CMaps named in `/Encoding` of Type 0 composite fonts.
 *
 * The PDF spec (ISO 32000-1 §9.7.5.2) defines Identity-H / Identity-V plus a
 * long list of predefined CJK CMaps (GBK-EUC-H, 90ms-RKSJ-H, ETen-B5-H,
 * UniJIS-UCS2-H, UniGB-UCS2-H, …). Each such CMap:
 *   1. SEGMENTS the byte stream into codes using its codespace ranges — and for
 *      the CJK encodings those ranges are MIXED-WIDTH (ASCII is 1 byte, the CJK
 *      block is 2 bytes). Segmenting "widest-first" or "always 2 bytes" is wrong.
 *   2. MAPS each code to a CID via large lookup tables that live in Adobe's CMap
 *      resource packages (Adobe-Japan1, Adobe-GB1, Adobe-CNS1, Adobe-Korea1,
 *      Adobe-KR). Those tables are NOT bundled in this repo.
 *
 * What this file does correctly WITHOUT the resource data:
 *   - Identity-H / Identity-V: exact (every 2-byte BE pair IS the CID).
 *   - The mixed-width CJK CMaps: we reproduce their CODESPACE structure so byte
 *     SEGMENTATION is correct (1-byte ASCII vs 2-byte CJK split at the right
 *     boundary). This keeps glyph counts and per-glyph byte offsets right, which
 *     is what layout/redaction/advance need.
 *   - CID mapping for those CMaps DEGRADES to CID == code (the raw segmented
 *     integer). That is NOT the Adobe registry CID; it is a documented last
 *     resort (see [degraded]). Glyph resolution via /CIDToGIDMap will therefore
 *     be wrong for these fonts unless the font is Identity-keyed or a /ToUnicode
 *     is present. Shipping the Adobe CMap resource data is TODO (see deferred).
 *
 * An EMBEDDED /Encoding CMap *stream* (as opposed to a predefined name) is fully
 * supported via [CMap.codeUnits] — see [CompositeFont]. That path does real
 * codespace segmentation AND real cidchar/cidrange CID mapping from the stream.
 */
internal interface CodeUnitReader {
    /** Read one code unit at [offset] from [bytes]; returns (cid, bytesConsumed) or null on EOF. */
    fun next(bytes: ByteArray, offset: Int): Pair<Int, Int>?

    /**
     * True when this reader cannot produce real Adobe-registry CIDs (no bundled
     * resource data) and is falling back to CID == segmented-code. Callers that
     * care about CID correctness (e.g. non-Identity /CIDToGIDMap) can detect this.
     */
    val degraded: Boolean get() = false
}

internal object IdentityCodeUnitReader : CodeUnitReader {
    override fun next(bytes: ByteArray, offset: Int): Pair<Int, Int>? {
        if (offset >= bytes.size) return null
        // Strictly, Identity-H requires 2 bytes per code unit; a stray odd
        // trailing byte gets treated as a 1-byte CID so we don't drop data.
        if (offset + 1 >= bytes.size) {
            return (bytes[offset].toInt() and 0xFF) to 1
        }
        val cid = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        return cid to 2
    }
}

internal object SingleByteCodeUnitReader : CodeUnitReader {
    override fun next(bytes: ByteArray, offset: Int): Pair<Int, Int>? {
        if (offset >= bytes.size) return null
        return (bytes[offset].toInt() and 0xFF) to 1
    }
}

/**
 * A codespace-driven reader for the mixed-width predefined CJK CMaps. It splits
 * bytes by matching the byte-prefix of a set of [Range]s (each with a per-byte
 * low/high pattern), then returns CID == segmented-code (a DEGRADED mapping,
 * see [PredefinedCMaps]).
 */
internal class CodespaceReader(
    private val ranges: List<Range>,
) : CodeUnitReader {

    /** A codespace range: per-byte inclusive [low,high] of a fixed [width]. */
    class Range(val width: Int, val low: IntArray, val high: IntArray) {
        fun matches(bytes: ByteArray, offset: Int): Boolean {
            if (offset + width > bytes.size) return false
            for (i in 0 until width) {
                val b = bytes[offset + i].toInt() and 0xFF
                if (b < low[i] || b > high[i]) return false
            }
            return true
        }
    }

    override val degraded: Boolean get() = true

    override fun next(bytes: ByteArray, offset: Int): Pair<Int, Int>? {
        if (offset >= bytes.size) return null
        // Prefer the longest matching range (more specific).
        var best: Range? = null
        for (r in ranges) {
            if (r.matches(bytes, offset)) {
                if (best == null || r.width > best.width) best = r
            }
        }
        val width = best?.width ?: ranges.minOf { it.width }.coerceAtMost(bytes.size - offset).coerceAtLeast(1)
        var code = 0
        val n = width.coerceAtMost(bytes.size - offset)
        for (i in 0 until n) code = (code shl 8) or (bytes[offset + i].toInt() and 0xFF)
        return code to width
    }
}

internal object PredefinedCMaps {

    private fun range(width: Int, low: IntArray, high: IntArray) = CodespaceReader.Range(width, low, high)

    // ── Codespace structures of the common predefined CJK encodings ──────────
    // These are the well-known lead/trail byte patterns of each source encoding.
    // They cover segmentation only; CID mapping is degraded to CID==code.

    /** Shift-JIS family (90ms-RKSJ, 90pv-RKSJ, Ext-RKSJ, …). 1-byte ASCII/half-width kana, 2-byte kanji. */
    private val shiftJis: List<CodespaceReader.Range> = listOf(
        range(1, intArrayOf(0x00), intArrayOf(0x80)),
        range(1, intArrayOf(0xA0), intArrayOf(0xDF)),
        range(2, intArrayOf(0x81, 0x40), intArrayOf(0x9F, 0xFC)),
        range(2, intArrayOf(0xE0, 0x40), intArrayOf(0xFC, 0xFC)),
    )

    /** EUC family (EUC-H/V, GBK/EUC on the GB side uses similar lead ranges). 1-byte ASCII, 2-byte 0xA1..0xFE pairs. */
    private val euc: List<CodespaceReader.Range> = listOf(
        range(1, intArrayOf(0x00), intArrayOf(0x80)),
        range(2, intArrayOf(0x8E, 0xA0), intArrayOf(0x8E, 0xDF)),      // JIS X 0201 kana (EUC-JP)
        range(2, intArrayOf(0xA1, 0xA1), intArrayOf(0xFE, 0xFE)),
    )

    /** GBK-EUC / GB2312 lead-byte structure. 1-byte ASCII, 2-byte with trail 0x40+. */
    private val gbk: List<CodespaceReader.Range> = listOf(
        range(1, intArrayOf(0x00), intArrayOf(0x80)),
        range(2, intArrayOf(0x81, 0x40), intArrayOf(0xFE, 0xFE)),
    )

    /** Big5 (ETen-B5, B5pc, HKscs). 1-byte ASCII, 2-byte lead 0x81..0xFE, trail 0x40..0x7E / 0xA1..0xFE. */
    private val big5: List<CodespaceReader.Range> = listOf(
        range(1, intArrayOf(0x00), intArrayOf(0x80)),
        range(2, intArrayOf(0x81, 0x40), intArrayOf(0xFE, 0x7E)),
        range(2, intArrayOf(0x81, 0xA1), intArrayOf(0xFE, 0xFE)),
    )

    /** Wansung / UHC (Korean). 1-byte ASCII, 2-byte lead 0x81..0xFE. */
    private val wansung: List<CodespaceReader.Range> = gbk

    /** Pure 2-byte Unicode-keyed CMaps (UniXXX-UCS2-H/V). Every code is a 2-byte BE unit. */
    private val ucs2: List<CodespaceReader.Range> = listOf(
        range(2, intArrayOf(0x00, 0x00), intArrayOf(0xFF, 0xFF)),
    )

    /**
     * UTF-16 CMaps (UniXXX-UTF16-H/V). 2-byte BMP units + 4-byte surrogate
     * pairs. We model both widths so segmentation of astral codepoints is right.
     */
    private val utf16: List<CodespaceReader.Range> = listOf(
        range(2, intArrayOf(0x00, 0x00), intArrayOf(0xD7, 0xFF)),
        range(4, intArrayOf(0xD8, 0x00, 0xDC, 0x00), intArrayOf(0xDB, 0xFF, 0xDF, 0xFF)),
        range(2, intArrayOf(0xE0, 0x00), intArrayOf(0xFF, 0xFF)),
    )

    /**
     * Resolve a named `/Encoding` to a [CodeUnitReader].
     *
     * - Identity-H / Identity-V → exact 2-byte reader.
     * - Known mixed-width CJK families → a codespace-correct segmenting reader
     *   with a DEGRADED (CID == code) mapping (no bundled Adobe resource data).
     * - Unknown `-H`/`-V` names → treated as 2-byte UCS2-style (degraded).
     * - null / unknown 1-byte → single-byte.
     */
    fun reader(name: String?): CodeUnitReader {
        if (name == null) return SingleByteCodeUnitReader
        if (name == "Identity-H" || name == "Identity-V" || name == "Identity") return IdentityCodeUnitReader

        // Unicode-keyed predefined CMaps: the code IS a Unicode code unit, so a
        // 2-byte (UCS2) or 2/4-byte (UTF16) segmentation is correct AND the code
        // doubles as a usable index; still flagged degraded (CID != code in the
        // Adobe registry, but for Identity-keyed CIDFontType2 it round-trips).
        if (name.startsWith("Uni", ignoreCase = false)) {
            return when {
                name.contains("UTF16") -> CodespaceReader(utf16)
                name.contains("UTF8") -> CodespaceReader(listOf(
                    range(1, intArrayOf(0x00), intArrayOf(0x7F)),
                    range(2, intArrayOf(0xC0, 0x80), intArrayOf(0xDF, 0xBF)),
                    range(3, intArrayOf(0xE0, 0x80, 0x80), intArrayOf(0xEF, 0xBF, 0xBF)),
                    range(4, intArrayOf(0xF0, 0x80, 0x80, 0x80), intArrayOf(0xF7, 0xBF, 0xBF, 0xBF)),
                ))
                else -> CodespaceReader(ucs2) // UCS2 and anything else Uni*
            }
        }

        // Source-encoding-keyed predefined CJK CMaps: segment by codespace.
        val ranges: List<CodespaceReader.Range>? = when {
            name.contains("RKSJ") -> shiftJis
            name.contains("B5") -> big5                 // ETen-B5-H, B5pc-H, HKscs-B5-H
            name.contains("GBK") -> gbk
            name.contains("GBpc") || name.contains("GBT") || name.startsWith("GB-") -> euc
            name.contains("KSC") || name.contains("KSCms") || name.contains("Wansung") -> wansung
            name.contains("EUC") -> euc
            else -> null
        }
        if (ranges != null) return CodespaceReader(ranges)

        // Unknown horizontal/vertical CMap name with no derivable structure:
        // assume 2-byte (the overwhelming majority) and degrade the mapping.
        if (name.endsWith("-H") || name.endsWith("-V")) return CodespaceReader(ucs2)
        return SingleByteCodeUnitReader
    }
}
