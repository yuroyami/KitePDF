package io.github.yuroyami.kitepdf.core.font

/**
 * GPOS mark-to-base attachment (lookup type 4): the positioning offset that places
 * a combining mark's anchor onto its base glyph's anchor (Arabic harakat, Hebrew
 * points, Latin/Vietnamese diacritics). The offset is returned in font design units
 * (base anchor − mark anchor); the caller applies it to the mark glyph's origin
 * without touching the pen advance (marks are zero-advance).
 *
 * Three lookup types, all anchor-based:
 *
 *  - **type 4, mark-to-base**: a mark onto a letter.
 *  - **type 6, mark-to-mark**: a mark onto the mark below it, which is what
 *    stacks two diacritics instead of overprinting them.
 *  - **type 5, mark-to-ligature**: a mark onto one component of a ligature.
 *
 * A [Plan] picks the lookups as HarfBuzz does (#915): those of the `mark`, `mkmk`, `abvm` and `blwm` features of
 * the language system of the text's script and language, in lookup list order. Without a plan,
 * or for a font with no such language system, every lookup of the three types counts, as before.
 * A mark-to-mark lookup's flag picks the mark below: one that `MarkAttachmentType` or
 * `UseMarkFilteringSet` leaves out is passed over, by the classes and sets of GDEF.
 *
 * Anchor formats 1/2/3 all read; format 3's device tables are ignored, so the
 * plain x/y coordinate is used.
 */
public class OpenTypeMarks private constructor(
    private val lookups: List<MarkLookup>,
    private val scripts: Map<String, ScriptRecord>,
    private val features: List<Pair<String, IntArray>>,
    private val markAttach: Map<Int, Int>?,
    private val markSets: List<Set<Int>>,
) {
    /** The lookups that a text of one script and language takes, in lookup list order. */
    public class Plan internal constructor(internal val lookups: List<Int>)

    internal class ScriptRecord(val default: IntArray?, val languages: Map<String, IntArray>)

    internal class MarkLookup(val type: Int, val flag: Int, val markSet: Int, val toBase: List<MarkBase>, val toLigature: List<MarkLigature>)

    private val all = Plan(lookups.indices.toList())

    /**
     * The plan for [script], an OpenType script tag such as `latn`, and [language], an OpenType
     * language system tag such as `VIT `, or a BCP 47 tag such as `vi`, which [OpenTypeLanguage]
     * maps. The default language system of the script stands for a language the font lacks, and
     * `DFLT` for a script it lacks. A font with neither takes every lookup.
     */
    public fun plan(script: String?, language: String?): Plan {
        val record = script?.let { scripts[it] } ?: scripts["DFLT"] ?: scripts["dflt"] ?: return all
        val tag = language?.let { if (it.length == 4 && it.any { c -> c.isUpperCase() }) it else OpenTypeLanguage.tagOf(it) }
        val featureIndices = tag?.let { record.languages[it] } ?: record.default ?: return all
        val chosen = featureIndices.flatMap { fi ->
            val f = features.getOrNull(fi) ?: return@flatMap emptyList()
            if (f.first in MARK_FEATURES) f.second.toList() else emptyList()
        }.distinct().sorted()
        return Plan(chosen)
    }

    private fun lookupsOf(plan: Plan?): List<MarkLookup> = (plan ?: all).lookups.mapNotNull { lookups.getOrNull(it) }

    /** Attachment offset (font units) placing [markGid] on [baseGid], or null. */
    public fun offset(baseGid: Int, markGid: Int, plan: Plan? = null): Pair<Double, Double>? {
        for (l in lookupsOf(plan)) if (l.type == 4 && !passesOver(l, markGid)) for (st in l.toBase) st.offset(baseGid, markGid)?.let { return it }
        return null
    }

    /**
     * Offset placing [markGid] on top of [belowGid], another mark, relative to
     * that mark's own drawn origin. This is what stacks diacritics.
     */
    public fun stackOffset(belowGid: Int, markGid: Int): Pair<Double, Double>? =
        stackOffset(intArrayOf(belowGid), markGid, null)?.first

    /**
     * The mark-to-mark offset of [markGid] and the index in [below] of the mark it goes on: [below]
     * holds the marks before it since the base, the nearest first. Each lookup of [plan] takes the
     * nearest mark that its flag does not pass over, and the first lookup that covers the pair wins.
     */
    public fun stackOffset(below: IntArray, markGid: Int, plan: Plan?): Pair<Pair<Double, Double>, Int>? {
        for (l in lookupsOf(plan)) {
            // A lookup applies only to a mark its flag does not pass over, as HarfBuzz applies it.
            if (l.type != 6 || passesOver(l, markGid)) continue
            val at = below.indices.firstOrNull { !passesOver(l, below[it]) } ?: continue
            for (st in l.toBase) st.offset(below[at], markGid)?.let { return it to at }
        }
        return null
    }

    /** Whether the flag of [lookup] passes over the mark [gid] (OpenType 1.9, "Lookup Table"). */
    private fun passesOver(lookup: MarkLookup, gid: Int): Boolean {
        val flag = lookup.flag
        return when {
            flag and IGNORE_MARKS != 0 -> true
            flag and USE_MARK_FILTERING_SET != 0 -> markSets.getOrNull(lookup.markSet)?.contains(gid) != true
            flag and MARK_ATTACHMENT_TYPE != 0 -> (markAttach?.get(gid) ?: 0) != (flag ushr 8)
            else -> false
        }
    }

    /** Offset placing [markGid] on [component] of the ligature [ligGid], or null. */
    public fun ligatureOffset(ligGid: Int, markGid: Int, component: Int, plan: Plan? = null): Pair<Double, Double>? {
        for (l in lookupsOf(plan)) if (l.type == 5 && !passesOver(l, markGid)) for (st in l.toLigature) st.offset(ligGid, markGid, component)?.let { return it }
        return null
    }

    public companion object {
        /** The features whose lookups place marks: `mark` and `mkmk`, and the above-base and below-base marks of the Indic and USE shapers. */
        private val MARK_FEATURES = setOf("mark", "mkmk", "abvm", "blwm")
        private const val IGNORE_MARKS = 0x0008
        private const val USE_MARK_FILTERING_SET = 0x0010
        private const val MARK_ATTACHMENT_TYPE = 0xFF00

        /** The marks of [gpos], with the mark classes and mark sets of [gdef] for the lookup flags. */
        public fun from(gpos: ByteArray?, gdef: ByteArray? = null): OpenTypeMarks? {
            gpos ?: return null
            val lookups = runCatching { parse(gpos) }.getOrNull() ?: return null
            if (lookups.none { it.toBase.isNotEmpty() || it.toLigature.isNotEmpty() }) return null
            val (scripts, features) = runCatching { layout(gpos) }.getOrDefault(emptyMap<String, ScriptRecord>() to emptyList())
            val (attach, sets) = gdef?.let { runCatching { parseGdef(it) }.getOrNull() } ?: (null to emptyList())
            return OpenTypeMarks(lookups, scripts, features, attach, sets)
        }

        /** The ScriptList and the FeatureList of [b], a GPOS table. */
        private fun layout(b: ByteArray): Pair<Map<String, ScriptRecord>, List<Pair<String, IntArray>>> {
            val r = R(b)
            r.u16(); r.u16()
            val scriptOff = r.u16()
            val featureOff = r.u16()
            fun tag(v: Long) = CharArray(4) { ((v ushr (24 - 8 * it)) and 0xFF).toInt().toChar() }.concatToString()
            fun langSys(off: Int): IntArray { val lr = R(b); lr.seek(off); lr.u16(); lr.u16(); val n = lr.u16(); return IntArray(n) { lr.u16() } }
            val scripts = HashMap<String, ScriptRecord>()
            if (scriptOff != 0) {
                r.seek(scriptOff)
                val n = r.u16()
                repeat(n) {
                    val t = tag(r.u32())
                    val so = scriptOff + r.u16()
                    val sr = R(b); sr.seek(so)
                    val defaultOff = sr.u16()
                    val ln = sr.u16()
                    val langs = HashMap<String, IntArray>()
                    repeat(ln) { val lt = tag(sr.u32()); langs[lt] = langSys(so + sr.u16()) }
                    scripts[t] = ScriptRecord(defaultOff.takeIf { it != 0 }?.let { langSys(so + it) }, langs)
                }
            }
            val features = ArrayList<Pair<String, IntArray>>()
            if (featureOff != 0) {
                r.seek(featureOff)
                val n = r.u16()
                repeat(n) {
                    val t = tag(r.u32())
                    val fo = featureOff + r.u16()
                    val fr = R(b); fr.seek(fo); fr.u16()
                    val c = fr.u16()
                    features += t to IntArray(c) { fr.u16() }
                }
            }
            return scripts to features
        }

        /** The mark attachment classes and the mark glyph sets of a GDEF table. */
        private fun parseGdef(b: ByteArray): Pair<Map<Int, Int>?, List<Set<Int>>> {
            val r = R(b)
            r.u16()
            val minor = r.u16()
            r.u16(); r.u16(); r.u16()
            val markAttachOff = r.u16()
            val markSetsOff = if (minor >= 2) r.u16() else 0
            val sets = ArrayList<Set<Int>>()
            if (markSetsOff != 0) {
                r.seek(markSetsOff); r.u16()
                val n = r.u16()
                val offs = LongArray(n) { r.u32() }
                for (o in offs) sets += readCoverage(b, markSetsOff + o.toInt()).keys
            }
            return (markAttachOff.takeIf { it != 0 }?.let { readClassDef(b, it) }) to sets
        }

        private fun readClassDef(b: ByteArray, off: Int): Map<Int, Int> {
            val r = R(b); r.seek(off)
            val out = HashMap<Int, Int>()
            when (r.u16()) {
                1 -> { val start = r.u16(); val n = r.u16(); for (i in 0 until n) out[start + i] = r.u16() }
                2 -> {
                    val n = r.u16()
                    var expanded = 0L
                    for (i in 0 until n) {
                        val s = r.u16(); val e = r.u16(); val c = r.u16()
                        expanded += maxOf(0, e - s + 1)
                        if (expanded > MAX_RANGE_GLYPHS) return out
                        for (g in s..e) out[g] = c
                    }
                }
            }
            return out
        }

        private fun parse(b: ByteArray): List<MarkLookup> {
            val r = R(b)
            r.u16(); r.u16() // major/minor
            r.u16() // scriptList
            r.u16() // featureList
            val lookupListOff = r.u16()
            r.seek(lookupListOff)
            val lookupCount = r.u16()
            val lookupOffsets = IntArray(lookupCount) { r.u16() }
            val out = ArrayList<MarkLookup>()
            for (lo in lookupOffsets) {
                val base = lookupListOff + lo
                r.seek(base)
                val type = r.u16()
                val flag = r.u16()
                val subCount = r.u16()
                val subOffsets = IntArray(subCount) { r.u16() }
                val markSet = if (flag and USE_MARK_FILTERING_SET != 0) r.u16() else 0
                val toBase = ArrayList<MarkBase>()
                val toLiga = ArrayList<MarkLigature>()
                var lookupType = type
                for (so in subOffsets) {
                    var subBase = base + so
                    var effType = type
                    if (type == 9) { // extension positioning: redirect
                        val er = R(b); er.seek(subBase)
                        er.u16() // format
                        effType = er.u16()
                        subBase += er.u32().toInt()
                    }
                    lookupType = effType
                    when (effType) {
                        // 4 and 6 share a layout: coverage, coverage, classes,
                        // mark array, and an array of anchors per class.
                        4, 6 -> runCatching { parseMarkBase(b, subBase) }.getOrNull()?.let { toBase.add(it) }
                        5 -> runCatching { parseMarkLigature(b, subBase) }.getOrNull()?.let { toLiga.add(it) }
                    }
                }
                // Every lookup keeps its place, so the indices of the FeatureList still name it.
                out += MarkLookup(lookupType, flag, markSet, toBase, toLiga)
            }
            return out
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
                    var expanded = 0L
                    for (i in 0 until n) {
                        val s = r.u16(); val e = r.u16(); val startIdx = r.u16()
                        // Valid ranges do not overlap, so they cover each glyph id once at most (#984).
                        expanded += maxOf(0, e - s + 1)
                        if (expanded > MAX_RANGE_GLYPHS) return emptyMap()
                        for (g in s..e) out[g] = startIdx + (g - s)
                    }
                }
            }
            return out
        }
    }

    internal class MarkBase(
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

    internal class MarkLigature(
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

/**
 * The most glyphs the ranges of one coverage or class definition may cover: every glyph id once, with
 * room for a font whose ranges overlap a little (#984).
 */
private const val MAX_RANGE_GLYPHS = 2L * 65536
