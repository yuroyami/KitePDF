package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.ComputedStyle
import io.github.yuroyami.kitepdf.epub.css.CssFloat
import io.github.yuroyami.kitepdf.epub.css.Display
import io.github.yuroyami.kitepdf.epub.css.Edge
import io.github.yuroyami.kitepdf.epub.css.ListType
import io.github.yuroyami.kitepdf.epub.css.PseudoContent
import io.github.yuroyami.kitepdf.epub.css.PseudoSide
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import io.github.yuroyami.kitepdf.epub.css.TextTransform
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
    /** Zip path of the document being built; the base for `#fragment` links. */
    private val docPath: String = "",
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
        // Ids of INLINE descendants (they get no box) anchor to this block.
        val inlineAnchors = ArrayList<String>()

        fun flush() {
            if (inl.hasContent()) {
                children.add(TextBlockBox(style, inl.take(), pendingMarker, markerColor))
                pendingMarker = null
            } else {
                inl.reset()
            }
        }

        // ::before generated content precedes the element's own children. A
        // block-display pseudo becomes a synthetic block child; anything else
        // flows inline.
        fun injectPseudo(side: PseudoSide) {
            if (isRoot) return
            val pc = resolver.computePseudo(el, ancestors, style, side) ?: return
            if (pc.style.display == Display.BLOCK) { flush(); children.add(pseudoBlock(pc)) }
            else inl.appendText(pc.text, pc.style)
        }
        injectPseudo(PseudoSide.BEFORE)

        for (child in el.children) when (child) {
            is HtmlNode.Text -> inl.appendText(child.text, style)
            is HtmlNode.Element -> {
                if (child.tag == "br") { inl.addBreak(); continue }
                if (child.tag == "img" || child.tag == "image") {
                    val src = child.attrs["src"] ?: child.attrs["href"] ?: child.attrs["xlink:href"]
                    if (src != null && src.isNotBlank()) {
                        val cs = resolver.compute(child, childAncestors, style)
                        val aw = child.attrs["width"]?.trim()?.removeSuffix("px")?.toDoubleOrNull()
                        val ah = child.attrs["height"]?.trim()?.removeSuffix("px")?.toDoubleOrNull()
                        // img is inline by default (CSS): it flows on the line
                        // unless the author blocks or floats it.
                        if ((cs.display == Display.INLINE || cs.display == Display.INLINE_BLOCK) &&
                            cs.cssFloat == CssFloat.NONE
                        ) {
                            inl.addImage(resolveHref(src), style, cs.widthPt ?: aw?.times(0.75), cs.heightPt ?: ah?.times(0.75))
                        } else {
                            flush()
                            children.add(ImageBox(cs, resolveHref(src), attrWidth = aw, attrHeight = ah))
                        }
                    }
                    continue
                }
                if (child.tag == "svg") { // inline SVG: paint as a vector image box
                    SvgImage.fromElement(child)?.let {
                        flush(); children.add(ImageBox(resolver.compute(child, childAncestors, style), "", it))
                    }
                    continue
                }
                val cs = resolver.compute(child, childAncestors, style)
                when (cs.display) {
                    Display.NONE -> {}
                    Display.INLINE, Display.INLINE_BLOCK -> processInline(child, cs, childAncestors, inl, inlineAnchors)
                    Display.TABLE -> { flush(); children.addAll(buildTable(child, cs, childAncestors)) }
                    Display.LIST_ITEM -> { flush(); children.add(buildBlock(child, cs, childAncestors, marker(cs, ordinal++), cs.color)) }
                    // BLOCK, plus stray table parts outside a table: treat as blocks (no text lost).
                    else -> { flush(); children.add(buildBlock(child, cs, childAncestors, null, BLACK)) }
                }
            }
        }
        injectPseudo(PseudoSide.AFTER)
        flush()
        return BlockBox(style, children).also { box ->
            el.attrs["id"]?.let(box.anchors::add)
            if (el.tag == "a") el.attrs["name"]?.let(box.anchors::add) // legacy anchor
            box.anchors += inlineAnchors
        }
    }

    /** A synthetic block child holding a `display:block` pseudo's content. */
    private fun pseudoBlock(pc: PseudoContent): BlockBox {
        val run = InlineRun(
            text = pc.text, fontSizePt = pc.style.fontSizePt,
            bold = pc.style.bold, italic = pc.style.italic, family = pc.style.fontFamily,
            color = pc.style.color, valign = pc.style.verticalAlign, underline = pc.style.underline,
            fontFamilyName = pc.style.fontFamilyName,
        )
        return BlockBox(pc.style, listOf(TextBlockBox(pc.style, listOf(run))))
    }

    /**
     * Build a `display:table` element, flattening row groups. Returns the
     * caption block (extracted, laid ABOVE the table) followed by the
     * [TableBox]; `<col>`/`<colgroup>` widths pin their columns; under
     * `border-collapse: collapse` each shared cell edge is painted once.
     */
    private fun buildTable(el: HtmlNode.Element, style: ComputedStyle, ancestors: List<HtmlNode.Element>): List<LayoutBox> {
        val rows = ArrayList<TableRowBox>()
        var caption: BlockBox? = null
        val childAncestors = listOf(el) + ancestors
        fun addRowsFrom(container: HtmlNode.Element, containerAncestors: List<HtmlNode.Element>) {
            val anc = listOf(container) + containerAncestors
            for (c in container.children) {
                if (c !is HtmlNode.Element) continue
                if (c.tag == "caption" && caption == null) {
                    caption = buildBlock(c, resolver.compute(c, anc, style), anc, null, BLACK)
                    continue
                }
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
        val finalRows = if (style.borderCollapse) collapseBorders(rows) else rows
        val table = TableBox(style, finalRows, scanColWidths(el, style, childAncestors))
        return listOfNotNull(caption, table)
    }

    /** `<col span width>` / `<colgroup width>` -> column index -> width (pt). */
    private fun scanColWidths(el: HtmlNode.Element, style: ComputedStyle, ancestors: List<HtmlNode.Element>): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        var idx = 0
        fun widthOf(c: HtmlNode.Element): Double? =
            resolver.compute(c, ancestors, style).widthPt
                ?: c.attrs["width"]?.trim()?.removeSuffix("px")?.toDoubleOrNull()?.let { it * 0.75 }
        fun scan(container: HtmlNode.Element) {
            for (c in container.children) {
                if (c !is HtmlNode.Element) continue
                when (c.tag) {
                    "col" -> {
                        val span = c.attrs["span"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        val w = widthOf(c)
                        repeat(span) { if (w != null) out[idx] = w; idx++ }
                    }
                    "colgroup" -> {
                        if (c.children.any { it is HtmlNode.Element && it.tag == "col" }) scan(c)
                        else {
                            val span = c.attrs["span"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                            val w = widthOf(c)
                            repeat(span) { if (w != null) out[idx] = w; idx++ }
                        }
                    }
                }
            }
        }
        scan(el)
        return out
    }

    /**
     * `border-collapse: collapse`: each interior shared edge paints once, the
     * wider edge winning; the loser's edge is dropped from that cell's style.
     * Cells are rebuilt with the adjusted styles (styles are immutable).
     */
    private fun collapseBorders(rows: List<TableRowBox>): List<TableRowBox> {
        // Grid of covering cells (spans fill every slot they touch).
        val grid = HashMap<Long, BlockBox>()
        fun key(r: Int, c: Int) = r.toLong() * 100_000L + c
        for (row in rows) for (cell in row.cells) {
            for (dr in 0 until cell.rowspan) for (dc in 0 until cell.colspan) {
                grid.getOrPut(key(cell.gridRow + dr, cell.gridCol + dc)) { cell }
            }
        }
        val dropTop = HashSet<BlockBox>()
        val dropLeft = HashSet<BlockBox>()
        val dropBottom = HashSet<BlockBox>()
        val dropRight = HashSet<BlockBox>()
        for (row in rows) for (cell in row.cells) {
            grid[key(cell.gridRow - 1, cell.gridCol)]?.takeIf { it !== cell }?.let { above ->
                if (above.style.borderBottom.effective >= cell.style.borderTop.effective) dropTop.add(cell)
                else dropBottom.add(above)
            }
            grid[key(cell.gridRow, cell.gridCol - 1)]?.takeIf { it !== cell }?.let { left ->
                if (left.style.borderRight.effective >= cell.style.borderLeft.effective) dropLeft.add(cell)
                else dropRight.add(left)
            }
        }
        return rows.map { row ->
            TableRowBox(
                row.style,
                row.cells.map { cell ->
                    val s = cell.style
                    val ns = s.copy(
                        borderTop = if (cell in dropTop) Edge.NONE else s.borderTop,
                        borderLeft = if (cell in dropLeft) Edge.NONE else s.borderLeft,
                        borderBottom = if (cell in dropBottom) Edge.NONE else s.borderBottom,
                        borderRight = if (cell in dropRight) Edge.NONE else s.borderRight,
                    )
                    if (ns === s || ns == s) cell else BlockBox(ns, cell.children).also {
                        it.colspan = cell.colspan; it.rowspan = cell.rowspan
                        it.gridRow = cell.gridRow; it.gridCol = cell.gridCol
                        it.anchors += cell.anchors
                    }
                },
            )
        }
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

    private fun processInline(
        el: HtmlNode.Element,
        style: ComputedStyle,
        ancestors: List<HtmlNode.Element>,
        inl: Inline,
        anchorSink: MutableList<String>,
    ) {
        // Inline elements never get a box; their anchors attach to the block.
        el.attrs["id"]?.let(anchorSink::add)
        if (el.tag == "a") el.attrs["name"]?.let(anchorSink::add)
        if (el.tag == "ruby") { processRuby(el, style, ancestors, inl, anchorSink); return }

        val link = if (el.tag == "a") el.attrs["href"]?.takeIf { it.isNotBlank() }?.let(::resolveLink) else null
        if (link != null) inl.beginLink(link)
        try {
            // Inline generated content flows with the element's own runs (a
            // block-display pseudo inside an inline element is treated inline).
            resolver.computePseudo(el, ancestors, style, PseudoSide.BEFORE)?.let { inl.appendText(it.text, it.style) }
            val childAncestors = listOf(el) + ancestors
            for (child in el.children) when (child) {
                is HtmlNode.Text -> inl.appendText(child.text, style)
                is HtmlNode.Element -> {
                    if (child.tag == "br") { inl.addBreak(); continue }
                    if (child.tag == "img" || child.tag == "image") {
                        // Inline image: flows on the line, bottom on the baseline.
                        val src = child.attrs["src"] ?: child.attrs["href"] ?: child.attrs["xlink:href"]
                        if (src != null && src.isNotBlank()) {
                            val cs = resolver.compute(child, childAncestors, style)
                            val aw = child.attrs["width"]?.trim()?.removeSuffix("px")?.toDoubleOrNull()?.times(0.75)
                            val ah = child.attrs["height"]?.trim()?.removeSuffix("px")?.toDoubleOrNull()?.times(0.75)
                            inl.addImage(resolveHref(src), style, cs.widthPt ?: aw, cs.heightPt ?: ah)
                        }
                        continue
                    }
                    val cs = resolver.compute(child, childAncestors, style)
                    if (cs.display != Display.NONE) processInline(child, cs, childAncestors, inl, anchorSink)
                }
            }
            resolver.computePseudo(el, ancestors, style, PseudoSide.AFTER)?.let { inl.appendText(it.text, it.style) }
        } finally {
            if (link != null) inl.endLink()
        }
    }

    /**
     * Resolve an `<a href>`: external URLs (any scheme) stay verbatim; a bare
     * `#fragment` targets this document; a relative path resolves against the
     * document's directory, keeping its fragment.
     */
    private fun resolveLink(href: String): String {
        val h = href.trim()
        if (SCHEME.containsMatchIn(h)) return h
        val path = h.substringBefore('#')
        val frag = h.substringAfter('#', "")
        val resolved = if (path.isEmpty()) docPath else resolveHref(path)
        return if (frag.isEmpty()) resolved else "$resolved#$frag"
    }

    /**
     * `<ruby>`: the base (text / `<rb>` / other inline children) flows normally
     * but tagged as one ruby group; every `<rt>` contributes to the reading
     * painted above it; `<rp>` fallback punctuation is dropped. Multiple `<rt>`
     * segments (jukugo ruby) merge into one reading over the whole base, a
     * documented simplification.
     */
    private fun processRuby(
        el: HtmlNode.Element,
        style: ComputedStyle,
        ancestors: List<HtmlNode.Element>,
        inl: Inline,
        anchorSink: MutableList<String>,
    ) {
        val childAncestors = listOf(el) + ancestors
        val reading = StringBuilder()
        fun collectText(e: HtmlNode.Element) {
            for (c in e.children) when (c) {
                is HtmlNode.Text -> reading.append(c.text)
                is HtmlNode.Element -> if (c.tag != "rp") collectText(c)
            }
        }
        for (c in el.children) if (c is HtmlNode.Element && c.tag == "rt") collectText(c)
        val readingText = reading.toString().replace(WHITESPACE, " ").trim()

        inl.beginRuby(readingText.takeIf { it.isNotEmpty() })
        try {
            for (c in el.children) when (c) {
                is HtmlNode.Text -> inl.appendText(c.text, style)
                is HtmlNode.Element -> when {
                    c.tag == "rt" || c.tag == "rp" -> {}
                    c.tag == "br" -> inl.addBreak()
                    else -> {
                        val cs = resolver.compute(c, childAncestors, style)
                        if (cs.display != Display.NONE) processInline(c, cs, childAncestors, inl, anchorSink)
                    }
                }
            }
        } finally {
            inl.endRuby()
        }
    }

    /** Per-block inline-run accumulator with HTML whitespace collapsing. */
    private class Inline {
        private var runs = ArrayList<InlineRun>()
        private var pendingSpace = false
        private var blockHasContent = false
        private var lastWasBreak = false
        // Active <ruby> group: runs made between beginRuby/endRuby carry the id +
        // reading so the layout can keep the base together and paint the reading.
        private var rubyGroup = -1
        private var rubyText: String? = null
        private var nextRubyId = 0

        fun beginRuby(reading: String?) {
            if (reading != null) { rubyGroup = nextRubyId++; rubyText = reading }
        }

        fun endRuby() { rubyGroup = -1; rubyText = null }

        // Active <a href>: nested anchors save/restore the enclosing target.
        private val linkStack = ArrayDeque<String?>()
        private var linkHref: String? = null

        fun beginLink(href: String) { linkStack.addLast(linkHref); linkHref = href }

        fun endLink() { linkHref = linkStack.removeLastOrNull() }

        fun hasContent() = runs.any { it.text.isNotEmpty() || it.hardBreak }

        fun take(): List<InlineRun> {
            val r = runs; runs = ArrayList(); reset(); return r
        }

        fun reset() { runs = ArrayList(); pendingSpace = false; blockHasContent = false; lastWasBreak = false }

        fun addBreak() {
            runs.add(InlineRun("", fontSizePt = 0.0, hardBreak = true))
            pendingSpace = false; lastWasBreak = true
        }

        /** An inline `<img>`: one U+FFFC run carrying the source + size hints. */
        fun addImage(src: String, style: ComputedStyle, cssW: Double?, cssH: Double?) {
            if (pendingSpace && blockHasContent && !lastWasBreak) {
                runs.add(makeRun(" ", style))
            }
            pendingSpace = false; lastWasBreak = false; blockHasContent = true
            runs.add(
                makeRun("￼", style).copy(imageSrc = src, imageCssW = cssW, imageCssH = cssH),
            )
        }

        fun appendText(raw: String, style: ComputedStyle) {
            if (raw.isEmpty()) return
            if (style.whiteSpace == WhiteSpaceMode.PRE || style.whiteSpace == WhiteSpaceMode.PRE_WRAP || style.whiteSpace == WhiteSpaceMode.PRE_LINE) {
                runs.add(makeRun(transformPre(raw, style.textTransform), style))
                blockHasContent = true; pendingSpace = false; lastWasBreak = false
                return
            }
            val b = StringBuilder(raw.length)
            for (ch in raw) {
                if (ch.isWhitespace()) {
                    pendingSpace = true
                } else {
                    // Word boundary BEFORE consuming pendingSpace: capitalize needs it,
                    // and it must survive across appendText calls (runs split mid-word).
                    val boundary = pendingSpace || !blockHasContent || lastWasBreak
                    if (pendingSpace && blockHasContent && !lastWasBreak) b.append(' ')
                    pendingSpace = false; lastWasBreak = false
                    b.append(transformChar(ch, style.textTransform, boundary)); blockHasContent = true
                }
            }
            if (b.isNotEmpty()) runs.add(makeRun(b.toString(), style))
        }

        private fun transformChar(ch: Char, tt: TextTransform, wordBoundary: Boolean): Char = when (tt) {
            TextTransform.NONE -> ch
            TextTransform.UPPERCASE -> ch.uppercaseChar()
            TextTransform.LOWERCASE -> ch.lowercaseChar()
            TextTransform.CAPITALIZE -> if (wordBoundary) ch.uppercaseChar() else ch
        }

        /** Transform preserved-whitespace text: word boundaries follow whitespace. */
        private fun transformPre(raw: String, tt: TextTransform): String {
            if (tt == TextTransform.NONE) return raw
            val sb = StringBuilder(raw.length)
            var boundary = true
            for (ch in raw) {
                sb.append(transformChar(ch, tt, boundary))
                boundary = ch.isWhitespace()
            }
            return sb.toString()
        }

        private fun makeRun(text: String, style: ComputedStyle) = InlineRun(
            text = text, fontSizePt = style.fontSizePt,
            bold = style.bold, italic = style.italic, family = style.fontFamily,
            color = style.color, valign = style.verticalAlign, underline = style.underline,
            fontFamilyName = style.fontFamilyName,
            rubyGroup = rubyGroup, rubyText = rubyText,
            href = linkHref,
            letterSpacingPt = style.letterSpacingPt, wordSpacingPt = style.wordSpacingPt,
            smallCaps = style.smallCaps,
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
        val WHITESPACE = Regex("\\s+")
        /** A URI scheme prefix (`https:`, `mailto:`, ...): the href is external. */
        val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    }
}
