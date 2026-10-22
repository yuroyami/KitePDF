package io.github.yuroyami.kitepdf.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A caret sits on the logical edge of its char in every direction: the start of a right-to-left
 * run is its right edge, and a caret in a vertical line runs across the column (#406).
 */
class KiteCaretTest {

    private fun text(line: KiteTextLine) = KiteStructuredText(listOf(KiteTextBlock(listOf(line))))

    @Test
    fun a_caret_sits_on_the_logical_edge_of_its_char() {
        val ltr = text(KiteTextLine("abcd", KiteRectangle(20.0, 5.0, 60.0, 15.0), doubleArrayOf(20.0, 30.0, 40.0, 50.0, 60.0)))
        val rtl = text(KiteTextLine("abcd", KiteRectangle(20.0, 5.0, 60.0, 15.0), doubleArrayOf(60.0, 50.0, 40.0, 30.0, 20.0)))
        val column = text(KiteTextLine("abcd", KiteRectangle(20.0, 20.0, 30.0, 60.0), doubleArrayOf(20.0, 30.0, 40.0, 50.0, 60.0), vertical = true))
        for ((name, t, start, end) in listOf(Quad("ltr", ltr, 20.0, 60.0), Quad("rtl", rtl, 60.0, 20.0), Quad("column", column, 20.0, 60.0))) {
            val first = t.caretAt(0, after = false)!!
            val last = t.caretAt(3, after = true)!!
            assertEquals(start, first.position, "$name: the start")
            assertEquals(end, last.position, "$name: the end")
            // The inside of the first char lies toward the second, and of the last toward the third.
            assertEquals(t.blocks[0].lines[0].charEdges[1], first.inside, "$name: into the first char")
            assertEquals(t.blocks[0].lines[0].charEdges[3], last.inside, "$name: into the last char")
        }
        val c = column.caretAt(1, after = false)!!
        assertEquals(listOf(20.0, 30.0), listOf(c.from, c.to), "a caret in a column spans the column")
        val h = ltr.caretAt(1, after = false)!!
        assertEquals(listOf(5.0, 15.0), listOf(h.from, h.to), "a caret on a line spans the line")
        assertNull(ltr.caretAt(4, after = false), "no char, no caret")
    }

    private data class Quad(val name: String, val text: KiteStructuredText, val start: Double, val end: Double)
}
