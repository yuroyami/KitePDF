package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.GsubGlyph

/**
 * The Thai fallback for a font with no GSUB for Thai (#637): a tone mark or a vowel above or below
 * takes the Private Use Area variant that such fonts carry, lowered where no upper vowel is under
 * it, moved left after a tall consonant, and a consonant drops its descender before a lower vowel,
 * as HarfBuzz's `do_thai_pua_shaping` does with the same tables and state machines.
 */
internal object ThaiPuaFallback {

    private enum class Consonant { NC, AC, RC, DC, NONE }
    private enum class Mark { AV, BV, T, NONE }
    private enum class Action { NOP, SD, SL, SDL, RD }

    private fun consonant(u: Int): Consonant = when (u) {
        0x0E1B, 0x0E1D, 0x0E1F -> Consonant.AC
        0x0E0D, 0x0E10 -> Consonant.RC
        0x0E0E, 0x0E0F -> Consonant.DC
        in 0x0E01..0x0E2E -> Consonant.NC
        else -> Consonant.NONE
    }

    private fun mark(u: Int): Mark = when (u) {
        0x0E31, in 0x0E34..0x0E37, 0x0E47, in 0x0E4D..0x0E4E -> Mark.AV
        in 0x0E38..0x0E3A -> Mark.BV
        in 0x0E48..0x0E4C -> Mark.T
        else -> Mark.NONE
    }

    /** An edge of a state machine: what it does to the mark, and the next state. */
    private class Edge(val action: Action, val next: Int)

    // Rows are states, columns AV, BV, T, as hb-ot-shaper-thai.cc lists them.
    private val above = arrayOf(
        arrayOf(Edge(Action.NOP, 3), Edge(Action.NOP, 0), Edge(Action.SD, 3)),
        arrayOf(Edge(Action.SL, 2), Edge(Action.NOP, 1), Edge(Action.SDL, 2)),
        arrayOf(Edge(Action.NOP, 3), Edge(Action.NOP, 2), Edge(Action.SL, 3)),
        arrayOf(Edge(Action.NOP, 3), Edge(Action.NOP, 3), Edge(Action.NOP, 3)),
    )
    private val below = arrayOf(
        arrayOf(Edge(Action.NOP, 0), Edge(Action.NOP, 2), Edge(Action.NOP, 0)),
        arrayOf(Edge(Action.NOP, 1), Edge(Action.RD, 2), Edge(Action.NOP, 1)),
        arrayOf(Edge(Action.NOP, 2), Edge(Action.SD, 2), Edge(Action.NOP, 2)),
    )

    private fun aboveStart(c: Consonant): Int = when (c) {
        Consonant.AC -> 1
        Consonant.NONE -> 3
        else -> 0
    }

    private fun belowStart(c: Consonant): Int = when (c) {
        Consonant.RC -> 1
        Consonant.DC, Consonant.NONE -> 2
        else -> 0
    }

    private val SD = mapOf(0x0E48 to 0xF70A, 0x0E49 to 0xF70B, 0x0E4A to 0xF70C, 0x0E4B to 0xF70D, 0x0E4C to 0xF70E, 0x0E38 to 0xF718, 0x0E39 to 0xF719, 0x0E3A to 0xF71A)
    private val SDL = mapOf(0x0E48 to 0xF705, 0x0E49 to 0xF706, 0x0E4A to 0xF707, 0x0E4B to 0xF708, 0x0E4C to 0xF709)
    private val SL = mapOf(
        0x0E48 to 0xF713, 0x0E49 to 0xF714, 0x0E4A to 0xF715, 0x0E4B to 0xF716, 0x0E4C to 0xF717, 0x0E31 to 0xF710,
        0x0E34 to 0xF701, 0x0E35 to 0xF702, 0x0E36 to 0xF703, 0x0E37 to 0xF704, 0x0E47 to 0xF712, 0x0E4D to 0xF711,
    )
    private val RD = mapOf(0x0E0D to 0xF70F, 0x0E10 to 0xF700)

    /** Applies the fallback to [glyphs], whose characters are [cps], with the font's [gidFor]. */
    fun apply(glyphs: List<GsubGlyph>, cps: List<Int>, gidFor: (Int) -> Int) {
        var aboveState = 3
        var belowState = 2
        var base = -1
        for (i in glyphs.indices) {
            val u = cps[i]
            val m = mark(u)
            if (m == Mark.NONE) {
                val c = consonant(u)
                aboveState = aboveStart(c)
                belowState = belowStart(c)
                base = i
                continue
            }
            val a = above[aboveState][m.ordinal]
            val b = below[belowState][m.ordinal]
            aboveState = a.next
            belowState = b.next
            val action = if (a.action != Action.NOP) a.action else b.action
            val at = if (action == Action.RD) base else i
            if (at < 0) continue
            val table = when (action) {
                Action.SD -> SD
                Action.SDL -> SDL
                Action.SL -> SL
                Action.RD -> RD
                Action.NOP -> continue
            }
            val pua = table[cps[at]] ?: continue
            val gid = gidFor(pua)
            if (gid != 0) glyphs[at].gid = gid
        }
    }
}
