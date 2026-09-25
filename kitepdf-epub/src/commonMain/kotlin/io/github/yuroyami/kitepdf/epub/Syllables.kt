package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.GsubGlyph

/**
 * What HarfBuzz's syllable-based shapers share (#211, #317): regular patterns over the
 * categories of a word, the scanner that splits a word into syllables by the longest match,
 * the loop over syllables, and the dotted circle a broken syllable gets. The type of a
 * syllable sits in the low four bits of [GsubGlyph.syllable].
 */
internal object Syllables {

    /**
     * A regular pattern over the categories of a word. [ends] takes the positions a match may
     * start at, as bits counted from [start], and gives the positions it may end at. A
     * syllable is at most 63 characters long.
     */
    abstract class Pattern {
        abstract fun ends(cats: IntArray, start: Int, from: Long): Long
    }

    private class One(private val set: Long) : Pattern() {
        override fun ends(cats: IntArray, start: Int, from: Long): Long {
            var out = 0L
            var bits = from
            while (bits != 0L) {
                val j = bits.countTrailingZeroBits()
                bits = bits and (bits - 1)
                val i = start + j
                if (j < 63 && i < cats.size && (set ushr cats[i]) and 1L != 0L) out = out or (1L shl (j + 1))
            }
            return out
        }
    }

    private class Seq(private val parts: Array<out Pattern>) : Pattern() {
        override fun ends(cats: IntArray, start: Int, from: Long): Long {
            var m = from
            for (part in parts) {
                if (m == 0L) return 0L
                m = part.ends(cats, start, m)
            }
            return m
        }
    }

    private class Alt(private val parts: Array<out Pattern>) : Pattern() {
        override fun ends(cats: IntArray, start: Int, from: Long): Long = parts.fold(0L) { m, part -> m or part.ends(cats, start, from) }
    }

    private class Opt(private val part: Pattern) : Pattern() {
        override fun ends(cats: IntArray, start: Int, from: Long): Long = from or part.ends(cats, start, from)
    }

    private class Star(private val part: Pattern) : Pattern() {
        override fun ends(cats: IntArray, start: Int, from: Long): Long {
            var all = from
            var frontier = from
            while (frontier != 0L) {
                frontier = part.ends(cats, start, frontier) and all.inv()
                all = all or frontier
            }
            return all
        }
    }

    /** A set of categories, each below 64, as bits. */
    fun flags(vararg categories: Int): Long = categories.fold(0L) { m, c -> m or (1L shl c) }

    fun cat(vararg categories: Int): Pattern = One(flags(*categories))
    fun seq(vararg parts: Pattern): Pattern = Seq(parts)
    fun alt(vararg parts: Pattern): Pattern = Alt(parts)
    fun opt(part: Pattern): Pattern = Opt(part)
    fun star(part: Pattern): Pattern = Star(part)

    /**
     * Numbers the syllables of [glyphs] as a Ragel scanner does: at each glyph, the longest
     * match of [patterns] wins, the earlier pattern on a tie, and a glyph that no pattern
     * matches is a syllable of type [other] on its own.
     */
    fun find(glyphs: List<GsubGlyph>, category: (GsubGlyph) -> Int, patterns: List<Pair<Pattern, Int>>, other: Int) {
        val cats = IntArray(glyphs.size) { category(glyphs[it]) }
        var i = 0
        var serial = 0
        while (i < cats.size) {
            var length = 0
            var type = other
            for ((pattern, t) in patterns) {
                val longest = 63 - pattern.ends(cats, i, 1L).countLeadingZeroBits()
                if (longest > length) { length = longest; type = t }
            }
            if (length == 0) { length = 1; type = other }
            serial++
            for (k in i until i + length) glyphs[k].syllable = (serial shl 4) or type
            i += length
        }
    }

    /** Calls [action] with the start and end of each syllable of [glyphs]. */
    inline fun forEach(glyphs: List<GsubGlyph>, action: (Int, Int) -> Unit) {
        var start = 0
        while (start < glyphs.size) {
            var end = start + 1
            while (end < glyphs.size && glyphs[end].syllable == glyphs[start].syllable) end++
            action(start, end)
            start = end
        }
    }

    /**
     * A syllable of type [broken], such as a lone vowel sign, gets a dotted circle [circle] to
     * sit on, after a leading glyph of category [repha] when the script has one, as HarfBuzz's
     * hb_syllabic_insert_dotted_circles inserts it. The circle takes [shaperData]. Nothing is
     * inserted when the font has no dotted circle.
     */
    fun insertDottedCircles(
        glyphs: MutableList<GsubGlyph>, circle: Int, broken: Int, repha: Int?, shaperData: Int, category: (GsubGlyph) -> Int,
    ) {
        if (circle <= 0) return
        var i = 0
        var last = -1
        while (i < glyphs.size) {
            val g = glyphs[i]
            if (g.syllable != last && g.syllable and 0xF == broken) {
                last = g.syllable
                if (repha != null) while (i < glyphs.size && glyphs[i].syllable == last && category(glyphs[i]) == repha) i++
                glyphs.add(i, GsubGlyph(circle, g.cluster, g.features).also { it.syllable = last; it.shaperData = shaperData; it.inserted = true })
            }
            i++
        }
    }

    /** Gives every glyph of a syllable the first cluster of it, so that its first glyph carries its whole text. */
    fun mergeClusters(glyphs: MutableList<GsubGlyph>) {
        forEach(glyphs) { start, end ->
            val cluster = (start until end).minOf { glyphs[it].cluster }
            for (i in start until end) glyphs[i].cluster = cluster
        }
    }
}
