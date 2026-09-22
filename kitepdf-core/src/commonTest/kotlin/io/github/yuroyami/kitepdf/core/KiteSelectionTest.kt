package io.github.yuroyami.kitepdf.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Selection support on [KiteStructuredText]: flattened char indexing
 * ([KiteStructuredText.charIndexAt]), range text with line/block separators
 * ([KiteStructuredText.textRange]) and the shared per-line quad walker
 * ([KiteStructuredText.quadsFor]).
 */
class KiteSelectionTest {

    /** Two blocks; block 0 has two lines. 10pt-wide chars, 10pt-tall lines. */
    private fun fixture(): KiteStructuredText {
        fun line(text: String, x: Double, yTop: Double) = KiteTextLine(
            text = text,
            bounds = KiteRectangle(left = x, bottom = yTop, right = x + text.length * 10.0, top = yTop + 10.0),
            charEdges = DoubleArray(text.length + 1) { x + it * 10.0 },
        )
        return KiteStructuredText(
            listOf(
                KiteTextBlock(listOf(line("abc", 0.0, 0.0), line("defg", 0.0, 12.0))),
                KiteTextBlock(listOf(line("hi", 0.0, 40.0))),
            ),
        )
    }

    @Test
    fun char_index_at_hits_exact_chars_and_clamps_within_a_line() {
        val st = fixture()
        assertEquals(0, st.charIndexAt(4.0, 5.0), "first char of line 1")
        assertEquals(2, st.charIndexAt(25.0, 5.0), "third char")
        assertEquals(2, st.charIndexAt(200.0, 5.0), "x past the line end clamps to the last char")
        assertEquals(3, st.charIndexAt(-50.0, 17.0), "x before line 2 clamps to its first char")
        assertEquals(8, st.charIndexAt(15.0, 45.0), "second block's 'i' (x 10..20)")
        assertEquals(7, st.charIndexAt(5.0, 45.0), "second block's 'h' (x 0..10)")
        assertNull(st.charIndexAt(5.0, 30.0), "y between lines hits nothing")
    }

    @Test
    fun text_range_inserts_line_and_block_separators() {
        val st = fixture()
        assertEquals("abc", st.textRange(0, 2))
        assertEquals("bc\nde", st.textRange(1, 4), "line break becomes \\n")
        assertEquals("g\n\nh", st.textRange(6, 7), "block break becomes \\n\\n")
        assertEquals("abc\ndefg\n\nhi", st.textRange(0, 8), "full range matches plainText")
        assertEquals(st.plainText, st.textRange(0, st.charCount - 1))
    }

    @Test
    fun quads_walk_one_rect_per_line() {
        val st = fixture()
        val quads = st.quadsFor(1, 4) // "bc" + "de"
        assertEquals(2, quads.size)
        assertEquals(10.0, quads[0].left)
        assertEquals(30.0, quads[0].right)
        assertEquals(0.0, quads[0].bottom)
        assertEquals(10.0, quads[0].top)
        assertEquals(0.0, quads[1].left)
        assertEquals(20.0, quads[1].right)

        val cross = st.quadsFor(6, 7) // last char of block 0 + first of block 1
        assertEquals(2, cross.size)
        assertEquals(30.0, cross[0].left)
        assertEquals(40.0, cross[0].right)
        assertEquals(0.0, cross[1].left)
        assertEquals(10.0, cross[1].right)
    }

    @Test
    fun right_to_left_edges_preserve_logical_selection_and_positive_quads() {
        val st = KiteStructuredText(listOf(KiteTextBlock(listOf(KiteTextLine(
            text = "אב",
            bounds = KiteRectangle(80.0, 0.0, 100.0, 10.0),
            charEdges = doubleArrayOf(100.0, 90.0, 80.0),
        )))))
        assertEquals(0, st.charIndexAt(95.0, 5.0))
        assertEquals(1, st.charIndexAt(85.0, 5.0))
        assertEquals(0, st.charIndexAt(110.0, 5.0))
        assertEquals(1, st.charIndexAt(70.0, 5.0))
        assertEquals("אב", st.textRange(0, 1))
        assertEquals(KiteRectangle(80.0, 0.0, 100.0, 10.0), st.quadsFor(0, 1).single())
        assertEquals(KiteRectangle(80.0, 0.0, 90.0, 10.0), st.search("ב").single().quads.single())
    }

    @Test
    fun highlights_bound_all_selected_edges_when_positions_change_direction() {
        val st = KiteStructuredText(listOf(KiteTextBlock(listOf(KiteTextLine(
            text = "abc",
            bounds = KiteRectangle(0.0, 0.0, 40.0, 10.0),
            charEdges = doubleArrayOf(10.0, 40.0, 0.0, 20.0),
        )))))
        val bounds = KiteRectangle(0.0, 0.0, 40.0, 10.0)
        assertEquals(bounds, st.quadsFor(0, 2).single())
        assertEquals(bounds, st.search("abc").single().quads.single())
        assertEquals(bounds, st.search("ab").single().quads.single())
    }

    @Test
    fun descending_vertical_edges_hit_and_highlight_in_display_space() {
        val st = KiteStructuredText(listOf(KiteTextBlock(listOf(KiteTextLine(
            text = "ab",
            bounds = KiteRectangle(0.0, 80.0, 10.0, 100.0),
            charEdges = doubleArrayOf(100.0, 90.0, 80.0),
            vertical = true,
        )))))
        assertEquals(0, st.charIndexAt(5.0, 95.0))
        assertEquals(1, st.charIndexAt(5.0, 85.0))
        assertEquals(1, st.charIndexAt(5.0, 70.0))
        assertEquals(KiteRectangle(0.0, 80.0, 10.0, 100.0), st.quadsFor(0, 1).single())
        assertEquals(KiteRectangle(0.0, 80.0, 10.0, 90.0), st.search("b").single().quads.single())
    }
}
