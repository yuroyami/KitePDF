package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.compression.Inflate
import kotlin.io.encoding.Base64

/**
 * Built-in CMaps named in `/Encoding` of Type 0 composite fonts.
 *
 * ISO 32000-1, 9.7.5.2 defines Identity-H and Identity-V, and a list of predefined
 * CJK CMaps such as GBK-EUC-H, 90ms-RKSJ-H and UniJIS-UCS2-H. Each one splits the
 * bytes into codes by its codespace ranges, which mix widths: ASCII takes 1 byte and
 * the CJK block 2. Then it maps each code to a CID of an Adobe character collection.
 *
 * - Identity-H and Identity-V: every 2-byte pair is the CID.
 * - Every other CMap of the spec's list: [PredefinedCMapData] bundles Adobe's own
 *   tables, and [TableCMapReader] reads them (#198).
 * - Any other name: a synthesized reader keeps the byte segmentation of the family the
 *   name belongs to and falls back to CID = code, which is [CodeUnitReader.degraded].
 *
 * An embedded `/Encoding` CMap stream goes through [CMap.codeUnits] instead (see
 * [CompositeFont]).
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
     * True when the predefined CMap [name] writes top to bottom. ISO 32000-1, Table 118
     * names every vertical CMap with a `-V` ending, such as `Identity-V`, except the
     * Adobe-Japan1 CMap that is called `V`.
     */
    fun isVertical(name: String?): Boolean = name == "V" || name?.endsWith("-V") == true

    /**
     * True when the codes of the predefined CMap [name] are UTF-16 code units, as in
     * UniJIS-UCS2-H or UniGB-UTF16-V. Such a code is its own Unicode text.
     */
    fun isUnicodeKeyed(name: String?): Boolean =
        name != null && name.startsWith("Uni") && ("UCS2" in name || "UTF16" in name)

    /**
     * The Adobe character collection, such as Japan1, that the bundled CMap [name] maps
     * codes into, or null for a name that is not bundled.
     */
    fun ordering(name: String?): String? {
        var cur = name
        var hops = 0
        while (cur != null && hops++ < 8) {
            val entry = PredefinedCMapData.entries[cur] ?: return null
            entry.ordering?.let { return it }
            cur = entry.usecmap
        }
        return null
    }

    /**
     * Resolve a named `/Encoding` to a [CodeUnitReader].
     *
     * - Identity-H / Identity-V → exact 2-byte reader.
     * - A bundled CMap → [TableCMapReader], with the real CIDs.
     * - Other names of a known family → a codespace-correct segmenting reader
     *   with a DEGRADED (CID == code) mapping.
     * - Unknown `-H`/`-V` names → treated as 2-byte UCS2-style (degraded).
     * - null / unknown 1-byte → single-byte.
     */
    fun reader(name: String?): CodeUnitReader {
        if (name == null) return SingleByteCodeUnitReader
        if (name == "Identity-H" || name == "Identity-V" || name == "Identity") return IdentityCodeUnitReader

        // Bundled Adobe CMaps: full codespace segmentation AND real registry CID
        // mapping through the usecmap chain.
        TableCMapReader.forName(name)?.let { return it }

        // Unicode-keyed CMaps the spec does not list, such as UniJIS2004-UTF16-H: the
        // code IS a Unicode code unit, so a 2-byte (UCS2) or 2/4-byte (UTF16)
        // segmentation is correct, but the CID is not, so the reader is degraded.
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

/**
 * A [CodeUnitReader] over the bundled [PredefinedCMapData] tables: segments
 * by the CMap's OWN codespace ranges (own + usecmap chain) and maps each
 * code to its Adobe-registry CID through the chain. Unmapped codes resolve
 * to CID 0 (.notdef), matching the spec. Not degraded: these are the real
 * tables.
 *
 * A 4-byte code of a UTF-16 CMap, from D800DC00 up, does not fit a signed Int, so
 * codes compare unsigned.
 */
internal class TableCMapReader private constructor(
    private val chain: List<Decoded>,
) : CodeUnitReader {

    private class Decoded(
        val codespaces: List<CodespaceReader.Range>,
        /** Sorted single-code mappings: parallel code/cid arrays. */
        val charCodes: IntArray,
        val charCids: IntArray,
        /** Sorted ranges: parallel lo/hi/cid arrays. */
        val rangeLo: IntArray,
        val rangeHi: IntArray,
        val rangeCid: IntArray,
    )

    override fun next(bytes: ByteArray, offset: Int): Pair<Int, Int>? {
        if (offset >= bytes.size) return null
        var best: CodespaceReader.Range? = null
        for (d in chain) for (r in d.codespaces) {
            if (r.matches(bytes, offset) && (best == null || r.width > best!!.width)) best = r
        }
        val width = (best?.width ?: 1).coerceAtMost(bytes.size - offset)
        var code = 0
        for (i in 0 until width) code = (code shl 8) or (bytes[offset + i].toInt() and 0xFF)
        return cidFor(code) to width
    }

    private fun cidFor(code: Int): Int {
        for (d in chain) {
            // Exact single-code entries first (they override ranges).
            var lo = 0
            var hi = d.charCodes.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val v = d.charCodes[mid]
                when {
                    v == code -> return d.charCids[mid]
                    below(v, code) -> lo = mid + 1
                    else -> hi = mid - 1
                }
            }
            lo = 0
            hi = d.rangeLo.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when {
                    below(code, d.rangeLo[mid]) -> hi = mid - 1
                    below(d.rangeHi[mid], code) -> lo = mid + 1
                    else -> return d.rangeCid[mid] + (code - d.rangeLo[mid])
                }
            }
        }
        return 0 // .notdef
    }

    /** True when [a] is below [b], both read as unsigned 32-bit codes. */
    private fun below(a: Int, b: Int): Boolean = (a xor Int.MIN_VALUE) < (b xor Int.MIN_VALUE)

    companion object {
        // One SYNCHRONIZED lazy per bundled CMap name, keyed by the fixed
        // PredefinedCMapData key set and never mutated after construction.
        // Composite-font construction reaches this from concurrent page
        // renders and from the main-thread text/selection path; the previous
        // getOrPut memo on a plain HashMap raced them, and its nullable value
        // type made it re-decode and re-put on EVERY lookup of an unbundled
        // name (all Uni* CMaps). Unbundled names now fall through to null
        // without touching any state.
        private val readers: Map<String, Lazy<TableCMapReader?>> by lazy {
            PredefinedCMapData.entries.keys.associateWith { name -> lazy { build(name) } }
        }

        fun forName(name: String): TableCMapReader? = readers[name]?.value

        private fun build(name: String): TableCMapReader? {
            val chain = ArrayList<Decoded>()
            var cur: String? = name
            var hops = 0
            while (cur != null && hops++ < 8) {
                val entry = PredefinedCMapData.entries[cur] ?: break
                chain.add(decode(entry.table))
                cur = entry.usecmap
            }
            return if (chain.isEmpty()) null else TableCMapReader(chain)
        }

        /**
         * Reads one table of [PredefinedCMapData]: the codespace ranges, then the single
         * codes, then the code ranges, each code and CID a difference from the one before.
         * A code is added as a 32-bit pattern, so a code from 80000000 up wraps as it should.
         */
        private fun decode(table: String): Decoded {
            val v = Leb128(Inflate.decode(Base64.decode(table)))
            val csCount = v.next()
            val codespaces = ArrayList<CodespaceReader.Range>(csCount)
            repeat(csCount) {
                val w = v.next()
                val low = IntArray(w) { v.next() }
                val high = IntArray(w) { v.next() }
                codespaces.add(CodespaceReader.Range(w, low, high))
            }
            val nChars = v.next()
            val charCodes = IntArray(nChars)
            val charCids = IntArray(nChars)
            var code = 0
            var cid = 0
            for (i in 0 until nChars) {
                code += v.next()
                cid += v.signed()
                charCodes[i] = code
                charCids[i] = cid
            }
            val nRanges = v.next()
            val rangeLo = IntArray(nRanges)
            val rangeHi = IntArray(nRanges)
            val rangeCid = IntArray(nRanges)
            var lo = 0
            var nextCid = 0
            for (i in 0 until nRanges) {
                lo += v.next()
                val hi = lo + v.next()
                val first = nextCid + v.signed()
                rangeLo[i] = lo
                rangeHi[i] = hi
                rangeCid[i] = first
                nextCid = first + (hi - lo) + 1
            }
            return Decoded(codespaces, charCodes, charCids, rangeLo, rangeHi, rangeCid)
        }
    }
}

/**
 * Unsigned LEB128 numbers from [bytes], as the CJK tables store them. [next] keeps the low
 * 32 bits of a larger number, which is what a sum of 32-bit code differences needs.
 */
internal class Leb128(private val bytes: ByteArray) {
    private var p = 0

    fun next(): Int {
        var n = 0L
        var shift = 0
        while (true) {
            val b = bytes[p++].toInt() and 0xFF
            n = n or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return n.toInt()
            shift += 7
        }
    }

    /** A zigzag-coded difference: 0, -1, 1, -2, 2 and so on. */
    fun signed(): Int {
        val z = next()
        return (z ushr 1) xor -(z and 1)
    }
}
