package io.github.yuroyami.kitepdf.epub.css

/** The predefined counter styles that `list-style-type` and `counter()` share (CSS Counter Styles 3, 6). */
internal object CounterStyles {

    /** [n] in [type], with no suffix; a bullet style gives its bullet, and `none` an empty string. */
    fun format(n: Int, type: ListType): String = when (type) {
        ListType.NONE -> ""
        ListType.DISC -> "•"
        ListType.CIRCLE -> "◦"
        ListType.SQUARE -> "▪"
        ListType.DECIMAL -> n.toString()
        ListType.LOWER_ROMAN -> roman(n).lowercase()
        ListType.UPPER_ROMAN -> roman(n)
        ListType.LOWER_ALPHA -> alpha(n).lowercase()
        ListType.UPPER_ALPHA -> alpha(n)
    }

    /** Roman numerals from 1 to 3999; outside that range the decimal number, as the style's fallback is. */
    fun roman(n: Int): String {
        if (n !in 1..3999) return n.toString()
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        val sb = StringBuilder(); var x = n
        for (i in vals.indices) while (x >= vals[i]) { sb.append(syms[i]); x -= vals[i] }
        return sb.toString()
    }

    /** A, B, ... Z, AA, AB: the alphabetic style from 1; below 1 the decimal number. */
    fun alpha(n: Int): String {
        if (n < 1) return n.toString()
        val sb = StringBuilder(); var x = n
        while (x > 0) { x--; sb.append('A' + (x % 26)); x /= 26 }
        return sb.reverse().toString()
    }
}
