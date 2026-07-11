package io.github.yuroyami.kitepdf.text

import io.github.yuroyami.kitepdf.text.hyphen.HyphDe
import io.github.yuroyami.kitepdf.text.hyphen.HyphEs
import io.github.yuroyami.kitepdf.text.hyphen.HyphFr
import io.github.yuroyami.kitepdf.text.hyphen.HyphIt
import io.github.yuroyami.kitepdf.text.hyphen.HyphNl
import io.github.yuroyami.kitepdf.text.hyphen.HyphPt

/**
 * Knuth-Liang hyphenation (the TeX algorithm). Given a set of language patterns,
 * finds the valid hyphenation points inside a word so a justified line-breaker
 * can split long words. The pattern *data* is the language-specific part; the
 * bundled sets (see [forLanguage]) are the full TeX `hyph-*` pattern files for
 * German, French, Spanish, Italian, Portuguese and Dutch, plus a small built-in
 * English set for common words.
 *
 * Patterns are compiled into a trie so lookup is O(word length x max pattern
 * length) regardless of pattern-set size — the German set alone has ~37k
 * patterns, which the previous scan-every-pattern approach would re-walk per
 * word.
 */
public class Hyphenator(
    patterns: List<String>,
    private val minPrefix: Int = 2,
    private val minSuffix: Int = 3,
) {
    /** Trie node; [points] is non-null where a complete pattern ends. */
    private class Node {
        val children = HashMap<Char, Node>()
        var points: IntArray? = null
    }

    private val root = Node()

    init {
        for (p in patterns) insert(p)
    }

    private fun insert(pattern: String) {
        if (pattern.isEmpty()) return
        val letters = StringBuilder()
        val points = ArrayList<Int>().apply { add(0) }
        for (c in pattern) {
            if (c in '0'..'9') points[points.size - 1] = c - '0'
            else { letters.append(c); points.add(0) }
        }
        var node = root
        for (c in letters) node = node.children.getOrPut(c) { Node() }
        node.points = points.toIntArray()
    }

    /** Indices in [word] where a hyphen may be inserted (`word[0,i)` + `-` + `word[i,)`). */
    public fun hyphenate(word: String): List<Int> {
        if (word.length < minPrefix + minSuffix) return emptyList()
        val w = ".${word.lowercase()}."
        val values = IntArray(w.length + 1)
        for (start in w.indices) {
            var node = root
            var i = start
            while (i < w.length) {
                node = node.children[w[i]] ?: break
                i++
                val pts = node.points ?: continue
                for (k in pts.indices) {
                    if (pts[k] > values[start + k]) values[start + k] = pts[k]
                }
            }
        }
        val breaks = ArrayList<Int>()
        for (i in minPrefix..(word.length - minSuffix)) {
            if (values[i + 1] % 2 == 1) breaks.add(i) // odd priority between word[i-1] and word[i]
        }
        return breaks
    }

    public companion object {
        /** A small English pattern set for common words. Replace with the full `hyph-en-us` set for quality. */
        public fun enUs(): Hyphenator = EN_US_SHARED

        /**
         * The bundled hyphenator for a BCP-47-ish language tag (`"de"`,
         * `"de-DE"`, `"fr_FR"`, ...), matched on the primary subtag. Returns
         * null when no bundled set covers the language — callers should fall
         * back to [enUs] or skip hyphenation as their policy dictates.
         *
         * Instances are shared and built lazily on first use (the German trie
         * alone compiles ~37k patterns).
         */
        public fun forLanguage(tag: String?): Hyphenator? = when (primarySubtag(tag)) {
            "en" -> EN_US_SHARED
            "de" -> DE
            "fr" -> FR
            "es" -> ES
            "it" -> IT
            "pt" -> PT
            "nl" -> NL
            else -> null
        }

        private fun primarySubtag(tag: String?): String? {
            if (tag.isNullOrBlank()) return null
            val t = tag.trim().lowercase()
            val end = t.indexOfFirst { it == '-' || it == '_' }
            return if (end < 0) t else t.substring(0, end)
        }

        private fun parse(text: String): List<String> =
            text.split('\n').mapNotNull { line ->
                val t = line.trim()
                t.ifEmpty { null }
            }

        private val EN_US_SHARED by lazy { Hyphenator(EN_US) }
        private val DE by lazy { Hyphenator(parse(HyphDe.patterns), HyphDe.MIN_PREFIX, HyphDe.MIN_SUFFIX) }
        private val FR by lazy { Hyphenator(parse(HyphFr.patterns), HyphFr.MIN_PREFIX, HyphFr.MIN_SUFFIX) }
        private val ES by lazy { Hyphenator(parse(HyphEs.patterns), HyphEs.MIN_PREFIX, HyphEs.MIN_SUFFIX) }
        private val IT by lazy { Hyphenator(parse(HyphIt.patterns), HyphIt.MIN_PREFIX, HyphIt.MIN_SUFFIX) }
        private val PT by lazy { Hyphenator(parse(HyphPt.patterns), HyphPt.MIN_PREFIX, HyphPt.MIN_SUFFIX) }
        private val NL by lazy { Hyphenator(parse(HyphNl.patterns), HyphNl.MIN_PREFIX, HyphNl.MIN_SUFFIX) }

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
