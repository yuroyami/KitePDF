package io.github.yuroyami.kitepdf.cbz

import kotlin.test.Test
import kotlin.test.assertEquals

class NaturalOrderTest {

    private fun sorted(vararg names: String) = names.sortedWith(NaturalOrder)

    @Test
    fun digits_compare_by_numeric_value() {
        assertEquals(listOf("page2.jpg", "page10.jpg"), sorted("page10.jpg", "page2.jpg"))
    }

    @Test
    fun case_is_ignored() {
        assertEquals(listOf("Alpha.png", "beta.png"), sorted("beta.png", "Alpha.png"))
    }

    @Test
    fun text_before_digits_still_orders_by_text_first() {
        assertEquals(listOf("a10.png", "b2.png"), sorted("b2.png", "a10.png"))
    }

    @Test
    fun equal_numbers_with_leading_zeros_fall_back_to_string_order() {
        assertEquals(listOf("p002.png", "p2.png"), sorted("p2.png", "p002.png"))
    }

    @Test
    fun folder_prefixes_sort_naturally_too() {
        assertEquals(
            listOf("ch1/p1.png", "ch2/p1.png", "ch10/p1.png"),
            sorted("ch10/p1.png", "ch1/p1.png", "ch2/p1.png"),
        )
    }

    @Test
    fun digit_runs_are_exact_beyond_long_range() {
        assertEquals(
            listOf("p99999999999999999999999999.png", "p100000000000000000000000000.png"),
            sorted("p100000000000000000000000000.png", "p99999999999999999999999999.png"),
        )
    }
}
