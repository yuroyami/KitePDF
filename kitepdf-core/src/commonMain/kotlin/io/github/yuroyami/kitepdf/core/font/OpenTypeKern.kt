package io.github.yuroyami.kitepdf.core.font

/**
 * Horizontal kerning for an SFNT font, from either the legacy `kern` table
 * (format 0) or OpenType `GPOS` pair adjustment (lookup type 2, the `kern`
 * feature). Returns the x-advance adjustment between two glyphs in font design
 * units — callers normalise to 1/1000 em like every other advance.
 *
 * This is the positioning half of OpenType shaping; the substitution half
 * (`GSUB`: ligatures, Arabic contextual joining) is a separate, larger effort.
 * Only horizontal `xAdvance` of the first glyph is read (the by-far common case);
 * mark/cursive positioning and vertical kerning are out of scope.
 */
public class OpenTypeKern private constructor(
    private val legacy: Map<Long, Int>,          // (left shl 32 or right) -> value
    private val gpos: List<PairPosSubtable>,
) {
    /** Kerning adjustment (font units) applied to [left]'s advance before [right]. */
    public fun between(left: Int, right: Int): Int {
        legacy[(left.toLong() shl 32) or right.toLong()]?.let { if (it != 0) return it }
        for (st in gpos) { val v = st.value(left, right); if (v != 0) return v }
        return 0
    }

    public val isEmpty: Boolean get() = legacy.isEmpty() && gpos.isEmpty()

    public companion object {
        public fun from(kernTable: ByteArray?, gposTable: ByteArray?): OpenTypeKern? {
            val legacy = kernTable?.let { runCatching { parseKern(it) }.getOrNull() } ?: emptyMap()
            val gpos = gposTable?.let { runCatching { parseGposKern(it) }.getOrNull() } ?: emptyList()
            if (legacy.isEmpty() && gpos.isEmpty()) return null
            return OpenTypeKern(legacy, gpos)
        }

        // ---- legacy `kern` table (OpenType flavour, format 0 horizontal) -----
        private fun parseKern(b: ByteArray): Map<Long, Int> {
            val r = R(b)
            r.u16() // version 0
            val nTables = r.u16()
            val out = HashMap<Long, Int>()
            var pos = 4
            repeat(nTables) {
                r.seek(pos)
                r.u16() // subtable version
                val len = r.u16()
                val coverage = r.u16()
                val format = coverage ushr 8
                val horizontal = (coverage and 0x1) != 0
                if (format == 0 && horizontal) {
                    val nPairs = r.u16()
                    r.u16(); r.u16(); r.u16() // searchRange/entrySelector/rangeShift
                    repeat(nPairs) {
                        val left = r.u16(); val right = r.u16(); val value = r.s16()
                        out[(left.toLong() shl 32) or right.toLong()] = value
                    }
                }
                if (len <= 0) return out
                pos += len
            }
            return out
        }

        // ---- GPOS `kern` feature, lookup type 2 (pair adjustment) ------------
        private fun parseGposKern(b: ByteArray): List<PairPosSubtable> {
            val r = R(b)
            r.u16(); r.u16() // major/minor
            val scriptListOff = r.u16()
            val featureListOff = r.u16()
            val lookupListOff = r.u16()

            // FeatureList → indices of every feature tagged 'kern'.
            val kernLookupIdx = LinkedHashSet<Int>()
            r.seek(featureListOff)
            val featureCount = r.u16()
            for (i in 0 until featureCount) {
                val rec = featureListOff + 2 + i * 6
                val tag = R(b).let { it.seek(rec); it.u32() }
                val featOff = R(b).let { it.seek(rec + 4); it.u16() }
                if (tag == 0x6B65726EL) { // 'kern'
                    val fb = featureListOff + featOff
                    r.seek(fb); r.u16() // featureParams
                    val n = r.u16()
                    repeat(n) { kernLookupIdx.add(r.u16()) }
                }
            }
            if (kernLookupIdx.isEmpty()) return emptyList()

            // LookupList → the referenced type-2 subtables.
            r.seek(lookupListOff)
            val lookupCount = r.u16()
            val lookupOffsets = IntArray(lookupCount) { r.u16() }
            val out = ArrayList<PairPosSubtable>()
            for (li in kernLookupIdx) {
                if (li !in 0 until lookupCount) continue
                val lookupBase = lookupListOff + lookupOffsets[li]
                r.seek(lookupBase)
                val type = r.u16()
                r.u16() // lookupFlag
                val subCount = r.u16()
                val subOffsets = IntArray(subCount) { r.u16() }
                if (type != 2) continue
                for (so in subOffsets) {
                    runCatching { parsePairPos(b, lookupBase + so) }.getOrNull()?.let { out.add(it) }
                }
            }
            return out
        }

        private fun parsePairPos(b: ByteArray, base: Int): PairPosSubtable? {
            val r = R(b); r.seek(base)
            val format = r.u16()
            val coverageOff = r.u16()
            val valueFormat1 = r.u16()
            val valueFormat2 = r.u16()
            val coverage = parseCoverage(b, base + coverageOff)
            val v1size = valueRecordSize(valueFormat1)
            val v2size = valueRecordSize(valueFormat2)
            return when (format) {
                1 -> {
                    val pairSetCount = r.u16()
                    val pairSetOffsets = IntArray(pairSetCount) { r.u16() }
                    // For each covered first glyph, map secondGlyph -> xAdvance.
                    val sets = HashMap<Int, HashMap<Int, Int>>()
                    for ((gid, covIdx) in coverage) {
                        if (covIdx >= pairSetCount) continue
                        val ps = base + pairSetOffsets[covIdx]
                        val pr = R(b); pr.seek(ps)
                        val pairCount = pr.u16()
                        val m = HashMap<Int, Int>()
                        for (p in 0 until pairCount) {
                            val recBase = ps + 2 + p * (2 + v1size + v2size)
                            val rr = R(b); rr.seek(recBase)
                            val second = rr.u16()
                            val xAdv = readXAdvance(rr, valueFormat1)
                            if (xAdv != 0) m[second] = xAdv
                        }
                        if (m.isNotEmpty()) sets[gid] = m
                    }
                    PairPosSubtable(format1 = sets)
                }
                2 -> {
                    val classDef1Off = r.u16()
                    val classDef2Off = r.u16()
                    val class1Count = r.u16()
                    val class2Count = r.u16()
                    val cd1 = parseClassDef(b, base + classDef1Off)
                    val cd2 = parseClassDef(b, base + classDef2Off)
                    val recStart = base + 16
                    val recSize = v1size + v2size
                    val values = IntArray(class1Count * class2Count)
                    for (c1 in 0 until class1Count) for (c2 in 0 until class2Count) {
                        val recBase = recStart + (c1 * class2Count + c2) * recSize
                        val rr = R(b); rr.seek(recBase)
                        values[c1 * class2Count + c2] = readXAdvance(rr, valueFormat1)
                    }
                    PairPosSubtable(
                        coverageFmt2 = coverage.keys,
                        classDef1 = cd1, classDef2 = cd2,
                        class2Count = class2Count, classValues = values,
                    )
                }
                else -> null
            }
        }

        /** Bytes in a ValueRecord = 2 per set bit in the value format mask. */
        private fun valueRecordSize(fmt: Int): Int {
            var n = 0; var f = fmt
            while (f != 0) { n += f and 1; f = f ushr 1 }
            return n * 2
        }

        /** Read the xAdvance (font units) from a ValueRecord, skipping earlier fields. */
        private fun readXAdvance(r: R, fmt: Int): Int {
            if (fmt and 0x0001 != 0) r.s16() // xPlacement
            if (fmt and 0x0002 != 0) r.s16() // yPlacement
            val x = if (fmt and 0x0004 != 0) r.s16() else 0 // xAdvance
            return x
        }

        private fun parseCoverage(b: ByteArray, off: Int): Map<Int, Int> {
            val r = R(b); r.seek(off)
            val out = HashMap<Int, Int>()
            when (r.u16()) {
                1 -> { val n = r.u16(); for (i in 0 until n) out[r.u16()] = i }
                2 -> {
                    val n = r.u16()
                    for (i in 0 until n) {
                        val start = r.u16(); val end = r.u16(); val startIdx = r.u16()
                        for (g in start..end) out[g] = startIdx + (g - start)
                    }
                }
            }
            return out
        }

        private fun parseClassDef(b: ByteArray, off: Int): Map<Int, Int> {
            val r = R(b); r.seek(off)
            val out = HashMap<Int, Int>()
            when (r.u16()) {
                1 -> {
                    val start = r.u16(); val n = r.u16()
                    for (i in 0 until n) { val c = r.u16(); if (c != 0) out[start + i] = c }
                }
                2 -> {
                    val n = r.u16()
                    for (i in 0 until n) {
                        val s = r.u16(); val e = r.u16(); val c = r.u16()
                        if (c != 0) for (g in s..e) out[g] = c
                    }
                }
            }
            return out
        }
    }

    /** One GPOS type-2 subtable, in either pair (format 1) or class (format 2) shape. */
    private class PairPosSubtable(
        private val format1: Map<Int, Map<Int, Int>>? = null,
        private val coverageFmt2: Set<Int>? = null,
        private val classDef1: Map<Int, Int>? = null,
        private val classDef2: Map<Int, Int>? = null,
        private val class2Count: Int = 0,
        private val classValues: IntArray? = null,
    ) {
        fun value(left: Int, right: Int): Int {
            format1?.get(left)?.get(right)?.let { return it }
            if (coverageFmt2 != null && classValues != null && left in coverageFmt2) {
                val c1 = classDef1?.get(left) ?: 0
                val c2 = classDef2?.get(right) ?: 0
                val idx = c1 * class2Count + c2
                if (idx in classValues.indices) return classValues[idx]
            }
            return 0
        }
    }

    /** Minimal big-endian cursor over a table blob. */
    private class R(val b: ByteArray) {
        private var p = 0
        fun seek(o: Int) { p = o }
        fun u16(): Int { val v = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF); p += 2; return v }
        fun s16(): Int { val v = u16(); return if (v >= 0x8000) v - 0x10000 else v }
        fun u32(): Long {
            val v = ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
                ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
            p += 4; return v
        }
    }
}
