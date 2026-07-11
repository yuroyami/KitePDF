package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.text.Bidi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Unicode Bidirectional Algorithm (implicit subset). */
class BidiTest {

    // Hebrew alef/bet/gimel, Arabic alef/beh, Arabic-Indic digit.
    private val heb = intArrayOf(0x05D0, 0x05D1, 0x05D2)
    private val ara = intArrayOf(0x0627, 0x0628)

    private fun cps(s: String) = s.map { it.code }.toIntArray()

    private fun visualOrder(cps: IntArray, base: Int? = null): IntArray {
        val level = Bidi.baseLevel(cps, base)
        return Bidi.reorderVisually(Bidi.resolveLevels(cps, level))
    }

    @Test
    fun classifies_scripts() {
        assertEquals(Bidi.L, Bidi.classify('a'.code))
        assertEquals(Bidi.R, Bidi.classify(0x05D0))
        assertEquals(Bidi.AL, Bidi.classify(0x0627))
        assertEquals(Bidi.EN, Bidi.classify('5'.code))
        assertEquals(Bidi.AN, Bidi.classify(0x0660))
        assertEquals(Bidi.WS, Bidi.classify(' '.code))
    }

    @Test
    fun base_level_from_first_strong() {
        assertEquals(0, Bidi.baseLevel(cps("hello")))
        assertEquals(1, Bidi.baseLevel(heb))
        assertEquals(1, Bidi.baseLevel(cps("123 ") + heb), "numbers aren't strong (P2 skips EN); the first strong char is Hebrew -> RTL")
        assertEquals(1, Bidi.baseLevel(cps("hi"), explicit = 1), "explicit override")
    }

    @Test
    fun pure_ltr_is_identity() {
        val cps = cps("hello world")
        assertContentEquals(Bidi.resolveLevels(cps, 0), IntArray(cps.size) { 0 })
        assertContentEquals(IntArray(cps.size) { it }, visualOrder(cps))
    }

    @Test
    fun numbers_in_ltr_stay_in_place() {
        // "a5b" — the EN resolves to L (W7), all level 0, no reordering.
        assertContentEquals(intArrayOf(0, 1, 2), visualOrder(cps("a5b")))
    }

    @Test
    fun hebrew_run_in_ltr_paragraph_reverses() {
        // "abc" + אבג  → Latin stays, Hebrew reverses: a b c ג ב א
        val cps = cps("abc") + heb
        assertContentEquals(intArrayOf(0, 1, 2, 5, 4, 3), visualOrder(cps, base = 0))
    }

    @Test
    fun arabic_run_in_ltr_paragraph_reverses() {
        val cps = cps("x") + ara + cps("y") // x <alef><beh> y
        // Arabic (indices 1,2) reverse; Latin x,y stay.
        assertContentEquals(intArrayOf(0, 2, 1, 3), visualOrder(cps, base = 0))
    }

    @Test
    fun rtl_paragraph_puts_first_logical_char_on_the_right() {
        // RTL base, "אבג" then "abc": rightmost visual char is the first logical (alef).
        val cps = heb + cps("abc")
        val order = visualOrder(cps, base = 1)
        assertEquals(0, order.last(), "alef (logical 0) is rightmost")
        assertEquals(3, order.first(), "Latin 'a' (logical 3) is leftmost")
    }
}
