package io.github.yuroyami.kitepdf.text

import io.github.yuroyami.kitepdf.core.KiteStructuredText
import io.github.yuroyami.kitepdf.core.KiteTextBlock
import io.github.yuroyami.kitepdf.core.KiteTextLine
import io.github.yuroyami.kitepdf.core.Rectangle
import io.github.yuroyami.kitepdf.core.render.Matrix
import kotlin.math.abs

/**
 * T-81: maps [PdfStructuredText] (page user-space, y-up) onto the
 * format-neutral [KiteStructuredText] (display space, y-down, rotation
 * folded in), so search / selection / copy built on the core model work
 * for PDF pages exactly as they do for EPUB.
 *
 * Blocks and lines map 1:1. Line text is rebuilt with the same rules
 * [PdfTextLine.text] uses (gap-synthesised spaces, trailing whitespace
 * trimmed) while tracking a user-space boundary point per char, so the
 * produced [KiteTextLine.charEdges] stay aligned with the text.
 *
 * The Kite line model is one-dimensional (x edges within a horizontal
 * line). For a line whose display-space baseline runs left-to-right
 * (all /Rotate 0 and most /Rotate 180 content) the edges are exact glyph
 * boundaries; for rotated/vertical/reversed baselines the edges are
 * distributed evenly across the line's display box, which keeps every
 * highlight quad inside the correct box at reduced sub-line precision.
 */
internal object KiteTextAdapter {

    fun toKite(st: PdfStructuredText, display: Matrix): KiteStructuredText =
        KiteStructuredText(st.blocks.map { b -> KiteTextBlock(b.lines.map { toLine(it, display) }) })

    private class Ch(val c: Char, val end: Pair<Double, Double>)

    private fun toLine(line: PdfTextLine, display: Matrix): KiteTextLine {
        // Rebuild the text char-by-char, keeping each char's user-space end
        // boundary (its start is the previous entry's end).
        val chars = ArrayList<Ch>()
        var lineStart: Pair<Double, Double>? = null
        var prev: PdfTextSpan? = null
        for (s in line.spans) {
            val edges = s.charEdgePoints ?: evenEdges(s)
            if (prev != null) {
                val gap = s.bounds.left - prev.bounds.right
                if (gap > prev.fontSize * 0.25 && chars.isNotEmpty() && chars.last().c != ' ') {
                    chars.add(Ch(' ', edges.first())) // the joiner space ends where the next span starts
                }
            }
            if (lineStart == null) lineStart = edges.first()
            for (k in s.text.indices) chars.add(Ch(s.text[k], edges[k + 1]))
            prev = s
        }
        while (chars.isNotEmpty() && chars.last().c.isWhitespace()) chars.removeAt(chars.size - 1)
        val text = buildString { for (c in chars) append(c.c) }

        // Display-space bounds: the user-space hull mapped corner-wise.
        val corners = listOf(
            display.transformPoint(line.bounds.left, line.bounds.bottom),
            display.transformPoint(line.bounds.right, line.bounds.bottom),
            display.transformPoint(line.bounds.right, line.bounds.top),
            display.transformPoint(line.bounds.left, line.bounds.top),
        )
        val bounds = Rectangle(
            left = corners.minOf { it.first },
            bottom = corners.minOf { it.second },
            right = corners.maxOf { it.first },
            top = corners.maxOf { it.second },
        )

        val edges = DoubleArray(text.length + 1)
        if (text.isEmpty() || lineStart == null) {
            edges.fill(bounds.left)
            return KiteTextLine(text, bounds, edges)
        }
        val first = display.transformPoint(lineStart.first, lineStart.second)
        val lastPt = chars.last().end
        val last = display.transformPoint(lastPt.first, lastPt.second)
        val dx = last.first - first.first
        if (dx > 0 && dx >= abs(last.second - first.second)) {
            // Horizontal, left-to-right in display space: exact edges,
            // coerced monotonic against numeric jitter.
            var x = first.first
            edges[0] = x
            for (i in chars.indices) {
                val p = display.transformPoint(chars[i].end.first, chars[i].end.second)
                if (p.first > x) x = p.first
                edges[i + 1] = x
            }
        } else {
            for (i in 0..text.length) {
                edges[i] = bounds.left + (bounds.right - bounds.left) * i / text.length
            }
        }
        return KiteTextLine(text, bounds, edges)
    }

    /** Even split of the span box for spans without recorded glyph geometry. */
    private fun evenEdges(s: PdfTextSpan): List<Pair<Double, Double>> {
        val n = maxOf(1, s.text.length)
        val y = (s.bounds.top + s.bounds.bottom) / 2
        return (0..s.text.length).map { k ->
            (s.bounds.left + (s.bounds.right - s.bounds.left) * k / n) to y
        }
    }
}
