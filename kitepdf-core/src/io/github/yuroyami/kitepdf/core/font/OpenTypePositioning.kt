package io.github.yuroyami.kitepdf.core.font

/**
 * The GPOS lookups that move glyphs other than marks and kerning pairs: single adjustment (lookup
 * type 1), cursive attachment (type 3), and contextual and chained contextual positioning (types 7
 * and 8) that call them (OpenType 1.9, GPOS). Cursive attachment is what joins the letters of an
 * Urdu Nastaliq word on a slope; the contextual lookups move those letters before it (#877).
 * [OpenTypeMarks] places marks and [OpenTypeKern] reads pair kerning, so this class leaves lookup
 * types 2, 4, 5 and 6 to them.
 *
 * [position] works as HarfBuzz's GPOS stage does for one run of glyphs in logical order:
 *
 *  - the lookups of the `curs`, `kern`, `dist`, `abvm`, `blwm`, `mark` and `mkmk` features of the
 *    text's language system apply in lookup list order, each over the whole run;
 *  - a lookup passes over the glyphs that its flag leaves out, by the glyph classes, the mark
 *    attachment classes and the mark sets of GDEF;
 *  - a contextual lookup that matches applies its nested lookups at their sequence indices, and the
 *    next match starts after the input it matched;
 *  - a cursive attachment sets the advance of the two glyphs so that the exit anchor of one meets
 *    the entry anchor of the next, and links the child to its parent: with the `RightToLeft` flag
 *    the earlier glyph hangs on the later one, else the later on the earlier;
 *  - at the end each child's vertical offset adds its parent's, so a chain rises from the glyph
 *    that the attachment leaves on the baseline (HarfBuzz's `propagate_attachment_offsets`).
 *
 * Device tables and variation deltas are not read, so the values are those of the default instance.
 */
public class OpenTypePositioning private constructor(
    private val lookups: List<Lookup?>,
    private val scripts: Map<String, Pair<IntArray?, Map<String, IntArray>>>,
    private val features: List<Pair<String, IntArray>>,
    private val glyphClass: Map<Int, Int>,
    private val markAttach: Map<Int, Int>,
    private val markSets: List<Set<Int>>,
) {
    /** The lookups that a text of one script and language takes, in lookup list order. */
    public class Plan internal constructor(internal val lookups: IntArray)

    /**
     * Where [position] puts each glyph of a run, in font units and in the run's logical order: the
     * offsets of its drawing from the pen, and the advance that moves the pen past it.
     */
    public class Placement(public val xOffset: IntArray, public val yOffset: IntArray, public val xAdvance: IntArray)

    private val everyFeature: Plan by lazy { Plan(lookupsOf(features.indices.toList())) }

    private fun lookupsOf(featureIndices: Iterable<Int>): IntArray =
        featureIndices.flatMap { fi ->
            val f = features.getOrNull(fi) ?: return@flatMap emptyList()
            if (f.first in FEATURES) f.second.toList() else emptyList()
        }.distinct().filter { lookups.getOrNull(it) != null }.sorted().toIntArray()

    /**
     * The plan for [script], an OpenType script tag such as `arab`, and [language], an OpenType
     * language system tag or a BCP 47 tag, as [OpenTypeMarks.plan] chooses one. A font with no
     * language system for either takes the lookups of those features in every language system.
     */
    public fun plan(script: String?, language: String?): Plan {
        val record = script?.let { scripts[it] } ?: scripts["DFLT"] ?: scripts["dflt"] ?: return everyFeature
        val tag = language?.let { if (it.length == 4 && it.any { c -> c.isUpperCase() }) it else OpenTypeLanguage.tagOf(it) }
        val chosen = tag?.let { record.second[it] } ?: record.first ?: return everyFeature
        return Plan(lookupsOf(chosen.toList()))
    }

    /** Whether [plan] has any lookup to apply, so a caller can skip a run that nothing moves. */
    public fun moves(plan: Plan?): Boolean = (plan ?: everyFeature).lookups.isNotEmpty()

    /**
     * Places the glyphs [gids] of one run, in logical order, whose `hmtx` advances are [advances].
     * [rightToLeft] is the run's direction, which decides the side a cursive attachment adjusts.
     */
    public fun position(gids: IntArray, advances: IntArray, rightToLeft: Boolean, plan: Plan? = null): Placement {
        val run = Run(gids, advances.copyOf(), rightToLeft)
        for (index in (plan ?: everyFeature).lookups) {
            val lookup = lookups[index] ?: continue
            var at = 0
            while (at < gids.size) {
                at = if (!skips(lookup, gids[at])) (applyAt(run, lookup, at, 0) ?: (at + 1)) else at + 1
            }
        }
        for (i in gids.indices) propagate(run, i, 0)
        return Placement(run.xOffset, run.yOffset, run.xAdvance)
    }

    private class Run(val gids: IntArray, val xAdvance: IntArray, val rightToLeft: Boolean) {
        val xOffset = IntArray(gids.size)
        val yOffset = IntArray(gids.size)
        /** The distance in the run from each cursive child to its parent, 0 for none. */
        val chain = IntArray(gids.size)
    }

    /** Applies [lookup] at [at]; returns where the next glyph to try is, or null when it did not apply. */
    private fun applyAt(run: Run, lookup: Lookup, at: Int, depth: Int): Int? {
        for (sub in lookup.subtables) {
            val next = when (sub) {
                is Single -> sub.coverage[run.gids[at]]?.let { ci ->
                    val v = if (sub.values.size == 1) sub.values[0] else sub.values.getOrNull(ci) ?: return@let null
                    run.xOffset[at] += v[0]; run.yOffset[at] += v[1]; run.xAdvance[at] += v[2]
                    at + 1
                }
                is Cursive -> cursive(run, lookup, sub, at)
                is Context -> context(run, lookup, sub, at, depth)
            }
            if (next != null) return next
        }
        return null
    }

    private fun cursive(run: Run, lookup: Lookup, sub: Cursive, j: Int): Int? {
        val entry = sub.coverage[run.gids[j]]?.let { sub.entry[it] } ?: return null
        var i = j - 1
        while (i >= 0 && skips(lookup, run.gids[i])) i--
        if (i < 0) return null
        val exit = sub.coverage[run.gids[i]]?.let { sub.exit[it] } ?: return null
        if (run.rightToLeft) {
            val d = exit[0] + run.xOffset[i]
            run.xAdvance[i] -= d
            run.xOffset[i] -= d
            run.xAdvance[j] = entry[0] + run.xOffset[j]
        } else {
            run.xAdvance[i] = exit[0] + run.xOffset[i]
            val d = entry[0] + run.xOffset[j]
            run.xAdvance[j] -= d
            run.xOffset[j] -= d
        }
        var child = i
        var parent = j
        var y = entry[1] - exit[1]
        if (lookup.flag and RIGHT_TO_LEFT == 0) { child = j; parent = i; y = -y }
        reverseChain(run, child, parent, 0)
        run.chain[child] = parent - child
        run.yOffset[child] = y
        if (run.chain[parent] == -run.chain[child]) run.chain[parent] = 0
        return j + 1
    }

    /** A child attached before turns its old chain around, so that chain now hangs on the new parent (HarfBuzz's `reverse_cursive_minor_offset`). */
    private fun reverseChain(run: Run, i: Int, newParent: Int, depth: Int) {
        val chain = run.chain[i]
        if (chain == 0 || depth > MAX_DEPTH) return
        run.chain[i] = 0
        val j = i + chain
        if (j == newParent || j !in run.gids.indices) return
        reverseChain(run, j, newParent, depth + 1)
        run.yOffset[j] = -run.yOffset[i]
        run.chain[j] = -chain
    }

    private fun propagate(run: Run, i: Int, depth: Int) {
        val chain = run.chain[i]
        if (chain == 0 || depth > MAX_DEPTH) return
        run.chain[i] = 0
        val j = i + chain
        if (j !in run.gids.indices) return
        propagate(run, j, depth + 1)
        run.yOffset[i] += run.yOffset[j]
    }

    private fun context(run: Run, lookup: Lookup, sub: Context, at: Int, depth: Int): Int? {
        val gids = run.gids
        if (depth > MAX_DEPTH) return null
        for (rule in sub.rulesFor(gids[at]) ?: return null) {
            val positions = IntArray(rule.input.size + 1)
            positions[0] = at
            var p = at
            var ok = true
            for (k in rule.input.indices) {
                p++
                while (p < gids.size && skips(lookup, gids[p])) p++
                if (p >= gids.size || !sub.matches(Context.INPUT, rule.input[k], gids[p])) { ok = false; break }
                positions[k + 1] = p
            }
            if (!ok) continue
            var b = at
            for (k in rule.backtrack.indices) {
                b--
                while (b >= 0 && skips(lookup, gids[b])) b--
                if (b < 0 || !sub.matches(Context.BACKTRACK, rule.backtrack[k], gids[b])) { ok = false; break }
            }
            if (!ok) continue
            var a = p
            for (k in rule.lookahead.indices) {
                a++
                while (a < gids.size && skips(lookup, gids[a])) a++
                if (a >= gids.size || !sub.matches(Context.LOOKAHEAD, rule.lookahead[k], gids[a])) { ok = false; break }
            }
            if (!ok) continue
            for ((sequence, nested) in rule.records) {
                val target = positions.getOrNull(sequence) ?: continue
                val inner = lookups.getOrNull(nested) ?: continue
                applyAt(run, inner, target, depth + 1)
            }
            return p + 1
        }
        return null
    }

    /** Whether the flag of [lookup] passes over [gid] (OpenType 1.9, "Lookup Table"). */
    private fun skips(lookup: Lookup, gid: Int): Boolean {
        val flag = lookup.flag
        return when (glyphClass[gid]) {
            1 -> flag and IGNORE_BASE_GLYPHS != 0
            2 -> flag and IGNORE_LIGATURES != 0
            3 -> when {
                flag and IGNORE_MARKS != 0 -> true
                flag and USE_MARK_FILTERING_SET != 0 -> markSets.getOrNull(lookup.markSet)?.contains(gid) != true
                flag and MARK_ATTACHMENT_TYPE != 0 -> (markAttach[gid] ?: 0) != (flag ushr 8)
                else -> false
            }
            else -> false
        }
    }

    private class Lookup(val flag: Int, val markSet: Int, val subtables: List<Subtable>)

    private sealed class Subtable

    /** Type 1: x placement, y placement and x advance for each covered glyph, or one value for all. */
    private class Single(val coverage: Map<Int, Int>, val values: List<IntArray>) : Subtable()

    /** Type 3: the entry and exit anchors of each covered glyph. */
    private class Cursive(val coverage: Map<Int, Int>, val entry: Array<IntArray?>, val exit: Array<IntArray?>) : Subtable()

    private class Rule(val backtrack: IntArray, val input: IntArray, val lookahead: IntArray, val records: List<Pair<Int, Int>>)

    /**
     * Types 7 and 8 in their three formats: rules of glyph ids by the first glyph (format 1), of
     * classes by the first glyph's class (format 2), or one rule of coverages (format 3). Type 7 is
     * a chained rule with no backtrack and no lookahead.
     */
    private class Context(
        val format: Int,
        val coverage: Map<Int, Int>?,
        val byFirst: Map<Int, List<Rule>>,
        val classDefs: Array<Map<Int, Int>>,
        val coverages: Array<List<Map<Int, Int>>>,
        val rule: Rule?,
    ) : Subtable() {
        fun rulesFor(first: Int): List<Rule>? = when (format) {
            1 -> if (coverage?.containsKey(first) == true) byFirst[first] else null
            2 -> if (coverage?.containsKey(first) == true) byFirst[classDefs[INPUT][first] ?: 0] else null
            else -> if (coverages[INPUT].firstOrNull()?.containsKey(first) == true) listOfNotNull(rule) else null
        }

        /** Whether [gid] matches [value] of a rule on [side]: a glyph id, a class, or, in format 3, the index of a coverage. */
        fun matches(side: Int, value: Int, gid: Int): Boolean = when (format) {
            1 -> gid == value
            2 -> (classDefs[side][gid] ?: 0) == value
            else -> coverages[side].getOrNull(value)?.containsKey(gid) == true
        }

        companion object {
            const val BACKTRACK = 0
            const val INPUT = 1
            const val LOOKAHEAD = 2
        }
    }

    public companion object {
        private val FEATURES = setOf("curs", "kern", "dist", "abvm", "blwm", "mark", "mkmk")
        private const val RIGHT_TO_LEFT = 0x0001
        private const val IGNORE_BASE_GLYPHS = 0x0002
        private const val IGNORE_LIGATURES = 0x0004
        private const val IGNORE_MARKS = 0x0008
        private const val USE_MARK_FILTERING_SET = 0x0010
        private const val MARK_ATTACHMENT_TYPE = 0xFF00
        /** How deep a contextual lookup may call another, and how long an attachment chain may run. */
        private const val MAX_DEPTH = 64
        /** The most glyphs the ranges of one coverage or class definition may cover (#984). */
        private const val MAX_RANGE_GLYPHS = 2L * 65536

        /**
         * The positioning lookups of [gpos], with the glyph classes, mark classes and mark sets of
         * [gdef] for the lookup flags, or null when the table has none of types 1, 3, 7 and 8 in
         * the features that apply.
         */
        public fun from(gpos: ByteArray?, gdef: ByteArray? = null): OpenTypePositioning? {
            gpos ?: return null
            val lookups = runCatching { parseLookups(gpos) }.getOrNull() ?: return null
            if (lookups.all { it == null }) return null
            val (scripts, features) = runCatching { layout(gpos) }.getOrNull() ?: return null
            val classes = gdef?.let { runCatching { parseGdef(it) }.getOrNull() }
            val positioning = OpenTypePositioning(
                lookups, scripts, features,
                classes?.first.orEmpty(), classes?.second.orEmpty(), classes?.third.orEmpty(),
            )
            return positioning.takeIf { it.moves(null) }
        }

        private fun layout(b: ByteArray): Pair<Map<String, Pair<IntArray?, Map<String, IntArray>>>, List<Pair<String, IntArray>>> {
            val r = R(b)
            r.u16(); r.u16()
            val scriptListOff = r.u16()
            val featureListOff = r.u16()
            fun tag(at: Int) = CharArray(4) { (b[at + it].toInt() and 0xFF).toChar() }.concatToString()
            fun langSys(off: Int): IntArray { val lr = R(b); lr.seek(off + 4); val n = lr.u16(); return IntArray(n) { lr.u16() } }
            val scripts = HashMap<String, Pair<IntArray?, Map<String, IntArray>>>()
            r.seek(scriptListOff)
            val scriptCount = r.u16()
            for (i in 0 until scriptCount) {
                val recordAt = scriptListOff + 2 + i * 6
                r.seek(recordAt + 4)
                val scriptOff = scriptListOff + r.u16()
                r.seek(scriptOff)
                val defaultOff = r.u16()
                val langCount = r.u16()
                val languages = HashMap<String, IntArray>()
                for (k in 0 until langCount) {
                    val at = scriptOff + 4 + k * 6
                    r.seek(at + 4)
                    languages[tag(at)] = langSys(scriptOff + r.u16())
                }
                scripts[tag(recordAt)] = (if (defaultOff != 0) langSys(scriptOff + defaultOff) else null) to languages
            }
            r.seek(featureListOff)
            val featureCount = r.u16()
            val features = ArrayList<Pair<String, IntArray>>(featureCount)
            for (i in 0 until featureCount) {
                val recordAt = featureListOff + 2 + i * 6
                r.seek(recordAt + 4)
                val featureOff = featureListOff + r.u16()
                r.seek(featureOff + 2)
                val n = r.u16()
                features += tag(recordAt) to IntArray(n) { r.u16() }
            }
            return scripts to features
        }

        /** The glyph classes, the mark attachment classes and the mark glyph sets of a GDEF table. */
        private fun parseGdef(b: ByteArray): Triple<Map<Int, Int>, Map<Int, Int>, List<Set<Int>>> {
            val r = R(b)
            r.u16()
            val minor = r.u16()
            val glyphClassOff = r.u16()
            r.u16(); r.u16()
            val markAttachOff = r.u16()
            val markSetsOff = if (minor >= 2) r.u16() else 0
            val sets = ArrayList<Set<Int>>()
            if (markSetsOff != 0) {
                r.seek(markSetsOff + 2)
                val n = r.u16()
                val offs = LongArray(n) { r.u32() }
                for (o in offs) sets += readCoverage(b, markSetsOff + o.toInt()).keys
            }
            return Triple(
                if (glyphClassOff != 0) readClassDef(b, glyphClassOff) else emptyMap(),
                if (markAttachOff != 0) readClassDef(b, markAttachOff) else emptyMap(),
                sets,
            )
        }

        private fun parseLookups(b: ByteArray): List<Lookup?> {
            val r = R(b)
            r.seek(8)
            val lookupListOff = r.u16()
            r.seek(lookupListOff)
            val count = r.u16()
            val offsets = IntArray(count) { r.u16() }
            return offsets.map { lo ->
                runCatching {
                    val base = lookupListOff + lo
                    r.seek(base)
                    val type = r.u16()
                    val flag = r.u16()
                    val subCount = r.u16()
                    val subOffsets = IntArray(subCount) { r.u16() }
                    val markSet = if (flag and USE_MARK_FILTERING_SET != 0) r.u16() else 0
                    val subtables = ArrayList<Subtable>()
                    for (so in subOffsets) {
                        var at = base + so
                        var effType = type
                        if (type == 9) {
                            val er = R(b); er.seek(at)
                            er.u16()
                            effType = er.u16()
                            at += er.u32().toInt()
                        }
                        when (effType) {
                            1 -> parseSingle(b, at)
                            3 -> parseCursive(b, at)
                            7 -> parseContext(b, at, chained = false)
                            8 -> parseContext(b, at, chained = true)
                            else -> null
                        }?.let { subtables += it }
                    }
                    if (subtables.isEmpty()) null else Lookup(flag, markSet, subtables)
                }.getOrNull()
            }
        }

        /** A value record's x placement, y placement and x advance; the y advance and device tables are read past. */
        private fun readValue(r: R, format: Int): IntArray {
            val v = IntArray(3)
            if (format and 0x1 != 0) v[0] = r.s16()
            if (format and 0x2 != 0) v[1] = r.s16()
            if (format and 0x4 != 0) v[2] = r.s16()
            for (bit in intArrayOf(0x8, 0x10, 0x20, 0x40, 0x80)) if (format and bit != 0) r.u16()
            return v
        }

        private fun parseSingle(b: ByteArray, at: Int): Single {
            val r = R(b); r.seek(at)
            val format = r.u16()
            val coverage = readCoverage(b, at + r.u16())
            val valueFormat = r.u16()
            val values = when (format) {
                1 -> listOf(readValue(r, valueFormat))
                2 -> { val n = r.u16(); List(n) { readValue(r, valueFormat) } }
                else -> error("single adjustment format $format")
            }
            return Single(coverage, values)
        }

        private fun readAnchor(b: ByteArray, off: Int): IntArray {
            val r = R(b); r.seek(off + 2)
            return intArrayOf(r.s16(), r.s16())
        }

        private fun parseCursive(b: ByteArray, at: Int): Cursive {
            val r = R(b); r.seek(at)
            require(r.u16() == 1) { "cursive attachment format" }
            val coverage = readCoverage(b, at + r.u16())
            val n = r.u16()
            val entry = arrayOfNulls<IntArray>(n)
            val exit = arrayOfNulls<IntArray>(n)
            for (i in 0 until n) {
                val e = r.u16(); val x = r.u16()
                if (e != 0) entry[i] = readAnchor(b, at + e)
                if (x != 0) exit[i] = readAnchor(b, at + x)
            }
            return Cursive(coverage, entry, exit)
        }

        private fun records(r: R, n: Int): List<Pair<Int, Int>> = List(n) { r.u16() to r.u16() }

        private fun parseContext(b: ByteArray, at: Int, chained: Boolean): Context {
            val r = R(b); r.seek(at)
            val format = r.u16()
            val none = IntArray(0)
            fun ruleSets(setsAt: IntArray, base: Int, read: (R) -> Rule): List<List<Rule>> = setsAt.map { so ->
                if (so == 0) emptyList() else {
                    val sr = R(b); sr.seek(base + so)
                    val n = sr.u16()
                    val ruleOffs = IntArray(n) { sr.u16() }
                    ruleOffs.map { ro -> val rr = R(b); rr.seek(base + so + ro); read(rr) }
                }
            }
            fun readRule(rr: R): Rule = if (chained) {
                val back = IntArray(rr.u16()) { rr.u16() }
                val input = IntArray(maxOf(0, rr.u16() - 1)) { rr.u16() }
                val ahead = IntArray(rr.u16()) { rr.u16() }
                Rule(back, input, ahead, records(rr, rr.u16()))
            } else {
                val glyphs = rr.u16()
                val n = rr.u16()
                val input = IntArray(maxOf(0, glyphs - 1)) { rr.u16() }
                Rule(none, input, none, records(rr, n))
            }
            return when (format) {
                1 -> {
                    val coverage = readCoverage(b, at + r.u16())
                    val sets = ruleSets(IntArray(r.u16()) { r.u16() }, at, ::readRule)
                    val byFirst = HashMap<Int, List<Rule>>()
                    for ((gid, ci) in coverage) sets.getOrNull(ci)?.let { byFirst[gid] = it }
                    Context(1, coverage, byFirst, emptyArray(), emptyArray(), null)
                }
                2 -> {
                    val coverage = readCoverage(b, at + r.u16())
                    val defs = if (chained) {
                        val back = r.u16(); val input = r.u16(); val ahead = r.u16()
                        arrayOf(classDefAt(b, at, back), classDefAt(b, at, input), classDefAt(b, at, ahead))
                    } else {
                        val input = classDefAt(b, at, r.u16())
                        arrayOf(input, input, input)
                    }
                    val sets = ruleSets(IntArray(r.u16()) { r.u16() }, at, ::readRule)
                    val byClass = HashMap<Int, List<Rule>>()
                    for ((cls, rules) in sets.withIndex()) if (rules.isNotEmpty()) byClass[cls] = rules
                    Context(2, coverage, byClass, defs, emptyArray(), null)
                }
                3 -> if (chained) {
                    fun coverages(): List<Map<Int, Int>> = List(r.u16()) { r.u16() }.map { readCoverage(b, at + it) }
                    val back = coverages(); val input = coverages(); val ahead = coverages()
                    val rule = Rule(IntArray(back.size) { it }, IntArray(maxOf(0, input.size - 1)) { it + 1 }, IntArray(ahead.size) { it }, records(r, r.u16()))
                    Context(3, null, emptyMap(), emptyArray(), arrayOf(back, input, ahead), rule)
                } else {
                    val glyphs = r.u16()
                    val n = r.u16()
                    val input = List(glyphs) { r.u16() }.map { readCoverage(b, at + it) }
                    val rule = Rule(none, IntArray(maxOf(0, glyphs - 1)) { it + 1 }, none, records(r, n))
                    Context(3, null, emptyMap(), emptyArray(), arrayOf(emptyList(), input, emptyList()), rule)
                }
                else -> error("contextual positioning format $format")
            }
        }

        private fun classDefAt(b: ByteArray, base: Int, off: Int): Map<Int, Int> = if (off == 0) emptyMap() else readClassDef(b, base + off)

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
                        expanded += maxOf(0, e - s + 1)
                        if (expanded > MAX_RANGE_GLYPHS) return emptyMap()
                        for (g in s..e) out[g] = startIdx + (g - s)
                    }
                }
            }
            return out
        }
    }

    private class R(val b: ByteArray) {
        var p = 0
        fun seek(o: Int) { p = o }
        fun u16(): Int { val v = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF); p += 2; return v }
        fun s16(): Int { val v = u16(); return if (v >= 0x8000) v - 0x10000 else v }
        fun u32(): Long {
            val v = ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
                ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
            p += 4
            return v
        }
    }
}
