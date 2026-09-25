package io.github.yuroyami.kitepdf.core.font

/**
 * The substitution half of OpenType shaping: the `GSUB` table (OpenType 1.9, "GSUB: Glyph
 * Substitution Table").
 *
 * [substitute] runs a sequence of glyphs through the lookups of the features a shaper asks
 * for, the way HarfBuzz runs them: the script and language of the text pick a language
 * system, each stage of features applies its lookups in LookupList order, and a lookup flag
 * skips the glyphs its GDEF class excludes (#211). Every lookup type reads: single (1),
 * multiple (2), alternate (3), ligature (4), context (5), chained context (6), extension (7)
 * and reverse chained context (8). A context lookup applies its nested lookups at the glyphs
 * it matched.
 *
 * [single] and [ligatures] read one feature on its own, for a caller that wants one glyph.
 */
public class OpenTypeGsub private constructor(
    private val scripts: Map<String, Script>,
    private val features: List<Feature>,
    private val lookups: List<Lookup?>,
    private val gdef: Gdef?,
) {
    /** A ligature rule: [rest] are the 2nd..nth component glyph ids, [lig] the result. */
    public class LigRule(public val rest: IntArray, public val lig: Int)

    /** The single-substitution glyph for [gid] under [feature], or null. */
    public fun single(feature: String, gid: Int): Int? {
        for (lookup in lookupsOf(feature)) {
            for (st in lookup.subtables) if (st is SingleSubst) st.substitute(gid)?.let { return it }
        }
        return null
    }

    /** Ligature rules whose first component is [firstGid] under [feature], longest first. */
    public fun ligatures(feature: String, firstGid: Int): List<LigRule>? {
        val out = ArrayList<LigRule>()
        for (lookup in lookupsOf(feature)) {
            for (st in lookup.subtables) if (st is LigatureSubst) st.rulesFor(firstGid)?.let { out.addAll(it) }
        }
        return out.takeIf { it.isNotEmpty() }?.sortedByDescending { it.rest.size }
    }

    public val hasArabicJoining: Boolean
        get() = features.any { it.tag == "init" || it.tag == "medi" || it.tag == "fina" }

    /** True when the table has a script record tagged [tag], such as `arab` or `dev2`. */
    public fun hasScript(tag: String): Boolean = tag in scripts

    /** The lookups of every feature record tagged [tag], whatever its script, in LookupList order. */
    private fun lookupsOf(tag: String): List<Lookup> =
        features.filter { it.tag == tag }.flatMap { f -> f.lookups.toList() }.distinct().sorted().mapNotNull { lookups.getOrNull(it) }

    /**
     * Substitutes [glyphs] in place. [script] and [language] are OpenType tags, such as
     * `arab` and `URD `; a script the font lacks falls back to `DFLT`, `dflt` and `latn`, and
     * a language it lacks falls back to the default language system. Each list of [stages]
     * applies before the next, its lookups in LookupList order.
     *
     * A feature in [positional] applies only to the glyphs whose [GsubGlyph.features] name
     * it, as the joining forms of Arabic do. Every other feature applies to every glyph. A
     * lookup whose features are all in [perSyllable] matches only inside the
     * [GsubGlyph.syllable] of the glyph it starts at, as HarfBuzz constrains the features of an
     * Indic syllable.
     *
     * Matching passes over a [GsubGlyph.ignorable] glyph that does not match, as HarfBuzz
     * does, with two exceptions for the joiners. Inside the input, a ZWNJ never passes, and a
     * ZWJ does not pass for a lookup that a feature in [manualZwj] references. Before or after
     * the input, a ZWJ always passes, and a ZWNJ passes unless a feature in [manualZwnj]
     * references the lookup.
     */
    public fun substitute(
        glyphs: MutableList<GsubGlyph>,
        script: String,
        language: String?,
        stages: List<List<String>>,
        positional: Set<String> = emptySet(),
        perSyllable: Set<String> = emptySet(),
        manualZwj: Set<String> = emptySet(),
        manualZwnj: Set<String> = emptySet(),
    ) {
        val lang = languageSystem(script, language) ?: return
        val active = HashMap<String, MutableList<Int>>()
        lang.required.takeIf { it >= 0 }?.let { features.getOrNull(it) }?.let { f -> active.getOrPut(f.tag) { ArrayList() } += f.lookups.toList() }
        for (fi in lang.features) {
            val f = features.getOrNull(fi) ?: continue
            active.getOrPut(f.tag) { ArrayList() } += f.lookups.toList()
        }
        for (stage in stages) {
            // lookup index -> the features of this stage that reference it
            val stageLookups = HashMap<Int, MutableSet<String>>()
            for (tag in stage) for (li in active[tag].orEmpty()) stageLookups.getOrPut(li) { HashSet() } += tag
            for (li in stageLookups.keys.sorted()) {
                val tags = stageLookups.getValue(li)
                val lookup = lookups.getOrNull(li) ?: continue
                val global = tags.any { it !in positional }
                val applies: (GsubGlyph) -> Boolean =
                    if (global) { _ -> true } else { g -> g.features.any { it in tags } }
                Applier(glyphs, applies, tags.all { it in perSyllable }, tags.none { it in manualZwj }, tags.none { it in manualZwnj }).run(lookup)
            }
        }
    }

    /**
     * True when a lookup of [feature] for [script] would substitute exactly [gids], as
     * HarfBuzz's Indic shaper asks the font whether a consonant takes a below-base form. With
     * [zeroContext], a chained rule counts only when it needs no glyphs before or after its
     * input; without it, the rule counts whatever glyphs it needs around the input.
     */
    public fun wouldSubstitute(feature: String, script: String, gids: IntArray, zeroContext: Boolean = true): Boolean {
        if (gids.isEmpty()) return false
        val lang = languageSystem(script, null) ?: return false
        val indices = (listOf(lang.required) + lang.features.toList()).mapNotNull { features.getOrNull(it) }
            .filter { it.tag == feature }.flatMap { it.lookups.toList() }
        for (li in indices) {
            val lookup = lookups.getOrNull(li) ?: continue
            for (st in lookup.subtables) if (wouldApply(st, gids, zeroContext)) return true
        }
        return false
    }

    private fun wouldApply(st: Subtable, gids: IntArray, zeroContext: Boolean): Boolean {
        val first = gids[0]
        val rest = gids.copyOfRange(1, gids.size)
        return when (st) {
            is SingleSubst -> gids.size == 1 && st.substitute(first) != null
            is MultipleSubst -> gids.size == 1 && st.sequence(first) != null
            is AlternateSubst -> gids.size == 1 && st.alternates(first) != null
            is LigatureSubst -> st.rulesFor(first)?.any { it.rest.contentEquals(rest) } == true
            is ContextSubst.Glyphs -> st.coverage.indexOf(first).let { i -> i >= 0 && st.ruleSets.getOrNull(i)?.any { it.input.contentEquals(rest) } == true }
            is ContextSubst.Classes -> st.coverage.indexOf(first) >= 0 &&
                st.ruleSets.getOrNull(st.classes.classOf(first))?.any { r -> r.input.size == rest.size && r.input.indices.all { r.input[it] == st.classes.classOf(rest[it]) } } == true
            is ContextSubst.Coverages -> st.coverages.size == gids.size && gids.indices.all { st.coverages[it].indexOf(gids[it]) >= 0 }
            is ChainSubst.Glyphs -> st.coverage.indexOf(first).let { i ->
                i >= 0 && st.ruleSets.getOrNull(i)?.any { (!zeroContext || it.backtrack.isEmpty() && it.lookahead.isEmpty()) && it.input.contentEquals(rest) } == true
            }
            is ChainSubst.Classes -> st.coverage.indexOf(first) >= 0 &&
                st.ruleSets.getOrNull(st.input.classOf(first))?.any { r ->
                    (!zeroContext || r.backtrack.isEmpty() && r.lookahead.isEmpty()) &&
                        r.input.size == rest.size && r.input.indices.all { r.input[it] == st.input.classOf(rest[it]) }
                } == true
            is ChainSubst.Coverages -> (!zeroContext || st.backtrack.isEmpty() && st.lookahead.isEmpty()) && st.input.size == gids.size &&
                gids.indices.all { st.input[it].indexOf(gids[it]) >= 0 }
            is ReverseChainSubst -> gids.size == 1 && st.coverage.indexOf(first) >= 0
        }
    }

    private fun languageSystem(script: String, language: String?): LangSys? {
        val s = scripts[script] ?: scripts["DFLT"] ?: scripts["dflt"] ?: scripts["latn"] ?: return null
        return language?.let { s.languages[it] } ?: s.default ?: s.languages.values.firstOrNull()
    }

    /* ─── Applying lookups ─────────────────────────────────────────────────── */

    /**
     * One pass of lookups over [glyphs], where [applies] tells which glyphs the features of the
     * pass reach. [autoZwj] and [autoZwnj] say whether matching passes over a joiner, as
     * HarfBuzz's auto_zwj and auto_zwnj do.
     */
    private inner class Applier(
        val glyphs: MutableList<GsubGlyph>,
        val applies: (GsubGlyph) -> Boolean,
        val perSyllable: Boolean = false,
        val autoZwj: Boolean = true,
        val autoZwnj: Boolean = true,
    ) {
        /** How deep nested lookups go, as HarfBuzz limits them. */
        private var nesting = 0

        fun run(lookup: Lookup) {
            if (lookup.type == 8) {
                // A reverse chained lookup runs from the end, and only substitutes in place.
                var i = glyphs.size - 1
                while (i >= 0) {
                    val g = glyphs[i]
                    if (applies(g) && !skipped(g, lookup)) {
                        for (st in lookup.subtables) if (st is ReverseChainSubst && applyReverse(st, lookup, i)) break
                    }
                    i--
                }
                return
            }
            var i = 0
            while (i < glyphs.size) {
                val g = glyphs[i]
                if (!applies(g) || skipped(g, lookup)) { i++; continue }
                val next = applyAt(lookup, i)
                i = if (next < 0) i + 1 else next
            }
        }

        /** Applies the first subtable of [lookup] that matches at [i]; the index to go on from, or -1. */
        fun applyAt(lookup: Lookup, i: Int): Int {
            for (st in lookup.subtables) {
                val next = when (st) {
                    is SingleSubst -> st.substitute(glyphs[i].gid)?.let { replace(i, it); i + 1 } ?: -1
                    is MultipleSubst -> applyMultiple(st, i)
                    is AlternateSubst -> st.alternates(glyphs[i].gid)?.firstOrNull()?.let { replace(i, it); i + 1 } ?: -1
                    is LigatureSubst -> applyLigature(st, lookup, i)
                    is ContextSubst -> applyContext(st, lookup, i)
                    is ChainSubst -> applyChain(st, lookup, i)
                    is ReverseChainSubst -> -1
                }
                if (next >= 0) return next
            }
            return -1
        }

        private fun replace(i: Int, gid: Int) {
            glyphs[i].gid = gid
            glyphs[i].substituted = true
        }

        /** One glyph in a sequence is a single substitution, as HarfBuzz treats it; more are multiplied. */
        private fun applyMultiple(st: MultipleSubst, i: Int): Int {
            val seq = st.sequence(glyphs[i].gid) ?: return -1
            if (seq.size == 1) { replace(i, seq[0]); return i + 1 }
            val source = glyphs.removeAt(i)
            for ((k, gid) in seq.withIndex()) {
                // Each part counts as a component, unless the glyph is attached to a ligature.
                glyphs.add(i + k, source.copy(gid).also {
                    it.substituted = true
                    it.multiplied = true
                    if (source.ligId == 0) { it.ligComp = k; it.ligBase = false }
                })
            }
            return i + seq.size
        }

        private fun applyLigature(st: LigatureSubst, lookup: Lookup, i: Int): Int {
            val rules = st.rulesFor(glyphs[i].gid) ?: return -1
            for (rule in rules) {
                val positions = matchInput(lookup, i, rule.rest.size + 1) { k, g -> g.gid == rule.rest[k - 1] } ?: continue
                ligate(lookup, positions, rule.lig)
                return i + 1
            }
            return -1
        }

        /**
         * Replaces the glyphs at [positions] with [lig], which takes their clusters and the marks
         * between them. As HarfBuzz's ligate_input does, the marks between the components attach
         * to the component before them, and the marks that followed a component ligature move to
         * the matching component of the new one. A base with marks, or marks alone, form no new
         * ligature in this sense.
         */
        private fun ligate(lookup: Lookup, positions: IntArray, lig: Int) {
            val first = glyphs[positions[0]]
            val last = positions.last()
            val rest = (1 until positions.size).map { glyphs[positions[it]] }
            val baseLigature = glyphClass(first) == BASE && rest.all { glyphClass(it) == MARK }
            val markLigature = glyphClass(first) == MARK && rest.all { glyphClass(it) == MARK }
            val isLigature = !baseLigature && !markLigature
            val ligId = if (isLigature) glyphs.maxOf { it.ligId } + 1 else 0
            var lastLigId = first.ligId
            var lastComponents = componentCount(first)
            var componentsSoFar = lastComponents
            val total = positions.sumOf { componentCount(glyphs[it]) }
            if (isLigature) { first.ligId = ligId; first.ligBase = true; first.ligComponents = total }
            // Marks the lookup skipped between the components stay, after the ligature, in its cluster.
            for (p in positions[0]..last) glyphs[p].cluster = first.cluster
            for (k in 1 until positions.size) {
                for (p in positions[k - 1] + 1 until positions[k]) {
                    if (!isLigature) continue
                    val mark = glyphs[p]
                    val thisComponent = mark.component.takeIf { it != 0 } ?: lastComponents
                    mark.ligId = ligId
                    mark.ligBase = false
                    mark.ligComp = componentsSoFar - lastComponents + minOf(thisComponent, lastComponents)
                }
                val component = glyphs[positions[k]]
                lastLigId = component.ligId
                lastComponents = componentCount(component)
                componentsSoFar += lastComponents
            }
            if (!markLigature && lastLigId != 0) {
                var p = last + 1
                while (p < glyphs.size && glyphs[p].ligId == lastLigId) {
                    val thisComponent = glyphs[p].component
                    if (thisComponent == 0) break
                    glyphs[p].ligId = ligId
                    glyphs[p].ligComp = componentsSoFar - lastComponents + minOf(thisComponent, lastComponents)
                    p++
                }
            }
            first.gid = lig
            first.components = positions.size
            first.ligated = true
            first.multiplied = false
            first.substituted = true
            for (k in positions.size - 1 downTo 1) glyphs.removeAt(positions[k])
        }

        /** How many components [g] stands for: those of a ligature of class ligature, or 1, as HarfBuzz's get_lig_num_comps counts them. */
        private fun componentCount(g: GsubGlyph): Int = if (g.ligBase && glyphClass(g) == LIGATURE) g.ligComponents else 1

        private fun applyContext(st: ContextSubst, lookup: Lookup, i: Int): Int {
            val gid = glyphs[i].gid
            when (st) {
                is ContextSubst.Glyphs -> {
                    val index = st.coverage.indexOf(gid).takeIf { it >= 0 } ?: return -1
                    for (rule in st.ruleSets.getOrNull(index) ?: return -1) {
                        val positions = matchInput(lookup, i, rule.input.size + 1) { k, g -> g.gid == rule.input[k - 1] } ?: continue
                        return applyRecords(positions, rule.records)
                    }
                }
                is ContextSubst.Classes -> {
                    if (st.coverage.indexOf(gid) < 0) return -1
                    for (rule in st.ruleSets.getOrNull(st.classes.classOf(gid)) ?: return -1) {
                        val positions = matchInput(lookup, i, rule.input.size + 1) { k, g -> st.classes.classOf(g.gid) == rule.input[k - 1] } ?: continue
                        return applyRecords(positions, rule.records)
                    }
                }
                is ContextSubst.Coverages -> {
                    if (st.coverages.isEmpty() || st.coverages[0].indexOf(gid) < 0) return -1
                    val positions = matchInput(lookup, i, st.coverages.size) { k, g -> st.coverages[k].indexOf(g.gid) >= 0 } ?: return -1
                    return applyRecords(positions, st.records)
                }
            }
            return -1
        }

        private fun applyChain(st: ChainSubst, lookup: Lookup, i: Int): Int {
            val gid = glyphs[i].gid
            when (st) {
                is ChainSubst.Glyphs -> {
                    val index = st.coverage.indexOf(gid).takeIf { it >= 0 } ?: return -1
                    for (rule in st.ruleSets.getOrNull(index) ?: return -1) {
                        val positions = matchInput(lookup, i, rule.input.size + 1) { k, g -> g.gid == rule.input[k - 1] } ?: continue
                        if (!matchBacktrack(lookup, i, rule.backtrack.size) { k, g -> g.gid == rule.backtrack[k] }) continue
                        if (!matchLookahead(lookup, positions.last(), rule.lookahead.size) { k, g -> g.gid == rule.lookahead[k] }) continue
                        return applyRecords(positions, rule.records)
                    }
                }
                is ChainSubst.Classes -> {
                    if (st.coverage.indexOf(gid) < 0) return -1
                    for (rule in st.ruleSets.getOrNull(st.input.classOf(gid)) ?: return -1) {
                        val positions = matchInput(lookup, i, rule.input.size + 1) { k, g -> st.input.classOf(g.gid) == rule.input[k - 1] } ?: continue
                        if (!matchBacktrack(lookup, i, rule.backtrack.size) { k, g -> st.backtrack.classOf(g.gid) == rule.backtrack[k] }) continue
                        if (!matchLookahead(lookup, positions.last(), rule.lookahead.size) { k, g -> st.lookahead.classOf(g.gid) == rule.lookahead[k] }) continue
                        return applyRecords(positions, rule.records)
                    }
                }
                is ChainSubst.Coverages -> {
                    if (st.input.isEmpty() || st.input[0].indexOf(gid) < 0) return -1
                    val positions = matchInput(lookup, i, st.input.size) { k, g -> st.input[k].indexOf(g.gid) >= 0 } ?: return -1
                    if (!matchBacktrack(lookup, i, st.backtrack.size) { k, g -> st.backtrack[k].indexOf(g.gid) >= 0 }) return -1
                    if (!matchLookahead(lookup, positions.last(), st.lookahead.size) { k, g -> st.lookahead[k].indexOf(g.gid) >= 0 }) return -1
                    return applyRecords(positions, st.records)
                }
            }
            return -1
        }

        private fun applyReverse(st: ReverseChainSubst, lookup: Lookup, i: Int): Boolean {
            val index = st.coverage.indexOf(glyphs[i].gid).takeIf { it >= 0 } ?: return false
            if (!matchBacktrack(lookup, i, st.backtrack.size) { k, g -> st.backtrack[k].indexOf(g.gid) >= 0 }) return false
            if (!matchLookahead(lookup, i, st.lookahead.size) { k, g -> st.lookahead[k].indexOf(g.gid) >= 0 }) return false
            replace(i, st.substitutes.getOrNull(index) ?: return false)
            return true
        }

        /**
         * The positions of [count] input glyphs from [start], when [match] accepts glyphs 1 and
         * on; null when they do not match. Input glyphs carry the feature of the lookup, as
         * HarfBuzz's mask check asks.
         */
        private inline fun matchInput(lookup: Lookup, start: Int, count: Int, match: (Int, GsubGlyph) -> Boolean): IntArray? {
            val positions = IntArray(count)
            positions[0] = start
            var p = start
            val syllable = glyphs[start].syllable
            val firstLigId = glyphs[start].ligId
            val firstComponent = glyphs[start].component
            var baseMaySkip: Boolean? = null
            for (k in 1 until count) {
                p = seek(lookup, p, 1, syllable, context = false) { g -> applies(g) && match(k, g) } ?: return null
                val g = glyphs[p]
                // HarfBuzz forms nothing across marks attached to different ligature components.
                if (firstLigId != 0 && firstComponent != 0) {
                    if (g.ligId != firstLigId || g.component != firstComponent) {
                        // Unless the ligature they are attached to is one the lookup skips.
                        val skips = baseMaySkip ?: ligatureBaseSkipped(lookup, start, firstLigId).also { baseMaySkip = it }
                        if (!skips) return null
                    }
                } else if (g.ligId != 0 && g.component != 0 && g.ligId != firstLigId) {
                    return null
                }
                positions[k] = p
            }
            return positions
        }

        /** True when the lookup skips the ligature before [start] that the marks of [ligId] are attached to. */
        private fun ligatureBaseSkipped(lookup: Lookup, start: Int, ligId: Int): Boolean {
            var j = start
            while (j > 0 && glyphs[j - 1].ligId == ligId) {
                if (glyphs[j - 1].component == 0) return skipped(glyphs[j - 1], lookup)
                j--
            }
            return false
        }

        private inline fun matchBacktrack(lookup: Lookup, start: Int, count: Int, match: (Int, GsubGlyph) -> Boolean): Boolean {
            var p = start
            val syllable = glyphs[start].syllable
            for (k in 0 until count) p = seek(lookup, p, -1, syllable, context = true) { g -> match(k, g) } ?: return false
            return true
        }

        private inline fun matchLookahead(lookup: Lookup, end: Int, count: Int, match: (Int, GsubGlyph) -> Boolean): Boolean {
            var p = end
            val syllable = glyphs[end].syllable
            for (k in 0 until count) p = seek(lookup, p, 1, syllable, context = true) { g -> match(k, g) } ?: return false
            return true
        }

        /**
         * The next glyph from [from] in the direction of [step] that [matches], as HarfBuzz's
         * skipping iterator finds it: the lookup flag skips a glyph, and a default ignorable
         * that does not match is passed over. Null when a glyph that cannot be passed over does
         * not match. A per-syllable lookup matches only glyphs of [syllable].
         */
        private inline fun seek(lookup: Lookup, from: Int, step: Int, syllable: Int, context: Boolean, matches: (GsubGlyph) -> Boolean): Int? {
            var p = from + step
            while (p in glyphs.indices) {
                val g = glyphs[p]
                if (!skipped(g, lookup)) {
                    val sameSyllable = !perSyllable || syllable == 0 || g.syllable == syllable
                    if (sameSyllable && matches(g)) return p
                    if (!passable(g, context)) return null
                }
                p += step
            }
            return null
        }

        /** True for a default ignorable that matching may pass over, HarfBuzz's SKIP_MAYBE. */
        private fun passable(g: GsubGlyph, context: Boolean): Boolean = !g.substituted && when (g.ignorable) {
            null -> false
            GsubGlyph.Ignorable.ZWNJ -> context && autoZwnj
            GsubGlyph.Ignorable.ZWJ -> context || autoZwj
            GsubGlyph.Ignorable.OTHER -> true
        }

        /**
         * Applies the nested lookups of [records] at the matched [positions], and returns the
         * index after the last matched glyph. A nested lookup that changes the number of glyphs
         * shifts the positions after it, as HarfBuzz's apply_lookup does.
         */
        private fun applyRecords(positions: IntArray, records: List<Record>): Int {
            val matched = positions.toMutableList()
            var end = matched.last() + 1
            if (nesting >= MAX_NESTING) return end
            nesting++
            try {
                for (record in records) {
                    val idx = record.sequenceIndex
                    if (idx >= matched.size) continue
                    val at = matched[idx]
                    if (at >= glyphs.size) continue
                    val nested = lookups.getOrNull(record.lookupIndex) ?: continue
                    val before = glyphs.size
                    if (applyNested(nested, at) < 0) continue
                    var delta = glyphs.size - before
                    if (delta == 0) continue
                    end += delta
                    if (end < at) { delta += at - end; end = at }
                    var next = idx + 1
                    if (delta < 0) {
                        delta = maxOf(delta, next - matched.size)
                        next -= delta
                    }
                    // Shift the positions after the nested lookup by the change in length.
                    if (delta > 0) {
                        repeat(delta) { k -> matched.add(idx + 1 + k, 0) }
                        for (j in idx + 1..idx + delta) matched[j] = matched[j - 1] + 1
                        for (j in idx + delta + 1 until matched.size) matched[j] += delta
                    } else if (delta < 0) {
                        repeat(-delta) { if (idx + 1 < matched.size) matched.removeAt(idx + 1) }
                        for (j in idx + 1 until matched.size) matched[j] += delta
                    }
                }
            } finally {
                nesting--
            }
            return end.coerceIn(0, glyphs.size)
        }

        /** A nested lookup applies once, at [at], whatever the lookup flags say about that glyph. */
        private fun applyNested(lookup: Lookup, at: Int): Int {
            if (lookup.type == 8) {
                for (st in lookup.subtables) if (st is ReverseChainSubst && applyReverse(st, lookup, at)) return at + 1
                return -1
            }
            return applyAt(lookup, at)
        }

        /** True when the flag of [lookup] makes it pass over [g] (OpenType 1.9, "Lookup Table"). */
        fun skipped(g: GsubGlyph, lookup: Lookup): Boolean {
            val flag = lookup.flag
            if (flag and 0xFF1E == 0) return false
            val cls = glyphClass(g)
            return when (cls) {
                BASE -> flag and IGNORE_BASE != 0
                LIGATURE -> flag and IGNORE_LIGATURES != 0
                MARK -> when {
                    flag and IGNORE_MARKS != 0 -> true
                    flag and USE_MARK_FILTERING_SET != 0 -> gdef?.markSets?.getOrNull(lookup.markSet)?.indexOf(g.gid)?.let { it < 0 } ?: true
                    flag and MARK_ATTACHMENT_TYPE != 0 -> (gdef?.markAttach?.classOf(g.gid) ?: 0) != (flag ushr 8)
                    else -> false
                }
                else -> false
            }
        }

        /** The GDEF class of [g], or one made from what the caller knows when the font has no classes. */
        private fun glyphClass(g: GsubGlyph): Int {
            // HarfBuzz gives a glyph that a shaper inserted no class until a lookup replaces it.
            if (g.inserted && !g.substituted) return 0
            gdef?.glyphClasses?.let { return it.classOf(g.gid) }
            return when {
                g.isMark -> MARK
                g.ligated && !g.multiplied -> LIGATURE
                else -> BASE
            }
        }
    }

    /* ─── The parsed table ─────────────────────────────────────────────────── */

    private class LangSys(val required: Int, val features: IntArray)
    private class Script(val default: LangSys?, val languages: Map<String, LangSys>)
    private class Feature(val tag: String, val lookups: IntArray)
    private class Lookup(val type: Int, val flag: Int, val markSet: Int, val subtables: List<Subtable>)
    private class Record(val sequenceIndex: Int, val lookupIndex: Int)
    private class SeqRule(val input: IntArray, val records: List<Record>)
    private class ChainRule(val backtrack: IntArray, val input: IntArray, val lookahead: IntArray, val records: List<Record>)
    private class Gdef(val glyphClasses: ClassDef?, val markAttach: ClassDef?, val markSets: List<Coverage>)

    private sealed class Subtable

    private class SingleSubst(val coverage: Coverage, val delta: Int, val substitutes: IntArray?) : Subtable() {
        fun substitute(gid: Int): Int? {
            val i = coverage.indexOf(gid).takeIf { it >= 0 } ?: return null
            return substitutes?.getOrNull(i) ?: if (substitutes == null) (gid + delta) and 0xFFFF else null
        }
    }

    private class MultipleSubst(val coverage: Coverage, val sequences: Array<IntArray>) : Subtable() {
        fun sequence(gid: Int): IntArray? = coverage.indexOf(gid).takeIf { it >= 0 }?.let { sequences.getOrNull(it) }
    }

    private class AlternateSubst(val coverage: Coverage, val sets: Array<IntArray>) : Subtable() {
        fun alternates(gid: Int): IntArray? = coverage.indexOf(gid).takeIf { it >= 0 }?.let { sets.getOrNull(it) }
    }

    private class LigatureSubst(val coverage: Coverage, val sets: Array<List<LigRule>>) : Subtable() {
        /** The rules of [first] in the order of the font, which is the order of preference. */
        fun rulesFor(first: Int): List<LigRule>? = coverage.indexOf(first).takeIf { it >= 0 }?.let { sets.getOrNull(it) }
    }

    private sealed class ContextSubst : Subtable() {
        class Glyphs(val coverage: Coverage, val ruleSets: Array<List<SeqRule>>) : ContextSubst()
        class Classes(val coverage: Coverage, val classes: ClassDef, val ruleSets: Array<List<SeqRule>>) : ContextSubst()
        class Coverages(val coverages: List<Coverage>, val records: List<Record>) : ContextSubst()
    }

    private sealed class ChainSubst : Subtable() {
        class Glyphs(val coverage: Coverage, val ruleSets: Array<List<ChainRule>>) : ChainSubst()
        class Classes(
            val coverage: Coverage, val backtrack: ClassDef, val input: ClassDef, val lookahead: ClassDef,
            val ruleSets: Array<List<ChainRule>>,
        ) : ChainSubst()
        class Coverages(val backtrack: List<Coverage>, val input: List<Coverage>, val lookahead: List<Coverage>, val records: List<Record>) : ChainSubst()
    }

    private class ReverseChainSubst(
        val coverage: Coverage, val backtrack: List<Coverage>, val lookahead: List<Coverage>, val substitutes: IntArray,
    ) : Subtable()

    /**
     * A coverage table: the index of a glyph in it, by binary search over its glyphs (format 1)
     * or over its ranges (format 2).
     */
    private class Coverage(private val starts: IntArray, private val ends: IntArray, private val firstIndex: IntArray) {
        fun indexOf(gid: Int): Int {
            var lo = 0
            var hi = starts.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when {
                    gid < starts[mid] -> hi = mid - 1
                    gid > ends[mid] -> lo = mid + 1
                    else -> return firstIndex[mid] + (gid - starts[mid])
                }
            }
            return -1
        }
    }

    /** A class definition table: the class of a glyph, 0 for any glyph it does not list. */
    private class ClassDef(private val starts: IntArray, private val ends: IntArray, private val classes: IntArray) {
        fun classOf(gid: Int): Int {
            var lo = 0
            var hi = starts.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when {
                    gid < starts[mid] -> hi = mid - 1
                    gid > ends[mid] -> lo = mid + 1
                    else -> return classes[mid]
                }
            }
            return 0
        }
    }

    public companion object {
        /**
         * A table with no scripts, features or lookups, for a font without GSUB. A shaper still
         * reorders and normalizes its text, as HarfBuzz does for such a font.
         */
        public val EMPTY: OpenTypeGsub = OpenTypeGsub(emptyMap(), emptyList(), emptyList(), null)

        /** The glyph classes of GDEF (OpenType 1.9, "GDEF: Glyph Definition Table"). */
        private const val BASE = 1
        private const val LIGATURE = 2
        private const val MARK = 3

        private const val IGNORE_BASE = 0x2
        private const val IGNORE_LIGATURES = 0x4
        private const val IGNORE_MARKS = 0x8
        private const val USE_MARK_FILTERING_SET = 0x10
        private const val MARK_ATTACHMENT_TYPE = 0xFF00

        /** Nested lookups go no deeper, as HarfBuzz's HB_MAX_NESTING_LEVEL. */
        private const val MAX_NESTING = 64

        public fun from(gsub: ByteArray?): OpenTypeGsub? = from(gsub, null)

        /** The GSUB table [gsub] with the glyph classes of the GDEF table [gdef], or null when [gsub] cannot be read. */
        public fun from(gsub: ByteArray?, gdef: ByteArray?): OpenTypeGsub? {
            gsub ?: return null
            return runCatching { Parser(gsub).table(gdef?.let { runCatching { parseGdef(it) }.getOrNull() }) }.getOrNull()
        }

        private fun parseGdef(b: ByteArray): Gdef {
            val r = R(b)
            r.seek(0)
            r.u16()
            val minor = r.u16()
            val classOff = r.u16()
            r.u16() // attachList
            r.u16() // ligCaretList
            val markAttachOff = r.u16()
            val markSetsOff = if (minor >= 2) r.u16() else 0
            val markSets = ArrayList<Coverage>()
            if (markSetsOff != 0) {
                r.seek(markSetsOff)
                r.u16() // format
                val count = r.u16()
                val offsets = LongArray(count) { r.u32() }
                for (o in offsets) markSets += readCoverage(b, markSetsOff + o.toInt())
            }
            return Gdef(
                classOff.takeIf { it != 0 }?.let { readClassDef(b, it) },
                markAttachOff.takeIf { it != 0 }?.let { readClassDef(b, it) },
                markSets,
            )
        }

        private fun readCoverage(b: ByteArray, off: Int): Coverage {
            val r = R(b); r.seek(off)
            return when (r.u16()) {
                1 -> {
                    val n = r.u16()
                    val glyphs = IntArray(n) { r.u16() }
                    Coverage(glyphs, glyphs, IntArray(n) { it })
                }
                2 -> {
                    val n = r.u16()
                    val starts = IntArray(n); val ends = IntArray(n); val first = IntArray(n)
                    for (i in 0 until n) { starts[i] = r.u16(); ends[i] = r.u16(); first[i] = r.u16() }
                    Coverage(starts, ends, first)
                }
                else -> Coverage(IntArray(0), IntArray(0), IntArray(0))
            }
        }

        private fun readClassDef(b: ByteArray, off: Int): ClassDef {
            val r = R(b); r.seek(off)
            return when (r.u16()) {
                1 -> {
                    val start = r.u16()
                    val n = r.u16()
                    val classes = IntArray(n) { r.u16() }
                    ClassDef(IntArray(n) { start + it }, IntArray(n) { start + it }, classes)
                }
                2 -> {
                    val n = r.u16()
                    val starts = IntArray(n); val ends = IntArray(n); val classes = IntArray(n)
                    for (i in 0 until n) { starts[i] = r.u16(); ends[i] = r.u16(); classes[i] = r.u16() }
                    ClassDef(starts, ends, classes)
                }
                else -> ClassDef(IntArray(0), IntArray(0), IntArray(0))
            }
        }

        private fun tagString(v: Long): String = buildString {
            append(((v ushr 24) and 0xFF).toInt().toChar())
            append(((v ushr 16) and 0xFF).toInt().toChar())
            append(((v ushr 8) and 0xFF).toInt().toChar())
            append((v and 0xFF).toInt().toChar())
        }

        /** Reads the lists of one GSUB table; a subtable it cannot read is left out, not the table. */
        private class Parser(private val b: ByteArray) {

            fun table(gdef: Gdef?): OpenTypeGsub? {
                val r = R(b)
                r.u16(); r.u16() // major/minor
                val scriptListOff = r.u16()
                val featureListOff = r.u16()
                val lookupListOff = r.u16()
                val lookups = lookupList(lookupListOff)
                if (lookups.all { it == null }) return null
                return OpenTypeGsub(scriptList(scriptListOff), featureList(featureListOff), lookups, gdef)
            }

            private fun scriptList(off: Int): Map<String, Script> {
                val out = HashMap<String, Script>()
                val r = R(b); r.seek(off)
                val n = r.u16()
                repeat(n) {
                    val tag = tagString(r.u32())
                    val scriptOff = off + r.u16()
                    runCatching { script(scriptOff) }.getOrNull()?.let { out[tag] = it }
                }
                return out
            }

            private fun script(off: Int): Script {
                val r = R(b); r.seek(off)
                val defaultOff = r.u16()
                val n = r.u16()
                val languages = HashMap<String, LangSys>()
                repeat(n) {
                    val tag = tagString(r.u32())
                    val langOff = r.u16()
                    languages[tag] = langSys(off + langOff)
                }
                return Script(defaultOff.takeIf { it != 0 }?.let { langSys(off + it) }, languages)
            }

            private fun langSys(off: Int): LangSys {
                val r = R(b); r.seek(off)
                r.u16() // lookupOrder
                val required = r.u16().let { if (it == 0xFFFF) -1 else it }
                val n = r.u16()
                return LangSys(required, IntArray(n) { r.u16() })
            }

            private fun featureList(off: Int): List<Feature> {
                val r = R(b); r.seek(off)
                val n = r.u16()
                return List(n) {
                    val tag = tagString(r.u32())
                    val featOff = off + r.u16()
                    val fr = R(b); fr.seek(featOff)
                    fr.u16() // featureParams
                    val count = fr.u16()
                    Feature(tag, IntArray(count) { fr.u16() })
                }
            }

            private fun lookupList(off: Int): List<Lookup?> {
                val r = R(b); r.seek(off)
                val n = r.u16()
                val offsets = IntArray(n) { r.u16() }
                return offsets.map { runCatching { lookup(off + it) }.getOrNull() }
            }

            private fun lookup(off: Int): Lookup {
                val r = R(b); r.seek(off)
                val type = r.u16()
                val flag = r.u16()
                val n = r.u16()
                val subOffsets = IntArray(n) { off + r.u16() }
                val markSet = if (flag and USE_MARK_FILTERING_SET != 0) r.u16() else 0
                val subtables = ArrayList<Subtable>()
                var resolved = type
                for (so in subOffsets) {
                    var subOff = so
                    var effType = type
                    if (type == 7) {
                        // Extension: the real type and a 32-bit offset from the extension subtable.
                        val er = R(b); er.seek(so)
                        er.u16()
                        effType = er.u16()
                        subOff = so + er.u32().toInt()
                        resolved = effType
                    }
                    runCatching { subtable(effType, subOff) }.getOrNull()?.let { subtables += it }
                }
                return Lookup(if (resolved == 7) 0 else resolved, flag, markSet, subtables)
            }

            private fun subtable(type: Int, off: Int): Subtable? {
                val r = R(b); r.seek(off)
                val format = r.u16()
                return when (type) {
                    1 -> {
                        val cov = readCoverage(b, off + r.u16())
                        when (format) {
                            1 -> SingleSubst(cov, r.s16(), null)
                            2 -> { val n = r.u16(); SingleSubst(cov, 0, IntArray(n) { r.u16() }) }
                            else -> null
                        }
                    }
                    2, 3 -> {
                        val cov = readCoverage(b, off + r.u16())
                        val n = r.u16()
                        val sets = Array(n) { idx ->
                            val sr = R(b); sr.seek(off + R(b).also { it.seek(off + 6 + 2 * idx) }.u16())
                            val count = sr.u16()
                            IntArray(count) { sr.u16() }
                        }
                        if (type == 2) MultipleSubst(cov, sets) else AlternateSubst(cov, sets)
                    }
                    4 -> {
                        val cov = readCoverage(b, off + r.u16())
                        val n = r.u16()
                        val setOffsets = IntArray(n) { off + r.u16() }
                        LigatureSubst(cov, Array(n) { idx ->
                            val setBase = setOffsets[idx]
                            val sr = R(b); sr.seek(setBase)
                            val count = sr.u16()
                            val ligOffsets = IntArray(count) { setBase + sr.u16() }
                            ligOffsets.map { lo ->
                                val lr = R(b); lr.seek(lo)
                                val lig = lr.u16()
                                val comps = lr.u16()
                                LigRule(IntArray((comps - 1).coerceAtLeast(0)) { lr.u16() }, lig)
                            }
                        })
                    }
                    5 -> context(format, off)
                    6 -> chain(format, off)
                    8 -> {
                        val cov = readCoverage(b, off + r.u16())
                        val back = List(r.u16()) { readCoverage(b, off + r.u16()) }
                        val ahead = List(r.u16()) { readCoverage(b, off + r.u16()) }
                        val n = r.u16()
                        ReverseChainSubst(cov, back, ahead, IntArray(n) { r.u16() })
                    }
                    else -> null
                }
            }

            private fun records(r: R, count: Int): List<Record> = List(count) { Record(r.u16(), r.u16()) }

            private fun context(format: Int, off: Int): Subtable? {
                val r = R(b); r.seek(off + 2)
                return when (format) {
                    1, 2 -> {
                        val cov = readCoverage(b, off + r.u16())
                        val classes = if (format == 2) readClassDef(b, off + r.u16()) else null
                        val n = r.u16()
                        val setOffsets = IntArray(n) { r.u16() }
                        val sets = Array(n) { idx ->
                            if (setOffsets[idx] == 0) return@Array emptyList<SeqRule>()
                            val setBase = off + setOffsets[idx]
                            val sr = R(b); sr.seek(setBase)
                            val count = sr.u16()
                            val ruleOffsets = IntArray(count) { setBase + sr.u16() }
                            ruleOffsets.map { ro ->
                                val rr = R(b); rr.seek(ro)
                                val glyphCount = rr.u16()
                                val recordCount = rr.u16()
                                val input = IntArray((glyphCount - 1).coerceAtLeast(0)) { rr.u16() }
                                SeqRule(input, records(rr, recordCount))
                            }
                        }
                        if (classes != null) ContextSubst.Classes(cov, classes, sets) else ContextSubst.Glyphs(cov, sets)
                    }
                    3 -> {
                        val glyphCount = r.u16()
                        val recordCount = r.u16()
                        val coverages = List(glyphCount) { readCoverage(b, off + r.u16()) }
                        ContextSubst.Coverages(coverages, records(r, recordCount))
                    }
                    else -> null
                }
            }

            private fun chain(format: Int, off: Int): Subtable? {
                val r = R(b); r.seek(off + 2)
                return when (format) {
                    1, 2 -> {
                        val cov = readCoverage(b, off + r.u16())
                        val classDefs = if (format == 2) List(3) { readClassDef(b, off + r.u16()) } else null
                        val n = r.u16()
                        val setOffsets = IntArray(n) { r.u16() }
                        val sets = Array(n) { idx ->
                            if (setOffsets[idx] == 0) return@Array emptyList<ChainRule>()
                            val setBase = off + setOffsets[idx]
                            val sr = R(b); sr.seek(setBase)
                            val count = sr.u16()
                            val ruleOffsets = IntArray(count) { setBase + sr.u16() }
                            ruleOffsets.map { ro ->
                                val rr = R(b); rr.seek(ro)
                                val backtrack = IntArray(rr.u16()) { rr.u16() }
                                val inputCount = rr.u16()
                                val input = IntArray((inputCount - 1).coerceAtLeast(0)) { rr.u16() }
                                val lookahead = IntArray(rr.u16()) { rr.u16() }
                                ChainRule(backtrack, input, lookahead, records(rr, rr.u16()))
                            }
                        }
                        if (classDefs != null) ChainSubst.Classes(cov, classDefs[0], classDefs[1], classDefs[2], sets)
                        else ChainSubst.Glyphs(cov, sets)
                    }
                    3 -> {
                        val back = List(r.u16()) { readCoverage(b, off + r.u16()) }
                        val input = List(r.u16()) { readCoverage(b, off + r.u16()) }
                        val ahead = List(r.u16()) { readCoverage(b, off + r.u16()) }
                        ChainSubst.Coverages(back, input, ahead, records(r, r.u16()))
                    }
                    else -> null
                }
            }
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
 * One glyph of a run that [OpenTypeGsub.substitute] reshapes.
 *
 * @property gid The glyph id.
 * @property cluster The index of the first character this glyph stands for. A ligature takes
 *   the cluster of its first component, and so do the marks between its components; the
 *   glyphs of a multiple substitution keep the cluster of the glyph they replace.
 * @property features The features that reach this glyph besides the global ones, such as the
 *   joining form of an Arabic letter.
 * @property isMark True for a combining mark. A lookup flag reads it when the font has no
 *   GDEF glyph classes of its own.
 * @property ignorable The kind of default ignorable character this glyph stands for, which a
 *   lookup may pass over, or null for any other glyph. CGJ, the Mongolian free variation
 *   selectors and the tag characters stay null, because HarfBuzz does not pass over them in GSUB.
 */
public class GsubGlyph(
    public var gid: Int,
    public var cluster: Int,
    public var features: Set<String> = emptySet(),
    public val isMark: Boolean = false,
    public val ignorable: Ignorable? = null,
) {
    /** The default ignorable characters a lookup may pass over, as HarfBuzz tells them apart. */
    public enum class Ignorable { ZWJ, ZWNJ, OTHER }

    /** The syllable of the glyph, which a per-syllable lookup does not match across. */
    public var syllable: Int = 0

    /** Free for the shaper: the category and position an Indic shaper keeps with each glyph. */
    public var shaperData: Int = 0

    /** How many glyphs a ligature joined into this one, 1 for any other glyph. */
    public var components: Int = 1
        internal set

    /**
     * True once any substitution replaced this glyph. A lookup no longer passes over it. A
     * shaper may clear it, as HarfBuzz clears it between the stages of its Universal Shaping
     * Engine to see what the next stage substitutes.
     */
    public var substituted: Boolean = false

    /**
     * The ligature component this glyph belongs to, as HarfBuzz's lig_comp counts it: for a mark
     * a ligature passed over, the component of the ligature it attaches to; for a part of a
     * multiple substitution, its index among the parts; 0 for a ligature and any other glyph.
     */
    public val component: Int get() = if (ligBase) 0 else ligComp

    /** The ligature this glyph is, or attaches to, as HarfBuzz's lig_id numbers them; 0 for none. */
    internal var ligId: Int = 0
    internal var ligComp: Int = 0
    internal var ligBase: Boolean = false
    internal var ligComponents: Int = 1

    /** True once a ligature substitution produced this glyph. A shaper may clear it. */
    public var ligated: Boolean = false

    /** True when a multiple substitution produced this glyph and no ligature has joined it since. A shaper may clear it. */
    public var multiplied: Boolean = false

    /**
     * True for a glyph a shaper inserted, such as a dotted circle. Until a substitution replaces
     * it, it has no glyph class, so no lookup flag passes over it, as in HarfBuzz.
     */
    public var inserted: Boolean = false

    internal fun copy(gid: Int): GsubGlyph = GsubGlyph(gid, cluster, features, isMark, ignorable).also {
        it.components = components
        it.ligId = ligId
        it.ligComp = ligComp
        it.ligBase = ligBase
        it.ligComponents = ligComponents
        it.substituted = substituted
        it.ligated = ligated
        it.multiplied = multiplied
        it.inserted = inserted
        it.syllable = syllable
        it.shaperData = shaperData
    }
}
