package io.github.yuroyami.kitepdf.core.text

import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.text.hyphen.HyphenPatternSets
import io.github.yuroyami.kitepdf.core.withLock

/**
 * Knuth-Liang hyphenation (the TeX algorithm). Given a set of language patterns,
 * finds the valid hyphenation points inside a word so a justified line-breaker
 * can split long words. The pattern *data* is the language-specific part; the
 * bundled sets (see [forLanguage]) are the TeX `hyph-*` pattern files of the
 * hyph-utf8 project, with their exception lists, for every language whose
 * licence lets an Apache-2.0 library carry them (#207).
 *
 * Patterns are compiled into a trie so lookup is O(word length x max pattern
 * length) regardless of pattern-set size. The trie lives in flat arrays, a
 * node's edges sorted for a binary search, because a map for each node would
 * hold the 63k patterns of Hungarian in tens of megabytes of heap.
 */
public class Hyphenator internal constructor(
    patterns: List<String>,
    private val minPrefix: Int,
    private val minSuffix: Int,
    exceptions: List<String>,
) {
    public constructor(
        patterns: List<String>,
        minPrefix: Int = 2,
        minSuffix: Int = 3,
    ) : this(patterns, minPrefix, minSuffix, emptyList())

    // The edges of node n are edgeChar/edgeNode[firstEdge[n] until firstEdge[n + 1]], sorted by char.
    private val firstEdge: IntArray
    private val edgeChar: CharArray
    private val edgeNode: IntArray
    // Where node n's points start in pointData (their count first), or -1 where no pattern ends.
    private val nodePoints: IntArray
    private val pointData: ByteArray

    /** TeX's `\hyphenation` words, lowered, with their break points; they replace the patterns. */
    private val exceptionBreaks = HashMap<String, IntArray>()

    init {
        val keys = ArrayList<String>(patterns.size)
        val values = ArrayList<ByteArray>(patterns.size)
        for (pattern in patterns) {
            val letters = StringBuilder()
            val points = ArrayList<Byte>().apply { add(0) }
            for (c in pattern) {
                if (c in '0'..'9') points[points.size - 1] = (c - '0').toByte()
                else { letters.append(c); points.add(0) }
            }
            if (letters.isEmpty()) continue
            keys.add(letters.toString())
            values.add(points.toByteArray())
        }
        // Sorted, every node's patterns are one run, the one that ends there first (a later
        // duplicate wins, as it did when each insert overwrote), then one run for each next char.
        val order = keys.indices.sortedWith { a, b -> keys[a].compareTo(keys[b]).let { if (it != 0) it else a - b } }
        val first = IntBuilder()
        val chars = StringBuilder()
        val targets = IntBuilder()
        val ends = IntBuilder()
        val data = ArrayList<Byte>()
        // Breadth first, so a node's edges are written together: node n is the n-th run queued.
        val runFrom = IntBuilder().apply { add(0) }
        val runTo = IntBuilder().apply { add(order.size) }
        val runDepth = IntBuilder().apply { add(0) }
        var node = 0
        while (node < runFrom.size) {
            var lo = runFrom[node]
            val hi = runTo[node]
            val depth = runDepth[node]
            first.add(chars.length)
            var ending = -1
            while (lo < hi && keys[order[lo]].length == depth) ending = order[lo++]
            if (ending < 0) ends.add(-1) else {
                ends.add(data.size)
                data.add(values[ending].size.toByte())
                for (v in values[ending]) data.add(v)
            }
            var i = lo
            while (i < hi) {
                val c = keys[order[i]][depth]
                var j = i + 1
                while (j < hi && keys[order[j]][depth] == c) j++
                chars.append(c)
                targets.add(runFrom.size)
                runFrom.add(i); runTo.add(j); runDepth.add(depth + 1)
                i = j
            }
            node++
        }
        first.add(chars.length)
        firstEdge = first.toArray()
        edgeChar = CharArray(chars.length) { chars[it] }
        edgeNode = targets.toArray()
        nodePoints = ends.toArray()
        pointData = data.toByteArray()

        for (word in exceptions) {
            val letters = StringBuilder()
            val breaks = ArrayList<Int>()
            for (c in word) {
                if (c == '-') breaks.add(letters.length) else letters.append(c.lowercaseChar())
            }
            if (letters.isNotEmpty()) exceptionBreaks[letters.toString()] = breaks.toIntArray()
        }
    }

    /** The bytes the trie's arrays hold, which is nearly all the heap a hyphenator keeps. */
    internal val trieBytes: Long
        get() = 4L * firstEdge.size + 2L * edgeChar.size + 4L * edgeNode.size + 4L * nodePoints.size + pointData.size

    /** Node [node]'s child along [c], or -1. */
    private fun child(node: Int, c: Char): Int {
        var lo = firstEdge[node]
        var hi = firstEdge[node + 1] - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val m = edgeChar[mid]
            when {
                m < c -> lo = mid + 1
                m > c -> hi = mid - 1
                else -> return edgeNode[mid]
            }
        }
        return -1
    }

    /** Indices in [word] where a hyphen may be inserted (`word[0,i)` + `-` + `word[i,)`). */
    public fun hyphenate(word: String): List<Int> {
        if (word.length < minPrefix + minSuffix) return emptyList()
        // One character for one: `lowercase()` makes İ two, which moved every later break (#616).
        val lower = CharArray(word.length) { word[it].lowercaseChar() }.concatToString()
        val breaks = ArrayList<Int>()
        val exception = exceptionBreaks[lower]
        if (exception != null) {
            for (i in exception) if (i >= minPrefix && i <= word.length - minSuffix) breaks.add(i)
        } else {
            val w = ".$lower."
            val values = IntArray(w.length + 1)
            for (start in w.indices) {
                var node = 0
                var i = start
                while (i < w.length) {
                    node = child(node, w[i])
                    if (node < 0) break
                    i++
                    val at = nodePoints[node]
                    if (at < 0) continue
                    for (k in 0 until pointData[at]) {
                        val v = pointData[at + 1 + k].toInt()
                        if (v > values[start + k]) values[start + k] = v
                    }
                }
            }
            for (i in minPrefix..(word.length - minSuffix)) {
                if (values[i + 1] % 2 == 1) breaks.add(i) // odd priority between word[i-1] and word[i]
            }
        }
        // A combining mark stays on the letter before it, so no break goes before one (#617).
        breaks.removeAll { isMark(word[it]) }
        return breaks
    }

    public companion object {
        /** The full American English set, `hyph-en-us`, with its exceptions. */
        public fun enUs(): Hyphenator = forLanguage("en-US")!!

        /**
         * The bundled hyphenator for a BCP 47 language tag (`"de"`, `"de-DE"`,
         * `"fr_FR"`, `"sr-Latn"`, `"el-polyton"`, ...), or null when no bundled
         * set covers the language. Callers decide what a null means; EPUB layout
         * leaves such a language unhyphenated (#615).
         *
         * The primary subtag picks the language. The rest of the tag picks
         * among its sets where hyph-utf8 has more than one (RFC 5646, 2.2):
         * the region picks British English for the countries that spell that way,
         * the variant `1901` the traditional German spelling (the Swiss one in
         * Switzerland and Liechtenstein), `polyton` polytonic Greek, and the
         * script Latin or Cyrillic Serbian. Serbian, Bosnian and Serbo-Croatian
         * with no script get both alphabets, since their sets share no letter.
         * Private-use subtags pick the sets that hyph-utf8 names with them
         * (`la-x-classic`, `la-x-liturgic`, `fi-x-school`).
         *
         * Instances are shared and built on first use: only the sets a document
         * asks for are read.
         */
        public fun forLanguage(tag: String?): Hyphenator? {
            val ids = patternSetsFor(tag) ?: return null
            val key = ids.joinToString("+")
            return lock.withLock { cache.getOrPut(key) { build(ids) } }
        }

        private val lock = KiteLock()
        private val cache = HashMap<String, Hyphenator>()

        /** The countries whose English follows British spelling and so British hyphenation. */
        private val BRITISH = setOf("gb", "uk", "ie", "au", "nz", "za", "in")

        /** The hyph-utf8 sets for [tag], or null when none is bundled. */
        internal fun patternSetsFor(tag: String?): List<String>? {
            if (tag.isNullOrBlank()) return null
            val parts = tag.trim().lowercase().split('-', '_').filter { it.isNotEmpty() }
            val language = parts.firstOrNull() ?: return null
            // Everything after a singleton is an extension, and after `x` private use (RFC 5646, 2.2.6, 2.2.7).
            val singleton = (1 until parts.size).firstOrNull { parts[it].length == 1 } ?: parts.size
            val subtags = parts.subList(1, singleton)
            val privateUse = if (parts.getOrNull(singleton) == "x") parts.subList(singleton + 1, parts.size) else emptyList()
            val script = subtags.firstOrNull { it.length == 4 && it.all { c -> c in 'a'..'z' } }
            val region = subtags.firstOrNull {
                (it.length == 2 && it.all { c -> c in 'a'..'z' }) || (it.length == 3 && it.all { c -> c in '0'..'9' })
            }
            val variants = subtags.filter { it.length in 5..8 || (it.length == 4 && it[0] in '0'..'9') }
            val id = when (language) {
                "en" -> if (region in BRITISH) "en-gb" else "en-us"
                "de" -> when {
                    "1901" !in variants -> "de-1996"
                    region == "ch" || region == "li" -> "de-ch-1901"
                    else -> "de-1901"
                }
                "el" -> if ("polyton" in variants) "el-polyton" else "el-monoton"
                "fi" -> if ("school" in privateUse) "fi-x-school" else "fi"
                "la" -> when {
                    "classic" in privateUse -> "la-x-classic"
                    "liturgic" in privateUse -> "la-x-liturgic"
                    else -> "la"
                }
                "sr", "sh", "bs", "cnr" -> return when (script) {
                    "latn" -> listOf("sh-latn")
                    "cyrl" -> listOf("sh-cyrl")
                    null -> listOf("sh-latn", "sh-cyrl")
                    else -> null
                }
                "no", "nb", "nn" -> "nb"
                "mn" -> if (script == null || script == "cyrl") "mn-cyrl" else null
                // Kurmanji is written in Latin letters; the Arabic script is Sorani's.
                "ku", "kmr" -> if (script == null || script == "latn") "kmr" else null
                // Chinese is not hyphenated, but its Latin transcription is.
                "zh" -> if (script == "latn" && "pinyin" in variants) "zh-latn-pinyin" else null
                else -> language
            }
            return if (id != null && HyphenPatternSets.byId(id) != null) listOf(id) else null
        }

        private fun build(ids: List<String>): Hyphenator {
            val sets = ids.map { HyphenPatternSets.byId(it)!! }
            return Hyphenator(
                sets.flatMap { lines(it.patterns) },
                sets.first().minPrefix,
                sets.first().minSuffix,
                sets.flatMap { lines(it.exceptions) },
            )
        }

        private fun lines(text: String): List<String> =
            text.split('\n').mapNotNull { line ->
                val t = line.trim()
                t.ifEmpty { null }
            }

        private fun isMark(c: Char): Boolean = when (c.category) {
            CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK -> true
            else -> false
        }
    }
}

/** A growable IntArray, so building a trie of a hundred thousand nodes boxes no Int. */
private class IntBuilder {
    private var items = IntArray(64)
    var size: Int = 0
        private set

    fun add(v: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = v
    }

    operator fun get(i: Int): Int = items[i]

    fun toArray(): IntArray = items.copyOf(size)
}
