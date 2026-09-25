package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.GsubGlyph
import io.github.yuroyami.kitepdf.core.font.OpenTypeGsub

/**
 * The shaper of the Brahmic scripts of India (#211), after the Indic shaper of HarfBuzz 14.4.
 * GSUB alone cannot shape these scripts, because a syllable must be reordered around the
 * features:
 *
 * 1. [prepare] puts a dotted circle into a vowel sequence that would draw like another vowel,
 *    and decomposes and composes the characters as HarfBuzz normalizes them.
 * 2. Each character gets a category and a position, `rvrn`, `ltra` and `ltrm` apply, the text
 *    splits into syllables, and `locl` and `ccmp` apply.
 * 3. Initial reordering finds the base consonant of each syllable, marks a leading Ra and
 *    virama as reph, moves a pre-base matra to the front, and chooses the features each
 *    glyph takes: half forms before the base, below-base and post-base forms after it.
 * 4. The basic features apply one at a time: nukt, akhn, rphf, rkrf, pref, blwf, abvf, half,
 *    pstf, vatu and cjct.
 * 5. Final reordering moves the pre-base matra after the half forms, the reph to its place,
 *    and a pre-base-reordering consonant before the base.
 * 6. The presentation features apply: init, pres, abvs, blws, psts and haln.
 *
 * Devanagari, Bengali, Gurmukhi, Gujarati, Oriya, Tamil, Telugu, Kannada and Malayalam are
 * covered, under the new script tags (`dev2`) and the old ones (`deva`). A syllable keeps one
 * cluster, so its text stays whole.
 */
internal object IndicShaper {

    /* ─── Categories and positions (HarfBuzz's I_Cat and ot_position_t) ─────── */

    private const val X = 0
    private const val C = 1
    private const val V = 2
    private const val N = 3
    private const val H = 4
    private const val ZWNJ = 5
    private const val ZWJ = 6
    private const val M = 7
    private const val SM = 8
    private const val A = 9
    private const val PLACEHOLDER = 10
    private const val DOTTED_CIRCLE = 11
    private const val RS = 12
    private const val MPST = 13
    private const val REPHA = 14
    private const val RA = 15
    private const val CM = 16
    private const val SYMBOL = 17
    private const val CS = 18
    private const val SMPST = 57

    private const val START = 0
    private const val RA_TO_BECOME_REPH = 1
    private const val PRE_M = 2
    private const val PRE_C = 3
    private const val BASE_C = 4
    private const val AFTER_MAIN = 5
    private const val ABOVE_C = 6
    private const val BEFORE_SUB = 7
    private const val BELOW_C = 8
    private const val AFTER_SUB = 9
    private const val BEFORE_POST = 10
    private const val POST_C = 11
    private const val AFTER_POST = 12
    private const val SMVD = 13
    private const val END = 14

    /** A letter, mark or format character, after which a pre-base matra takes no `init`. */
    private const val WORD_CHARACTER = 1 shl 16

    /** How a script writes its reph: Ra and virama, Ra, virama and ZWJ, or a sign of its own. */
    private enum class RephMode { IMPLICIT, EXPLICIT, LOG_REPHA }

    /**
     * How one script places its reph, after HarfBuzz's indic_configs. [block] holds the
     * characters of the script, and [virama] is its virama.
     */
    private enum class Config(
        val block: IntRange,
        val virama: Int,
        val rephPosition: Int,
        val rephMode: RephMode = RephMode.IMPLICIT,
        /** Telugu and Kannada give a consonant before the base no below-base form. */
        val blwfPostOnly: Boolean = false,
    ) {
        DEVANAGARI(0x0900..0x097F, 0x094D, BEFORE_POST),
        BENGALI(0x0980..0x09FF, 0x09CD, AFTER_SUB),
        GURMUKHI(0x0A00..0x0A7F, 0x0A4D, BEFORE_SUB),
        GUJARATI(0x0A80..0x0AFF, 0x0ACD, BEFORE_POST),
        ORIYA(0x0B00..0x0B7F, 0x0B4D, AFTER_MAIN),
        TAMIL(0x0B80..0x0BFF, 0x0BCD, AFTER_POST),
        TELUGU(0x0C00..0x0C7F, 0x0C4D, AFTER_POST, RephMode.EXPLICIT, blwfPostOnly = true),
        KANNADA(0x0C80..0x0CFF, 0x0CCD, AFTER_POST, blwfPostOnly = true),
        MALAYALAM(0x0D00..0x0D7F, 0x0D4D, AFTER_MAIN, RephMode.LOG_REPHA);

        /** Tamil and Malayalam have no half forms, so a pre-base matra or consonant goes right before the base. */
        val noHalfForms: Boolean get() = this == TAMIL || this == MALAYALAM
    }

    private fun config(script: String): Config? = when (script) {
        "dev2", "deva" -> Config.DEVANAGARI
        "bng2", "beng" -> Config.BENGALI
        "gur2", "guru" -> Config.GURMUKHI
        "gjr2", "gujr" -> Config.GUJARATI
        "ory2", "orya" -> Config.ORIYA
        "tml2", "taml" -> Config.TAMIL
        "tel2", "telu" -> Config.TELUGU
        "knd2", "knda" -> Config.KANNADA
        "mlm2", "mlym" -> Config.MALAYALAM
        else -> null
    }

    /**
     * True when this shaper shapes [script] in a font with [gsub]. As in HarfBuzz, a font
     * without a tag for the script that falls back to its `DFLT` or `latn` script takes the
     * default features, with no reordering.
     */
    fun handles(script: String, gsub: OpenTypeGsub): Boolean {
        if (config(script) == null) return false
        if (gsub.hasScript(script)) return true
        val fallback = listOf("DFLT", "dflt", "latn").firstOrNull { gsub.hasScript(it) }
        return fallback != "DFLT" && fallback != "latn"
    }

    /** The basic features, each applied on its own, in this order. */
    private val BASIC = listOf("nukt", "akhn", "rphf", "rkrf", "pref", "blwf", "abvf", "half", "pstf", "vatu", "cjct")

    /** The features that reach only the glyphs reordering chose for them. */
    private val POSITIONAL = setOf("rphf", "pref", "blwf", "abvf", "half", "pstf", "init")

    /** The features of the shaper itself, which HarfBuzz constrains to a syllable and lets see the joiners. */
    private val OWN = BASIC.toSet() + setOf("init", "pres", "abvs", "blws", "psts", "haln")

    /** The features HarfBuzz constrains to a syllable. */
    private val PER_SYLLABLE = OWN + setOf("locl", "ccmp")

    /* ─── Preparing the characters (HarfBuzz's preprocess_text and normalizer) ─── */

    /**
     * The characters of the word [cps] in [script] as HarfBuzz hands them to its Indic shaper.
     * A dotted circle goes before the last character of a vowel sequence that would draw like
     * another vowel, when the font has one. Then [Normalizer] decomposes every character as far
     * as the font has glyphs for the parts, puts the marks in canonical order, and composes a
     * mark with the character before it when the font has a glyph for the result. A nukta form
     * that Unicode keeps decomposed stays decomposed, and a split matra does not compose again.
     */
    fun prepare(script: String, cps: IntArray, hasGlyph: (Int) -> Boolean): Normalizer.Result {
        val config = config(script)
        val codes = ArrayList<Int>(cps.size + 2)
        val sources = ArrayList<Int>(cps.size + 2)
        val circle = config != null && hasGlyph(0x25CC)
        var i = 0
        while (i < cps.size) {
            val rest = if (circle && cps[i] in config.block) {
                CONFUSABLE_VOWELS[cps[i]]?.firstOrNull { r -> i + r.size < cps.size && r.indices.all { cps[i + 1 + it] == r[it] } }
            } else null
            if (rest == null) { codes += cps[i]; sources += i; i++; continue }
            for (k in 0 until rest.size) { codes += cps[i + k]; sources += i + k }
            codes += 0x25CC
            sources += i + rest.size
            codes += cps[i + rest.size]
            sources += i + rest.size
            i += rest.size + 1
        }
        return Normalizer.normalize(
            codes.toIntArray(), sources.toIntArray(), hasGlyph, shortCircuit = false,
            decompose = ::decomposition, compose = ::composition,
        )
    }

    /**
     * Vowel sequences that would draw like another vowel, from the USE script development spec
     * as HarfBuzz reads it: each first character, with the rest of each sequence it starts. A
     * dotted circle goes before the last character of the sequence.
     */
    private val CONFUSABLE_VOWELS: Map<Int, List<IntArray>> = mapOf(
        0x0905 to lasts(0x093A, 0x093B, 0x093E, 0x0945, 0x0946, 0x0949, 0x094A, 0x094B, 0x094C, 0x094F, 0x0956, 0x0957),
        0x0906 to lasts(0x093A, 0x0945, 0x0946, 0x0947, 0x0948),
        0x0909 to lasts(0x0941),
        0x090F to lasts(0x0945, 0x0946, 0x0947),
        0x0930 to listOf(intArrayOf(0x094D, 0x0907)),
        0x0985 to lasts(0x09BE),
        0x098B to lasts(0x09C3),
        0x098C to lasts(0x09E2),
        0x0A05 to lasts(0x0A3E, 0x0A48, 0x0A4C),
        0x0A72 to lasts(0x0A3F, 0x0A40, 0x0A47),
        0x0A73 to lasts(0x0A41, 0x0A42, 0x0A4B),
        0x0A85 to lasts(0x0ABE, 0x0AC5, 0x0AC7, 0x0AC8, 0x0AC9, 0x0ACB, 0x0ACC),
        0x0AC5 to lasts(0x0ABE),
        0x0B05 to lasts(0x0B3E),
        0x0B0F to lasts(0x0B57),
        0x0B13 to lasts(0x0B57),
        0x0B85 to lasts(0x0BC2),
        0x0C12 to lasts(0x0C4C, 0x0C55),
        0x0C3F to lasts(0x0C55),
        0x0C46 to lasts(0x0C55),
        0x0C4A to lasts(0x0C55),
        0x0C89 to lasts(0x0CBE),
        0x0C8B to lasts(0x0CBE),
        0x0C92 to lasts(0x0CCC),
        0x0D07 to lasts(0x0D57),
        0x0D09 to lasts(0x0D57),
        0x0D0E to lasts(0x0D46),
        0x0D12 to lasts(0x0D3E, 0x0D57),
    )

    private fun lasts(vararg last: Int): List<IntArray> = last.map { intArrayOf(it) }

    /** HarfBuzz's decompose_indic: Devanagari Rra, Bengali Rra and Rha, and Tamil Au stay whole. */
    private fun decomposition(cp: Int): IntArray? =
        if (cp == 0x0931 || cp == 0x09DC || cp == 0x09DD || cp == 0x0B94) null else Normalizer.decomposition(cp)

    /** HarfBuzz's compose_indic: a split matra does not compose again, and Bengali Yya does. */
    private fun composition(a: Int, b: Int): Int? = when {
        Normalizer.isMark(a) -> null
        a == 0x09AF && b == 0x09BC -> 0x09DF
        else -> Normalizer.composition(a, b)
    }

    /** HarfBuzz's general categories from format to non-spacing mark: the letters, the marks and the format characters. */
    private fun isWordCharacter(cp: Int): Boolean = when (cp.toChar().category) {
        CharCategory.FORMAT, CharCategory.UNASSIGNED, CharCategory.PRIVATE_USE, CharCategory.SURROGATE,
        CharCategory.LOWERCASE_LETTER, CharCategory.MODIFIER_LETTER, CharCategory.OTHER_LETTER,
        CharCategory.TITLECASE_LETTER, CharCategory.UPPERCASE_LETTER, CharCategory.COMBINING_SPACING_MARK,
        CharCategory.ENCLOSING_MARK, CharCategory.NON_SPACING_MARK -> true
        else -> false
    }

    /* ─── Shaping ──────────────────────────────────────────────────────────── */

    /**
     * Shapes the [glyphs] of one word in [script], whose characters are [codePoints] from
     * [prepare], in place. [gidFor] gives the glyph of a character, for the virama and the
     * dotted circle.
     */
    fun shape(gsub: OpenTypeGsub, script: String, glyphs: MutableList<GsubGlyph>, codePoints: IntArray, gidFor: (Int) -> Int, optionalLigatures: Boolean) {
        val config = config(script) ?: return
        // A font without the new tag of the script shapes by the old specification.
        val oldSpec = !(script.endsWith('2') && gsub.hasScript(script))
        val zeroContext = !oldSpec && config != Config.MALAYALAM
        for ((i, g) in glyphs.withIndex()) {
            g.shaperData = properties(codePoints[i]) or (if (isWordCharacter(codePoints[i])) WORD_CHARACTER else 0)
        }
        gsub.substitute(glyphs, script, null, listOf(listOf("rvrn"), listOf("ltra", "ltrm")))
        findSyllables(glyphs)
        gsub.substitute(glyphs, script, null, listOf(listOf("locl", "ccmp")), perSyllable = PER_SYLLABLE)

        val virama = gidFor(config.virama)
        updateConsonantPositions(gsub, script, glyphs, virama, zeroContext)
        insertDottedCircles(glyphs, gidFor(0x25CC))
        forEachSyllable(glyphs) { start, end -> initialReordering(config, oldSpec, zeroContext, gsub, script, glyphs, start, end) }
        for (feature in BASIC) gsub.substitute(glyphs, script, null, listOf(listOf(feature)), POSITIONAL, PER_SYLLABLE, OWN, OWN)
        forEachSyllable(glyphs) { start, end -> finalReordering(config, glyphs, start, end, virama) }
        // HarfBuzz turns liga off for these scripts.
        val presentation = listOf("init", "pres", "abvs", "blws", "psts", "haln", "rlig", "rclt", "calt") +
            if (optionalLigatures) listOf("clig") else emptyList()
        gsub.substitute(glyphs, script, null, listOf(presentation), POSITIONAL, PER_SYLLABLE, OWN, OWN)

        // A reordered syllable is one cluster, so its first glyph carries the whole text of it.
        forEachSyllable(glyphs) { start, end ->
            val cluster = (start until end).minOf { glyphs[it].cluster }
            for (i in start until end) glyphs[i].cluster = cluster
        }
    }

    /* ─── Properties ───────────────────────────────────────────────────────── */

    private fun category(g: GsubGlyph): Int = g.shaperData and 0xFF
    private fun position(g: GsubGlyph): Int = (g.shaperData ushr 8) and 0xFF
    private fun setCategory(g: GsubGlyph, cat: Int) { g.shaperData = (g.shaperData and 0xFF.inv()) or cat }
    private fun setPosition(g: GsubGlyph, pos: Int) { g.shaperData = (g.shaperData and 0xFF00.inv()) or (pos shl 8) }

    private fun flags(vararg categories: Int): Long = categories.fold(0L) { m, c -> m or (1L shl c) }
    private val CONSONANTS = flags(C, CS, RA, CM, V, PLACEHOLDER, DOTTED_CIRCLE)
    private val JOINERS = flags(ZWJ, ZWNJ)
    private val MATRAS = flags(M, MPST)
    private val HALANT = flags(H)

    /** True when the category of [g] is in [set], whatever substitutions did to the glyph. */
    private fun inSet(g: GsubGlyph, set: Long): Boolean = (set ushr category(g)) and 1L != 0L

    /** A glyph a ligature produced matches no category, as HarfBuzz's is_one_of says. */
    private fun isOneOf(g: GsubGlyph, set: Long): Boolean = !g.ligated && inSet(g, set)
    private fun isConsonant(g: GsubGlyph): Boolean = isOneOf(g, CONSONANTS)
    private fun isJoiner(g: GsubGlyph): Boolean = isOneOf(g, JOINERS)
    private fun isHalant(g: GsubGlyph): Boolean = isOneOf(g, HALANT)

    private fun p(cat: Int, pos: Int): Int = cat or (pos shl 8)

    /**
     * The category of [cp] in the low byte and its position above it: HarfBuzz's table, which
     * its gen-indic-table.py builds from IndicSyllabicCategory.txt and IndicPositionalCategory.txt
     * of Unicode 17 with its own overrides.
     */
    private fun properties(cp: Int): Int = when (cp) {
        // Latin, punctuation and symbols
        0x002D, in 0x0030..0x0039, 0x00A0, 0x00D7, in 0x2010..0x2015, 0x2022, in 0x25FB..0x25FE -> p(PLACEHOLDER, BASE_C)
        0x00B2, 0x00B3, 0x2074, in 0x2082..0x2084 -> p(SMPST, SMVD)
        0x200C -> p(ZWNJ, END)
        0x200D -> p(ZWJ, END)
        0x25CC -> p(DOTTED_CIRCLE, BASE_C)
        // Devanagari
        in 0x0900..0x0903, 0x0953, 0x0954 -> p(SM, SMVD)
        in 0x0904..0x0914, 0x0960, 0x0961, in 0x0972..0x0977 -> p(V, BASE_C)
        in 0x0915..0x092F, in 0x0931..0x0939, in 0x0958..0x095F, in 0x0978..0x097F -> p(C, BASE_C)
        0x0930 -> p(RA, BASE_C)
        0x093A, 0x093B, 0x093E, in 0x0940..0x094C, 0x094F, in 0x0955..0x0957, 0x0962, 0x0963 -> p(M, AFTER_SUB)
        0x093C -> p(N, END)
        0x093D -> p(SYMBOL, SMVD)
        0x093F, 0x094E -> p(M, PRE_M)
        0x094D -> p(H, BELOW_C)
        0x0951, 0x0952 -> p(A, SMVD)
        in 0x0966..0x096F -> p(PLACEHOLDER, BASE_C)
        // Bengali
        0x0980, in 0x09E6..0x09EF, 0x09FC -> p(PLACEHOLDER, BASE_C)
        in 0x0981..0x0983, 0x09FE -> p(SM, SMVD)
        in 0x0985..0x098C, 0x098F, 0x0990, 0x0993, 0x0994, 0x09E0, 0x09E1 -> p(V, BASE_C)
        in 0x0995..0x09A8, in 0x09AA..0x09AF, 0x09B2, in 0x09B6..0x09B9, 0x09CE, 0x09DC, 0x09DD, 0x09DF, 0x09F1 -> p(C, BASE_C)
        0x09B0, 0x09F0 -> p(RA, BASE_C)
        0x09BC -> p(N, END)
        0x09BD -> p(SYMBOL, SMVD)
        0x09BE, 0x09C0, 0x09CB, 0x09CC, 0x09D7 -> p(M, AFTER_POST)
        0x09BF, 0x09C7, 0x09C8 -> p(M, PRE_M)
        in 0x09C1..0x09C4, 0x09E2, 0x09E3 -> p(M, AFTER_SUB)
        0x09CD -> p(H, BELOW_C)
        // Gurmukhi
        in 0x0A01..0x0A03, 0x0A70, 0x0A71 -> p(SM, SMVD)
        in 0x0A05..0x0A0A, 0x0A0F, 0x0A10, 0x0A13, 0x0A14 -> p(V, BASE_C)
        in 0x0A15..0x0A28, in 0x0A2A..0x0A2F, 0x0A32, 0x0A33, 0x0A35, 0x0A36, 0x0A38, 0x0A39, in 0x0A59..0x0A5C,
            0x0A5E, 0x0A72, 0x0A73 -> p(C, BASE_C)
        0x0A30 -> p(RA, BASE_C)
        0x0A3C -> p(N, END)
        0x0A3E, 0x0A41, 0x0A42, 0x0A47, 0x0A48, 0x0A4B, 0x0A4C -> p(M, AFTER_POST)
        0x0A3F -> p(M, PRE_M)
        0x0A40 -> p(MPST, AFTER_POST)
        0x0A4D -> p(H, BELOW_C)
        0x0A51 -> p(M, BELOW_C)
        in 0x0A66..0x0A6F -> p(PLACEHOLDER, BASE_C)
        0x0A75 -> p(CM, BASE_C)
        // Gujarati
        in 0x0A81..0x0A83 -> p(SM, SMVD)
        in 0x0A85..0x0A8D, in 0x0A8F..0x0A91, 0x0A93, 0x0A94, 0x0AE0, 0x0AE1 -> p(V, BASE_C)
        in 0x0A95..0x0AA8, in 0x0AAA..0x0AAF, 0x0AB2, 0x0AB3, in 0x0AB5..0x0AB9, 0x0AF9 -> p(C, BASE_C)
        0x0AB0 -> p(RA, BASE_C)
        0x0ABC, 0x0AFB, in 0x0AFD..0x0AFF -> p(N, END)
        0x0ABD -> p(SYMBOL, SMVD)
        0x0ABE, in 0x0AC0..0x0AC4, 0x0AC9, 0x0ACB, 0x0ACC, 0x0AE2, 0x0AE3 -> p(M, AFTER_POST)
        0x0ABF -> p(M, PRE_M)
        0x0AC5, 0x0AC7, 0x0AC8 -> p(M, AFTER_SUB)
        0x0ACD -> p(H, BELOW_C)
        in 0x0AE6..0x0AEF -> p(PLACEHOLDER, BASE_C)
        0x0AFA, 0x0AFC -> p(A, SMVD)
        // Oriya
        0x0B01 -> p(SM, BEFORE_SUB)
        0x0B02, 0x0B03 -> p(SM, SMVD)
        in 0x0B05..0x0B0C, 0x0B0F, 0x0B10, 0x0B13, 0x0B14, 0x0B60, 0x0B61 -> p(V, BASE_C)
        in 0x0B15..0x0B28, in 0x0B2A..0x0B2F, 0x0B32, 0x0B33, in 0x0B35..0x0B39, 0x0B5C, 0x0B5D, 0x0B5F, 0x0B71 -> p(C, BASE_C)
        0x0B30 -> p(RA, BASE_C)
        0x0B3C, 0x0B55 -> p(N, END)
        0x0B3D -> p(SYMBOL, SMVD)
        0x0B3E, 0x0B40, 0x0B4B, 0x0B4C, 0x0B57 -> p(M, AFTER_POST)
        0x0B3F, 0x0B48, 0x0B56 -> p(M, AFTER_MAIN)
        in 0x0B41..0x0B44, 0x0B62, 0x0B63 -> p(M, AFTER_SUB)
        0x0B47 -> p(M, PRE_M)
        0x0B4D -> p(H, BELOW_C)
        in 0x0B66..0x0B6F -> p(PLACEHOLDER, BASE_C)
        // Tamil
        0x0B82 -> p(SM, SMVD)
        0x0B83 -> p(X, END)
        in 0x0B85..0x0B8A, in 0x0B8E..0x0B90, in 0x0B92..0x0B94 -> p(V, BASE_C)
        0x0B95, 0x0B99, 0x0B9A, 0x0B9C, 0x0B9E, 0x0B9F, 0x0BA3, 0x0BA4, in 0x0BA8..0x0BAA, 0x0BAE, 0x0BAF,
            in 0x0BB1..0x0BB9 -> p(C, BASE_C)
        0x0BB0 -> p(RA, BASE_C)
        0x0BBE, 0x0BBF, 0x0BC1, 0x0BC2, in 0x0BCA..0x0BCC, 0x0BD7 -> p(M, AFTER_POST)
        0x0BC0 -> p(M, AFTER_SUB)
        in 0x0BC6..0x0BC8 -> p(M, PRE_M)
        0x0BCD -> p(H, ABOVE_C)
        in 0x0BE6..0x0BEF -> p(PLACEHOLDER, BASE_C)
        // Telugu
        in 0x0C00..0x0C04 -> p(SM, SMVD)
        in 0x0C05..0x0C0C, in 0x0C0E..0x0C10, in 0x0C12..0x0C14, 0x0C60, 0x0C61 -> p(V, BASE_C)
        in 0x0C15..0x0C28, in 0x0C2A..0x0C2F, in 0x0C31..0x0C39, in 0x0C58..0x0C5A, 0x0C5D -> p(C, BASE_C)
        0x0C30 -> p(RA, BASE_C)
        0x0C3C -> p(N, END)
        0x0C3D -> p(SYMBOL, SMVD)
        in 0x0C3E..0x0C42, in 0x0C46..0x0C48, in 0x0C4A..0x0C4C, 0x0C55, 0x0C56, 0x0C62, 0x0C63 -> p(M, BEFORE_SUB)
        0x0C43, 0x0C44 -> p(M, AFTER_SUB)
        0x0C4D -> p(H, ABOVE_C)
        in 0x0C66..0x0C6F -> p(PLACEHOLDER, BASE_C)
        // Kannada
        0x0C80, in 0x0CE6..0x0CEF -> p(PLACEHOLDER, BASE_C)
        in 0x0C81..0x0C83, 0x0CF3 -> p(SM, SMVD)
        in 0x0C85..0x0C8C, in 0x0C8E..0x0C90, in 0x0C92..0x0C94, 0x0CE0, 0x0CE1 -> p(V, BASE_C)
        in 0x0C95..0x0CA8, in 0x0CAA..0x0CAF, in 0x0CB1..0x0CB3, in 0x0CB5..0x0CB9, 0x0CDD, 0x0CDE -> p(C, BASE_C)
        0x0CB0 -> p(RA, BASE_C)
        0x0CBC -> p(N, END)
        0x0CBD -> p(SYMBOL, SMVD)
        in 0x0CBE..0x0CC2, 0x0CC6, 0x0CCC, 0x0CE2, 0x0CE3 -> p(M, BEFORE_SUB)
        0x0CC3, 0x0CC4, 0x0CC7, 0x0CC8, 0x0CCA, 0x0CCB, 0x0CD5, 0x0CD6 -> p(M, AFTER_SUB)
        0x0CCD -> p(H, ABOVE_C)
        0x0CF1, 0x0CF2 -> p(CS, BASE_C)
        // Malayalam
        in 0x0D00..0x0D03 -> p(SM, SMVD)
        0x0D04, in 0x0D66..0x0D6F -> p(PLACEHOLDER, BASE_C)
        in 0x0D05..0x0D0C, in 0x0D0E..0x0D10, in 0x0D12..0x0D14, in 0x0D5F..0x0D61 -> p(V, BASE_C)
        in 0x0D15..0x0D2F, in 0x0D31..0x0D3A, in 0x0D54..0x0D56, in 0x0D7A..0x0D7F -> p(C, BASE_C)
        0x0D30 -> p(RA, BASE_C)
        0x0D3B, 0x0D3C -> p(M, AFTER_SUB)
        0x0D3D -> p(SYMBOL, SMVD)
        in 0x0D3E..0x0D44, in 0x0D4A..0x0D4C, 0x0D57, 0x0D62, 0x0D63 -> p(M, AFTER_POST)
        in 0x0D46..0x0D48 -> p(M, PRE_M)
        0x0D4D -> p(H, ABOVE_C)
        0x0D4E -> p(REPHA, END)
        // Vedic Extensions
        in 0x1CD0..0x1CD2, in 0x1CD4..0x1CE8, 0x1CED, 0x1CF4, in 0x1CF7..0x1CF9 -> p(A, SMVD)
        in 0x1CE9..0x1CEC, in 0x1CEE..0x1CF1 -> p(SYMBOL, SMVD)
        0x1CF2, 0x1CF3 -> p(C, BASE_C)
        0x1CF5, 0x1CF6 -> p(CS, BASE_C)
        0x1CFA -> p(PLACEHOLDER, BASE_C)
        // Devanagari Extended
        in 0xA8E0..0xA8F1 -> p(A, SMVD)
        in 0xA8F2..0xA8F7 -> p(SYMBOL, SMVD)
        0xA8FE -> p(V, BASE_C)
        0xA8FF -> p(M, AFTER_SUB)
        // Grantha
        in 0x11301..0x11303 -> p(SM, SMVD)
        0x1133B, 0x1133C -> p(N, END)
        else -> p(X, END)
    }

    /* ─── Syllables (HarfBuzz's hb-ot-shaper-indic-machine.rl) ────────────── */

    private const val CONSONANT_SYLLABLE = 0
    private const val VOWEL_SYLLABLE = 1
    private const val STANDALONE_CLUSTER = 2
    private const val SYMBOL_CLUSTER = 3
    private const val BROKEN_CLUSTER = 4
    private const val NON_INDIC_CLUSTER = 5

    /**
     * A regular pattern over the categories of a word. [ends] takes the positions a match may
     * start at, as bits counted from [start], and gives the positions it may end at. A
     * syllable is at most 63 characters long.
     */
    private abstract class Pattern {
        abstract fun ends(cats: IntArray, start: Int, from: Long): Long
    }

    private class One(private val set: Long) : Pattern() {
        override fun ends(cats: IntArray, start: Int, from: Long): Long {
            var out = 0L
            var bits = from
            while (bits != 0L) {
                val j = bits.countTrailingZeroBits()
                bits = bits and (bits - 1)
                val i = start + j
                if (j < 63 && i < cats.size && (set ushr cats[i]) and 1L != 0L) out = out or (1L shl (j + 1))
            }
            return out
        }
    }

    private class Seq(private val parts: Array<out Pattern>) : Pattern() {
        override fun ends(cats: IntArray, start: Int, from: Long): Long {
            var m = from
            for (part in parts) {
                if (m == 0L) return 0L
                m = part.ends(cats, start, m)
            }
            return m
        }
    }

    private class Alt(private val parts: Array<out Pattern>) : Pattern() {
        override fun ends(cats: IntArray, start: Int, from: Long): Long = parts.fold(0L) { m, part -> m or part.ends(cats, start, from) }
    }

    private class Opt(private val part: Pattern) : Pattern() {
        override fun ends(cats: IntArray, start: Int, from: Long): Long = from or part.ends(cats, start, from)
    }

    private class Star(private val part: Pattern) : Pattern() {
        override fun ends(cats: IntArray, start: Int, from: Long): Long {
            var all = from
            var frontier = from
            while (frontier != 0L) {
                frontier = part.ends(cats, start, frontier) and all.inv()
                all = all or frontier
            }
            return all
        }
    }

    private fun cat(vararg categories: Int): Pattern = One(flags(*categories))
    private fun seq(vararg parts: Pattern): Pattern = Seq(parts)
    private fun alt(vararg parts: Pattern): Pattern = Alt(parts)
    private fun opt(part: Pattern): Pattern = Opt(part)
    private fun star(part: Pattern): Pattern = Star(part)

    /** The syllable patterns with their types, in the order that breaks a tie between two matches of one length. */
    private val SYLLABLES: List<Pair<Pattern, Int>> = run {
        val c = cat(C, RA)
        val n = seq(opt(seq(opt(cat(ZWNJ)), cat(RS))), opt(seq(cat(N), opt(cat(N)))))
        val z = cat(ZWJ, ZWNJ)
        val reph = alt(seq(cat(RA), cat(H)), cat(REPHA))
        val sm = cat(SM, SMPST)
        val cn = seq(c, opt(cat(ZWJ)), n)
        val symbol = seq(cat(SYMBOL), opt(cat(N)))
        val matraGroup = seq(star(z), alt(cat(M), seq(opt(sm), cat(MPST))), opt(cat(N)), opt(cat(H)))
        val syllableTail = seq(opt(seq(opt(z), sm, opt(sm), opt(cat(ZWNJ)))), star(cat(A)))
        val halantGroup = seq(opt(z), cat(H), opt(seq(cat(ZWJ), opt(cat(N)))))
        val finalHalantGroup = alt(halantGroup, seq(cat(H), cat(ZWNJ)))
        val halantOrMatraGroup = alt(finalHalantGroup, star(matraGroup))
        val complexSyllableTail = seq(star(seq(halantGroup, cn)), opt(cat(CM)), halantOrMatraGroup, syllableTail)
        listOf(
            seq(opt(cat(REPHA, CS)), cn, complexSyllableTail) to CONSONANT_SYLLABLE,
            seq(opt(reph), cat(V), n, alt(cat(ZWJ), complexSyllableTail)) to VOWEL_SYLLABLE,
            seq(alt(seq(opt(cat(REPHA, CS)), cat(PLACEHOLDER)), seq(opt(reph), cat(DOTTED_CIRCLE))), n, complexSyllableTail) to STANDALONE_CLUSTER,
            seq(symbol, syllableTail) to SYMBOL_CLUSTER,
            cat(SMPST) to NON_INDIC_CLUSTER,
            seq(opt(reph), n, complexSyllableTail) to BROKEN_CLUSTER,
        )
    }

    /**
     * Numbers the syllables of [glyphs] as HarfBuzz's scanner does: the longest match of any
     * pattern wins, and a character no pattern matches is a syllable of its own. The low four
     * bits of [GsubGlyph.syllable] hold the type of the syllable.
     */
    private fun findSyllables(glyphs: List<GsubGlyph>) {
        val cats = IntArray(glyphs.size) { category(glyphs[it]) }
        var i = 0
        var serial = 0
        while (i < cats.size) {
            var length = 0
            var type = NON_INDIC_CLUSTER
            for ((pattern, t) in SYLLABLES) {
                val longest = 63 - pattern.ends(cats, i, 1L).countLeadingZeroBits()
                if (longest > length) { length = longest; type = t }
            }
            if (length == 0) length = 1
            serial++
            for (k in i until i + length) glyphs[k].syllable = (serial shl 4) or type
            i += length
        }
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

    /**
     * A broken syllable, such as a lone matra, gets a dotted circle to sit on, after a leading
     * reph sign, as HarfBuzz gives it. Nothing is inserted when the font has no dotted circle.
     */
    private fun insertDottedCircles(glyphs: MutableList<GsubGlyph>, circle: Int) {
        if (circle <= 0) return
        var i = 0
        var last = -1
        while (i < glyphs.size) {
            val g = glyphs[i]
            if (g.syllable != last && g.syllable and 0xF == BROKEN_CLUSTER) {
                last = g.syllable
                while (i < glyphs.size && glyphs[i].syllable == last && category(glyphs[i]) == REPHA) i++
                glyphs.add(i, GsubGlyph(circle, g.cluster, g.features).also { it.syllable = last; it.shaperData = p(DOTTED_CIRCLE, END) })
            }
            i++
        }
    }

    /**
     * A glyph with a below-base or post-base form in the font takes that position, as
     * HarfBuzz's consonant_position_from_face finds it: blwf or vatu with the virama on either
     * side makes it below-base, and pstf or pref makes it post-base.
     */
    private fun updateConsonantPositions(gsub: OpenTypeGsub, script: String, glyphs: List<GsubGlyph>, virama: Int, zeroContext: Boolean) {
        if (virama <= 0) return
        val cache = HashMap<Int, Int>()
        for (g in glyphs) {
            if (position(g) != BASE_C) continue
            val pos = cache.getOrPut(g.gid) {
                val before = intArrayOf(virama, g.gid)
                val after = intArrayOf(g.gid, virama)
                fun would(feature: String) =
                    gsub.wouldSubstitute(feature, script, before, zeroContext) || gsub.wouldSubstitute(feature, script, after, zeroContext)
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

    private fun initialReordering(
        config: Config, oldSpec: Boolean, zeroContext: Boolean, gsub: OpenTypeGsub, script: String,
        glyphs: MutableList<GsubGlyph>, start: Int, end: Int,
    ) {
        val type = glyphs[start].syllable and 0xF
        if (type == SYMBOL_CLUSTER || type == NON_INDIC_CLUSTER) return

        // Kannada reads Ra, virama and ZWJ as Ra, ZWJ and virama.
        if (config == Config.KANNADA && start + 3 <= end && isOneOf(glyphs[start], flags(RA)) &&
            isOneOf(glyphs[start + 1], HALANT) && isOneOf(glyphs[start + 2], flags(ZWJ))
        ) {
            val t = glyphs[start + 1]
            glyphs[start + 1] = glyphs[start + 2]
            glyphs[start + 2] = t
        }

        // 1. The base consonant. A leading reph is not a candidate: Ra and virama the font joins
        // into one, Ra, virama and ZWJ in Telugu, or a reph sign of its own in Malayalam.
        var base = end
        var hasReph = false
        var limit = start
        val implicit = config.rephMode == RephMode.IMPLICIT && start + 3 <= end && !isJoiner(glyphs[start + 2])
        val explicit = config.rephMode == RephMode.EXPLICIT && start + 3 <= end && category(glyphs[start + 2]) == ZWJ
        if (implicit || explicit) {
            val pair = intArrayOf(glyphs[start].gid, glyphs[start + 1].gid)
            if (gsub.wouldSubstitute("rphf", script, pair, zeroContext) ||
                (explicit && gsub.wouldSubstitute("rphf", script, pair + glyphs[start + 2].gid, zeroContext))
            ) {
                limit += 2
                while (limit < end && isJoiner(glyphs[limit])) limit++
                base = start
                hasReph = true
            }
        } else if (config.rephMode == RephMode.LOG_REPHA && category(glyphs[start]) == REPHA) {
            limit += 1
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
                // A ZWJ after a virama asks for a half form, so the search stops.
                break
            }
        } while (i > limit)
        if (hasReph && base == start && limit - base <= 2) hasReph = false

        // 2. Positions.
        for (k in start until base) setPosition(glyphs[k], minOf(PRE_C, position(glyphs[k])))
        if (base < end) setPosition(glyphs[base], BASE_C)
        if (hasReph) setPosition(glyphs[start], RA_TO_BECOME_REPH)

        // The old specification moves the first virama after the base behind the last consonant.
        if (oldSpec) {
            for (k in base + 1 until end) {
                if (category(glyphs[k]) != H) continue
                var j = end - 1
                while (j > k && !isConsonant(glyphs[j]) && !(config == Config.KANNADA && category(glyphs[j]) == H)) j--
                if (category(glyphs[j]) != H && j > k) glyphs.add(j, glyphs.removeAt(k))
                break
            }
        }

        // Joiners, nuktas, medials and viramas move with the character before them.
        var lastPos = START
        for (k in start until end) {
            val g = glyphs[k]
            if (inSet(g, ATTACHED)) {
                setPosition(g, lastPos)
                if (category(g) == H && position(g) == PRE_M) {
                    // A virama does not move with a left matra.
                    for (j in k downTo start + 1) if (position(glyphs[j - 1]) != PRE_M) { setPosition(g, position(glyphs[j - 1])); break }
                }
            } else if (position(g) != SMVD) {
                if (category(g) == MPST && k > start && category(glyphs[k - 1]) == SM) setPosition(glyphs[k - 1], position(g))
                lastPos = position(g)
            }
        }
        // A post-base consonant takes what came since the last consonant or matra.
        var last = base
        for (k in base + 1 until end) {
            if (isConsonant(glyphs[k])) {
                for (j in last + 1 until k) if (position(glyphs[j]) < SMVD) setPosition(glyphs[j], position(glyphs[k]))
                last = k
            } else if (inSet(glyphs[k], MATRAS)) {
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
        // Two left matras keep their written order, each with the nukta after it.
        if (firstLeft < lastLeft) {
            glyphs.subList(firstLeft, lastLeft + 1).reverse()
            var k = firstLeft
            for (j in firstLeft..lastLeft) if (inSet(glyphs[j], MATRAS)) { glyphs.subList(k, j + 1).reverse(); k = j + 1 }
        }

        // 4. The features each glyph takes.
        var k = start
        while (k < end && position(glyphs[k]) == RA_TO_BECOME_REPH) { glyphs[k].features += "rphf"; k++ }
        val preBase = if (oldSpec || config.blwfPostOnly) listOf("half") else listOf("half", "blwf")
        for (j in start until base) glyphs[j].features += preBase
        for (j in base + 1 until end) glyphs[j].features += listOf("blwf", "abvf", "pstf")
        // The old Devanagari specification gives a Ra and virama before the base its below-base form.
        if (oldSpec && config == Config.DEVANAGARI) {
            for (j in start until base - 1) {
                if (category(glyphs[j]) == RA && category(glyphs[j + 1]) == H && (j + 2 == base || category(glyphs[j + 2]) != ZWJ)) {
                    glyphs[j].features += "blwf"
                    glyphs[j + 1].features += "blwf"
                }
            }
        }
        // A virama and Ra that the font reorders before the base, as in Malayalam, take pref.
        if (base + 2 < end) {
            for (j in base + 1 until end - 1) {
                if (gsub.wouldSubstitute("pref", script, intArrayOf(glyphs[j].gid, glyphs[j + 1].gid), zeroContext)) {
                    glyphs[j].features += "pref"
                    glyphs[j + 1].features += "pref"
                    break
                }
            }
        }
        // A ZWNJ keeps the consonant before it from taking a half form.
        for (j in start + 1 until end) {
            if (!isJoiner(glyphs[j])) continue
            val nonJoiner = category(glyphs[j]) == ZWNJ
            var q = j
            do {
                q--
                if (nonJoiner) glyphs[q].features -= "half"
            } while (q > start && !isConsonant(glyphs[q]))
        }
    }

    /** The categories that take the position of the character before them. */
    private val ATTACHED = flags(ZWJ, ZWNJ, N, RS, CM, H)

    /* ─── Final reordering (HarfBuzz's final_reordering_syllable_indic) ─────── */

    private fun finalReordering(config: Config, glyphs: MutableList<GsubGlyph>, start: Int, end: Int, virama: Int) {
        // A virama that a multiple substitution split out of a ligature is a virama again.
        if (virama > 0) {
            for (i in start until end) {
                val g = glyphs[i]
                if (g.gid == virama && g.ligated && g.multiplied) {
                    setCategory(g, H)
                    g.ligated = false
                    g.multiplied = false
                }
            }
        }

        var tryPref = (start until end).any { "pref" in glyphs[it].features }
        var base = start
        while (base < end) {
            if (position(glyphs[base]) >= BASE_C) {
                if (tryPref && base + 1 < end) {
                    for (i in base + 1 until end) {
                        if ("pref" !in glyphs[i].features) continue
                        // A pref candidate that formed nothing: the base is around it.
                        if (!formed(glyphs[i])) {
                            base = i
                            while (base < end && isHalant(glyphs[base])) base++
                            if (base < end) setPosition(glyphs[base], BASE_C)
                            tryPref = false
                        }
                        break
                    }
                    if (base == end) break
                }
                // Malayalam skips over below-base forms that did not form.
                if (config == Config.MALAYALAM) {
                    var i = base + 1
                    while (i < end) {
                        while (i < end && isJoiner(glyphs[i])) i++
                        if (i == end || !isHalant(glyphs[i])) break
                        i++
                        while (i < end && isJoiner(glyphs[i])) i++
                        if (i < end && isConsonant(glyphs[i]) && position(glyphs[i]) == BELOW_C) {
                            base = i
                            setPosition(glyphs[base], BASE_C)
                        }
                        i++
                    }
                }
                if (start < base && position(glyphs[base]) > BASE_C) base--
                break
            }
            base++
        }
        if (base == end && start < base && isOneOf(glyphs[base - 1], flags(ZWJ))) base--
        if (base < end) while (start < base && isOneOf(glyphs[base], flags(N, H))) base--

        // A pre-base matra moves after the last half form, before the main consonant.
        if (start + 1 < end && start < base) {
            var newPos = if (base == end) base - 2 else base - 1
            if (!config.noHalfForms) {
                while (true) {
                    while (newPos > start && !isOneOf(glyphs[newPos], MATRAS or HALANT)) newPos--
                    if (isHalant(glyphs[newPos]) && position(glyphs[newPos]) != PRE_M) {
                        // A ZWJ after the virama keeps the matra from moving past it.
                        if (newPos + 1 < end && category(glyphs[newPos + 1]) == ZWJ && newPos > start) { newPos--; continue }
                    } else {
                        newPos = start
                    }
                    break
                }
            }
            if (start < newPos && position(glyphs[newPos]) != PRE_M) {
                var i = newPos
                while (i > start) {
                    if (position(glyphs[i - 1]) == PRE_M) {
                        val oldPos = i - 1
                        if (oldPos < base && base <= newPos) base--
                        glyphs.add(newPos, glyphs.removeAt(oldPos))
                        newPos--
                    }
                    i--
                }
            }
        }

        // The reph moves to its place: a Ra and virama the font joined, or a reph sign it did not.
        if (start + 1 < end && position(glyphs[start]) == RA_TO_BECOME_REPH && ((category(glyphs[start]) == REPHA) xor formed(glyphs[start]))) {
            val target = rephTarget(config, glyphs, start, end, base)
            glyphs.add(target, glyphs.removeAt(start))
            if (start < base && base <= target) base--
        }

        // A pre-base-reordering consonant that the font formed moves before the base.
        if (tryPref && base + 1 < end) {
            for (i in base + 1 until end) {
                if ("pref" !in glyphs[i].features) continue
                if (formed(glyphs[i])) {
                    var newPos = base
                    if (!config.noHalfForms) while (newPos > start && !isOneOf(glyphs[newPos - 1], MATRAS or HALANT)) newPos--
                    if (newPos > start && isHalant(glyphs[newPos - 1]) && newPos < end && isJoiner(glyphs[newPos])) newPos++
                    glyphs.add(newPos, glyphs.removeAt(i))
                    if (newPos <= base && base < i) base++
                }
                break
            }
        }

        // A left matra at the start of a word takes init.
        if (position(glyphs[start]) == PRE_M && (start == 0 || glyphs[start - 1].shaperData and WORD_CHARACTER == 0)) {
            glyphs[start].features += "init"
        }
    }

    /** True for a glyph that a ligature formed and no multiple substitution split since. */
    private fun formed(g: GsubGlyph): Boolean = g.ligated && !g.multiplied

    private fun rephTarget(config: Config, glyphs: List<GsubGlyph>, start: Int, end: Int, base: Int): Int {
        // Right after the first explicit virama between the reph and the base.
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
                while (p + 1 < end && position(glyphs[p + 1]) != POST_C && position(glyphs[p + 1]) != AFTER_POST && position(glyphs[p + 1]) != SMVD) p++
                if (p < end) return p
            }
        }
        afterHalant()?.let { return it }
        // At the end of the syllable, before the signs that go last.
        var p = end - 1
        while (p > start && position(glyphs[p]) == SMVD) p--
        // Before a virama that follows a matra, so that the two can interact.
        if (isHalant(glyphs[p])) {
            var i = base + 1
            while (i < p) {
                if (inSet(glyphs[i], MATRAS)) p--
                i++
            }
        }
        return p
    }
}
