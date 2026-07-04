package io.github.yuroyami.kitepdf.epub

/**
 * The Unicode Arabic cursive-joining algorithm: given a run of code points, decide
 * each letter's contextual form (isolated / initial / medial / final) from its and
 * its neighbours' joining types. The caller then applies the matching GSUB feature
 * (`isol`/`init`/`medi`/`fina`) to swap in the connected glyph.
 *
 * Joining-type data covers the Arabic block (U+0600–U+06FF) + Arabic Supplement
 * (U+0750–U+077F): the right-joining letters are an explicit set, combining marks
 * are transparent, tatweel/ZWJ cause joining, and every other Arabic letter is
 * dual-joining. That is the common surface; niche Syriac/N'Ko/extended letters and
 * required-ligature reordering beyond `rlig` are out of scope.
 */
internal object ArabicJoining {

    enum class Jt { D, R, C, T, U } // dual, right, join-causing, transparent, non-joining
    enum class Form { ISOL, INIT, MEDI, FINA }

    /** True if any code point in [cps] is an Arabic letter worth shaping. */
    fun hasArabic(cps: IntArray): Boolean = cps.any { it in 0x0600..0x06FF || it in 0x0750..0x077F }

    /** The GSUB feature tag for a form. */
    fun feature(form: Form): String = when (form) {
        Form.ISOL -> "isol"; Form.INIT -> "init"; Form.MEDI -> "medi"; Form.FINA -> "fina"
    }

    /** Per-position contextual form, in the same (logical) order as [cps]. */
    fun forms(cps: IntArray): Array<Form> {
        val jt = Array(cps.size) { type(cps[it]) }
        val out = Array(cps.size) { Form.ISOL }
        for (i in cps.indices) {
            if (jt[i] == Jt.T || jt[i] == Jt.U) continue // marks / non-joiners keep their glyph
            val prev = prevNonTransparent(jt, i)
            val next = nextNonTransparent(jt, i)
            // Joins to the logically-previous letter (its right side connects).
            val joinsPrev = prev >= 0 && (jt[prev] == Jt.D || jt[prev] == Jt.C) &&
                (jt[i] == Jt.D || jt[i] == Jt.R || jt[i] == Jt.C)
            // Joins to the logically-next letter (its left side connects).
            val joinsNext = next >= 0 && (jt[next] == Jt.D || jt[next] == Jt.R || jt[next] == Jt.C) &&
                (jt[i] == Jt.D || jt[i] == Jt.C)
            out[i] = when {
                joinsPrev && joinsNext -> Form.MEDI
                joinsPrev -> Form.FINA
                joinsNext -> Form.INIT
                else -> Form.ISOL
            }
        }
        return out
    }

    private fun prevNonTransparent(jt: Array<Jt>, i: Int): Int {
        var k = i - 1
        while (k >= 0 && jt[k] == Jt.T) k--
        return k
    }

    private fun nextNonTransparent(jt: Array<Jt>, i: Int): Int {
        var k = i + 1
        while (k < jt.size && jt[k] == Jt.T) k++
        return if (k < jt.size) k else -1
    }

    fun type(cp: Int): Jt = when {
        cp == 0x0640 || cp == 0x200D -> Jt.C // tatweel, ZWJ
        cp == 0x200C -> Jt.U                 // ZWNJ
        isTransparent(cp) -> Jt.T
        cp in RIGHT_JOINING -> Jt.R
        isArabicLetter(cp) -> Jt.D
        else -> Jt.U
    }

    private fun isTransparent(cp: Int): Boolean =
        cp in 0x0610..0x061A || cp in 0x064B..0x065F || cp == 0x0670 ||
            cp in 0x06D6..0x06DC || cp in 0x06DF..0x06E4 || cp in 0x06E7..0x06E8 ||
            cp in 0x06EA..0x06ED || cp in 0x08E3..0x08FF

    private fun isArabicLetter(cp: Int): Boolean =
        cp in 0x0620..0x064A || cp in 0x066E..0x066F || cp in 0x0671..0x06D3 ||
            cp == 0x06D5 || cp in 0x06EE..0x06EF || cp in 0x06FA..0x06FF ||
            cp in 0x0750..0x077F

    // Right-joining letters (connect only to the preceding letter): alef family,
    // waw, the dal/reh/zain groups, teh marbuta, and their extended variants.
    private val RIGHT_JOINING: Set<Int> = hashSetOf(
        0x0622, 0x0623, 0x0624, 0x0625, 0x0627, 0x0629, 0x062F, 0x0630, 0x0631, 0x0632, 0x0648,
        0x0671, 0x0672, 0x0673, 0x0675, 0x0676, 0x0677, 0x0688, 0x0689, 0x068A, 0x068B, 0x068C,
        0x068D, 0x068E, 0x068F, 0x0690, 0x0691, 0x0692, 0x0693, 0x0694, 0x0695, 0x0696, 0x0697,
        0x0698, 0x0699, 0x06C0, 0x06C3, 0x06C4, 0x06C5, 0x06C6, 0x06C7, 0x06C8, 0x06C9, 0x06CA,
        0x06CB, 0x06CD, 0x06CF, 0x06D2, 0x06D3, 0x06D5, 0x06EE, 0x06EF,
    )
}
