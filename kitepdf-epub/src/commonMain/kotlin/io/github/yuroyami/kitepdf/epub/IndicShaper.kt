package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.GsubGlyph
import io.github.yuroyami.kitepdf.core.font.OpenTypeGsub

/**
 * The shaper of the Brahmic scripts of India (#211), after HarfBuzz's Indic shaper with the
 * new-spec script tags (`dev2`, `bng2`). GSUB alone cannot shape these scripts, because a
 * syllable must be reordered around the features:
 *
 * 1. The text splits into syllables, and each character gets a category and a position.
 * 2. `locl` and `ccmp` apply.
 * 3. Initial reordering finds the base consonant of each syllable, marks a leading Ra and
 *    virama as reph, moves a pre-base matra to the front, and chooses the features each
 *    glyph takes: half forms before the base, below-base and post-base forms after it.
 * 4. The basic features apply one at a time: nukt, akhn, rphf, rkrf, pref, blwf, abvf, half,
 *    pstf, vatu and cjct.
 * 5. Final reordering moves the pre-base matra after the half forms, and the reph to its place.
 * 6. The presentation features apply: init, pres, abvs, blws, psts and haln.
 *
 * Devanagari and Bengali are covered. A syllable keeps one cluster, so its text stays whole.
 */
internal object IndicShaper {

    /* ─── Categories and positions (HarfBuzz's I_Cat and indic_position_t) ─────── */

    private const val X = 0
    private const val C = 1
    private const val V = 2
    private const val N = 3
    private const val H = 4
    private const val ZWNJ = 5
    private const val ZWJ = 6
    private const val M = 7
    private const val SM = 8
    private const val A = 10
    private const val PLACEHOLDER = 11
    private const val DOTTED_CIRCLE = 12
    private const val RA = 16
    private const val SYMBOL = 18

    private const val START = 0
    private const val RA_TO_BECOME_REPH = 1
    private const val PRE_M = 2
    private const val PRE_C = 3
    private const val BASE_C = 4
    private const val AFTER_MAIN = 5
    private const val BELOW_C = 8
    private const val AFTER_SUB = 9
    private const val POST_C = 11
    private const val AFTER_POST = 12
    private const val FINAL_C = 13
    private const val SMVD = 14

    /** How one script places its reph and its matras. */
    private class Config(val virama: Int, val rephPosition: Int, val rightMatra: Int, val bottomMatra: Int, val topMatra: Int)

    private val DEVANAGARI = Config(0x094D, rephPosition = 10, rightMatra = AFTER_SUB, bottomMatra = AFTER_SUB, topMatra = AFTER_SUB)
    private val BENGALI = Config(0x09CD, rephPosition = AFTER_SUB, rightMatra = AFTER_POST, bottomMatra = AFTER_SUB, topMatra = AFTER_SUB)

    private fun config(script: String): Config? = when (script) {
        "dev2", "deva" -> DEVANAGARI
        "bng2", "beng" -> BENGALI
        else -> null
    }

    /** True for a script this shaper covers. */
    fun handles(script: String): Boolean = config(script) != null

    /** The basic features, each applied on its own, in this order. */
    private val BASIC = listOf("nukt", "akhn", "rphf", "rkrf", "pref", "blwf", "abvf", "half", "pstf", "vatu", "cjct")

    /** The features that reach only the glyphs reordering chose for them. */
    private val POSITIONAL = setOf("rphf", "pref", "blwf", "abvf", "half", "pstf", "init")

    /** The features HarfBuzz constrains to a syllable. */
    private val PER_SYLLABLE = setOf("locl", "ccmp") + BASIC + setOf("init", "pres", "abvs", "blws", "psts", "haln")

    /**
     * The canonical decomposition of [cp] that HarfBuzz keeps decomposed: a nukta form that
     * Unicode excludes from composition, or a split matra. Null for any other character.
     */
    fun decompose(cp: Int): IntArray? = when (cp) {
        in 0x0958..0x095F -> intArrayOf(intArrayOf(0x0915, 0x0916, 0x0917, 0x091C, 0x0921, 0x0922, 0x092B, 0x092F)[cp - 0x0958], 0x093C)
        0x09DC -> intArrayOf(0x09A1, 0x09BC)
        0x09DD -> intArrayOf(0x09A2, 0x09BC)
        0x09DF -> intArrayOf(0x09AF, 0x09BC)
        0x09CB -> intArrayOf(0x09C7, 0x09BE)
        0x09CC -> intArrayOf(0x09C7, 0x09D7)
        else -> null
    }

    /**
     * Shapes the [glyphs] of one word in [script], whose code points are [codePoints], in place.
     * [gidFor] gives the glyph of a code point, for the virama and the dotted circle.
     */
    fun shape(gsub: OpenTypeGsub, script: String, glyphs: MutableList<GsubGlyph>, codePoints: IntArray, gidFor: (Int) -> Int, optionalLigatures: Boolean) {
        val config = config(script) ?: return
        for ((i, g) in glyphs.withIndex()) g.shaperData = properties(codePoints[i], config)
        val broken = findSyllables(glyphs)
        gsub.substitute(glyphs, script, null, listOf(listOf("locl", "ccmp")), perSyllable = PER_SYLLABLE)

        val virama = gidFor(config.virama)
        updateConsonantPositions(gsub, script, glyphs, virama)
        insertDottedCircles(glyphs, broken, gidFor(0x25CC))
        forEachSyllable(glyphs) { start, end -> initialReordering(gsub, script, glyphs, start, end) }
        for (feature in BASIC) gsub.substitute(glyphs, script, null, listOf(listOf(feature)), POSITIONAL, PER_SYLLABLE)
        forEachSyllable(glyphs) { start, end -> finalReordering(config, glyphs, start, end) }
        val presentation = listOf("init", "pres", "abvs", "blws", "psts", "haln", "rlig", "rclt", "calt") +
            if (optionalLigatures) listOf("liga", "clig") else emptyList()
        gsub.substitute(glyphs, script, null, listOf(presentation), POSITIONAL, PER_SYLLABLE)

        // A reordered syllable is one cluster, so its first glyph carries the whole text of it.
        forEachSyllable(glyphs) { start, end ->
            val cluster = (start until end).minOf { glyphs[it].cluster }
            for (i in start until end) glyphs[i].cluster = cluster
        }
    }

    /* ─── Properties ───────────────────────────────────────────────────────── */

    private fun category(g: GsubGlyph): Int = g.shaperData and 0xFF
    private fun position(g: GsubGlyph): Int = g.shaperData ushr 8
    private fun setPosition(g: GsubGlyph, pos: Int) { g.shaperData = (g.shaperData and 0xFF) or (pos shl 8) }

    /** A glyph a ligature produced matches no category, as HarfBuzz's is_one_of says. */
    private fun isOneOf(g: GsubGlyph, vararg categories: Int): Boolean = !g.ligated && category(g) in categories
    private fun isConsonant(g: GsubGlyph): Boolean = isOneOf(g, C, RA, V, PLACEHOLDER, DOTTED_CIRCLE)
    private fun isJoiner(g: GsubGlyph): Boolean = isOneOf(g, ZWJ, ZWNJ)
    private fun isHalant(g: GsubGlyph): Boolean = isOneOf(g, H)

    /** The category of [cp] in the low byte and its position above it. */
    private fun properties(cp: Int, config: Config): Int {
        fun pack(cat: Int, pos: Int) = cat or (pos shl 8)
        fun matra(side: Char) = pack(M, when (side) { 'L' -> PRE_M; 'R' -> config.rightMatra; 'B' -> config.bottomMatra; else -> config.topMatra })
        return when (cp) {
            0x200C -> pack(ZWNJ, BASE_C)
            0x200D -> pack(ZWJ, BASE_C)
            0x25CC -> pack(DOTTED_CIRCLE, BASE_C)
            0x00A0, in 0x2010..0x2014 -> pack(PLACEHOLDER, BASE_C)
            in 0x0900..0x0903 -> pack(SM, SMVD)
            in 0x0904..0x0914, in 0x0960..0x0961, in 0x0972..0x0977 -> pack(V, BASE_C)
            0x0930 -> pack(RA, BASE_C)
            in 0x0915..0x0939, in 0x0958..0x095F, in 0x0978..0x097F -> pack(C, BASE_C)
            0x093C -> pack(N, BASE_C)
            0x093D -> pack(SYMBOL, BASE_C)
            0x093F, 0x094E -> matra('L')
            0x093B, 0x093E, 0x0940, in 0x0949..0x094C, 0x094F -> matra('R')
            in 0x0941..0x0944, 0x0956, 0x0957, 0x0962, 0x0963 -> matra('B')
            0x093A, in 0x0945..0x0948, 0x0955 -> matra('T')
            0x094D -> pack(H, BASE_C)
            in 0x0951..0x0954 -> pack(A, SMVD)
            in 0x0966..0x096F -> pack(PLACEHOLDER, BASE_C)
            in 0x0981..0x0983 -> pack(SM, SMVD)
            in 0x0985..0x0994, 0x09E0, 0x09E1 -> pack(V, BASE_C)
            0x09B0, 0x09F0 -> pack(RA, BASE_C)
            in 0x0995..0x09B9, 0x09CE, in 0x09DC..0x09DF, 0x09F1 -> pack(C, BASE_C)
            0x09BC -> pack(N, BASE_C)
            0x09BD -> pack(SYMBOL, BASE_C)
            0x09BF, 0x09C7, 0x09C8 -> matra('L')
            0x09BE, 0x09C0, 0x09D7 -> matra('R')
            in 0x09C1..0x09C4, 0x09E2, 0x09E3 -> matra('B')
            0x09CD -> pack(H, BASE_C)
            in 0x09E6..0x09EF -> pack(PLACEHOLDER, BASE_C)
            else -> pack(X, BASE_C)
        }
    }

    /* ─── Syllables (HarfBuzz's indic machine, in the parts these scripts use) ─── */

    /** Numbers the syllables of [glyphs] from 1, and returns the numbers of the broken ones. */
    private fun findSyllables(glyphs: List<GsubGlyph>): Set<Int> {
        val cats = IntArray(glyphs.size) { category(glyphs[it]) }
        val broken = HashSet<Int>()
        var i = 0
        var id = 0
        while (i < cats.size) {
            id++
            val end = when (cats[i]) {
                C, RA -> complexTail(cats, cn(cats, i))
                V, PLACEHOLDER, DOTTED_CIRCLE -> {
                    var k = modifier(cats, i + 1)
                    if (cats[i] == V && k < cats.size && cats[k] == ZWJ) k + 1 else complexTail(cats, k)
                }
                SYMBOL -> tail(cats, if (i + 1 < cats.size && cats[i + 1] == N) i + 2 else i + 1)
                M, N, H, SM, ZWJ, ZWNJ, A -> complexTail(cats, modifier(cats, i)).also { if (it > i) broken += id }
                else -> i + 1
            }.coerceAtLeast(i + 1)
            for (k in i until end) glyphs[k].syllable = id
            i = end
        }
        return broken
    }

    /** A consonant with an optional ZWJ and nukta: `c.ZWJ?.n?`. */
    private fun cn(cats: IntArray, i: Int): Int {
        var k = i + 1
        if (k < cats.size && cats[k] == ZWJ) k++
        return modifier(cats, k)
    }

    /** Up to two nuktas. */
    private fun modifier(cats: IntArray, i: Int): Int {
        var k = i
        if (k < cats.size && cats[k] == N) k++
        if (k < cats.size && cats[k] == N) k++
        return k
    }

    /** `(halant_group.cn)* halant_or_matra_group syllable_tail`. */
    private fun complexTail(cats: IntArray, from: Int): Int {
        var i = from
        // (z? H (ZWJ N?)? cn)*
        while (true) {
            var k = i
            if (k < cats.size && (cats[k] == ZWJ || cats[k] == ZWNJ)) k++
            if (k >= cats.size || cats[k] != H) break
            k++
            if (k < cats.size && cats[k] == ZWJ) { k++; if (k < cats.size && cats[k] == N) k++ }
            if (k < cats.size && (cats[k] == C || cats[k] == RA)) i = cn(cats, k) else break
        }
        // final_halant_group | matra_group*
        var k = i
        if (k < cats.size && (cats[k] == ZWJ || cats[k] == ZWNJ)) k++
        if (k < cats.size && cats[k] == H) {
            k++
            if (k < cats.size && cats[k] == ZWJ) { k++; if (k < cats.size && cats[k] == N) k++ }
            else if (k < cats.size && cats[k] == ZWNJ) k++
            i = k
        } else {
            while (true) {
                var m = i
                while (m < cats.size && (cats[m] == ZWJ || cats[m] == ZWNJ)) m++
                if (m >= cats.size || cats[m] != M) break
                m++
                if (m < cats.size && cats[m] == N) m++
                if (m < cats.size && cats[m] == H) m++
                i = m
            }
        }
        return tail(cats, i)
    }

    /** `(z?.SM.SM?.ZWNJ?)? A*`. */
    private fun tail(cats: IntArray, from: Int): Int {
        var i = from
        var k = i
        if (k < cats.size && (cats[k] == ZWJ || cats[k] == ZWNJ)) k++
        if (k < cats.size && cats[k] == SM) {
            k++
            if (k < cats.size && cats[k] == SM) k++
            if (k < cats.size && cats[k] == ZWNJ) k++
            i = k
        }
        while (i < cats.size && cats[i] == A) i++
        return i
    }

    private inline fun forEachSyllable(glyphs: List<GsubGlyph>, action: (Int, Int) -> Unit) {
        var start = 0
        while (start < glyphs.size) {
            var end = start + 1
            while (end < glyphs.size && glyphs[end].syllable == glyphs[start].syllable) end++
            action(start, end)
            start = end
        }
    }

    /** A broken syllable, such as a lone matra, gets a dotted circle to sit on, as HarfBuzz gives it. */
    private fun insertDottedCircles(glyphs: MutableList<GsubGlyph>, broken: Set<Int>, circle: Int) {
        if (broken.isEmpty() || circle <= 0) return
        var i = 0
        var last = -1
        while (i < glyphs.size) {
            val g = glyphs[i]
            if (g.syllable in broken && g.syllable != last) {
                glyphs.add(i, GsubGlyph(circle, g.cluster).also { it.syllable = g.syllable; it.shaperData = DOTTED_CIRCLE or (BASE_C shl 8) })
                last = g.syllable
                i++
            }
            i++
        }
    }

    /**
     * A consonant with a below-base or post-base form in the font takes that position, as
     * HarfBuzz's consonant_position_from_face finds it: blwf or vatu with the virama on either
     * side makes it below-base, and pstf or pref makes it post-base.
     */
    private fun updateConsonantPositions(gsub: OpenTypeGsub, script: String, glyphs: List<GsubGlyph>, virama: Int) {
        if (virama <= 0) return
        val cache = HashMap<Int, Int>()
        for (g in glyphs) {
            if (position(g) != BASE_C || category(g) !in intArrayOf(C, RA)) continue
            val pos = cache.getOrPut(g.gid) {
                val before = intArrayOf(virama, g.gid)
                val after = intArrayOf(g.gid, virama)
                fun would(feature: String) = gsub.wouldSubstitute(feature, script, before) || gsub.wouldSubstitute(feature, script, after)
                when {
                    would("blwf") || would("vatu") -> BELOW_C
                    would("pstf") || would("pref") -> POST_C
                    else -> BASE_C
                }
            }
            setPosition(g, pos)
        }
    }

    /* ─── Initial reordering (HarfBuzz's initial_reordering_consonant_syllable) ─── */

    private fun initialReordering(gsub: OpenTypeGsub, script: String, glyphs: MutableList<GsubGlyph>, start: Int, end: Int) {
        val first = category(glyphs[start])
        if (first !in intArrayOf(C, RA, V, PLACEHOLDER, DOTTED_CIRCLE)) return

        // 1. The base consonant. A leading Ra and virama that the font makes a reph is not a candidate.
        var base = end
        var hasReph = false
        var limit = start
        if (start + 3 <= end && !isJoiner(glyphs[start + 2]) &&
            gsub.wouldSubstitute("rphf", script, intArrayOf(glyphs[start].gid, glyphs[start + 1].gid))
        ) {
            limit += 2
            while (limit < end && isJoiner(glyphs[limit])) limit++
            base = start
            hasReph = true
        }
        var i = end
        var seenBelow = false
        do {
            i--
            val g = glyphs[i]
            if (isConsonant(g)) {
                val pos = position(g)
                if (pos != BELOW_C && (pos != POST_C || seenBelow)) { base = i; break }
                if (pos == BELOW_C) seenBelow = true
                base = i
            } else if (start < i && category(g) == ZWJ && category(glyphs[i - 1]) == H) {
                break
            }
        } while (i > limit)
        if (hasReph && base == start && limit - base <= 2) hasReph = false

        // 2. Positions.
        for (k in start until base) setPosition(glyphs[k], minOf(PRE_C, position(glyphs[k])))
        if (base < end) setPosition(glyphs[base], BASE_C)
        for (k in base + 1 until end) {
            if (category(glyphs[k]) != M) continue
            for (j in k + 1 until end) if (isConsonant(glyphs[j])) { setPosition(glyphs[j], FINAL_C); break }
            break
        }
        if (hasReph) setPosition(glyphs[start], RA_TO_BECOME_REPH)

        // Joiners, nuktas and viramas move with the character before them.
        var lastPos = START
        for (k in start until end) {
            val g = glyphs[k]
            val cat = category(g)
            if (cat == ZWJ || cat == ZWNJ || cat == N || cat == H) {
                setPosition(g, lastPos)
                if (cat == H && position(g) == PRE_M) {
                    for (j in k downTo start + 1) if (position(glyphs[j - 1]) != PRE_M) { setPosition(g, position(glyphs[j - 1])); break }
                }
            } else if (position(g) != SMVD) {
                lastPos = position(g)
            }
        }
        // A post-base consonant takes what came since the last consonant or matra.
        var last = base
        for (k in base + 1 until end) {
            if (isConsonant(glyphs[k])) {
                for (j in last + 1 until k) if (position(glyphs[j]) < SMVD) setPosition(glyphs[j], position(glyphs[k]))
                last = k
            } else if (category(glyphs[k]) == M) {
                last = k
            }
        }

        // 3. Sort by position, keeping the order of equal ones, and find the base again.
        val sorted = glyphs.subList(start, end).sortedBy { position(it) }
        for (k in sorted.indices) glyphs[start + k] = sorted[k]
        base = end
        var firstLeft = end
        var lastLeft = end
        for (k in start until end) {
            val pos = position(glyphs[k])
            if (pos == BASE_C) { base = k; break }
            if (pos == PRE_M) { if (firstLeft == end) firstLeft = k; lastLeft = k }
        }
        if (firstLeft < lastLeft) {
            glyphs.subList(firstLeft, lastLeft + 1).reverse()
            var k = firstLeft
            for (j in firstLeft..lastLeft) if (category(glyphs[j]) == M) { glyphs.subList(k, j + 1).reverse(); k = j + 1 }
        }

        // 4. The features each glyph takes.
        var k = start
        while (k < end && position(glyphs[k]) == RA_TO_BECOME_REPH) { glyphs[k].features += "rphf"; k++ }
        for (j in start until base) glyphs[j].features += listOf("half", "blwf")
        for (j in base + 1 until end) glyphs[j].features += listOf("blwf", "abvf", "pstf")
        // A ZWNJ keeps the consonant before it from taking a half form.
        for (j in start + 1 until end) {
            if (!isJoiner(glyphs[j])) continue
            val nonJoiner = category(glyphs[j]) == ZWNJ
            var p = j
            do {
                p--
                if (nonJoiner) glyphs[p].features -= "half"
            } while (p > start && !isConsonant(glyphs[p]))
        }
    }

    /* ─── Final reordering (HarfBuzz's final_reordering_syllable_indic) ─────── */

    private fun finalReordering(config: Config, glyphs: MutableList<GsubGlyph>, start: Int, end: Int) {
        var base = start
        while (base < end) {
            if (position(glyphs[base]) >= BASE_C) {
                if (start < base && position(glyphs[base]) > BASE_C) base--
                break
            }
            base++
        }
        if (base == end && start < base && isOneOf(glyphs[base - 1], ZWJ)) base--
        if (base < end) while (start < base && isOneOf(glyphs[base], N, H)) base--

        // A pre-base matra moves after the last half form, before the main consonant.
        if (start + 1 < end && start < base) {
            var newPos = if (base == end) base - 2 else base - 1
            while (true) {
                while (newPos > start && !isOneOf(glyphs[newPos], M, H)) newPos--
                if (isHalant(glyphs[newPos]) && position(glyphs[newPos]) != PRE_M) {
                    // A ZWJ after the virama keeps the matra from moving past it.
                    if (newPos + 1 < end && category(glyphs[newPos + 1]) == ZWJ && newPos > start) { newPos--; continue }
                } else {
                    newPos = start
                }
                break
            }
            if (start < newPos && position(glyphs[newPos]) != PRE_M) {
                var i = newPos
                while (i > start) {
                    if (position(glyphs[i - 1]) == PRE_M) {
                        val oldPos = i - 1
                        if (oldPos < base && base <= newPos) base--
                        val matra = glyphs.removeAt(oldPos)
                        glyphs.add(newPos, matra)
                        newPos--
                    }
                    i--
                }
            }
        }

        // The reph moves to its place, if the font formed it.
        if (start + 1 < end && position(glyphs[start]) == RA_TO_BECOME_REPH && glyphs[start].ligated) {
            val target = rephTarget(config, glyphs, start, end, base)
            val reph = glyphs.removeAt(start)
            glyphs.add(target, reph)
            if (start < base && base <= target) base--
        }

        // A left matra at the start of a word takes init.
        if (position(glyphs[start]) == PRE_M && (start == 0 || category(glyphs[start - 1]) == X)) glyphs[start].features += "init"
    }

    private fun rephTarget(config: Config, glyphs: List<GsubGlyph>, start: Int, end: Int, base: Int): Int {
        fun afterHalant(): Int? {
            var p = start + 1
            while (p < base && !isHalant(glyphs[p])) p++
            if (p < base && isHalant(glyphs[p])) {
                if (p + 1 < base && isJoiner(glyphs[p + 1])) p++
                return p
            }
            return null
        }
        if (config.rephPosition != AFTER_POST) {
            afterHalant()?.let { return it }
            if (config.rephPosition == AFTER_MAIN) {
                var p = base
                while (p + 1 < end && position(glyphs[p + 1]) <= AFTER_MAIN) p++
                if (p < end) return p
            }
            if (config.rephPosition == AFTER_SUB) {
                var p = base
                while (p + 1 < end && position(glyphs[p + 1]) !in intArrayOf(POST_C, AFTER_POST, SMVD)) p++
                if (p < end) return p
            }
        }
        afterHalant()?.let { return it }
        var p = end - 1
        while (p > start && position(glyphs[p]) == SMVD) p--
        if (isHalant(glyphs[p])) for (i in base + 1 until p) if (isOneOf(glyphs[i], M)) p--
        return p
    }
}
