package io.github.yuroyami.kitepdf.font

import io.github.yuroyami.kitepdf.filters.FilterChain
import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.parser.PdfStream
import io.github.yuroyami.kitepdf.render.KitePath

/**
 * Type 0 composite font (ISO 32000-1 §9.7) — wraps a CIDFont descendant
 * with an `/Encoding` CMap that turns byte sequences into CIDs.
 *
 * The flow:
 *   bytes ── /Encoding CMap ──▶ CID ── /CIDToGIDMap ──▶ GID ──▶ outline
 *
 * For text extraction the parent Type 0's `/ToUnicode` (when present)
 * handles bytes → unicode directly; otherwise we fall back to the CIDFont's
 * `/CIDSystemInfo` registry which we currently don't have full coverage of,
 * so unicode extraction may degrade to a placeholder character.
 *
 * /W widths use a compact two-form encoding:
 *   `[ cid [ w1 w2 … wn ] ]`            (n consecutive CIDs)
 *   `[ cidStart cidEnd width ]`         (range, all same width)
 * Mixed in one array. `/DW` is the default width for any CID not in /W.
 */
internal class CompositeFont(
    val baseFont: String,
    /** /Subtype of the descendant CIDFont: "CIDFontType0" (CFF) or "CIDFontType2" (TTF). */
    val descendantSubtype: String,
    /** Embedded TrueType outlines (CIDFontType2), or null. */
    val ttf: TrueTypeFont?,
    /** Embedded CFF outlines (CIDFontType0), or null. */
    val cff: CffFont?,
    /** Byte stream → code unit reader (Identity-H by default for CIDFonts). */
    val codeReader: CodeUnitReader,
    /**
     * Embedded `/Encoding` CMap *stream* (ISO 32000-1 §9.7.5.3), when /Encoding
     * was a stream rather than a predefined name. It performs codespace-correct
     * segmentation AND real code→CID mapping via its cidchar/cidrange tables;
     * when present it takes precedence over [codeReader].
     */
    val encodingCMap: CMap?,
    /** CID → GID mapping. Default identity for CIDFontType0; explicit for CIDFontType2. */
    val cidToGid: CidToGidMap,
    /** CID-keyed widths from /W + /DW. */
    val widths: CidWidthTable,
    /** ToUnicode CMap on the parent Type 0 dict. */
    val toUnicode: CMap?,
) {

    /**
     * Segment [bytes] at [offset] into one (cid, byteCount) code unit, using the
     * embedded /Encoding CMap when present, else the predefined [codeReader].
     * The single point of truth for composite code-unit segmentation, so every
     * caller (layout, advance, word-spacing) splits bytes identically.
     */
    fun nextCodeUnit(bytes: ByteArray, offset: Int): Pair<Int, Int>? {
        encodingCMap?.let { return it.codeUnitAt(bytes, offset) }
        return codeReader.next(bytes, offset)
    }

    /** Walk [bytes] into a sequence of (CID, byteCount). */
    fun codeUnits(bytes: ByteArray): Sequence<CidUnit> = sequence {
        encodingCMap?.let { cmap ->
            for (u in cmap.codeUnits(bytes)) {
                yield(CidUnit(cid = u.cid, byteOffset = u.byteOffset, byteCount = u.byteCount))
            }
            return@sequence
        }
        var offset = 0
        while (offset < bytes.size) {
            val (cid, consumed) = codeReader.next(bytes, offset) ?: break
            yield(CidUnit(cid = cid, byteOffset = offset, byteCount = consumed))
            offset += consumed
        }
    }

    /** Resolve a CID to a glyph outline, or null if neither embedded font has it. */
    fun outline(cid: Int): KitePath? {
        val gid = cidToGid.map(cid)
        ttf?.let { return it.outlinePath(gid) }
        cff?.let { return it.outline(gid) }
        return null
    }

    fun gidFor(cid: Int): Int = cidToGid.map(cid)
    fun widthOf(cid: Int): Double = widths.widthOf(cid)

    /** Decode the full byte run to unicode via ToUnicode CMap (preferred) or codepoint guess. */
    fun decode(bytes: ByteArray): String {
        toUnicode?.let { return it.decodeAll(bytes) }
        // No ToUnicode — emit replacement chars per code unit. Better than nothing.
        return buildString { codeUnits(bytes).forEach { append('�') } }
    }

    data class CidUnit(val cid: Int, val byteOffset: Int, val byteCount: Int)

    companion object {

        /**
         * Build a [CompositeFont] from a parent Type 0 font dict. Returns null
         * when the dict isn't actually a Type 0 or the descendant isn't usable.
         */
        fun from(parentDict: PdfDictionary, refs: IndirectResolver): CompositeFont? {
            if (parentDict.getName("Subtype") != "Type0") return null
            val descendants = parentDict.getArray("DescendantFonts", refs) ?: return null
            val descendant = descendants.firstOrNull()?.resolve(refs) as? PdfDictionary ?: return null
            val descendantSubtype = descendant.getName("Subtype") ?: return null

            // Resolve /Encoding — either a predefined name (Identity-H/V, CJK) or
            // an embedded CMap stream (ISO 32000-1 §9.7.5.3). A stream gives us
            // real codespace segmentation + code→CID mapping; a name resolves to
            // a predefined [CodeUnitReader] (Identity exact, CJK degraded).
            val encodingObj = parentDict["Encoding"]?.resolve(refs)
            val encodingName = (encodingObj as? PdfName)?.value
            val encodingCMap = (encodingObj as? PdfStream)?.let {
                runCatching { CMap.parse(FilterChain.decode(it)) }.getOrNull()
            }
            val codeReader = PredefinedCMaps.reader(encodingName)

            // Resolve descendant's embedded outlines.
            val descriptor = descendant["FontDescriptor"]?.resolve(refs) as? PdfDictionary
            val ttf = descriptor?.let { loadTtf(it, refs) }
            val cff = if (ttf == null) descriptor?.let { loadCff(it, refs) } else null

            val cidToGid = CidToGidMap.from(descendant["CIDToGIDMap"]?.resolve(refs))
            val widths = CidWidthTable.from(descendant, refs)
            val toUnicode = loadToUnicodeOnParent(parentDict, refs)
            val baseFont = parentDict.getName("BaseFont") ?: descendant.getName("BaseFont") ?: "Unknown"

            return CompositeFont(baseFont, descendantSubtype, ttf, cff, codeReader, encodingCMap, cidToGid, widths, toUnicode)
        }

        private fun loadTtf(descriptor: PdfDictionary, refs: IndirectResolver): TrueTypeFont? {
            val stream = (descriptor["FontFile2"]?.resolve(refs) as? PdfStream) ?: return null
            return runCatching { TrueTypeFont.parse(FilterChain.decode(stream)) }.getOrNull()
        }

        private fun loadCff(descriptor: PdfDictionary, refs: IndirectResolver): CffFont? {
            val stream = (descriptor["FontFile3"]?.resolve(refs) as? PdfStream) ?: return null
            return runCatching { CffFont.parse(FilterChain.decode(stream)) }.getOrNull()
        }

        private fun loadToUnicodeOnParent(parent: PdfDictionary, refs: IndirectResolver): CMap? {
            val ref = parent["ToUnicode"] ?: return null
            val resolved = ref.resolve(refs) as? PdfStream ?: return null
            return runCatching { CMap.parse(FilterChain.decode(resolved)) }.getOrNull()
        }
    }
}

/* ─── /CIDToGIDMap (ISO 32000-1 §9.7.4.2) ─────────────────────────────────── */

/**
 * Maps a CID → GID. Either /Identity (CID == GID — the default for
 * CIDFontType0) or a stream of 2N bytes where bytes[2i..2i+1] big-endian
 * gives the GID for CID i.
 */
internal class CidToGidMap private constructor(
    private val table: IntArray?,    // null = identity
) {
    fun map(cid: Int): Int {
        if (table == null) return cid          // /Identity
        return table.getOrNull(cid) ?: 0       // out-of-range → .notdef
    }

    companion object {
        fun from(obj: Any?): CidToGidMap = when (obj) {
            is PdfName -> if (obj.value == "Identity") CidToGidMap(null) else CidToGidMap(null)
            is PdfStream -> CidToGidMap(parseStream(obj))
            null -> CidToGidMap(null)
            else -> CidToGidMap(null)
        }

        private fun parseStream(stream: PdfStream): IntArray {
            val bytes = FilterChain.decode(stream)
            val n = bytes.size / 2
            return IntArray(n) { i ->
                ((bytes[i * 2].toInt() and 0xFF) shl 8) or (bytes[i * 2 + 1].toInt() and 0xFF)
            }
        }
    }
}

/* ─── /W widths array (ISO 32000-1 §9.7.4.3) ──────────────────────────────── */

/**
 * Variable-format CID width table. We parse the array once and resolve via
 * binary search over (cidStart, cidEnd, perGlyphIndex) — most documents
 * keep the array small (~100 entries) so the lookup is fine without an
 * interval tree.
 */
internal class CidWidthTable private constructor(
    private val starts: IntArray,
    private val ends: IntArray,
    /** widths[i] is the per-CID width for ranges where each glyph is separate; -1 means use [singleWidth]. */
    private val perCidIndex: IntArray,
    private val flatWidths: DoubleArray,
    private val singleWidth: DoubleArray,
    private val defaultWidth: Double,
    /** True when [starts] is ascending and ranges don't overlap → binary search. */
    private val sorted: Boolean,
) {

    fun widthOf(cid: Int): Double {
        // widthOf runs once per glyph laid out — for CJK pages that's thousands
        // of calls. When the /W ranges are ascending & disjoint (the usual case)
        // binary-search them; otherwise fall back to a linear scan for safety.
        if (sorted) {
            var lo = 0
            var hi = starts.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when {
                    cid < starts[mid] -> hi = mid - 1
                    cid > ends[mid] -> lo = mid + 1
                    else -> return widthAt(mid, cid)
                }
            }
            return defaultWidth
        }
        for (i in starts.indices) {
            if (cid in starts[i]..ends[i]) return widthAt(i, cid)
        }
        return defaultWidth
    }

    private fun widthAt(i: Int, cid: Int): Double =
        if (perCidIndex[i] < 0) {
            singleWidth[i]
        } else {
            flatWidths.getOrNull(perCidIndex[i] + (cid - starts[i])) ?: defaultWidth
        }

    companion object {
        fun from(descendant: PdfDictionary, refs: IndirectResolver): CidWidthTable {
            val defaultW = (descendant.getInt("DW")?.toInt()?.toDouble())
                ?: (descendant.getReal("DW"))
                ?: 1000.0
            // /W is frequently an INDIRECT reference (Word emits it that way), so
            // it must be resolved — otherwise every CID falls back to /DW and the
            // text spreads out (broken Arabic joining, spaced-out Latin/Cyrillic).
            val arr = descendant.getArray("W", refs) ?: return empty(defaultW)

            val starts = mutableListOf<Int>()
            val ends = mutableListOf<Int>()
            val perCidIdx = mutableListOf<Int>()
            val flat = mutableListOf<Double>()
            val singles = mutableListOf<Double>()

            var i = 0
            while (i < arr.size) {
                val cidStart = (arr.getOrNull(i) as? PdfInt)?.value?.toInt() ?: break
                val second = arr.getOrNull(i + 1) ?: break
                when (second) {
                    is PdfArray -> {
                        // Form 1: [ cidStart [ w1 w2 ... wn ] ]
                        val widths = second.map { it.toDoubleOr(defaultW) }
                        starts.add(cidStart)
                        ends.add(cidStart + widths.size - 1)
                        perCidIdx.add(flat.size)
                        flat.addAll(widths)
                        singles.add(0.0)
                        i += 2
                    }
                    is PdfInt, is PdfReal -> {
                        // Form 2: [ cidStart cidEnd width ]
                        val cidEnd = second.toIntOr(cidStart)
                        val w = arr.getOrNull(i + 2)?.toDoubleOr(defaultW) ?: break
                        starts.add(cidStart)
                        ends.add(cidEnd)
                        perCidIdx.add(-1)
                        flat.add(0.0)   // unused
                        singles.add(w)
                        i += 3
                    }
                    else -> { i++ }
                }
            }

            // Detect whether ranges are ascending & disjoint so widthOf() can
            // binary-search. /W is conventionally ordered, but the spec doesn't
            // require it, so verify rather than assume.
            var sorted = true
            for (j in 1 until starts.size) {
                if (starts[j] <= ends[j - 1]) { sorted = false; break }
            }

            return CidWidthTable(
                starts.toIntArray(), ends.toIntArray(),
                perCidIdx.toIntArray(), flat.toDoubleArray(), singles.toDoubleArray(),
                defaultW, sorted,
            )
        }

        private fun empty(default: Double): CidWidthTable =
            CidWidthTable(IntArray(0), IntArray(0), IntArray(0), DoubleArray(0), DoubleArray(0), default, sorted = true)

        private fun io.github.yuroyami.kitepdf.parser.PdfObject.toIntOr(fallback: Int): Int = when (this) {
            is PdfInt -> value.toInt()
            is PdfReal -> value.toInt()
            else -> fallback
        }

        private fun io.github.yuroyami.kitepdf.parser.PdfObject.toDoubleOr(fallback: Double): Double = when (this) {
            is PdfInt -> value.toDouble()
            is PdfReal -> value
            else -> fallback
        }
    }
}
