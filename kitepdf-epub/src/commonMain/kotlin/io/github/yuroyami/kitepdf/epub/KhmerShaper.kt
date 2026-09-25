package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.GsubGlyph
import io.github.yuroyami.kitepdf.core.font.OpenTypeGsub

/**
 * The shaper of Khmer (#317), after the Khmer shaper of HarfBuzz 14.4:
 *
 * 1. [prepare] decomposes the split vowels into their pre-base part and the vowel itself.
 * 2. Each character gets a category, `rvrn`, `ltra` and `ltrm` apply, and the text splits
 *    into syllables. A broken syllable gets a dotted circle.
 * 3. Reordering moves a coeng and Ro, and a pre-base vowel, to the start of the syllable.
 * 4. `locl`, `ccmp`, `pref`, `blwf`, `abvf`, `pstf` and `cfar` apply together, inside each
 *    syllable, and then `pres`, `abvs`, `blws` and `psts`.
 */
internal object KhmerShaper {

    /* ─── Categories (HarfBuzz's K_Cat) ─────────────────────────────────── */

    private const val X = 0
    private const val C = 1
    private const val V = 2
    private const val H = 4
    private const val ZWNJ = 5
    private const val ZWJ = 6
    private const val PLACEHOLDER = 10
    private const val DOTTED_CIRCLE = 11
    private const val RA = 15
    private const val VABV = 20
    private const val VBLW = 21
    private const val VPRE = 22
    private const val VPST = 23
    private const val ROBATIC = 25
    private const val XGROUP = 26
    private const val YGROUP = 27

    /** True for the script tag of Khmer, which HarfBuzz always shapes with this shaper. */
    fun handles(script: String): Boolean = script == "khmr"

    /** The features that reach only the glyphs reordering chose for them. */
    private val POSITIONAL = setOf("pref", "blwf", "abvf", "pstf", "cfar")

    /** The features HarfBuzz applies inside each syllable. */
    private val PER_SYLLABLE = POSITIONAL + setOf("locl", "ccmp")

    /** The features of the shaper itself, which see the joiners. */
    private val OWN = POSITIONAL + setOf("pres", "abvs", "blws", "psts")

    /**
     * The characters of the word [cps] as HarfBuzz hands them to its Khmer shaper: every
     * character decomposes as far as the font has glyphs for the parts, a split vowel into its
     * pre-base part and itself, and the marks go into canonical order.
     */
    fun prepare(cps: IntArray, hasGlyph: (Int) -> Boolean): Normalizer.Result = Normalizer.normalize(
        cps, IntArray(cps.size) { it }, hasGlyph, shortCircuit = false, decompose = ::decomposition, compose = ::composition,
    )

    /** HarfBuzz's decompose_khmer: a split vowel keeps itself after its pre-base part, U+17C1. */
    private fun decomposition(cp: Int): IntArray? = when (cp) {
        0x17BE, 0x17BF, 0x17C0, 0x17C4, 0x17C5 -> intArrayOf(0x17C1, cp)
        else -> Normalizer.decomposition(cp)
    }

    /** HarfBuzz's compose_khmer: a split vowel does not compose again. */
    private fun composition(a: Int, b: Int): Int? = if (Normalizer.isMark(a)) null else Normalizer.composition(a, b)

    /**
     * Shapes the [glyphs] of one word, whose characters are [codePoints] from [prepare], in
     * place. [gidFor] gives the glyph of a character, for the dotted circle.
     */
    fun shape(gsub: OpenTypeGsub, script: String, glyphs: MutableList<GsubGlyph>, codePoints: IntArray, gidFor: (Int) -> Int, optionalLigatures: Boolean) {
        for ((i, g) in glyphs.withIndex()) g.shaperData = category(codePoints[i])
        gsub.substitute(glyphs, script, null, listOf(listOf("rvrn"), listOf("ltra", "ltrm")))
        Syllables.find(glyphs, ::category, SYLLABLES, NON_KHMER_CLUSTER)
        Syllables.insertDottedCircles(glyphs, gidFor(0x25CC), BROKEN_CLUSTER, null, DOTTED_CIRCLE, ::category)
        Syllables.forEach(glyphs) { start, end -> if (glyphs[start].syllable and 0xF != NON_KHMER_CLUSTER) reorder(glyphs, start, end) }
        gsub.substitute(
            glyphs, script, null, listOf(listOf("locl", "ccmp", "pref", "blwf", "abvf", "pstf", "cfar")),
            POSITIONAL, PER_SYLLABLE, OWN, OWN,
        )
        // HarfBuzz turns liga off and clig on for Khmer.
        val presentation = listOf("pres", "abvs", "blws", "psts", "rlig", "rclt", "calt") + if (optionalLigatures) listOf("clig") else emptyList()
        gsub.substitute(glyphs, script, null, listOf(presentation), emptySet(), emptySet(), OWN, OWN)
        Syllables.mergeClusters(glyphs)
    }

    private fun category(g: GsubGlyph): Int = g.shaperData and 0xFF

    /** The Khmer category of [cp], from HarfBuzz's table for Unicode 17. */
    private fun category(cp: Int): Int = when (cp) {
        in 0x1780..0x1799, in 0x179B..0x17A2 -> C
        0x179A -> RA
        in 0x17A3..0x17B3 -> V
        0x17B6, 0x17BF, 0x17C0, 0x17C4, 0x17C5 -> VPST
        in 0x17B7..0x17BA, 0x17BE -> VABV
        in 0x17BB..0x17BD -> VBLW
        in 0x17C1..0x17C3 -> VPRE
        0x17C6, 0x17CB, in 0x17CD..0x17D1 -> XGROUP
        0x17C7, 0x17C8, 0x17D3, 0x17DD -> YGROUP
        0x17C9, 0x17CA, 0x17CC -> ROBATIC
        0x17D2 -> H
        0x00A0, 0x17D9, in 0x17E0..0x17E9, in 0x2010..0x2015, 0x2022, in 0x25FB..0x25FE -> PLACEHOLDER
        0x200C -> ZWNJ
        0x200D -> ZWJ
        0x25CC -> DOTTED_CIRCLE
        else -> X
    }

    /* ─── Syllables (HarfBuzz's hb-ot-shaper-khmer-machine.rl) ────────────── */

    private const val CONSONANT_SYLLABLE = 0
    private const val BROKEN_CLUSTER = 1
    private const val NON_KHMER_CLUSTER = 2

    private val SYLLABLES: List<Pair<Syllables.Pattern, Int>> = with(Syllables) {
        val c = cat(C, RA, V)
        val joiner = cat(ZWJ, ZWNJ)
        val cn = seq(c, opt(seq(opt(joiner), cat(ROBATIC))))
        val xgroup = star(seq(star(joiner), cat(XGROUP)))
        val ygroup = star(cat(YGROUP))
        val matraGroup = seq(
            opt(cat(VPRE)), xgroup, opt(cat(VBLW)), xgroup, opt(seq(opt(joiner), cat(VABV))), xgroup, opt(cat(VPST)),
        )
        val syllableTail = seq(xgroup, matraGroup, xgroup, opt(seq(cat(H), c)), ygroup)
        val brokenCluster = seq(opt(cat(ROBATIC)), star(seq(cat(H), cn)), alt(cat(H), syllableTail))
        listOf(
            seq(alt(cn, cat(PLACEHOLDER), cat(DOTTED_CIRCLE)), brokenCluster) to CONSONANT_SYLLABLE,
            brokenCluster to BROKEN_CLUSTER,
        )
    }

    /* ─── Reordering (HarfBuzz's reorder_consonant_syllable) ────────────── */

    private fun reorder(glyphs: MutableList<GsubGlyph>, start: Int, end: Int) {
        for (i in start + 1 until end) glyphs[i].features += listOf("blwf", "abvf", "pstf")
        var coengs = 0
        var i = start + 1
        while (i < end) {
            if (category(glyphs[i]) == H && coengs <= 2 && i + 1 < end) {
                coengs++
                // A coeng and Ro take pref and move to the start; what follows them takes cfar.
                if (category(glyphs[i + 1]) == RA) {
                    glyphs[i].features += "pref"
                    glyphs[i + 1].features += "pref"
                    val coeng = glyphs.removeAt(i)
                    val ro = glyphs.removeAt(i)
                    glyphs.add(start, coeng)
                    glyphs.add(start + 1, ro)
                    for (j in i + 2 until end) glyphs[j].features += "cfar"
                    coengs = 2
                }
            } else if (category(glyphs[i]) == VPRE) {
                // A pre-base vowel moves to the start.
                glyphs.add(start, glyphs.removeAt(i))
            }
            i++
        }
    }
}
