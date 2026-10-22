package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.KiteStructuredText
import io.github.yuroyami.kitepdf.core.KiteTextBlock
import io.github.yuroyami.kitepdf.core.KiteTextLine
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/** One run of glyphs that an SVG draws, with the ids of the elements around it (#523). */
internal class SvgTextRun(
    val glyphs: List<TextGlyph>,
    val fontSize: Double,
    val textToDevice: KiteMatrix,
    val box: KiteRectangle,
    val ids: List<String>,
)

/**
 * The text an [SvgImage] draws, in the space that [SvgImage.textContent] was given (#523).
 *
 * A line is the runs of glyphs along one baseline, as a `<text>` and its `<tspan>`s draw them,
 * with a space where the runs leave a gap. A block is the lines set one under another a line
 * apart, as a paragraph written as one `<text>` per line is. SVG has no paragraphs of its own,
 * so the lines are grouped the way a reader sees them.
 *
 * Rectangles keep the smaller y in [KiteRectangle.bottom], as [KiteStructuredText] does.
 */
public class SvgTextContent private constructor(
    /** The text, in the order the image draws it. */
    public val text: KiteStructuredText,
    private val lineRuns: List<List<SvgTextRun>>,
    private val idBoxes: Map<String, KiteRectangle>,
) {
    /**
     * Where the element with [id] is: one rectangle per line of the text inside it, or the box of
     * what it draws when it draws no text. Empty when no element with that id draws anything.
     */
    public fun rectsOf(id: String): List<KiteRectangle> {
        val rects = lineRuns.mapNotNull { runs ->
            runs.filter { id in it.ids }.map { it.box }.reduceOrNull { a, b -> a.union(b) }
        }
        return rects.ifEmpty { listOfNotNull(idBoxes[id]) }
    }

    /** One run placed on its line: where its text-space x lands along the line and across it. */
    private class Placed(val run: SvgTextRun) {
        private val m = run.textToDevice

        /** A run that goes more up or down than across is a vertical line. */
        val vertical = abs(m.b) > abs(m.a)

        /** Which way the text runs along its line, and which way the next line lies across it. */
        val dir = if (vertical) sign(m.b) else sign(m.a)
        val down = if (vertical) sign(-m.c) else sign(-m.d)

        /** The em, in the device space. */
        val size = run.fontSize * hypot(m.c, m.d)

        fun along(x: Double) = if (vertical) m.b * x + m.f else m.a * x + m.e
        fun across(x: Double) = if (vertical) m.a * x + m.e else m.b * x + m.f
    }

    private class Line(first: Placed) {
        val vertical = first.vertical
        val dir = first.dir
        val down = first.down
        val size = first.size
        val startAcross = first.across(0.0)
        var endAcross = startAcross
        var end = first.along(0.0)
        val runs = ArrayList<SvgTextRun>()
        val chars = StringBuilder()
        val starts = ArrayList<Double>()
        val ends = ArrayList<Double>()

        /** Spaces fold to one, and a line starts with none. */
        fun add(c: Char, start: Double, stop: Double) {
            val space = c.isWhitespace()
            if (space && (chars.isEmpty() || chars.last() == ' ')) return
            chars.append(if (space) ' ' else c)
            starts += start
            ends += stop
        }

        fun continues(p: Placed): Boolean {
            if (p.vertical != vertical || p.dir != dir) return false
            val em = minOf(size, p.size)
            if (abs(p.across(0.0) - endAcross) > 0.25 * em) return false
            return (p.along(0.0) - end) * dir > -0.5 * em
        }

        fun take(p: Placed) {
            val em = minOf(size, p.size)
            if (runs.isNotEmpty() && (p.along(0.0) - end) * dir > 0.2 * em) add(' ', end, p.along(0.0))
            runs += p.run
            var pen = 0.0
            for (g in p.run.glyphs) {
                val advance = g.advanceWidth * p.run.fontSize / 1000.0 + g.advanceAdjust
                val t = g.text
                for (k in t.indices) add(t[k], p.along(pen + advance * k / t.length), p.along(pen + advance * (k + 1) / t.length))
                pen += advance
            }
            end = p.along(pen)
            endAcross = p.across(pen)
        }

        /** The line, without the spaces it ends with, or null when it holds no text. */
        fun build(): KiteTextLine? {
            while (chars.isNotEmpty() && chars.last() == ' ') {
                chars.setLength(chars.length - 1)
                starts.removeAt(starts.lastIndex)
                ends.removeAt(ends.lastIndex)
            }
            if (chars.isEmpty()) return null
            val edges = DoubleArray(chars.length + 1) { if (it < starts.size) starts[it] else ends.last() }
            val bounds = runs.map { it.box }.reduce { a, b -> a.union(b) }
            return KiteTextLine(chars.toString(), bounds, edges, vertical)
        }

        /** Whether [next] is the line under this one in one paragraph. */
        fun joins(next: Line): Boolean {
            if (next.vertical != vertical || next.dir != dir || next.down != down) return false
            val em = minOf(size, next.size)
            if (maxOf(size, next.size) > 1.5 * em) return false
            val step = (next.startAcross - startAcross) * down
            if (step < 0.5 * em || step > 2.0 * em) return false
            val a0 = minOf(starts.first(), ends.last())
            val a1 = maxOf(starts.first(), ends.last())
            val b0 = minOf(next.starts.first(), next.ends.last())
            val b1 = maxOf(next.starts.first(), next.ends.last())
            return minOf(a1, b1) - maxOf(a0, b0) > -em
        }
    }

    internal companion object {
        fun of(pass: SvgLinkCanvas): SvgTextContent {
            val lines = ArrayList<Line>()
            for (run in pass.texts) {
                val p = Placed(run)
                val last = lines.lastOrNull()
                if (last != null && last.continues(p)) last.take(p) else lines += Line(p).also { it.take(p) }
            }
            val built = lines.mapNotNull { line -> line.build()?.let { line to it } }
            val blocks = ArrayList<KiteTextBlock>()
            var current = ArrayList<KiteTextLine>()
            for ((i, pair) in built.withIndex()) {
                if (i > 0 && !built[i - 1].first.joins(pair.first)) {
                    blocks += KiteTextBlock(current)
                    current = ArrayList()
                }
                current += pair.second
            }
            if (current.isNotEmpty()) blocks += KiteTextBlock(current)
            return SvgTextContent(KiteStructuredText(blocks), built.map { it.first.runs }, pass.ids)
        }
    }
}
