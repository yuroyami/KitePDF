package io.github.yuroyami.kitepdf.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The keys of one field between the reader and its keystroke script (#363). */
class KeystrokePipelineTest {

    @Test
    fun a_refused_letter_in_the_middle_does_not_move_the_digit_typed_after_it() {
        val keys = KeystrokePipeline("123")
        keys.typed(editOf("123", "1x23"))
        keys.typed(editOf("1x23", "1x923"))
        assertEquals("1x923", keys.screen)
        keys.answered(null)
        assertEquals("1923", keys.screen, "the refused x is gone, and the 9 stays where it was typed")
        keys.answered("1923")
        assertEquals("1923", keys.confirmed)
        assertNull(keys.next())
    }

    @Test
    fun a_rewritten_key_moves_the_keys_typed_after_it() {
        val keys = KeystrokePipeline("")
        keys.typed(editOf("", "a"))
        keys.typed(editOf("a", "ab"))
        keys.answered("A")
        assertEquals("Ab", keys.screen)
        keys.answered("AB")
        assertEquals("AB", keys.confirmed)
    }

    @Test
    fun a_deletion_of_a_refused_key_does_nothing() {
        val keys = KeystrokePipeline("12")
        keys.typed(editOf("12", "1x2"))
        keys.typed(editOf("1x2", "12"))
        keys.answered(null)
        assertEquals("12", keys.screen)
        keys.answered("12")
        assertEquals("12", keys.confirmed)
    }

    @Test
    fun each_key_is_asked_against_the_value_the_script_kept() {
        val keys = KeystrokePipeline("123")
        keys.typed(editOf("123", "1x23"))
        keys.typed(editOf("1x23", "1x923"))
        keys.answered(null)
        val next = keys.next()!!
        assertEquals("9", next.change)
        assertEquals(1, next.start, "the 9 goes after the 1 of the kept value")
    }
}
