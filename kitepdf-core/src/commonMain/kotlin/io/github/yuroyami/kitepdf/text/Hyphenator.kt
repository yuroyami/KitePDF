package io.github.yuroyami.kitepdf.text

/**
 * Knuth-Liang hyphenation (the TeX algorithm). Given a set of language patterns,
 * finds the valid hyphenation points inside a word so a justified line-breaker
 * can split long words. The pattern *data* is the language-specific part
 * (`hyph-*.tex` files hold thousands of patterns); this class is the engine, with
 * a small built-in English set for common words. Bundle full pattern files the
 * same way fonts are bundled when higher quality is needed.
 */
class Hyphenator(
    patterns: List<String>,
    private val minPrefix: Int = 2,
    private val minSuffix: Int = 3,
) {
    private class Pattern(val letters: String, val points: IntArray)

    private val compiled: List<Pattern> = patterns.map { compile(it) }

    /** Indices in [word] where a hyphen may be inserted (`word[0,i)` + `-` + `word[i,)`). */
    fun hyphenate(word: String): List<Int> {
        if (word.length < minPrefix + minSuffix) return emptyList()
        val w = ".${word.lowercase()}."
        val values = IntArray(w.length + 1)
        for (p in compiled) {
            var idx = w.indexOf(p.letters)
            while (idx >= 0) {
                for (k in p.points.indices) if (p.points[k] > values[idx + k]) values[idx + k] = p.points[k]
                idx = w.indexOf(p.letters, idx + 1)
            }
        }
        val breaks = ArrayList<Int>()
        for (i in minPrefix..(word.length - minSuffix)) {
            if (values[i + 1] % 2 == 1) breaks.add(i) // odd priority between word[i-1] and word[i]
        }
        return breaks
    }

    private fun compile(pattern: String): Pattern {
        val letters = StringBuilder()
        val points = ArrayList<Int>().apply { add(0) }
        for (c in pattern) {
            if (c in '0'..'9') points[points.size - 1] = c - '0'
            else { letters.append(c); points.add(0) }
        }
        return Pattern(letters.toString(), points.toIntArray())
    }

    companion object {
        /** A small English pattern set for common words. Replace with the full `hyph-en-us` set for quality. */
        fun enUs(): Hyphenator = Hyphenator(EN_US)

        private val EN_US = listOf(
            ".ach4", ".ad4der", ".af1t", ".al3t", ".am5at", ".an5c", ".ang4", ".ani5m",
            "hy3ph", "he2n", "hena4", "1na", "n2at", "1tio", "2io", "o2n", "1co", "2om",
            "1ti", "2it", "put3", "com5pu", "4te", "te2r", "1ci", "2iz", "a1na", "1ly",
            "2ur", "1ma", "1mi", "1mo", "1pe", "per2", "1re", "1ro", "1sa", "1si", "1su",
            "1ta", "1ti", "1to", "2ent", "3ment", "2tion", "1er", "2er.", "in1", "1in",
            "der5iv", "de4riva", "4tive", "1va", "1ve", "1vi", "ho1", "5graph",
        )
    }
}
