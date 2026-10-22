package io.github.yuroyami.kitepdf.core.font

/**
 * GPOS mark-to-base attachment (lookup type 4): the positioning offset that places
 * a combining mark's anchor onto its base glyph's anchor (Arabic harakat, Hebrew
 * points, Latin/Vietnamese diacritics). The offset is returned in font design units
 * (base anchor − mark anchor); the caller applies it to the mark glyph's origin
 * without touching the pen advance (marks are zero-advance).
 *
 * Three lookup types, all anchor-based and all consulted (the `mark` and
 * `mkmk` features are on by default, so no feature filtering is needed):
 *
 *  - **type 4, mark-to-base**: a mark onto a letter.
 *  - **type 6, mark-to-mark**: a mark onto the mark below it, which is what
 *    stacks two diacritics instead of overprinting them.
 *  - **type 5, mark-to-ligature**: a mark onto one component of a ligature.
 *
 * Anchor formats 1/2/3 all read; format 3's device tables are ignored, so the
 * plain x/y coordinate is used.
 */
public class OpenTypeMarks private constructor(
    private val markToBase: List<MarkBase>,
    private val markToMark: List<MarkBase>,
    private val markToLigature: List<MarkLigature>,
) {

    /** Attachment offset (font units) placing [markGid] on [baseGid], or null. */
    public fun offset(baseGid: Int, markGid: Int): Pair<Double, Double>? {
        for (st in markToBase) st.offset(baseGid, markGid)?.let { return it }
        return null
    }

    /**
     * Offset placing [markGid] on top of [belowGid], another mark, relative to
     * that mark's own drawn origin. This is what stacks diacritics.
     */
    public fun stackOffset(belowGid: Int, markGid: Int): Pair<Double, Double>? {
        for (st in markToMark) st.offset(belowGid, markGid)?.let { return it }
        return null
    }

    /** Offset placing [markGid] on [component] of the ligature [ligGid], or null. */
    public fun ligatureOffset(ligGid: Int, markGid: Int, component: Int): Pair<Double, Double>? {
        for (st in markToLigature) st.offset(ligGid, markGid, component)?.let { return it }
        return null
    }

    public companion object {
        public fun from(gpos: ByteArray?): OpenTypeMarks? {
            gpos ?: return null
            val parsed = runCatching { parse(gpos) }.getOrNull() ?: return null
            val (base, mark, liga) = parsed
            return if (base.isEmpty() && mark.isEmpty() && liga.isEmpty()) null
            else OpenTypeMarks(base, mark, liga)
        }

        private fun parse(b: ByteArray): Triple<List<MarkBase>, List<MarkBase>, List<MarkLigature>> {
            val r = R(b)
            r.u16(); r.u16() // major/minor
            r.u16() // scriptList
            r.u16() // featureList
            val lookupListOff = r.u16()
            r.seek(lookupListOff)
            val lookupCount = r.u16()
            val lookupOffsets = IntArray(lookupCount) { r.u16() }
            val toBase = ArrayList<MarkBase>()
            val toMark = ArrayList<MarkBase>()
            val toLiga = ArrayList<MarkLigature>()
            for (lo in lookupOffsets) {
                val base = lookupListOff + lo
                r.seek(base)
                var type = r.u16()
                r.u16() // flag
                val subCount = r.u16()
                val subOffsets = IntArray(subCount) { r.u16() }
                for (so in subOffsets) {
                    var subBase = base + so
                    var effType = type
                    if (type == 9) { // extension positioning: redirect
                        val er = R(b); er.seek(subBase)
                        er.u16() // format
                        effType = er.u16()
                        subBase += er.u32().toInt()
                    }
                    when (effType) {
                        // 4 and 6 share a layout: coverage, coverage, classes,
                        // mark array, and an array of anchors per class.
                        4 -> runCatching { parseMarkBase(b, subBase) }.getOrNull()?.let { toBase.add(it) }
                        6 -> runCatching { parseMarkBase(b, subBase) }.getOrNull()?.let { toMark.add(it) }
                        5 -> runCatching { parseMarkLigature(b, subBase) }.getOrNull()?.let { toLiga.add(it) }
                    }
                }
            }
            return Triple(toBase, toMark, toLiga)
        }

        /**
         * Type 5. Same head as type 4, but each covered ligature carries a
         * per-component list of anchors rather than one.
         */
        private fun parseMarkLigature(b: ByteArray, base: Int): MarkLigature? {
            val r = R(b); r.seek(base)
            if (r.u16() != 1) return null // posFormat
            val markCov = readCoverage(b, base + r.u16())
            val ligCov = readCoverage(b, base + r.u16())
            val markClassCount = r.u16()
            val markArrayOff = base + r.u16()
            val ligArrayOff = base + r.u16()

            val mr = R(b); mr.seek(markArrayOff)
            val markCount = mr.u16()
            val markClass = IntArray(markCount)
            val markAnchor = Array(markCount) { 0.0 to 0.0 }
            for (i in 0 until markCount) {
                markClass[i] = mr.u16()
                val anchorOff = mr.u16()
                markAnchor[i] = if (anchorOff == 0) 0.0 to 0.0 else readAnchor(b, markArrayOff + anchorOff)
            }

            val lr = R(b); lr.seek(ligArrayOff)
            val ligCount = lr.u16()
            val ligOffsets = IntArray(ligCount) { lr.u16() }
            val ligAnchors = Array(ligCount) { emptyArray<Array<Pair<Double, Double>?>>() }
            for (i in 0 until ligCount) {
                val attachBase = ligArrayOff + ligOffsets[i]
                val ar = R(b); ar.seek(attachBase)
                val componentCount = ar.u16()
                ligAnchors[i] = Array(componentCount) { arrayOfNulls<Pair<Double, Double>>(markClassCount) }
                for (c in 0 until componentCount) {
                    for (k in 0 until markClassCount) {
                        val off = ar.u16()
                        ligAnchors[i][c][k] = if (off == 0) null else readAnchor(b, attachBase + off)
                    }
                }
            }
            return MarkLigature(markCov, ligCov, markClass, markAnchor, ligAnchors)
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
            r.u16() // anchorFormat (1/2/3): device tables in 3 ignored
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

    private class MarkLigature(
        private val markCov: Map<Int, Int>,
        private val ligCov: Map<Int, Int>,
        private val markClass: IntArray,
        private val markAnchor: Array<Pair<Double, Double>>,
        private val ligAnchors: Array<Array<Array<Pair<Double, Double>?>>>,
    ) {
        fun offset(ligGid: Int, markGid: Int, component: Int): Pair<Double, Double>? {
            val mi = markCov[markGid] ?: return null
            val li = ligCov[ligGid] ?: return null
            if (mi >= markClass.size || li >= ligAnchors.size) return null
            val components = ligAnchors[li]
            if (components.isEmpty()) return null
            val c = component.coerceIn(0, components.size - 1)
            val ligA = components[c].getOrNull(markClass[mi]) ?: return null
            val markA = markAnchor[mi]
            return (ligA.first - markA.first) to (ligA.second - markA.second)
        }
    }

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
