package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.css.CssValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** calc(), min(), max() and clamp() in a length (CSS Values and Units 4, 10; #930). */
class CssMathTest {

    /** Font 12pt, root 10pt, percentages of 400pt, a 400 by 600 viewport. */
    private fun len(v: String) = CssValues.length(v, 12.0, 10.0, 400.0, 400.0, 600.0)

    @Test
    fun the_four_functions_give_the_length_their_terms_make() {
        assertEquals(75.0, len("calc(100px)"))
        assertEquals(75.0, len("calc(50px + 50px)"))
        assertEquals(75.0, len("max(100px, 10px)"))
        assertEquals(75.0, len("clamp(100px, 5px, 200px)"))
        assertEquals(400.0 - 24.0, len("calc(100% - 2em)"))
        assertEquals(15.0, len("CALC( 2 * 10pt - 5pt )"))
        assertEquals(30.0, len("calc((10pt + 5pt) * 2)"))
        assertEquals(5.0, len("calc(10pt / 2)"))
        assertEquals(20.0, len("min(max(1rem, 20pt), 50%)"))
        assertEquals(15.0, len("clamp(10px, 50vw, 20px)"))
        assertEquals(200.0, len("clamp(10px, 50vw, 2000px)"), "the middle value on a narrow page")
    }

    @Test
    fun a_type_mismatch_or_a_division_by_zero_is_invalid() {
        assertNull(len("calc(10px + 2)"), "a length and a number")
        assertNull(len("calc(10px * 2px)"), "two lengths multiplied")
        assertNull(len("calc(10px / 0)"))
        assertNull(len("calc(10px / 2px)"))
        assertNull(len("calc(2 * 3)"), "a number where a length is wanted")
        assertNull(len("calc(10px +5px)"), "+ needs white space around it")
        assertNull(len("calc(10px"))
        assertNull(len("clamp(1px, 2px)"))
        assertNull(len("calc(" + "(".repeat(100) + "1px" + ")".repeat(100) + ")"), "nesting past any sane style")
    }

    @Test
    fun a_number_reads_plain_or_from_a_math_function() {
        assertEquals(1.5, CssValues.number("calc(1.2 * 1.25)"))
        assertEquals(2.0, CssValues.number("max(1, 2)"))
        assertEquals(1.5, CssValues.number("1.5"))
        assertNull(CssValues.number("calc(1px)"), "a length is not a number")
        assertNull(CssValues.number("calc(1 / 0)"))
    }
}
