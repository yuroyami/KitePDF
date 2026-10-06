package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.text.Hyphenator
import io.github.yuroyami.kitepdf.core.text.hyphen.HyphenPatternSets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Every bundled hyph-utf8 set (#207): its data reaches the trie whole, a language tag finds it,
 * and the engine's handling of case, marks and exceptions matches the reference implementation
 * of tools/generate_hyphenation.py, which produced [HyphenationGolden].
 */
class HyphenationSetsTest {

    private fun hyphenatorOf(id: String): Hyphenator {
        val set = assertNotNull(HyphenPatternSets.byId(id), "set $id")
        fun lines(text: String) = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return Hyphenator(lines(set.patterns), set.minPrefix, set.minSuffix, lines(set.exceptions))
    }

    @Test
    fun every_set_matches_the_reference_implementation() {
        val byId = HashMap<String, Hyphenator>()
        val seen = HashSet<String>()
        var failures = 0
        val report = StringBuilder()
        for (line in HyphenationGolden.lines) {
            val (id, word, expected) = line.split('\t')
            seen.add(id)
            val want = if (expected.isEmpty()) emptyList() else expected.split(',').map { it.toInt() }
            val got = byId.getOrPut(id) { hyphenatorOf(id) }.hyphenate(word)
            if (got != want) {
                failures++
                if (failures <= 20) report.append("$id \"$word\": expected $want, got $got\n")
            }
        }
        assertEquals(0, failures, report.toString())
        assertEquals(HyphenPatternSets.ids.toSet(), seen, "every bundled set has golden words")
    }

    /** The tag that reaches each set whose name is not a plain language subtag. */
    private val tags = mapOf(
        "de-1901" to "de-1901",
        "de-1996" to "de-DE",
        "de-ch-1901" to "de-CH-1901",
        "el-monoton" to "el",
        "el-polyton" to "el-polyton",
        "en-gb" to "en-GB",
        "en-us" to "en-US",
        "fi-x-school" to "fi-x-school",
        "la-x-classic" to "la-x-classic",
        "la-x-liturgic" to "la-x-liturgic",
        "mn-cyrl" to "mn-Cyrl-MN",
        "sh-cyrl" to "sr-Cyrl",
        "sh-latn" to "sr-Latn-RS",
        "zh-latn-pinyin" to "zh-Latn-pinyin",
    )

    @Test
    fun a_language_tag_reaches_every_set() {
        for (id in HyphenPatternSets.ids) {
            val tag = tags[id] ?: id
            assertEquals(listOf(id), Hyphenator.patternSetsFor(tag), "tag $tag")
            assertNotNull(Hyphenator.forLanguage(tag), "tag $tag")
        }
    }

    @Test
    fun bcp47_subtags_pick_the_set() {
        assertEquals(listOf("de-1996"), Hyphenator.patternSetsFor("de-CH"), "modern Swiss spelling is the reformed one")
        assertEquals(listOf("de-1901"), Hyphenator.patternSetsFor("de-AT-1901"))
        assertEquals(listOf("en-gb"), Hyphenator.patternSetsFor("en_IE"))
        assertEquals(listOf("en-us"), Hyphenator.patternSetsFor("en-Latn-US"))
        assertEquals(listOf("nb"), Hyphenator.patternSetsFor("nn-NO"), "Nynorsk and Bokmål share one file")
        assertEquals(listOf("nb"), Hyphenator.patternSetsFor("no"))
        assertEquals(listOf("sh-latn", "sh-cyrl"), Hyphenator.patternSetsFor("sr"), "no script: both alphabets")
        assertEquals(listOf("sh-latn", "sh-cyrl"), Hyphenator.patternSetsFor("bs-BA"))
        assertEquals(listOf("kmr"), Hyphenator.patternSetsFor("ku"))
        assertNull(Hyphenator.patternSetsFor("ku-Arab"), "Sorani has no set")
        assertNull(Hyphenator.patternSetsFor("zh-Hans"), "Chinese is not hyphenated")
        assertNull(Hyphenator.patternSetsFor("cs"), "Czech patterns are GPL only")
        assertNull(Hyphenator.patternSetsFor("th"), "Thai breaks with no visible hyphen")
        // An extension's subtags are no region or variant.
        assertEquals(listOf("en-us"), Hyphenator.patternSetsFor("en-u-rg-gbzzzz"))
        assertEquals(listOf("fi"), Hyphenator.patternSetsFor("fi-FI"))
    }

    @Test
    fun serbian_without_a_script_hyphenates_both_alphabets() {
        val sr = Hyphenator.forLanguage("sr")!!
        assertSame(sr, Hyphenator.forLanguage("sr-RS"))
        assertEquals(hyphenatorOf("sh-latn").hyphenate("ministarstvo"), sr.hyphenate("ministarstvo"))
        assertEquals(hyphenatorOf("sh-cyrl").hyphenate("министарство"), sr.hyphenate("министарство"))
        assertTrue(sr.hyphenate("министарство").isNotEmpty())
    }

    /**
     * The largest set, Hungarian with 63k patterns, stays a few megabytes. A map for each trie node
     * held it in tens of megabytes, a cost the JVM suites' 3 GB heap would never notice (#218).
     */
    @Test
    fun the_largest_set_stays_small() {
        val hu = Hyphenator.forLanguage("hu")!!
        assertTrue(hu.trieBytes < 4_000_000, "Hungarian trie holds ${hu.trieBytes} bytes")
    }

    /** `lowercase()` makes İ two characters; the points must still index the word as given (#616). */
    @Test
    fun turkish_dotted_capital_i_keeps_the_points_in_place() {
        val tr = Hyphenator.forLanguage("tr")!!
        assertEquals(listOf(2, 5), tr.hyphenate("istanbul"))
        assertEquals(listOf(2, 5), tr.hyphenate("İstanbul"))
        assertEquals(listOf(2, 5), tr.hyphenate("İSTANBUL"))
    }
}
