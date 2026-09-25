package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.KitePath

/**
 * Type 0 composite font (ISO 32000-1 §9.7). Wraps a CIDFont descendant
 * with an `/Encoding` CMap that turns byte sequences into CIDs.
 *
 * The flow:
 *   bytes ── /Encoding CMap ──▶ CID ── /CIDToGIDMap ──▶ GID ──▶ outline
 *
 * For text the parent Type 0's `/ToUnicode` (when present) handles bytes →
 * unicode directly. Otherwise [textOf] follows ISO 32000-1, 9.10.2: the code of
 * a Unicode-keyed CMap is its own text, and a CID of an Adobe CJK collection
 * finds its text through [CidUnicode].
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
    /** True when the CMap's writing mode is 1: glyphs stack down the page (ISO 32000-1, 9.7.4.3). */
    val vertical: Boolean = false,
    /** CID-keyed vertical metrics from /W2 + /DW2, read only for a [vertical] font. */
    private val verticalWidths: CidVerticalTable = CidVerticalTable.DEFAULT,
    /** True when /Encoding is a predefined CMap whose codes are UTF-16, such as UniJIS-UCS2-H. */
    private val unicodeKeyed: Boolean = false,
    /** The Adobe collection the CIDs belong to, such as Japan1, or null for another collection. */
    private val ordering: String? = null,
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
        // A CID-keyed CFF program selects the glyph through its charset (ISO 32000-1, 9.7.4.2).
        cff?.let { return it.glyphSpaceOutline(it.glyphIdForCid(gid)) }
        return null
    }

    fun gidFor(cid: Int): Int = cidToGid.map(cid).let { gid -> cff?.glyphIdForCid(gid)?.coerceAtLeast(0) ?: gid }
    fun widthOf(cid: Int): Double = widths.widthOf(cid)
    fun verticalMetricsOf(cid: Int): PdfVerticalMetrics = verticalWidths.metrics(cid, widthOf(cid))

    /** Decode the full byte run to unicode via ToUnicode CMap (preferred) or [textOf]. */
    fun decode(bytes: ByteArray): String {
        toUnicode?.let { return it.decodeAll(bytes) }
        // A code without any text shows one replacement character.
        return buildString {
            for (u in codeUnits(bytes)) append(textOf(bytes, u.byteOffset, u.byteCount, u.cid).ifEmpty { "\uFFFD" })
        }
    }

    /**
     * The Unicode text of the code of [count] bytes at [offset], whose CID is [cid]. ISO
     * 32000-1, 9.10.2: the ToUnicode map first. Without one, the code of a Unicode-keyed
     * CMap is its own UTF-16 text, and a CID of an Adobe CJK collection finds its text in
     * that collection's UCS2 CMap (#309). Empty when none of them applies.
     */
    fun textOf(bytes: ByteArray, offset: Int, count: Int, cid: Int): String {
        toUnicode?.let { return it.decodeAll(bytes.copyOfRange(offset, offset + count)) }
        if (unicodeKeyed) {
            return CharArray(count / 2) { i ->
                (((bytes[offset + 2 * i].toInt() and 0xFF) shl 8) or (bytes[offset + 2 * i + 1].toInt() and 0xFF)).toChar()
            }.concatToString()
        }
        // CID 0 is .notdef, which draws nothing and has no text.
        if (cid == 0) return ""
        return ordering?.let { CidUnicode.text(it, cid) } ?: ""
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

            // Resolve /Encoding: either a predefined name (Identity-H/V, CJK) or
            // an embedded CMap stream (ISO 32000-1 §9.7.5.3). A stream gives us
            // real codespace segmentation + code→CID mapping; a name resolves to
            // a predefined [CodeUnitReader] (Identity exact, CJK degraded).
            val encodingObj = parentDict["Encoding"]?.resolve(refs)
            val encodingName = (encodingObj as? PdfName)?.value
            val encodingCMap = (encodingObj as? PdfStream)?.let {
                runCatching { CMap.parse(FilterChain.decode(it)) }.getOrNull()
            }
            val codeReader = PredefinedCMaps.reader(encodingName)
            // Writing mode 1 comes from an embedded CMap's stream /WMode or its program, or
            // from a predefined name such as Identity-V (ISO 32000-1, 9.7.5.2 and 9.7.5.3).
            val vertical = if (encodingObj is PdfStream) {
                (encodingObj.dict.getInt("WMode")?.toInt() ?: encodingCMap?.writingMode) == 1
            } else {
                PredefinedCMaps.isVertical(encodingName)
            }

            // Resolve descendant's embedded outlines.
            val descriptor = descendant["FontDescriptor"]?.resolve(refs) as? PdfDictionary
            // /FontFile3 holds a bare CFF program or, since PDF 1.6, a whole OpenType font.
            val fontFile2 = descriptor?.let { loadTtf(it, refs) }
            val fontFile3 = if (fontFile2 == null) descriptor?.let { loadFontFile3(it, refs) } else null
            val ttf = fontFile2 ?: fontFile3?.let { FontFile3.trueType(it) }
            val cff = if (ttf == null) fontFile3?.let { FontFile3.cff(it) } else null

            val cidToGid = CidToGidMap.from(descendant["CIDToGIDMap"]?.resolve(refs))
            val widths = CidWidthTable.from(descendant, refs)
            val toUnicode = loadToUnicodeOnParent(parentDict, refs)
            val baseFont = parentDict.getName("BaseFont") ?: descendant.getName("BaseFont") ?: "Unknown"

            val verticalWidths = if (vertical) CidVerticalTable.from(descendant, refs) else CidVerticalTable.DEFAULT
            // ISO 32000-1, 9.10.2 takes the collection from the CMap, and the font's own
            // CIDSystemInfo names it for an Identity CMap or an embedded one.
            val ordering = PredefinedCMaps.ordering(encodingName) ?: adobeOrdering(descendant, refs)
            return CompositeFont(
                baseFont, descendantSubtype, ttf, cff, codeReader, encodingCMap, cidToGid, widths, toUnicode,
                vertical, verticalWidths,
                unicodeKeyed = encodingCMap == null && PredefinedCMaps.isUnicodeKeyed(encodingName),
                ordering = ordering,
            )
        }

        /** The ordering of the font's CIDSystemInfo when it names one of the four Adobe CJK collections, else null. */
        private fun adobeOrdering(descendant: PdfDictionary, refs: IndirectResolver): String? {
            val info = descendant["CIDSystemInfo"]?.resolve(refs) as? PdfDictionary ?: return null
            val registry = (info["Registry"]?.resolve(refs) as? io.github.yuroyami.kitepdf.core.parser.PdfString)?.asText()
            val ordering = (info["Ordering"]?.resolve(refs) as? io.github.yuroyami.kitepdf.core.parser.PdfString)?.asText()
            return ordering.takeIf { registry == "Adobe" && it in ADOBE_CJK_ORDERINGS }
        }

        private val ADOBE_CJK_ORDERINGS = setOf("Japan1", "GB1", "CNS1", "Korea1")

        private fun loadTtf(descriptor: PdfDictionary, refs: IndirectResolver): TrueTypeFont? {
            val stream = (descriptor["FontFile2"]?.resolve(refs) as? PdfStream) ?: return null
            return runCatching { TrueTypeFont.parse(FilterChain.decode(stream)) }.getOrNull()
        }

        /** The decoded `/FontFile3` stream, or null. [FontFile3] reads what it holds. */
        private fun loadFontFile3(descriptor: PdfDictionary, refs: IndirectResolver): ByteArray? {
            val stream = (descriptor["FontFile3"]?.resolve(refs) as? PdfStream) ?: return null
            return runCatching { FilterChain.decode(stream) }.getOrNull()
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
 * Maps a CID → GID. Either /Identity (CID == GID, the default for
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

/* ─── /W2 vertical metrics (ISO 32000-1 §9.7.4.3) ─────────────────────────── */

/**
 * CID-keyed vertical metrics for a font in vertical writing mode. `/W2` takes the two
 * forms of `/W`, with three numbers per CID, `w1y vx vy`, instead of one width:
 *   `[ cid [ w1y vx vy  w1y vx vy … ] ]`  and  `[ cidStart cidEnd w1y vx vy ]`.
 * A CID that `/W2` does not list takes `/DW2`, `[vy w1y]`, whose default is
 * `[880 -1000]`, and a `vx` of half its horizontal width.
 */
internal class CidVerticalTable private constructor(
    private val starts: IntArray,
    private val ends: IntArray,
    /** Where each range's first `w1y vx vy` triple starts in [triples]. */
    private val offsets: IntArray,
    /** True when a range lists one triple per CID, false when one triple covers it. */
    private val perCid: BooleanArray,
    private val triples: DoubleArray,
    private val defaultOriginY: Double,
    private val defaultDisplacement: Double,
) {

    /** The metrics of [cid], whose horizontal width is [width]. The first range that holds it wins. */
    fun metrics(cid: Int, width: Double): PdfVerticalMetrics {
        for (i in starts.indices) {
            if (cid < starts[i] || cid > ends[i]) continue
            val at = offsets[i] + if (perCid[i]) 3 * (cid - starts[i]) else 0
            return PdfVerticalMetrics(triples[at], triples[at + 1], triples[at + 2])
        }
        return PdfVerticalMetrics(defaultDisplacement, width / 2.0, defaultOriginY)
    }

    companion object {
        /** No /W2, and the /DW2 default. */
        val DEFAULT = CidVerticalTable(IntArray(0), IntArray(0), IntArray(0), BooleanArray(0), DoubleArray(0), 880.0, -1000.0)

        fun from(descendant: PdfDictionary, refs: IndirectResolver): CidVerticalTable {
            val dw2 = descendant.getArray("DW2", refs)
            val originY = dw2?.getOrNull(0)?.numberOrNull() ?: 880.0
            val displacement = dw2?.getOrNull(1)?.numberOrNull() ?: -1000.0
            val starts = mutableListOf<Int>()
            val ends = mutableListOf<Int>()
            val offsets = mutableListOf<Int>()
            val perCid = mutableListOf<Boolean>()
            val triples = mutableListOf<Double>()
            val w2 = descendant.getArray("W2", refs)
            var i = 0
            while (w2 != null && i < w2.size) {
                val first = (w2.getOrNull(i) as? PdfInt)?.value?.toInt() ?: break
                when (val second = w2.getOrNull(i + 1)) {
                    is PdfArray -> {
                        // A number that is not one reads as 0 rather than shifting the triples after it.
                        val values = second.map { it.numberOrNull() ?: 0.0 }
                        val count = values.size / 3
                        if (count > 0) {
                            starts.add(first); ends.add(first + count - 1)
                            offsets.add(triples.size); perCid.add(true)
                            triples.addAll(values.subList(0, 3 * count))
                        }
                        i += 2
                    }
                    is PdfInt, is PdfReal -> {
                        val last = second.numberOrNull()?.toInt() ?: break
                        val w1 = w2.getOrNull(i + 2)?.numberOrNull() ?: break
                        val vx = w2.getOrNull(i + 3)?.numberOrNull() ?: break
                        val vy = w2.getOrNull(i + 4)?.numberOrNull() ?: break
                        starts.add(first); ends.add(last)
                        offsets.add(triples.size); perCid.add(false)
                        triples.add(w1); triples.add(vx); triples.add(vy)
                        i += 5
                    }
                    else -> i++
                }
            }
            return CidVerticalTable(
                starts.toIntArray(), ends.toIntArray(), offsets.toIntArray(), perCid.toBooleanArray(),
                triples.toDoubleArray(), originY, displacement,
            )
        }

        private fun PdfObject.numberOrNull(): Double? = when (this) {
            is PdfInt -> value.toDouble()
            is PdfReal -> value
            else -> null
        }
    }
}

/* ─── /W widths array (ISO 32000-1 §9.7.4.3) ──────────────────────────────── */

/**
 * Variable-format CID width table. We parse the array once and resolve via
 * binary search over (cidStart, cidEnd, perGlyphIndex). Most documents
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
        // widthOf runs once per glyph laid out. For CJK pages that's thousands
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
            // we must resolve it, otherwise every CID falls back to /DW and the
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

        private fun io.github.yuroyami.kitepdf.core.parser.PdfObject.toIntOr(fallback: Int): Int = when (this) {
            is PdfInt -> value.toInt()
            is PdfReal -> value.toInt()
            else -> fallback
        }

        private fun io.github.yuroyami.kitepdf.core.parser.PdfObject.toDoubleOr(fallback: Double): Double = when (this) {
            is PdfInt -> value.toDouble()
            is PdfReal -> value
            else -> fallback
        }
    }
}
