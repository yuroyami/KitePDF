package io.github.yuroyami.kitepdf.epub

/**
 * The cursive joining of Arabic and Syriac (#315), after the Arabic shaper of HarfBuzz 14.4:
 * the contextual form of each letter from its joining type and those of its neighbours. The
 * caller then applies the GSUB feature of each form, such as `init` or `fina`.
 *
 * The joining types of U+0600 to U+08FF come from ArabicShaping.txt of Unicode 17. Outside
 * those blocks, a character is transparent when it is a non-spacing or enclosing mark or a
 * format character, and non-joining otherwise, as the header of that file says.
 */
internal object ArabicJoining {

    /**
     * The joining types, with the two joining groups of Syriac that have forms of their own:
     * non-joining, right-joining, dual-joining, Alaph, Dalath and Rish, and transparent. A
     * join-causing character, such as ZWJ or tatweel, counts as dual-joining.
     */
    enum class Jt { U, R, D, ALAPH, DALATH_RISH, T }

    /** The contextual forms, each named after its GSUB feature. */
    enum class Form { ISOL, INIT, MEDI, FINA, FIN2, FIN3, MED2 }

    /** True if any code point in [cps] is in the blocks of Arabic and Syriac, U+0600 to U+08FF. */
    fun hasArabic(cps: IntArray): Boolean = cps.any { it in 0x0600..0x08FF }

    /** The GSUB feature tag of [form]. */
    fun feature(form: Form): String = form.name.lowercase()

    /**
     * The form of each character of [cps], in logical order, or null for a character that
     * takes none: a non-joining or a transparent one. HarfBuzz's state machine decides it.
     */
    fun forms(cps: IntArray): Array<Form?> {
        val out = arrayOfNulls<Form>(cps.size)
        var prev = -1
        var state = 0
        for ((i, cp) in cps.withIndex()) {
            val type = type(cp)
            if (type == Jt.T) continue
            val entry = STATES[state][type.ordinal]
            if (entry.prev != null && prev >= 0) out[prev] = entry.prev
            out[i] = entry.curr
            prev = i
            state = entry.next
        }
        return out
    }

    /** The joining type of [cp]. */
    fun type(cp: Int): Jt = when (cp) {
        in 0x0610..0x061A, 0x061C, in 0x064B..0x065F, 0x0670, in 0x06D6..0x06DC, in 0x06DF..0x06E4, 0x06E7, 0x06E8,
            in 0x06EA..0x06ED, 0x070F, 0x0711, in 0x0730..0x074A, in 0x07A6..0x07B0, in 0x07EB..0x07F3, 0x07FD,
            in 0x0816..0x0819, in 0x081B..0x0823, in 0x0825..0x0827, in 0x0829..0x082D, in 0x0859..0x085B,
            in 0x0897..0x089F, in 0x08CA..0x08E1, in 0x08E3..0x08FF -> Jt.T
        0x0620, 0x0626, 0x0628, in 0x062A..0x062E, in 0x0633..0x0647, 0x0649, 0x064A, 0x066E, 0x066F, in 0x0678..0x0687,
            in 0x069A..0x06BF, 0x06C1, 0x06C2, 0x06CC, 0x06CE, 0x06D0, 0x06D1, in 0x06FA..0x06FC, 0x06FF,
            in 0x0712..0x0714, in 0x071A..0x071D, in 0x071F..0x0727, 0x0729, 0x072B, 0x072D, 0x072E, in 0x074E..0x0758,
            in 0x075C..0x076A, in 0x076D..0x0770, 0x0772, in 0x0775..0x0777, in 0x077A..0x077F, in 0x07CA..0x07EA,
            0x07FA, in 0x0841..0x0845, 0x0848, in 0x084A..0x0853, 0x0855, 0x0860, in 0x0862..0x0865, 0x0868,
            in 0x0883..0x0886, in 0x0889..0x088D, 0x088F, in 0x08A0..0x08A9, 0x08AF, 0x08B0, in 0x08B3..0x08B8,
            in 0x08BA..0x08C8 -> Jt.D
        in 0x0622..0x0625, 0x0627, 0x0629, in 0x062F..0x0632, 0x0648, in 0x0671..0x0673, in 0x0675..0x0677,
            in 0x0688..0x0699, 0x06C0, in 0x06C3..0x06CB, 0x06CD, 0x06CF, 0x06D2, 0x06D3, 0x06D5, 0x06EE, 0x06EF,
            in 0x0717..0x0719, 0x071E, 0x0728, 0x072C, 0x074D, in 0x0759..0x075B, 0x076B, 0x076C, 0x0771, 0x0773, 0x0774,
            0x0778, 0x0779, 0x0840, 0x0846, 0x0847, 0x0849, 0x0854, in 0x0856..0x0858, 0x0867, 0x0869, 0x086A,
            in 0x0870..0x0882, 0x088E, in 0x08AA..0x08AC, 0x08AE, 0x08B1, 0x08B2, 0x08B9 -> Jt.R
        0x0710 -> Jt.ALAPH
        0x0715, 0x0716, 0x072A, 0x072F -> Jt.DALATH_RISH
        in 0x0600..0x08FF, 0x200C, 0x180E -> Jt.U
        0x200D -> Jt.D
        else -> when (cp.toChar().category) {
            CharCategory.NON_SPACING_MARK, CharCategory.ENCLOSING_MARK, CharCategory.FORMAT -> Jt.T
            else -> Jt.U
        }
    }

    /** What one character does in a state: the form the previous character takes, its own form, and the next state. */
    private class Entry(val prev: Form?, val curr: Form?, val next: Int)

    /**
     * HarfBuzz's arabic_state_table, one row for each state, with one entry for each joining
     * type in the order of [Jt]. The states: 0 after a non-joining character, 1 after a
     * right-joining one or an isolated Alaph, 2 after a dual-joining one that is isolated so
     * far, 3 after a final dual-joining one, 4 after a final Alaph, 5 after an Alaph in fin2
     * or fin3, and 6 after Dalath or Rish.
     */
    private val STATES: Array<Array<Entry>> = run {
        val none: Form? = null
        fun e(prev: Form?, curr: Form?, next: Int) = Entry(prev, curr, next)
        arrayOf(
            arrayOf(e(none, none, 0), e(none, Form.ISOL, 1), e(none, Form.ISOL, 2), e(none, Form.ISOL, 1), e(none, Form.ISOL, 6)),
            arrayOf(e(none, none, 0), e(none, Form.ISOL, 1), e(none, Form.ISOL, 2), e(none, Form.FIN2, 5), e(none, Form.ISOL, 6)),
            arrayOf(e(none, none, 0), e(Form.INIT, Form.FINA, 1), e(Form.INIT, Form.FINA, 3), e(Form.INIT, Form.FINA, 4), e(Form.INIT, Form.FINA, 6)),
            arrayOf(e(none, none, 0), e(Form.MEDI, Form.FINA, 1), e(Form.MEDI, Form.FINA, 3), e(Form.MEDI, Form.FINA, 4), e(Form.MEDI, Form.FINA, 6)),
            arrayOf(e(none, none, 0), e(Form.MED2, Form.ISOL, 1), e(Form.MED2, Form.ISOL, 2), e(Form.MED2, Form.FIN2, 5), e(Form.MED2, Form.ISOL, 6)),
            arrayOf(e(none, none, 0), e(Form.ISOL, Form.ISOL, 1), e(Form.ISOL, Form.ISOL, 2), e(Form.ISOL, Form.FIN2, 5), e(Form.ISOL, Form.ISOL, 6)),
            arrayOf(e(none, none, 0), e(none, Form.ISOL, 1), e(none, Form.ISOL, 2), e(none, Form.FIN3, 5), e(none, Form.ISOL, 6)),
        )
    }
}
