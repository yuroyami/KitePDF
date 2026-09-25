package io.github.yuroyami.kitepdf.epub

/**
 * The order a shaper puts combining marks in before GSUB runs (#211): each run of marks
 * sorts by canonical combining class (Unicode 17, 3.11), with the classes HarfBuzz modifies
 * so that fonts see the order they were built for. Hebrew points follow the SBL Hebrew order,
 * Arabic shadda goes before the other marks, and Thai sara u and uu go before phinthu.
 *
 * Only the blocks of the scripts [TextShaper] shapes are covered. A mark outside them has
 * class 0 and keeps its place.
 */
internal object CombiningClass {

    /** Sorts each run of marks in [items] by [modified] class, keeping the order of equal classes. */
    fun <T> reorder(items: MutableList<T>, codePoint: (T) -> Int) {
        var i = 0
        while (i < items.size) {
            if (modified(codePoint(items[i])) == 0) { i++; continue }
            var end = i + 1
            while (end < items.size && modified(codePoint(items[end])) != 0) end++
            if (end - i > 1) {
                val sorted = items.subList(i, end).sortedBy { modified(codePoint(it)) }
                for (k in sorted.indices) items[i + k] = sorted[k]
            }
            i = end
        }
    }

    /** The combining class of [cp] as HarfBuzz modifies it, 0 for a character that is not a mark. */
    fun modified(cp: Int): Int {
        val ccc = canonical(cp)
        return when (ccc) {
            in 10..26 -> HEBREW[ccc - 10]
            in 27..35 -> ARABIC[ccc - 27]
            84 -> 4
            91 -> 5
            103 -> 3
            else -> ccc
        }
    }

    /** Hebrew points 10 to 26, permuted into the SBL Hebrew order as HarfBuzz does. */
    private val HEBREW = intArrayOf(22, 15, 16, 17, 23, 18, 19, 20, 21, 14, 24, 12, 25, 13, 10, 11, 26)

    /** Arabic classes 27 to 35, with shadda moved before the other marks as HarfBuzz does. */
    private val ARABIC = intArrayOf(28, 29, 30, 31, 32, 33, 27, 34, 35)

    /** The canonical combining class of [cp] in the covered blocks (UnicodeData.txt, field 3). */
    private fun canonical(cp: Int): Int = when (cp) {
        in 0x0300..0x036F -> latin(cp)
        in 0x0483..0x0487 -> 230
        in 0x0591..0x05C7 -> hebrew(cp)
        in 0x0610..0x061A -> if (cp <= 0x0617) 230 else cp - 0x0618 + 30
        in 0x064B..0x065F -> arabic(cp)
        0x0670 -> 35
        in 0x06D6..0x06ED -> arabicExtended(cp)
        0x093C, 0x09BC, 0x0A3C, 0x0ABC, 0x0B3C, 0x0C3C, 0x0CBC -> 7
        0x094D, 0x09CD, 0x0A4D, 0x0ACD, 0x0B4D, 0x0BCD, 0x0C4D, 0x0CCD, 0x0D3B, 0x0D3C, 0x0D4D, 0x0DCA -> 9
        0x0951, 0x0953, 0x0954, 0x09FE -> 230
        0x0952 -> 220
        0x0C55 -> 84
        0x0C56 -> 91
        0x0E38, 0x0E39 -> 103
        0x0E3A -> 9
        in 0x0E48..0x0E4B -> 107
        0x0EB8, 0x0EB9 -> 118
        in 0x0EC8..0x0ECB -> 122
        0x1037 -> 7
        in 0x1CD0..0x1CF9 -> vedic(cp)
        0x1039, 0x103A -> 9
        in 0x20D0..0x20DC -> if (cp in 0x20D2..0x20D3 || cp in 0x20D8..0x20DA) 1 else 230
        in 0xA8E0..0xA8F1 -> 230
        in 0xFE20..0xFE2F -> if (cp in 0xFE27..0xFE2D) 220 else 230
        else -> 0
    }

    private fun vedic(cp: Int): Int = when (cp) {
        in 0x1CD0..0x1CD2, 0x1CDA, 0x1CDB, 0x1CE0, 0x1CF4, 0x1CF8, 0x1CF9 -> 230
        0x1CD4, in 0x1CE2..0x1CE8 -> 1
        in 0x1CD5..0x1CD9, in 0x1CDC..0x1CDF, 0x1CED -> 220
        else -> 0
    }

    private fun latin(cp: Int): Int = when (cp) {
        in 0x0300..0x0314 -> 230
        0x0315, 0x031A, 0x0358 -> 232
        in 0x0316..0x0319, in 0x031C..0x0320, in 0x0323..0x0326, in 0x0329..0x0333, in 0x0339..0x033C,
        in 0x0347..0x0349, 0x034D, 0x034E, in 0x0353..0x0356, 0x0359, 0x035A -> 220
        0x031B -> 216
        0x0321, 0x0322, 0x0327, 0x0328 -> 202
        in 0x0334..0x0338 -> 1
        0x0345 -> 240
        0x034F -> 0
        0x035C, 0x035F, 0x0362 -> 233
        0x035D, 0x035E, 0x0360, 0x0361 -> 234
        else -> 230
    }

    private fun hebrew(cp: Int): Int = when (cp) {
        in 0x05B0..0x05B9 -> cp - 0x05B0 + 10
        0x05BA -> 19
        0x05BB -> 20
        0x05BC -> 21
        0x05BD -> 22
        0x05BF -> 23
        0x05C1 -> 24
        0x05C2 -> 25
        0x05C4 -> 230
        0x05C5 -> 220
        0x05C7 -> 18
        0x0591, 0x0596, 0x059B, in 0x05A2..0x05A7, 0x05AA -> 220
        0x059A, 0x05AD -> 222
        0x05AE -> 228
        in 0x0592..0x05AF -> 230
        else -> 0
    }

    private fun arabic(cp: Int): Int = when (cp) {
        in 0x064B..0x0652 -> cp - 0x064B + 27
        0x0655, 0x0656, 0x065C, 0x065F -> 220
        else -> 230
    }

    private fun arabicExtended(cp: Int): Int = when (cp) {
        0x06DD, 0x06DE, 0x06E5, 0x06E6, 0x06E9 -> 0
        0x06E3, 0x06EA, 0x06ED -> 220
        else -> 230
    }
}
