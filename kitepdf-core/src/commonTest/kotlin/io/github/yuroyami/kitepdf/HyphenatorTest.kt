package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.text.Hyphenator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Knuth-Liang hyphenation engine. */
class HyphenatorTest {

    @Test
    fun controlled_pattern_breaks_where_the_odd_value_is() {
        // "a1b": an odd value between a and b -> break there.
        assertEquals(listOf(2), Hyphenator(listOf("a1b"), minPrefix = 1, minSuffix = 1).hyphenate("cab"))
    }

    @Test
    fun even_values_do_not_break() {
        assertEquals(emptyList(), Hyphenator(listOf("a2b"), minPrefix = 1, minSuffix = 1).hyphenate("cab"))
    }

    @Test
    fun respects_min_prefix_and_suffix() {
        // "x1y" would break at 1, but minPrefix=2 forbids it.
        assertEquals(emptyList(), Hyphenator(listOf("x1y"), minPrefix = 2, minSuffix = 2).hyphenate("xyz"))
        // Too short overall.
        assertEquals(emptyList(), Hyphenator(listOf("a1b")).hyphenate("ab"))
    }

    @Test
    fun english_set_hyphenates_a_common_word() {
        // "hy3ph" gives hy-phenation.
        assertTrue(2 in Hyphenator.enUs().hyphenate("hyphenation"), "hy-phenation")
    }

    @Test
    fun short_word_has_no_breaks() {
        assertEquals(emptyList(), Hyphenator.enUs().hyphenate("cat"))
    }
}
