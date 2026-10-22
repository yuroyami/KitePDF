package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.GsubGlyph
import io.github.yuroyami.kitepdf.core.font.OpenTypeGsub

/**
 * The shaper of Myanmar (#317), after the Myanmar shaper of HarfBuzz 14.4:
 *
 * 1. Each character gets a category, `rvrn`, `ltra` and `ltrm` apply, the text splits into
 *    syllables, and `locl` and `ccmp` apply inside each syllable.
 * 2. Reordering gives a broken syllable a dotted circle, finds the base consonant, and sorts
 *    each syllable by position: a medial Ra and a pre-base vowel go before the base, and a
 *    kinzi after it.
 * 3. `rphf`, `pref`, `blwf` and `pstf` apply one at a time, and then `pres`, `abvs`, `blws`
 *    and `psts`.
 */
internal object MyanmarShaper {

    /* ─── Categories and positions (HarfBuzz's M_Cat and ot_position_t) ─────── */

    private const val X = 0
    private const val C = 1
    private const val IV = 2
    private const val DB = 3
    private const val H = 4
    private const val ZWNJ = 5
    private const val ZWJ = 6
    private const val SM = 8
    private const val A = 9
    private const val GB = 10
    private const val DOTTED_CIRCLE = 11
    private const val RA = 15
    private const val CS = 18
    private const val SMPST = 57
    private const val VABV = 20
    private const val VBLW = 21
    private const val VPRE = 22
    private const val VPST = 23
    private const val AS = 32
    private const val MH = 35
    private const val MR = 36
    private const val MW = 37
    private const val MY = 38
    private const val PT = 39
    private const val VS = 40
    private const val ML = 41

    private const val PRE_M = 2
    private const val PRE_C = 3
    private const val BASE_C = 4
    private const val AFTER_MAIN = 5
    private const val BEFORE_SUB = 7
    private const val BELOW_C = 8
    private const val AFTER_SUB = 9

    /**
     * True when this shaper shapes [script] in a font with [gsub]. As in HarfBuzz, a font made
     * for the `mymr` tag from before the Myanmar shaping spec, and a font that falls back to its
     * `DFLT` or `latn` script, take the default features with no reordering.
     */
    fun handles(script: String, gsub: OpenTypeGsub): Boolean {
        if (script != "mym2" && script != "mymr") return false
        val chosen = listOf("mym2", "mymr", "DFLT", "dflt", "latn").firstOrNull { gsub.hasScript(it) }
        return chosen != "mymr" && chosen != "DFLT" && chosen != "latn"
    }

    /** The features HarfBuzz applies inside each syllable. */
    private val PER_SYLLABLE = setOf("locl", "ccmp", "rphf", "pref", "blwf", "pstf")

    /** The features of the shaper itself, which see a ZWJ. */
    private val MANUAL_ZWJ = setOf("rphf", "pref", "blwf", "pstf", "pres", "abvs", "blws", "psts")

    /**
     * The characters of the word [cps] as HarfBuzz hands them to its Myanmar shaper: every
     * character decomposes as far as the font has glyphs for the parts, the marks go into
     * canonical order, and a mark composes with the character before it when the font has it.
     */
    fun prepare(cps: IntArray, hasGlyph: (Int) -> Boolean): Normalizer.Result =
        Normalizer.normalize(cps, IntArray(cps.size) { it }, hasGlyph, shortCircuit = false)

    /**
     * Shapes the [glyphs] of one word, whose characters are [codePoints] from [prepare], in
     * place. [gidFor] gives the glyph of a character, for the dotted circle.
     */
    fun shape(gsub: OpenTypeGsub, script: String, glyphs: MutableList<GsubGlyph>, codePoints: IntArray, gidFor: (Int) -> Int, optionalLigatures: Boolean) {
        for ((i, g) in glyphs.withIndex()) g.shaperData = category(codePoints[i])
        gsub.substitute(glyphs, script, null, listOf(listOf("rvrn"), listOf("ltra", "ltrm")))
        Syllables.find(glyphs, ::category, SYLLABLES, NON_MYANMAR_CLUSTER)
        gsub.substitute(glyphs, script, null, listOf(listOf("locl", "ccmp")), perSyllable = PER_SYLLABLE)
        Syllables.insertDottedCircles(glyphs, gidFor(0x25CC), BROKEN_CLUSTER, null, DOTTED_CIRCLE, ::category)
        Syllables.forEach(glyphs) { start, end -> if (glyphs[start].syllable and 0xF != NON_MYANMAR_CLUSTER) reorder(glyphs, start, end) }
        for (feature in listOf("rphf", "pref", "blwf", "pstf")) {
            gsub.substitute(glyphs, script, null, listOf(listOf(feature)), perSyllable = PER_SYLLABLE, manualZwj = MANUAL_ZWJ)
        }
        val presentation = listOf("pres", "abvs", "blws", "psts", "rlig", "rclt", "calt") +
            if (optionalLigatures) listOf("liga", "clig") else emptyList()
        gsub.substitute(glyphs, script, null, listOf(presentation), manualZwj = MANUAL_ZWJ)
        Syllables.mergeClusters(glyphs)
    }

    private fun category(g: GsubGlyph): Int = g.shaperData and 0xFF
    private fun position(g: GsubGlyph): Int = (g.shaperData ushr 8) and 0xFF
    private fun setPosition(g: GsubGlyph, pos: Int) { g.shaperData = (g.shaperData and 0xFF00.inv()) or (pos shl 8) }

    /** The Myanmar category of [cp], from HarfBuzz's table for Unicode 17. */
    private fun category(cp: Int): Int = when (cp) {
        0x00A0, in 0x1040..0x104B, in 0x1090..0x1099, in 0x2010..0x2015, 0x2022, in 0x25FB..0x25FE,
        in 0xA9F0..0xA9F9, in 0xAA74..0xAA76 -> GB
        in 0x1000..0x1003, in 0x1005..0x101A, in 0x101C..0x1020, 0x103F, 0x104E, 0x1050, 0x1051, in 0x105B..0x105D, 0x1061,
        0x1065, 0x1066, in 0x106E..0x1070, in 0x1075..0x1081, 0x108E, in 0xA9E0..0xA9E4, in 0xA9E7..0xA9EF,
        in 0xA9FA..0xA9FE, in 0xAA60..0xAA6F, in 0xAA71..0xAA73, 0xAA7A, 0xAA7E, 0xAA7F -> C
        0x1004, 0x101B, 0x105A -> RA
        in 0x1021..0x102A, in 0x1052..0x1055 -> IV
        0x102B, 0x102C, 0x1056, 0x1057, 0x1062, 0x1067, 0x1068, 0x1083 -> VPST
        0x102D, 0x102E, in 0x1033..0x1035, in 0x1071..0x1074, 0x1085, 0x1086, 0x109D, 0xA9E5 -> VABV
        0x102F, 0x1030, 0x1058, 0x1059 -> VBLW
        0x1031, 0x1084 -> VPRE
        0x1032, 0x1036 -> A
        0x1037, 0xAA7C, 0xAA7D -> DB
        0x1038, in 0x1087..0x108D, 0x108F, in 0x109A..0x109C -> SM
        0x1039 -> H
        0x103A -> AS
        0x103B, 0x105E, 0x105F -> MY
        0x103C -> MR
        0x103D, 0x1082 -> MW
        0x103E -> MH
        0x1060 -> ML
        0x1063, 0x1064, in 0x1069..0x106D, 0xAA7B -> PT
        0x200C -> ZWNJ
        0x200D -> ZWJ
        0x25CC -> DOTTED_CIRCLE
        in 0xFE00..0xFE0F -> VS
        else -> X
    }

    /* ─── Syllables (HarfBuzz's hb-ot-shaper-myanmar-machine.rl) ──────────── */

    private const val CONSONANT_SYLLABLE = 0
    private const val BROKEN_CLUSTER = 1
    private const val NON_MYANMAR_CLUSTER = 2

    private val SYLLABLES: List<Pair<Syllables.Pattern, Int>> = with(Syllables) {
        val j = cat(ZWJ, ZWNJ)
        val kinzi = seq(cat(RA), cat(AS), cat(H))
        val sm = cat(SM, SMPST)
        val c = cat(C, RA)
        val dbAs = opt(seq(cat(DB), opt(cat(AS))))
        val medialGroup = seq(
            opt(cat(MY)), opt(cat(AS)), opt(cat(MR)),
            opt(seq(alt(seq(cat(MW), opt(cat(MH)), opt(cat(ML))), seq(cat(MH), opt(cat(ML))), cat(ML)), opt(cat(AS)))),
        )
        val mainVowelGroup = seq(star(seq(cat(VPRE), opt(cat(VS)))), star(cat(VABV)), star(cat(VBLW)), star(cat(A)), dbAs)
        val postVowelGroup = seq(cat(VPST), opt(cat(MH)), opt(cat(ML)), star(cat(AS)), star(cat(VABV)), star(cat(A)), dbAs)
        val toneGroup = alt(sm, seq(cat(PT), star(cat(A)), opt(cat(DB)), opt(cat(AS))))
        val complexSyllableTail = seq(star(cat(AS)), medialGroup, mainVowelGroup, star(postVowelGroup), star(toneGroup), opt(j))
        val syllableTail = seq(star(seq(cat(H), alt(c, cat(IV)), opt(cat(VS)))), alt(cat(H), complexSyllableTail))
        listOf(
            seq(opt(alt(kinzi, cat(CS))), alt(c, cat(IV), cat(GB), cat(DOTTED_CIRCLE)), opt(cat(VS)), syllableTail) to CONSONANT_SYLLABLE,
            alt(j, cat(SMPST)) to NON_MYANMAR_CLUSTER,
            seq(opt(kinzi), opt(cat(VS)), syllableTail) to BROKEN_CLUSTER,
        )
    }

    /* ─── Reordering (HarfBuzz's initial_reordering_consonant_syllable) ─────── */

    private val CONSONANTS = Syllables.flags(C, CS, RA, IV, GB, DOTTED_CIRCLE)

    /** A glyph a ligature produced matches no category, as HarfBuzz's is_one_of_myanmar says. */
    private fun isConsonant(g: GsubGlyph): Boolean = !g.ligated && (CONSONANTS ushr category(g)) and 1L != 0L

    private fun reorder(glyphs: MutableList<GsubGlyph>, start: Int, end: Int) {
        // The base is the first consonant after a kinzi, Ra, asat and virama.
        var base = end
        var limit = start
        val hasReph = start + 3 <= end && category(glyphs[start]) == RA && category(glyphs[start + 1]) == AS &&
            category(glyphs[start + 2]) == H
        if (hasReph) { limit += 3; base = start }
        if (!hasReph) base = limit
        for (i in limit until end) if (isConsonant(glyphs[i])) { base = i; break }

        var i = start
        while (i < start + (if (hasReph) 3 else 0)) setPosition(glyphs[i++], AFTER_MAIN)
        while (i < base) setPosition(glyphs[i++], PRE_C)
        if (i < end) setPosition(glyphs[i++], BASE_C)
        var pos = AFTER_MAIN
        while (i < end) {
            val g = glyphs[i]
            val cat = category(g)
            when {
                cat == MR -> setPosition(g, PRE_C)
                cat == VPRE -> setPosition(g, PRE_M)
                cat == VS -> setPosition(g, position(glyphs[i - 1]))
                pos == AFTER_MAIN && cat == VBLW -> { pos = BELOW_C; setPosition(g, pos) }
                pos == BELOW_C && cat == A -> setPosition(g, BEFORE_SUB)
                pos == BELOW_C && cat == VBLW -> setPosition(g, pos)
                pos == BELOW_C -> { pos = AFTER_SUB; setPosition(g, pos) }
                else -> setPosition(g, pos)
            }
            i++
        }

        val sorted = glyphs.subList(start, end).sortedBy { position(it) }
        for (k in sorted.indices) glyphs[start + k] = sorted[k]
        // Two left vowels keep their written order, each with what follows it.
        var firstLeft = end
        var lastLeft = end
        for (k in start until end) {
            if (position(glyphs[k]) == PRE_M) { if (firstLeft == end) firstLeft = k; lastLeft = k }
        }
        if (firstLeft < lastLeft) {
            glyphs.subList(firstLeft, lastLeft + 1).reverse()
            var k = firstLeft
            for (j in firstLeft..lastLeft) if (category(glyphs[j]) == VPRE) { glyphs.subList(k, j + 1).reverse(); k = j + 1 }
        }
    }
}
