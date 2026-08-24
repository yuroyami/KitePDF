package io.github.yuroyami.kitepdf.cbz

/**
 * Filename order a human expects: digit runs compare by value (page2 before
 * page10), everything else case-insensitively. Ties (p2 vs p002) fall back to
 * plain string order so the sort stays total and deterministic.
 */
internal object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val (na, ni) = digitRun(a, i)
                val (nb, nj) = digitRun(b, j)
                if (na != nb) return na.compareTo(nb)
                i = ni
                j = nj
            } else {
                val d = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (d != 0) return d
                i++
                j++
            }
        }
        val d = (a.length - i).compareTo(b.length - j)
        if (d != 0) return d
        return a.compareTo(b)
    }

    /** The numeric value of the digit run starting at [from], and its end. */
    private fun digitRun(s: String, from: Int): Pair<Long, Int> {
        var v = 0L
        var k = from
        while (k < s.length && s[k].isDigit()) {
            if (v < Long.MAX_VALUE / 16) v = v * 10 + (s[k] - '0')
            k++
        }
        return v to k
    }
}
