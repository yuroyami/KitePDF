package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.GsubGlyph

/**
 * The Arabic fallback for a font with no joining features (#636): each letter takes the glyph of
 * its presentation form that the font maps in its cmap, Arabic Presentation Forms-A and -B
 * (U+FB50 to U+FDFF, U+FE70 to U+FEFF), and lam with alef takes the lam-alef ligature, as
 * HarfBuzz's `hb-ot-shaper-arabic-fallback` builds its lookups from the same characters.
 */
internal object ArabicFallback {

    /**
     * Applies the fallback to [glyphs], whose characters are [cps] and whose joining forms
     * [forms] gives by glyph index. A form the font has no glyph for keeps the letter's own glyph.
     */
    fun apply(glyphs: MutableList<GsubGlyph>, cps: List<Int>, forms: List<ArabicJoining.Form?>, gidFor: (Int) -> Int) {
        val codes = cps.toMutableList()
        val shapes = forms.toMutableList()
        var i = 0
        while (i < glyphs.size) {
            val cp = codes[i]
            val form = shapes[i]
            // Lam and an alef make one glyph: isolated after an initial lam, final after a medial one.
            if (cp == LAM && (form == ArabicJoining.Form.INIT || form == ArabicJoining.Form.MEDI) && i + 1 < glyphs.size && codes[i + 1] in ALEFS) {
                val ligature = ligatures[key(codes[i + 1], if (form == ArabicJoining.Form.INIT) 'S' else 'F')]
                val gid = ligature?.let(gidFor) ?: 0
                if (gid != 0) {
                    glyphs[i].gid = gid
                    glyphs.removeAt(i + 1)
                    codes.removeAt(i + 1)
                    shapes.removeAt(i + 1)
                    i++
                    continue
                }
            }
            val letter = when (form) {
                ArabicJoining.Form.ISOL -> 'S'
                ArabicJoining.Form.INIT -> 'I'
                ArabicJoining.Form.MEDI -> 'M'
                ArabicJoining.Form.FINA -> 'F'
                else -> null
            }
            val presentation = letter?.let { singles[key(cp, it)] }
            val gid = presentation?.let(gidFor) ?: 0
            if (gid != 0) glyphs[i].gid = gid
            i++
        }
    }

    private const val LAM = 0x0644
    private val ALEFS = setOf(0x0622, 0x0623, 0x0625, 0x0627)

    private fun key(cp: Int, form: Char): Long = (cp.toLong() shl 8) or form.code.toLong()

    private fun parse(table: String): Map<Long, Int> = table.trim().split(' ').associate { entry ->
        key(entry.substring(0, 4).toInt(16), entry[4]) to entry.substring(5).toInt(16)
    }

    /**
     * Each letter and form, with the presentation form that the Unicode Character Database 15.1
     * decomposes to it: the letter in hexadecimal, S, I, M or F for isolated, initial, medial or
     * final, and the presentation form. The first of two presentation forms wins, from U+FE70 on.
     */
    private val singles: Map<Long, Int> by lazy {
        parse(
            "0621SFE80 0622FFE82 0622SFE81 0623FFE84 0623SFE83 0624FFE86 0624SFE85 0625FFE88 0625SFE87 0626FFE8A 0626IFE8B " +
            "0626MFE8C 0626SFE89 0627FFE8E 0627SFE8D 0628FFE90 0628IFE91 0628MFE92 0628SFE8F 0629FFE94 0629SFE93 062AFFE96 " +
            "062AIFE97 062AMFE98 062ASFE95 062BFFE9A 062BIFE9B 062BMFE9C 062BSFE99 062CFFE9E 062CIFE9F 062CMFEA0 062CSFE9D " +
            "062DFFEA2 062DIFEA3 062DMFEA4 062DSFEA1 062EFFEA6 062EIFEA7 062EMFEA8 062ESFEA5 062FFFEAA 062FSFEA9 0630FFEAC " +
            "0630SFEAB 0631FFEAE 0631SFEAD 0632FFEB0 0632SFEAF 0633FFEB2 0633IFEB3 0633MFEB4 0633SFEB1 0634FFEB6 0634IFEB7 " +
            "0634MFEB8 0634SFEB5 0635FFEBA 0635IFEBB 0635MFEBC 0635SFEB9 0636FFEBE 0636IFEBF 0636MFEC0 0636SFEBD 0637FFEC2 " +
            "0637IFEC3 0637MFEC4 0637SFEC1 0638FFEC6 0638IFEC7 0638MFEC8 0638SFEC5 0639FFECA 0639IFECB 0639MFECC 0639SFEC9 " +
            "063AFFECE 063AIFECF 063AMFED0 063ASFECD 0641FFED2 0641IFED3 0641MFED4 0641SFED1 0642FFED6 0642IFED7 0642MFED8 " +
            "0642SFED5 0643FFEDA 0643IFEDB 0643MFEDC 0643SFED9 0644FFEDE 0644IFEDF 0644MFEE0 0644SFEDD 0645FFEE2 0645IFEE3 " +
            "0645MFEE4 0645SFEE1 0646FFEE6 0646IFEE7 0646MFEE8 0646SFEE5 0647FFEEA 0647IFEEB 0647MFEEC 0647SFEE9 0648FFEEE " +
            "0648SFEED 0649FFEF0 0649IFBE8 0649MFBE9 0649SFEEF 064AFFEF2 064AIFEF3 064AMFEF4 064ASFEF1 0671FFB51 0671SFB50 " +
            "0677SFBDD 0679FFB67 0679IFB68 0679MFB69 0679SFB66 067AFFB5F 067AIFB60 067AMFB61 067ASFB5E 067BFFB53 067BIFB54 " +
            "067BMFB55 067BSFB52 067EFFB57 067EIFB58 067EMFB59 067ESFB56 067FFFB63 067FIFB64 067FMFB65 067FSFB62 0680FFB5B " +
            "0680IFB5C 0680MFB5D 0680SFB5A 0683FFB77 0683IFB78 0683MFB79 0683SFB76 0684FFB73 0684IFB74 0684MFB75 0684SFB72 " +
            "0686FFB7B 0686IFB7C 0686MFB7D 0686SFB7A 0687FFB7F 0687IFB80 0687MFB81 0687SFB7E 0688FFB89 0688SFB88 068CFFB85 " +
            "068CSFB84 068DFFB83 068DSFB82 068EFFB87 068ESFB86 0691FFB8D 0691SFB8C 0698FFB8B 0698SFB8A 06A4FFB6B 06A4IFB6C " +
            "06A4MFB6D 06A4SFB6A 06A6FFB6F 06A6IFB70 06A6MFB71 06A6SFB6E 06A9FFB8F 06A9IFB90 06A9MFB91 06A9SFB8E 06ADFFBD4 " +
            "06ADIFBD5 06ADMFBD6 06ADSFBD3 06AFFFB93 06AFIFB94 06AFMFB95 06AFSFB92 06B1FFB9B 06B1IFB9C 06B1MFB9D 06B1SFB9A " +
            "06B3FFB97 06B3IFB98 06B3MFB99 06B3SFB96 06BAFFB9F 06BASFB9E 06BBFFBA1 06BBIFBA2 06BBMFBA3 06BBSFBA0 06BEFFBAB " +
            "06BEIFBAC 06BEMFBAD 06BESFBAA 06C0FFBA5 06C0SFBA4 06C1FFBA7 06C1IFBA8 06C1MFBA9 06C1SFBA6 06C5FFBE1 06C5SFBE0 " +
            "06C6FFBDA 06C6SFBD9 06C7FFBD8 06C7SFBD7 06C8FFBDC 06C8SFBDB 06C9FFBE3 06C9SFBE2 06CBFFBDF 06CBSFBDE 06CCFFBFD " +
            "06CCIFBFE 06CCMFBFF 06CCSFBFC 06D0FFBE5 06D0IFBE6 06D0MFBE7 06D0SFBE4 06D2FFBAF 06D2SFBAE 06D3FFBB1 06D3SFBB0 ",
        )
    }

    /** The lam-alef ligatures: the alef, S or F, and the ligature (U+FEF5 to U+FEFC). */
    private val ligatures: Map<Long, Int> by lazy { parse("0622FFEF6 0622SFEF5 0623FFEF8 0623SFEF7 0625FFEFA 0625SFEF9 0627FFEFC 0627SFEFB") }
}
