package io.github.yuroyami.kitepdf.epub

/**
 * The cursive joining of Arabic and Syriac (#315), after the Arabic shaper of HarfBuzz 14.4:
 * the contextual form of each letter from its joining type and those of its neighbours. The
 * caller then applies the GSUB feature of each form, such as `init` or `fina`.
 *
 * The joining types come from ArabicShaping.txt of Unicode 17, for every plane (#319). A
 * character that the file does not list is transparent when it is a non-spacing or enclosing
 * mark or a format character, and non-joining otherwise, as the header of that file says.
 */
internal object ArabicJoining {

    /**
     * The joining types, with the two joining groups of Syriac that have forms of their own:
     * non-joining, left-joining, right-joining, dual-joining, Alaph, Dalath and Rish, and
     * transparent. A join-causing character, such as ZWJ or tatweel, counts as dual-joining.
     */
    enum class Jt { U, L, R, D, ALAPH, DALATH_RISH, T }

    /** The contextual forms, each named after its GSUB feature. */
    enum class Form { ISOL, INIT, MEDI, FINA, FIN2, FIN3, MED2 }

    /** True if any code point in [cps] is in a script that joins, such as Arabic, Syriac, Mongolian or Adlam. */
    fun hasArabic(cps: IntArray): Boolean = cps.any { UnicodeScript.of(it) in JOINING_SCRIPTS }

    /** The scripts that HarfBuzz gives joining forms, by their ISO 15924 codes. */
    private val JOINING_SCRIPTS = setOf(
        "Adlm", "Arab", "Chrs", "Mand", "Mani", "Mong", "Nkoo", "Ougr", "Phag", "Phlp", "Rohg", "Sogd", "Syrc",
    )

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
        // A Mongolian free variation selector takes the form of the letter before it.
        for (i in 1 until cps.size) if (cps[i] in 0x180B..0x180D || cps[i] == 0x180F) out[i] = out[i - 1]
        return out
    }

    /** The joining type of [cp]. */
    fun type(cp: Int): Jt = when (table[cp]) {
        1 -> Jt.L
        2 -> Jt.R
        3 -> Jt.D
        4 -> Jt.ALAPH
        5 -> Jt.DALATH_RISH
        6 -> Jt.T
        7 -> Jt.U
        else -> when (GeneralCategory.of(cp)) {
            CharCategory.NON_SPACING_MARK, CharCategory.ENCLOSING_MARK, CharCategory.FORMAT -> Jt.T
            else -> Jt.U
        }
    }

    private val table by lazy { PackedRuns(TYPES) }

    /** What one character does in a state: the form the previous character takes, its own form, and the next state. */
    private class Entry(val prev: Form?, val curr: Form?, val next: Int)

    /**
     * HarfBuzz's arabic_state_table, one row for each state, with one entry for each joining
     * type in the order of [Jt]. The states: 0 after a non-joining character, 1 after a
     * right-joining one or an isolated Alaph, 2 after a left-joining or dual-joining one that is
     * isolated so far, 3 after a final dual-joining one, 4 after a final Alaph, 5 after an Alaph
     * in fin2 or fin3, and 6 after Dalath or Rish.
     */
    private val STATES: Array<Array<Entry>> = run {
        val none: Form? = null
        fun e(prev: Form?, curr: Form?, next: Int) = Entry(prev, curr, next)
        arrayOf(
            arrayOf(e(none, none, 0), e(none, Form.ISOL, 2), e(none, Form.ISOL, 1), e(none, Form.ISOL, 2), e(none, Form.ISOL, 1), e(none, Form.ISOL, 6)),
            arrayOf(e(none, none, 0), e(none, Form.ISOL, 2), e(none, Form.ISOL, 1), e(none, Form.ISOL, 2), e(none, Form.FIN2, 5), e(none, Form.ISOL, 6)),
            arrayOf(e(none, none, 0), e(none, Form.ISOL, 2), e(Form.INIT, Form.FINA, 1), e(Form.INIT, Form.FINA, 3), e(Form.INIT, Form.FINA, 4), e(Form.INIT, Form.FINA, 6)),
            arrayOf(e(none, none, 0), e(none, Form.ISOL, 2), e(Form.MEDI, Form.FINA, 1), e(Form.MEDI, Form.FINA, 3), e(Form.MEDI, Form.FINA, 4), e(Form.MEDI, Form.FINA, 6)),
            arrayOf(e(none, none, 0), e(none, Form.ISOL, 2), e(Form.MED2, Form.ISOL, 1), e(Form.MED2, Form.ISOL, 2), e(Form.MED2, Form.FIN2, 5), e(Form.MED2, Form.ISOL, 6)),
            arrayOf(e(none, none, 0), e(none, Form.ISOL, 2), e(Form.ISOL, Form.ISOL, 1), e(Form.ISOL, Form.ISOL, 2), e(Form.ISOL, Form.FIN2, 5), e(Form.ISOL, Form.ISOL, 6)),
            arrayOf(e(none, none, 0), e(none, Form.ISOL, 2), e(none, Form.ISOL, 1), e(none, Form.ISOL, 2), e(none, Form.FIN3, 5), e(none, Form.ISOL, 6)),
        )
    }

    /**
     * The joining type of every character that ArabicShaping.txt lists, in runs of one type: the
     * first and last code point in six hex digits each, and the type in two: 1 left-joining, 2
     * right-joining, 3 dual-joining or join-causing, 4 Alaph, 5 Dalath and Rish, 6 transparent
     * and 7 non-joining.
     */
    private const val TYPES =
        "000600000605070006080006080700060B00060B07000620000620030006210006210700062200062502000626000626030006270006270200062800" +
        "0628030006290006290200062A00062E0300062F00063202000633000647030006480006480200064900064A0300066E00066F030006710006730200" +
        "06740006740700067500067702000678000687030006880006990200069A0006BF030006C00006C0020006C10006C2030006C30006CB020006CC0006" +
        "CC030006CD0006CD020006CE0006CE030006CF0006CF020006D00006D1030006D20006D3020006D50006D5020006DD0006DD070006EE0006EF020006" +
        "FA0006FC030006FF0006FF0300070F00070F060007100007100400071200071403000715000716050007170007190200071A00071D0300071E00071E" +
        "0200071F00072703000728000728020007290007290300072A00072A0500072B00072B0300072C00072C0200072D00072E0300072F00072F0500074D" +
        "00074D0200074E0007580300075900075B0200075C00076A0300076B00076C0200076D00077003000771000771020007720007720300077300077402" +
        "000775000777030007780007790200077A00077F030007CA0007EA030007FA0007FA0300084000084002000841000845030008460008470200084800" +
        "0848030008490008490200084A0008530300085400085402000855000855030008560008580200086000086003000861000861070008620008650300" +
        "086600086607000867000867020008680008680300086900086A0200087000088202000883000886030008870008880700088900088D0300088E0008" +
        "8E0200088F00088F03000890000891070008A00008A9030008AA0008AC020008AD0008AD070008AE0008AE020008AF0008B0030008B10008B2020008" +
        "B30008B8030008B90008B9020008BA0008C8030008E20008E207001806001806070018070018070300180A00180A0300180E00180E07001820001878" +
        "0300188000188407001885001886060018870018A8030018AA0018AA0300200C00200C0700200D00200D0300202F00202F070020660020690700A840" +
        "00A8710300A87200A8720100A87300A87307010AC0010AC403010AC5010AC502010AC6010AC607010AC7010AC702010AC8010AC807010AC9010ACA02" +
        "010ACB010ACC07010ACD010ACD01010ACE010AD202010AD3010AD603010AD7010AD701010AD8010ADC03010ADD010ADD02010ADE010AE003010AE101" +
        "0AE102010AE2010AE307010AE4010AE402010AEB010AEE03010AEF010AEF02010B80010B8003010B81010B8102010B82010B8203010B83010B850201" +
        "0B86010B8803010B89010B8902010B8A010B8B03010B8C010B8C02010B8D010B8D03010B8E010B8F02010B90010B9003010B91010B9102010BA9010B" +
        "AC02010BAD010BAE03010BAF010BAF07010D00010D0001010D01010D2103010D22010D2202010D23010D2303010EC2010EC202010EC3010EC403010E" +
        "C6010EC703010F30010F3203010F33010F3302010F34010F4403010F45010F4507010F51010F5303010F54010F5402010F70010F7303010F74010F75" +
        "02010F76010F8103010FB0010FB003010FB1010FB107010FB2010FB303010FB4010FB602010FB7010FB707010FB8010FB803010FB9010FBA02010FBB" +
        "010FBC03010FBD010FBD02010FBE010FBF03010FC0010FC007010FC1010FC103010FC2010FC302010FC4010FC403010FC5010FC807010FC9010FC902" +
        "010FCA010FCA03010FCB010FCB010110BD0110BD070110CD0110CD0701E90001E9430301E94B01E94B06"
}
