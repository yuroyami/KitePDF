package io.github.yuroyami.kitepdf.core.font

/**
 * GPOS mark-to-base attachment (lookup type 4): the positioning offset that places
 * a combining mark's anchor onto its base glyph's anchor — Arabic harakat, Hebrew
 * points, Latin/Vietnamese diacritics. The offset is returned in font design units
 * (base anchor − mark anchor); the caller applies it to the mark glyph's origin
 * without touching the pen advance (marks are zero-advance).
 *
 * Scope: mark-to-base (type 4), anchor formats 1/2/3 (device tables ignored — the
 * x/y coordinate is used). Mark-to-mark stacking (type 6) and mark-to-ligature
 * (type 5) are not handled yet. All type-4 subtables are consulted (the `mark`
 * feature is on by default), so no feature filtering is needed.
 */
public class OpenTypeMarks private constructor(private val subtables: List<MarkBase>) {

    /** Attachment offset (font units) placing [markGid] on [baseGid], or null. */
    public fun offset(baseGid: Int, markGid: Int): Pair<Double, Double>? {
        for (st in subtables) st.offset(baseGid, markGid)?.let { return it }
        return null
    }

    public companion object {
        public fun from(gpos: ByteArray?): OpenTypeMarks? {
            gpos ?: return null
            val subs = runCatching { parse(gpos) }.getOrNull() ?: return null
            return if (subs.isEmpty()) null else OpenTypeMarks(subs)
        }

        private fun parse(b: ByteArray): List<MarkBase> {
            val r = R(b)
            r.u16(); r.u16() // major/minor
            r.u16() // scriptList
            r.u16() // featureList
            val lookupListOff = r.u16()
            r.seek(lookupListOff)
            val lookupCount = r.u16()
            val lookupOffsets = IntArray(lookupCount) { r.u16() }
            val out = ArrayList<MarkBase>()
            for (lo in lookupOffsets) {
                val base = lookupListOff + lo
                r.seek(base)
                val type = r.u16()
                r.u16() // flag
                val subCount = r.u16()
                val subOffsets = IntArray(subCount) { r.u16() }
                if (type != 4) continue
                for (so in subOffsets) runCatching { parseMarkBase(b, base + so) }.getOrNull()?.let { out.add(it) }
            }
            return out
        }

        private fun parseMarkBase(b: ByteArray, base: Int): MarkBase? {
            val r = R(b); r.seek(base)
            if (r.u16() != 1) return null // posFormat
            val markCov = readCoverage(b, base + r.u16())
            val baseCov = readCoverage(b, base + r.u16())
            val markClassCount = r.u16()
            val markArrayOff = base + r.u16()
            val baseArrayOff = base + r.u16()

            // MarkArray: per mark-coverage index → (class, anchor).
            val mr = R(b); mr.seek(markArrayOff)
            val markCount = mr.u16()
            val markClass = IntArray(markCount)
            val markAnchor = Array(markCount) { 0.0 to 0.0 }
            for (i in 0 until markCount) {
                markClass[i] = mr.u16()
                val anchorOff = mr.u16()
                markAnchor[i] = if (anchorOff == 0) 0.0 to 0.0 else readAnchor(b, markArrayOff + anchorOff)
            }

            // BaseArray: per base-coverage index → anchor per mark class.
            val br = R(b); br.seek(baseArrayOff)
            val baseCount = br.u16()
            val baseAnchors = Array(baseCount) { arrayOfNulls<Pair<Double, Double>>(markClassCount) }
            for (i in 0 until baseCount) {
                for (c in 0 until markClassCount) {
                    val off = br.u16()
                    baseAnchors[i][c] = if (off == 0) null else readAnchor(b, baseArrayOff + off)
                }
            }
            return MarkBase(markCov, baseCov, markClass, markAnchor, baseAnchors)
        }

        private fun readAnchor(b: ByteArray, off: Int): Pair<Double, Double> {
            val r = R(b); r.seek(off)
            r.u16() // anchorFormat (1/2/3) — device tables in 3 ignored
            val x = r.s16(); val y = r.s16()
            return x.toDouble() to y.toDouble()
        }

        private fun readCoverage(b: ByteArray, off: Int): Map<Int, Int> {
            val r = R(b); r.seek(off)
            val out = HashMap<Int, Int>()
            when (r.u16()) {
                1 -> { val n = r.u16(); for (i in 0 until n) out[r.u16()] = i }
                2 -> {
                    val n = r.u16()
                    for (i in 0 until n) {
                        val s = r.u16(); val e = r.u16(); val startIdx = r.u16()
                        for (g in s..e) out[g] = startIdx + (g - s)
                    }
                }
            }
            return out
        }
    }

    private class MarkBase(
        private val markCov: Map<Int, Int>,
        private val baseCov: Map<Int, Int>,
        private val markClass: IntArray,
        private val markAnchor: Array<Pair<Double, Double>>,
        private val baseAnchors: Array<Array<Pair<Double, Double>?>>,
    ) {
        fun offset(baseGid: Int, markGid: Int): Pair<Double, Double>? {
            val mi = markCov[markGid] ?: return null
            val bi = baseCov[baseGid] ?: return null
            if (mi >= markClass.size || bi >= baseAnchors.size) return null
            val cls = markClass[mi]
            val baseA = baseAnchors[bi].getOrNull(cls) ?: return null
            val markA = markAnchor[mi]
            return (baseA.first - markA.first) to (baseA.second - markA.second)
        }
    }

    private class R(val b: ByteArray) {
        private var p = 0
        fun seek(o: Int) { p = o }
        fun u16(): Int { val v = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF); p += 2; return v }
        fun s16(): Int { val v = u16(); return if (v >= 0x8000) v - 0x10000 else v }
    }
}
