package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.ComputedStyle
import io.github.yuroyami.kitepdf.epub.css.Display
import io.github.yuroyami.kitepdf.epub.css.ListType
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import io.github.yuroyami.kitepdf.epub.css.WhiteSpaceMode
import io.github.yuroyami.kitepdf.render.RgbColor

/**
 * Builds the CSS [LayoutBox] tree from the DOM, driven by the cascade
 * ([StyleResolver]). Each block-level element becomes a [BlockBox]; its inline
 * content (text + inline elements) is gathered into anonymous [TextBlockBox]
 * children, interleaved with nested block boxes and images. `display:none`
 * subtrees are dropped; list-item markers come from `list-style-type` + an
 * ordinal. Whitespace collapses except under `white-space: pre*`.
 */
internal class BoxBuilder(
    private val resolver: StyleResolver,
    private val resolveHref: (String) -> String,
) {
    fun build(root: HtmlNode.Element): BlockBox =
        buildBlock(root, resolver.initial(), emptyList(), marker = null, markerColor = BLACK, isRoot = true)

    private fun buildBlock(
        el: HtmlNode.Element,
        style: ComputedStyle,
        ancestors: List<HtmlNode.Element>,
        marker: String?,
        markerColor: RgbColor,
        isRoot: Boolean = false,
    ): BlockBox {
        val children = ArrayList<LayoutBox>()
        val inl = Inline()
        var pendingMarker = marker
        val childAncestors = if (isRoot) ancestors else listOf(el) + ancestors
        var ordinal = el.attrs["start"]?.toIntOrNull() ?: 1

        fun flush() {
            if (inl.hasContent()) {
                children.add(TextBlockBox(style, inl.take(), pendingMarker, markerColor))
                pendingMarker = null
            } else {
                inl.reset()
            }
        }

        for (child in el.children) when (child) {
            is HtmlNode.Text -> inl.appendText(child.text, style)
            is HtmlNode.Element -> {
                if (child.tag == "br") { inl.addBreak(); continue }
                if (child.tag == "img" || child.tag == "image") {
                    val src = child.attrs["src"] ?: child.attrs["href"] ?: child.attrs["xlink:href"]
                    if (src != null && src.isNotBlank()) { flush(); children.add(ImageBox(resolver.compute(child, childAncestors, style), resolveHref(src))) }
                    continue
                }
                val cs = resolver.compute(child, childAncestors, style)
                when (cs.display) {
                    Display.NONE -> {}
                    Display.INLINE, Display.INLINE_BLOCK -> processInline(child, cs, childAncestors, inl)
                    Display.TABLE -> { flush(); children.add(buildTable(child, cs, childAncestors)) }
                    Display.LIST_ITEM -> { flush(); children.add(buildBlock(child, cs, childAncestors, marker(cs, ordinal++), cs.color)) }
                    // BLOCK, plus stray table parts outside a table: treat as blocks (no text lost).
                    else -> { flush(); children.add(buildBlock(child, cs, childAncestors, null, BLACK)) }
                }
            }
        }
        flush()
        return BlockBox(style, children)
    }

    /** Build a `display:table` element into a [TableBox], flattening row groups. */
    private fun buildTable(el: HtmlNode.Element, style: ComputedStyle, ancestors: List<HtmlNode.Element>): TableBox {
        val rows = ArrayList<TableRowBox>()
        val childAncestors = listOf(el) + ancestors
        fun addRowsFrom(container: HtmlNode.Element, containerAncestors: List<HtmlNode.Element>) {
            val anc = listOf(container) + containerAncestors
            for (c in container.children) {
                if (c !is HtmlNode.Element) continue
                val cs = resolver.compute(c, anc, style)
                when (cs.display) {
                    Display.TABLE_ROW -> rows.add(buildRow(c, cs, anc))
                    Display.TABLE_ROW_GROUP -> addRowsFrom(c, anc)
                    else -> {}
                }
            }
        }
        addRowsFrom(el, ancestors)
        placeCells(rows)
        return TableBox(style, rows)
    }

    /** Assign each cell a grid (row, col), skipping cells occupied by rowspans from above. */
    private fun placeCells(rows: List<TableRowBox>) {
        val occupied = HashSet<Long>()
        fun key(r: Int, c: Int) = r.toLong() * 100_000L + c
        for ((r, row) in rows.withIndex()) {
            var col = 0
            for (cell in row.cells) {
                while (occupied.contains(key(r, col))) col++
                cell.gridRow = r; cell.gridCol = col
                for (dr in 0 until cell.rowspan) for (dc in 0 until cell.colspan) occupied.add(key(r + dr, col + dc))
                col += cell.colspan
            }
        }
    }

    private fun buildRow(el: HtmlNode.Element, style: ComputedStyle, ancestors: List<HtmlNode.Element>): TableRowBox {
        val cells = ArrayList<BlockBox>()
        val childAncestors = listOf(el) + ancestors
        for (c in el.children) {
            if (c !is HtmlNode.Element) continue
            val cs = resolver.compute(c, childAncestors, style)
            if (cs.display == Display.TABLE_CELL || cs.display == Display.BLOCK) {
                val cell = buildBlock(c, cs, childAncestors, null, BLACK)
                cell.colspan = c.attrs["colspan"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                cell.rowspan = c.attrs["rowspan"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                cells.add(cell)
            }
        }
        return TableRowBox(style, cells)
    }

    private fun processInline(el: HtmlNode.Element, style: ComputedStyle, ancestors: List<HtmlNode.Element>, inl: Inline) {
        val childAncestors = listOf(el) + ancestors
        for (child in el.children) when (child) {
            is HtmlNode.Text -> inl.appendText(child.text, style)
            is HtmlNode.Element -> {
                if (child.tag == "br") { inl.addBreak(); continue }
                if (child.tag == "img" || child.tag == "image") continue // inline images: Phase 5
                val cs = resolver.compute(child, childAncestors, style)
                if (cs.display != Display.NONE) processInline(child, cs, childAncestors, inl)
            }
        }
    }

    /** Per-block inline-run accumulator with HTML whitespace collapsing. */
    private class Inline {
        private var runs = ArrayList<InlineRun>()
        private var pendingSpace = false
        private var blockHasContent = false
        private var lastWasBreak = false

        fun hasContent() = runs.any { it.text.isNotEmpty() || it.hardBreak }

        fun take(): List<InlineRun> {
            val r = runs; runs = ArrayList(); reset(); return r
        }

        fun reset() { runs = ArrayList(); pendingSpace = false; blockHasContent = false; lastWasBreak = false }

        fun addBreak() {
            runs.add(InlineRun("", fontSizePt = 0.0, hardBreak = true))
            pendingSpace = false; lastWasBreak = true
        }

        fun appendText(raw: String, style: ComputedStyle) {
            if (raw.isEmpty()) return
            if (style.whiteSpace == WhiteSpaceMode.PRE || style.whiteSpace == WhiteSpaceMode.PRE_WRAP || style.whiteSpace == WhiteSpaceMode.PRE_LINE) {
                runs.add(makeRun(raw, style)); blockHasContent = true; pendingSpace = false; lastWasBreak = false
                return
            }
            val b = StringBuilder(raw.length)
            for (ch in raw) {
                if (ch.isWhitespace()) {
                    pendingSpace = true
                } else {
                    if (pendingSpace && blockHasContent && !lastWasBreak) b.append(' ')
                    pendingSpace = false; lastWasBreak = false
                    b.append(ch); blockHasContent = true
                }
            }
            if (b.isNotEmpty()) runs.add(makeRun(b.toString(), style))
        }

        private fun makeRun(text: String, style: ComputedStyle) = InlineRun(
            text = text, fontSizePt = style.fontSizePt,
            bold = style.bold, italic = style.italic, family = style.fontFamily,
            color = style.color, valign = style.verticalAlign, underline = style.underline,
            fontFamilyName = style.fontFamilyName,
        )
    }

    private fun marker(style: ComputedStyle, ordinal: Int): String? = when (style.listType) {
        ListType.NONE -> null
        ListType.DISC -> "•"
        ListType.CIRCLE -> "◦"
        ListType.SQUARE -> "▪"
        ListType.DECIMAL -> "$ordinal."
        ListType.LOWER_ROMAN -> roman(ordinal).lowercase() + "."
        ListType.UPPER_ROMAN -> roman(ordinal) + "."
        ListType.LOWER_ALPHA -> alpha(ordinal).lowercase() + "."
        ListType.UPPER_ALPHA -> alpha(ordinal) + "."
    }

    private fun roman(n: Int): String {
        if (n !in 1..3999) return n.toString()
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        val sb = StringBuilder(); var x = n
        for (i in vals.indices) while (x >= vals[i]) { sb.append(syms[i]); x -= vals[i] }
        return sb.toString()
    }

    private fun alpha(n: Int): String {
        if (n < 1) return n.toString()
        val sb = StringBuilder(); var x = n
        while (x > 0) { x--; sb.append('A' + (x % 26)); x /= 26 }
        return sb.reverse().toString()
    }

    private companion object {
        val BLACK = RgbColor(0.0, 0.0, 0.0)
    }
}
