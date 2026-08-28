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
            if (ca in '0'..'9' && cb in '0'..'9') {
                val aEnd = digitRunEnd(a, i)
                val bEnd = digitRunEnd(b, j)
                val numeric = compareDigitRuns(a, i, aEnd, b, j, bEnd)
                if (numeric != 0) return numeric
                i = aEnd
                j = bEnd
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

    private fun digitRunEnd(s: String, from: Int): Int {
        var end = from
        while (end < s.length && s[end] in '0'..'9') end++
        return end
    }

    /**
     * Compare decimal runs without parsing them into a fixed-width integer.
     * Comic scanners routinely emit long timestamps and sequence ids; exact
     * length/lexicographic comparison cannot overflow and allocates nothing.
     */
    private fun compareDigitRuns(
        a: String,
        aStart: Int,
        aEnd: Int,
        b: String,
        bStart: Int,
        bEnd: Int,
    ): Int {
        var ai = aStart
        var bi = bStart
        while (ai < aEnd && a[ai] == '0') ai++
        while (bi < bEnd && b[bi] == '0') bi++
        val significantA = aEnd - ai
        val significantB = bEnd - bi
        if (significantA != significantB) return significantA.compareTo(significantB)
        while (ai < aEnd) {
            val order = a[ai].compareTo(b[bi])
            if (order != 0) return order
            ai++
            bi++
        }
        return 0
    }
}
