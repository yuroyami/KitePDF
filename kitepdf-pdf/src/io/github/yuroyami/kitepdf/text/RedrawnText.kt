package io.github.yuroyami.kitepdf.text

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Text that a page draws twice in one place reads once (#642, #643).
 *
 * A producer can draw a label a second time over itself. A fake bold fills the label and then
 * strokes it, or fills it again a fraction of a point to the side, and a halo strokes it wide in
 * white under the fill. Each pass is a text-showing operator of its own (ISO 32000-1, 9.4.3), and
 * the text layer takes the runs of every render mode (9.3.6, #274), so an aeronautical chart that
 * draws its labels this way read "TARBES, FRANCETARBES, FRANCE".
 *
 * A glyph matches a glyph that an earlier run drew, and that stays, when the two have the same
 * text, the same size within a tenth, the same direction, and origins less than a tenth of an em
 * apart on each axis. That is MuPDF's test for fake bold (`check_for_fake_bold` with
 * `FAKE_BOLD_MAX_DIST` in stext-device.c), which MuPDF runs when it collects styles. A glyph that
 * matches one in the same font drops out, so a pass may split its text into other runs than the
 * first pass did. A glyph that matches only in another font drops out only when its whole run
 * does: two fonts with other widths drift apart along a label, and an OCR layer in a font of its
 * own lies close to the text it covers but not on it, so half of such a run would read as noise.
 *
 * Unlike MuPDF, a space never matches. It paints nothing, so a producer can set two spaces of
 * one line a hair apart, as a right-to-left line does around a dot leader, and the second is no
 * second pass. A space drops out only with the glyph before it in its run. MuPDF also compares
 * no sizes, so it drops the first letter of a large label that starts where a small one does,
 * and both labels stay here. The glyphs of one run never match each other, so a double letter
 * in condensed type stays.
 */
internal object RedrawnText {

    /**
     * [runs] in their order, without each glyph that an earlier run drew in its place. A run that
     * loses glyphs splits around them, and a piece with nothing but spaces left drops out. Runs
     * compare only within one marked-content sequence, so each sequence of a tagged page keeps
     * its own text.
     */
    fun drop(runs: List<TextCollectorCanvas.TextRun>): List<TextCollectorCanvas.TextRun> {
        if (runs.size < 2) return runs
        var glyphCount = 0
        for (run in runs) glyphCount += run.glyphs.size
        val seen = Grid(glyphCount)
        val out = ArrayList<TextCollectorCanvas.TextRun>(runs.size)
        var probe: Probe? = null
        // One run at a time, in arrays grown to the longest run.
        var pens = DoubleArray(64)
        var blanks = BooleanArray(64)
        var marks = arrayOfNulls<Mark>(64)
        var matches = arrayOfNulls<Match>(64)
        var keep = BooleanArray(64)
        for (run in runs) {
            val m = run.textMatrix
            val glyphs = run.glyphs
            val n = glyphs.size
            // MuPDF's size of a glyph: the expansion of its text rendering matrix.
            val size = abs(run.fontSize) * sqrt(abs(m.a * m.d - m.b * m.c))
            val length = sqrt(m.a * m.a + m.b * m.b)
            if (n == 0 || !size.isFinite() || size <= 0.0 || !length.isFinite() || length <= 0.0) {
                out += run
                continue
            }
            val ux = m.a / length
            val uy = m.b / length
            // Runs of one size and direction share their size classes and cells.
            var p = probe
            if (p == null || p.size != size || p.ux != ux || p.uy != uy) {
                p = Probe(size, ux, uy)
                probe = p
            }
            if (n + 1 > pens.size) {
                val capacity = max(n + 1, 2 * pens.size)
                pens = DoubleArray(capacity)
                blanks = BooleanArray(capacity)
                marks = arrayOfNulls(capacity)
                matches = arrayOfNulls(capacity)
                keep = BooleanArray(capacity)
            }
            // Where each glyph starts along the run, as TextRun.toSpan advances.
            pens[0] = 0.0
            var withText = 0
            var matched = 0
            for (k in 0 until n) {
                val g = glyphs[k]
                pens[k + 1] = pens[k] + g.advanceWidth * run.fontSize / 1000.0 + g.advanceAdjust
                marks[k] = null
                matches[k] = null
                blanks[k] = blank(g.text)
                if (blanks[k]) continue
                withText++
                val x = m.transformX(pens[k], 0.0)
                val y = m.transformY(pens[k], 0.0)
                if (!x.isFinite() || !y.isFinite()) continue
                val mark = Mark(g.text, run.mcid, x, y, size, ux, uy, run.fontSpec)
                marks[k] = mark
                val match = p.drawnBefore(seen, mark)
                matches[k] = match
                if (match != null) matched++
            }
            val whole = withText > 0 && matched == withText
            var lost = 0
            for (k in 0 until n) {
                keep[k] = when (matches[k]) {
                    // A space, or a glyph without text, goes with the glyph before it.
                    null -> !blanks[k] || k == 0 || keep[k - 1]
                    Match.SAME_FONT -> false
                    Match.OTHER_FONT -> !whole
                }
                if (!keep[k]) lost++
            }
            // Only later runs compare with this one.
            for (k in 0 until n) {
                val mark = marks[k] ?: continue
                if (keep[k]) p.remember(seen, mark)
            }
            if (lost == 0) {
                out += run
                continue
            }
            // Each piece that stays starts where its first glyph does.
            var first = -1
            for (k in 0..n) {
                val stays = k < n && keep[k]
                if (stays && first < 0) first = k
                if (stays || first < 0) continue
                if ((first until k).any { !blanks[it] }) {
                    out += run.copy(
                        glyphs = glyphs.subList(first, k).toList(),
                        textMatrix = m.concat(KiteMatrix.translation(pens[first], 0.0)),
                    )
                }
                first = -1
            }
        }
        return out
    }

    /** Whether [text] is empty or white space alone, as the text of a space glyph is. */
    internal fun blank(text: String): Boolean {
        for (i in text.indices) if (!text[i].isWhitespace()) return false
        return true
    }

    /** How a glyph matches a glyph of an earlier run. */
    private enum class Match { SAME_FONT, OTHER_FONT }

    /**
     * A glyph with text that a run drew: its text and sequence, its origin, size, baseline
     * direction and font on the page, and once it stays, the next glyph filed under its key.
     */
    private class Mark(
        val text: String,
        val mcid: Int?,
        val x: Double,
        val y: Double,
        val size: Double,
        val ux: Double,
        val uy: Double,
        val font: FontSpec,
    ) {
        val textHash = text.hashCode()
        var next: Mark? = null
    }

    /** The size classes and cells that the glyphs of one size and direction file under and look in. */
    private class Probe(val size: Double, val ux: Double, val uy: Double) {
        val reach = MAX_DIST * size
        val own = sizeClass(size)
        val ownScale = 1.0 / cellSize(own)

        // A glyph whose size is within a tenth of this one's has a class in this range.
        val lowest = sizeClass(size * (1.0 - MAX_DIST))
        val scales = DoubleArray(sizeClass(size / (1.0 - MAX_DIST)) - lowest + 1) { 1.0 / cellSize(lowest + it) }

        /** How a run before this one drew [mark], by the rules of [RedrawnText], or null when none did. */
        fun drawnBefore(seen: Grid, mark: Mark): Match? {
            var found: Match? = null
            var budget = MAX_CANDIDATES
            for (c in scales.indices) {
                val scale = scales[c]
                // A cell is wider than twice the reach, so the reach spans two cells at most.
                val left = floor((mark.x - reach) * scale).toInt()
                val bottom = floor((mark.y - reach) * scale).toInt()
                val right = min(floor((mark.x + reach) * scale).toInt(), left + 1)
                val top = min(floor((mark.y + reach) * scale).toInt(), bottom + 1)
                for (i in left..right) {
                    for (j in bottom..top) {
                        var e = seen[cellKey(mark, lowest + c, i, j)]
                        while (e != null) {
                            if (--budget < 0) return found
                            if (abs(e.x - mark.x) < reach && abs(e.y - mark.y) < reach && e.text == mark.text &&
                                e.mcid == mark.mcid && abs(e.size - mark.size) <= MAX_DIST * max(e.size, mark.size) &&
                                e.ux * mark.ux + e.uy * mark.uy >= SAME_DIRECTION
                            ) {
                                if (e.font == mark.font) return Match.SAME_FONT
                                found = Match.OTHER_FONT
                            }
                            e = e.next
                        }
                    }
                }
            }
            return found
        }

        /** Files [mark] in the cell of its own class, where later glyphs of a size near its own look. */
        fun remember(seen: Grid, mark: Mark) =
            seen.add(cellKey(mark, own, floor(mark.x * ownScale).toInt(), floor(mark.y * ownScale).toInt()), mark)
    }

    /**
     * The glyphs that stay, by a hash of their cell: open addressing over plain arrays, so a
     * lookup allocates nothing, on the JVM and in JavaScript alike. Two cells with one hash share
     * a list, which costs a comparison and changes no result, since [Probe.drawnBefore] checks
     * each glyph of a list in full.
     */
    private class Grid(glyphs: Int) {
        // Room for every glyph at half load, so the table rarely grows.
        private var keys = IntArray(capacityFor(glyphs))
        private var heads = arrayOfNulls<Mark>(keys.size)
        private var used = 0

        operator fun get(key: Int): Mark? {
            val mask = keys.size - 1
            var i = key and mask
            while (true) {
                val k = keys[i]
                if (k == key) return heads[i]
                if (k == 0) return null
                i = (i + 1) and mask
            }
        }

        fun add(key: Int, mark: Mark) {
            if (2 * (used + 1) > keys.size) grow()
            val mask = keys.size - 1
            var i = key and mask
            while (true) {
                val k = keys[i]
                if (k == key) {
                    mark.next = heads[i]
                    heads[i] = mark
                    return
                }
                if (k == 0) {
                    keys[i] = key
                    heads[i] = mark
                    used++
                    return
                }
                i = (i + 1) and mask
            }
        }

        private fun grow() {
            val oldKeys = keys
            val oldHeads = heads
            keys = IntArray(oldKeys.size * 2)
            heads = arrayOfNulls(oldKeys.size * 2)
            val mask = keys.size - 1
            for (s in oldKeys.indices) {
                val key = oldKeys[s]
                if (key == 0) continue
                var i = key and mask
                while (keys[i] != 0) i = (i + 1) and mask
                keys[i] = key
                heads[i] = oldHeads[s]
            }
        }
    }

    /** The smallest power of two, of 64 slots at least, that holds [glyphs] at half load. */
    private fun capacityFor(glyphs: Int): Int {
        var capacity = 64
        while (capacity < 2 * glyphs && capacity < 1 shl 30) capacity = capacity shl 1
        return capacity
    }

    /**
     * A hash of the cell [x], [y] in the grid of [sizeClass], for the text and sequence of [mark]:
     * the parts weighed by odd constants, then MurmurHash3's final mix. Never 0, which marks an
     * empty slot of a [Grid].
     */
    private fun cellKey(mark: Mark, sizeClass: Int, x: Int, y: Int): Int {
        var h = mark.textHash * 0x5bd1e995 + (mark.mcid ?: -1) * 0x27d4eb2f + sizeClass * 0x165667b1 +
            x * 0x1b873593 + y * 0x2545f491
        h = (h xor (h ushr 16)) * MIX_1
        h = (h xor (h ushr 13)) * MIX_2
        h = h xor (h ushr 16)
        return if (h == 0) 1 else h
    }

    /** Size classes an octave apart, so two sizes within a tenth of each other are one or two classes. */
    private fun sizeClass(size: Double): Int = floor(ln(size) / LN_2).toInt()

    /**
     * The cell of a size class: the size where the class starts. The largest glyph that looks in a
     * class is the size where the class ends divided by 0.9, and a cell is wider than twice its
     * reach. A coordinate far off the page saturates to the last cell, which only shares a cell.
     */
    private fun cellSize(sizeClass: Int): Double = 2.0.pow(sizeClass)

    /** MuPDF's `FAKE_BOLD_MAX_DIST`: how far two passes of one glyph may lie apart on each axis, in ems. */
    internal const val MAX_DIST = 0.1

    /** The cosine above which two baselines run the same way, MuPDF's test for one line. */
    internal const val SAME_DIRECTION = 0.999

    /**
     * How many filed glyphs one lookup reads at most. A cell holds a glyph or two of one text,
     * so only a page built to defeat the grid reaches this, and its lookups stay bounded.
     */
    private const val MAX_CANDIDATES = 64

    private val LN_2 = ln(2.0)

    private const val MIX_1 = 0x85ebca6bL.toInt()
    private const val MIX_2 = 0xc2b2ae35L.toInt()
}
