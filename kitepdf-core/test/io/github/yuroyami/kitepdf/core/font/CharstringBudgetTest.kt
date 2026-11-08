package io.github.yuroyami.kitepdf.core.font

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * A glyph program runs within a budget, so a corrupt font becomes an empty glyph and the rest of
 * the page draws (#981, #982).
 */
class CharstringBudgetTest {

    /** One small Type 1 or Type 2 integer operand, -107 to 107. */
    private fun n(v: Int): Byte = (v + 139).toByte()

    /** Subroutine k calls subroutine k + 1 ten times; the last draws one line. [bias] maps an index to its operand. */
    private fun fanOut(levels: Int, bias: Int): List<ByteArray> = List(levels) { k ->
        if (k == levels - 1) byteArrayOf(n(10), n(0), 5, 11)
        else ByteArray(21) { i -> if (i == 20) 11 else if (i % 2 == 0) n(k + 1 - bias) else 10 }
    }

    private fun main(bias: Int, vararg head: Byte): ByteArray =
        head + ByteArray(20) { i -> if (i % 2 == 0) n(0 - bias) else 10 } + byteArrayOf(14)

    @Test
    fun aType1SeacThatNamesItsOwnGlyphDrawsOnceAndStops() {
        // 0 500 hsbw 0 0 0 65 65 seac, where code 65 resolves to this same glyph.
        val cs = byteArrayOf(n(0), 248.toByte(), 136.toByte(), 13, n(0), n(0), n(0), 204.toByte(), 204.toByte(), 12, 6)
        Type1CharstringInterpreter(cs, emptyList()) { cs }.interpret()
    }

    @Test
    fun subroutinesThatFanOutEndAsAnEmptyGlyph() {
        val start = TimeSource.Monotonic.markNow()
        // Nine levels of ten calls would be 10^9 lines.
        val type1 = Type1CharstringInterpreter(main(0, n(0), 248.toByte(), 136.toByte(), 13), fanOut(9, 0)).interpret()
        assertTrue(type1.segments.isEmpty(), "Type 1: ${type1.segments.size} segments")
        val type2 = CharstringInterpreter(main(107, n(0), n(0), 21), fanOut(9, 107), emptyList(), 0.0, 0.0).interpret()
        assertTrue(type2.segments.isEmpty(), "Type 2: ${type2.segments.size} segments")
        assertTrue(start.elapsedNow().inWholeSeconds < 10, "took ${start.elapsedNow()}")
    }

    @Test
    fun aProgramWithinTheBudgetStillDraws() {
        // Three levels: 1,000 lines.
        val type1 = Type1CharstringInterpreter(main(0, n(0), 248.toByte(), 136.toByte(), 13), fanOut(3, 0)).interpret()
        assertTrue(type1.segments.size >= 1000, "Type 1: ${type1.segments.size} segments")
        val type2 = CharstringInterpreter(main(107, n(0), n(0), 21), fanOut(3, 107), emptyList(), 0.0, 0.0).interpret()
        assertTrue(type2.segments.size >= 1000, "Type 2: ${type2.segments.size} segments")
    }
}
