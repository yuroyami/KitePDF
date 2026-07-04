package io.github.yuroyami.kitepdf.font

/**
 * The substitution half of OpenType shaping (`GSUB`), scoped to the two lookup
 * types that carry the highest-value features for reflowable text:
 *
 *  - **Type 1 single substitution** — drives Arabic contextual joining
 *    (`init`/`medi`/`fina`/`isol`) and simple 1:1 alternates. A glyph maps to one
 *    replacement glyph.
 *  - **Type 4 ligature substitution** — `liga`/`rlig` (fi/fl, Arabic lam-alef): a
 *    run of component glyphs collapses to one ligature glyph.
 *
 * Lookups are indexed by the feature tag that references them, so a shaper can ask
 * "apply feature X to this glyph". Type 7 (extension) is unwrapped. Contextual /
 * chaining lookups (types 5/6/8) and GPOS mark positioning are out of scope.
 */
class OpenTypeGsub private constructor(
    private val single: Map<String, MutableMap<Int, Int>>,
    private val liga: Map<String, MutableMap<Int, MutableList<LigRule>>>,
) {
    /** A ligature rule: [rest] are the 2nd..nth component glyph ids, [lig] the result. */
    class LigRule(val rest: IntArray, val lig: Int)

    /** The single-substitution glyph for [gid] under [feature], or null. */
    fun single(feature: String, gid: Int): Int? = single[feature]?.get(gid)

    /** Ligature rules whose first component is [firstGid] under [feature], longest first. */
    fun ligatures(feature: String, firstGid: Int): List<LigRule>? = liga[feature]?.get(firstGid)

    val hasArabicJoining: Boolean
        get() = single.keys.any { it == "init" || it == "medi" || it == "fina" }

    companion object {
        private val WANT = setOf("init", "medi", "fina", "isol", "liga", "rlig", "calt")

        fun from(gsub: ByteArray?): OpenTypeGsub? {
            gsub ?: return null
            return runCatching { parse(gsub) }.getOrNull()
        }

        private fun parse(b: ByteArray): OpenTypeGsub? {
            val r = R(b)
            r.u16(); r.u16() // major/minor
            r.u16() // scriptListOffset (script filtering skipped — features apply broadly)
            val featureListOff = r.u16()
            val lookupListOff = r.u16()

            // feature tag -> the lookup indices it references
            val tagLookups = HashMap<String, MutableList<Int>>()
            r.seek(featureListOff)
            val featureCount = r.u16()
            for (i in 0 until featureCount) {
                r.seek(featureListOff + 2 + i * 6)
                val tag = tagString(r.u32())
                val featOff = r.u16()
                if (tag !in WANT) continue
                r.seek(featureListOff + featOff)
                r.u16() // featureParams
                val n = r.u16()
                val ids = tagLookups.getOrPut(tag) { ArrayList() }
                repeat(n) { ids.add(r.u16()) }
            }
            if (tagLookups.isEmpty()) return null

            r.seek(lookupListOff)
            val lookupCount = r.u16()
            val lookupOffsets = IntArray(lookupCount) { r.u16() }

            val single = HashMap<String, MutableMap<Int, Int>>()
            val liga = HashMap<String, MutableMap<Int, MutableList<LigRule>>>()
            for ((tag, ids) in tagLookups) {
                for (li in ids) {
                    if (li !in 0 until lookupCount) continue
                    parseLookup(b, lookupListOff + lookupOffsets[li], tag, single, liga)
                }
            }
            if (single.isEmpty() && liga.isEmpty()) return null
            return OpenTypeGsub(single, liga)
        }

        private fun parseLookup(
            b: ByteArray, base: Int, tag: String,
            single: HashMap<String, MutableMap<Int, Int>>,
            liga: HashMap<String, MutableMap<Int, MutableList<LigRule>>>,
        ) {
            val r = R(b); r.seek(base)
            var type = r.u16()
            r.u16() // lookupFlag
            val subCount = r.u16()
            val subOffsets = IntArray(subCount) { r.u16() }
            for (so in subOffsets) {
                var subBase = base + so
                var effType = type
                if (type == 7) { // extension: redirect to the real type/offset
                    val er = R(b); er.seek(subBase)
                    er.u16() // format
                    effType = er.u16()
                    subBase += er.u32().toInt()
                }
                when (effType) {
                    1 -> parseSingle(b, subBase, single.getOrPut(tag) { HashMap() })
                    4 -> parseLigature(b, subBase, liga.getOrPut(tag) { HashMap() })
                }
            }
        }

        private fun parseSingle(b: ByteArray, base: Int, out: MutableMap<Int, Int>) {
            val r = R(b); r.seek(base)
            val format = r.u16()
            val cov = base + r.u16()
            when (format) {
                1 -> {
                    val delta = r.s16()
                    for (gid in readCoverageOrdered(b, cov)) out[gid] = (gid + delta) and 0xFFFF
                }
                2 -> {
                    val n = r.u16()
                    val subs = IntArray(n) { r.u16() }
                    val covGids = readCoverageOrdered(b, cov)
                    for (i in covGids.indices) if (i < n) out[covGids[i]] = subs[i]
                }
            }
        }

        private fun parseLigature(b: ByteArray, base: Int, out: MutableMap<Int, MutableList<LigRule>>) {
            val r = R(b); r.seek(base)
            r.u16() // format (1)
            val cov = base + r.u16()
            val setCount = r.u16()
            val setOffsets = IntArray(setCount) { r.u16() }
            val covGids = readCoverageOrdered(b, cov)
            for (i in covGids.indices) {
                if (i >= setCount) break
                val firstGid = covGids[i]
                val setBase = base + setOffsets[i]
                val sr = R(b); sr.seek(setBase)
                val ligCount = sr.u16()
                val ligOffsets = IntArray(ligCount) { sr.u16() }
                val rules = out.getOrPut(firstGid) { ArrayList() }
                for (lo in ligOffsets) {
                    val lr = R(b); lr.seek(setBase + lo)
                    val ligGlyph = lr.u16()
                    val compCount = lr.u16()
                    val rest = IntArray((compCount - 1).coerceAtLeast(0)) { lr.u16() }
                    rules.add(LigRule(rest, ligGlyph))
                }
                // Greedy longest-match first.
                rules.sortByDescending { it.rest.size }
            }
        }

        /** Coverage glyph ids in coverage-index order (index i -> glyph). */
        private fun readCoverageOrdered(b: ByteArray, off: Int): IntArray {
            val r = R(b); r.seek(off)
            return when (r.u16()) {
                1 -> { val n = r.u16(); IntArray(n) { r.u16() } }
                2 -> {
                    val n = r.u16()
                    val out = ArrayList<Int>()
                    for (i in 0 until n) {
                        val s = r.u16(); val e = r.u16(); r.u16() // startCoverageIndex
                        for (g in s..e) out.add(g)
                    }
                    out.toIntArray()
                }
                else -> IntArray(0)
            }
        }

        private fun tagString(v: Long): String = buildString {
            append(((v ushr 24) and 0xFF).toInt().toChar())
            append(((v ushr 16) and 0xFF).toInt().toChar())
            append(((v ushr 8) and 0xFF).toInt().toChar())
            append((v and 0xFF).toInt().toChar())
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
