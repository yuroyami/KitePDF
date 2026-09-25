package io.github.yuroyami.kitepdf.epub

/**
 * The simple case mappings of a code point (#322): the Basic Multilingual Plane through `Char`,
 * and the cased scripts outside it through the runs of UnicodeData.txt of Unicode 17, which
 * give the same result on every platform.
 */
internal object CaseMapping {

    fun uppercase(cp: Int): Int = if (cp < 0x10000) cp.toChar().uppercaseChar().code else shift(cp, LOWERCASE, -1)

    fun lowercase(cp: Int): Int = if (cp < 0x10000) cp.toChar().lowercaseChar().code else shift(cp, UPPERCASE, 1)

    /** The titlecase of [cp], which differs from its uppercase only for a few digraphs of the BMP. */
    fun titlecase(cp: Int): Int = if (cp < 0x10000) cp.toChar().titlecaseChar().code else uppercase(cp)

    /** True for a lowercase letter, which has an uppercase. */
    fun isLowercase(cp: Int): Boolean = if (cp < 0x10000) cp.toChar().isLowerCase() else uppercase(cp) != cp

    private fun shift(cp: Int, runs: IntArray, sign: Int): Int {
        for (r in 0 until runs.size / 3) if (cp >= runs[r * 3] && cp <= runs[r * 3 + 1]) return cp + sign * runs[r * 3 + 2]
        return cp
    }

    /** The lowercase letters outside the BMP: the first, the last, and how far above its capital each one is. */
    private val LOWERCASE = intArrayOf(
        0x10428, 0x1044F, 40, // Deseret
        0x104D8, 0x104FB, 40, // Osage
        0x10597, 0x105A1, 39, 0x105A3, 0x105B1, 39, 0x105B3, 0x105B9, 39, 0x105BB, 0x105BC, 39, // Vithkuqi
        0x10CC0, 0x10CF2, 64, // Old Hungarian
        0x10D70, 0x10D85, 32, // Garay
        0x118C0, 0x118DF, 32, // Warang Citi
        0x16E60, 0x16E7F, 32, // Medefaidrin
        0x16EBB, 0x16ED3, 27, // Beria Erfe
        0x1E922, 0x1E943, 34, // Adlam
    )

    /** The uppercase letters outside the BMP: the first, the last, and how far below its small letter each one is. */
    private val UPPERCASE = intArrayOf(
        0x10400, 0x10427, 40, // Deseret
        0x104B0, 0x104D3, 40, // Osage
        0x10570, 0x1057A, 39, 0x1057C, 0x1058A, 39, 0x1058C, 0x10592, 39, 0x10594, 0x10595, 39, // Vithkuqi
        0x10C80, 0x10CB2, 64, // Old Hungarian
        0x10D50, 0x10D65, 32, // Garay
        0x118A0, 0x118BF, 32, // Warang Citi
        0x16E40, 0x16E5F, 32, // Medefaidrin
        0x16EA0, 0x16EB8, 27, // Beria Erfe
        0x1E900, 0x1E921, 34, // Adlam
    )
}
