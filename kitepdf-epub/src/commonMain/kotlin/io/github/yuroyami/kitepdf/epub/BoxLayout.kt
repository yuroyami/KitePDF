package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.ComputedStyle
import io.github.yuroyami.kitepdf.epub.css.CssVAlign
import io.github.yuroyami.kitepdf.epub.css.GenericFont
import io.github.yuroyami.kitepdf.epub.css.TextAlign
import io.github.yuroyami.kitepdf.epub.css.WhiteSpaceMode
import io.github.yuroyami.kitepdf.font.FontFamily
import io.github.yuroyami.kitepdf.font.FontSpec
import io.github.yuroyami.kitepdf.font.TextGlyph
import io.github.yuroyami.kitepdf.render.ImageXObject
import io.github.yuroyami.kitepdf.render.RgbColor

/**
 * Positions the [LayoutBox] tree in document space (x from content-left, y down).
 * Resolves each block's box model — margin / border / padding / width (auto-fill,
 * explicit, max-width) — collapses adjacent sibling vertical margins, stacks
 * children, and lays inline content into line boxes honouring `text-align`
 * (incl. **justify**), first-line `text-indent`, `line-height`, and vertical-align.
 * Anonymous [TextBlockBox]es carry no box decorations (their parent [BlockBox]
 * owns the margin/border/padding/background).
 */
internal class BoxLayout(
    private val loadImage: (String) -> ImageXObject? = { null },
    private val maxImageHeight: Double = Double.MAX_VALUE,
    private val fonts: FontRegistry = FontRegistry.EMPTY,
) {
    /** Lay out [root] to fill [contentWidth]; returns total document height. */
    fun layout(root: BlockBox, contentWidth: Double): Double {
        layoutBlock(root, xLeft = 0.0, availWidth = contentWidth, topY = 0.0)
        return root.borderBoxHeight
    }

    private fun layoutBlock(box: BlockBox, xLeft: Double, availWidth: Double, topY: Double) {
        val s = box.style
        val bL = s.borderLeft.effective; val bR = s.borderRight.effective
        val extra = s.marginLeftPt + s.marginRightPt + bL + bR + s.paddingLeftPt + s.paddingRightPt
        var contentW = s.widthPt ?: (availWidth - extra)
        s.maxWidthPt?.let { if (contentW > it) contentW = it }
        contentW = contentW.coerceAtLeast(0.0)

        box.borderBoxWidth = bL + s.paddingLeftPt + contentW + s.paddingRightPt + bR
        val leftMargin = if (s.marginLeftAuto && s.marginRightAuto) maxOf(0.0, (availWidth - box.borderBoxWidth) / 2) else s.marginLeftPt
        box.x = xLeft + leftMargin
        box.y = topY
        val contentLeft = box.x + bL + s.paddingLeftPt
        val contentTop = box.y + s.borderTop.effective + s.paddingTopPt

        var cursorY = contentTop
        var prevBottom = 0.0
        var first = true
        for (child in box.children) {
            // Anonymous text boxes carry no margins; real block children do.
            val topMargin = if (child is BlockBox) child.style.marginTopPt else 0.0
            val botMargin = if (child is BlockBox) child.style.marginBottomPt else 0.0
            val gap = if (first) topMargin else maxOf(prevBottom, topMargin)
            layoutChild(child, contentLeft, contentW, cursorY + gap)
            cursorY = child.bottom
            prevBottom = botMargin
            first = false
        }
        cursorY += prevBottom

        var contentH = cursorY - contentTop
        s.heightPt?.let { contentH = it }
        box.borderBoxHeight = s.borderTop.effective + s.paddingTopPt + contentH + s.paddingBottomPt + s.borderBottom.effective
    }

    private fun layoutChild(child: LayoutBox, contentLeft: Double, contentW: Double, topY: Double): kotlin.Unit = when (child) {
        is BlockBox -> layoutBlock(child, contentLeft, contentW, topY)
        is TextBlockBox -> layoutTextBlock(child, contentLeft, contentW, topY)
        is ImageBox -> layoutImage(child, contentLeft, contentW, topY)
        is TableBox -> layoutTable(child, contentLeft, contentW, topY)
        is TableRowBox -> {} // rows are laid out by their table, never as a direct block child
    }

    private fun layoutTextBlock(box: TextBlockBox, contentLeft: Double, contentW: Double, topY: Double) {
        box.x = contentLeft; box.y = topY; box.borderBoxWidth = contentW
        val lines = layoutInline(box.runs, contentW, contentLeft, topY, box.style, box.marker, box.markerColor)
        lines.forEachIndexed { i, ln -> ln.owner = box; ln.ownerIndex = i }
        box.lines = lines
        box.borderBoxHeight = lines.sumOf { it.height }
    }

    private fun layoutImage(box: ImageBox, contentLeft: Double, contentW: Double, topY: Double) {
        val img = loadImage(box.zipPath)
        if (img == null || img.width <= 0 || img.height <= 0) {
            box.x = contentLeft; box.y = topY; box.borderBoxWidth = 0.0; box.borderBoxHeight = 0.0; return
        }
        box.image = img
        val aspect = img.height.toDouble() / img.width
        var w = contentW; var h = w * aspect
        if (h > maxImageHeight) { h = maxImageHeight; w = h / aspect }
        box.drawWidth = w; box.drawHeight = h
        box.x = contentLeft + (contentW - w) / 2.0
        box.y = topY
        box.borderBoxWidth = w; box.borderBoxHeight = h
    }

    // ---- tables --------------------------------------------------------------

    private fun layoutTable(box: TableBox, contentLeft: Double, availWidth: Double, topY: Double) {
        val s = box.style
        val bL = s.borderLeft.effective; val bR = s.borderRight.effective
        val avail = (availWidth - s.marginLeftPt - s.marginRightPt - bL - bR - s.paddingLeftPt - s.paddingRightPt).coerceAtLeast(0.0)

        val rowCount = box.rows.size
        val allCells = box.rows.flatMap { it.cells }
        val cols = allCells.maxOfOrNull { it.gridCol + it.colspan } ?: 0
        if (cols == 0 || rowCount == 0) {
            box.x = contentLeft + s.marginLeftPt; box.y = topY
            box.borderBoxWidth = bL + s.paddingLeftPt + avail + s.paddingRightPt + bR
            box.borderBoxHeight = s.borderTop.effective + s.paddingTopPt + s.paddingBottomPt + s.borderBottom.effective
            return
        }

        // Column widths: single-column cells set the base; spanning cells top up their columns.
        val colPref = DoubleArray(cols); val colMin = DoubleArray(cols)
        for (cell in allCells) if (cell.colspan == 1) {
            val (p, m) = measureCell(cell)
            if (p > colPref[cell.gridCol]) colPref[cell.gridCol] = p
            if (m > colMin[cell.gridCol]) colMin[cell.gridCol] = m
        }
        for (cell in allCells) if (cell.colspan > 1) {
            val (p, m) = measureCell(cell)
            spread(colPref, cell.gridCol, cell.colspan, p)
            spread(colMin, cell.gridCol, cell.colspan, m)
        }

        val totalPref = colPref.sum(); val totalMin = colMin.sum()
        val widths = DoubleArray(cols)
        val tableW: Double
        val target = s.widthPt
        when {
            target != null && target > totalMin -> { distribute(widths, colPref, colMin, target); tableW = target }
            totalPref <= avail -> { for (c in 0 until cols) widths[c] = colPref[c]; tableW = totalPref }
            totalMin <= avail -> {
                val slack = avail - totalMin; val prefSlack = totalPref - totalMin
                for (c in 0 until cols) widths[c] = colMin[c] + (if (prefSlack > 0) slack * (colPref[c] - colMin[c]) / prefSlack else slack / cols)
                tableW = avail
            }
            else -> { for (c in 0 until cols) widths[c] = colMin[c]; tableW = totalMin }
        }
        val colOffset = DoubleArray(cols + 1)
        for (c in 0 until cols) colOffset[c + 1] = colOffset[c] + widths[c]

        box.borderBoxWidth = bL + s.paddingLeftPt + tableW + s.paddingRightPt + bR
        val leftMargin = if (s.marginLeftAuto && s.marginRightAuto) maxOf(0.0, (availWidth - box.borderBoxWidth) / 2) else s.marginLeftPt
        box.x = contentLeft + leftMargin; box.y = topY
        val tContentLeft = box.x + bL + s.paddingLeftPt
        val tContentTop = box.y + s.borderTop.effective + s.paddingTopPt

        // Pass A: lay cells at their column width to measure heights.
        for (cell in allCells) {
            val cw = colOffset[cell.gridCol + cell.colspan] - colOffset[cell.gridCol]
            layoutBlock(cell, tContentLeft + colOffset[cell.gridCol], cw, tContentTop)
        }
        // Row heights: single-row cells set the base; rowspan cells top up their last spanned row.
        val rowHeight = DoubleArray(rowCount)
        for (cell in allCells) if (cell.rowspan == 1 && cell.borderBoxHeight > rowHeight[cell.gridRow]) rowHeight[cell.gridRow] = cell.borderBoxHeight
        for (cell in allCells) if (cell.rowspan > 1) {
            val last = (cell.gridRow + cell.rowspan - 1).coerceAtMost(rowCount - 1)
            val cur = (cell.gridRow..last).sumOf { rowHeight[it] }
            if (cell.borderBoxHeight > cur) rowHeight[last] += cell.borderBoxHeight - cur
        }
        val rowTop = DoubleArray(rowCount + 1)
        for (r in 0 until rowCount) rowTop[r + 1] = rowTop[r] + rowHeight[r]
        for ((r, row) in box.rows.withIndex()) {
            row.x = tContentLeft; row.y = tContentTop + rowTop[r]; row.borderBoxWidth = tableW; row.borderBoxHeight = rowHeight[r]
        }

        // Pass B: place cells at their final y and stretch to the height of their spanned rows.
        for (cell in allCells) {
            val cw = colOffset[cell.gridCol + cell.colspan] - colOffset[cell.gridCol]
            layoutBlock(cell, tContentLeft + colOffset[cell.gridCol], cw, tContentTop + rowTop[cell.gridRow])
            val lastRow = (cell.gridRow + cell.rowspan).coerceAtMost(rowCount)
            cell.borderBoxHeight = rowTop[lastRow] - rowTop[cell.gridRow]
        }

        box.borderBoxHeight = s.borderTop.effective + s.paddingTopPt + rowTop[rowCount] + s.paddingBottomPt + s.borderBottom.effective
    }

    private fun spread(arr: DoubleArray, start: Int, span: Int, total: Double) {
        val end = (start + span).coerceAtMost(arr.size)
        if (end <= start) return
        val cur = (start until end).sumOf { arr[it] }
        if (total > cur) { val add = (total - cur) / (end - start); for (c in start until end) arr[c] += add }
    }

    private fun distribute(widths: DoubleArray, pref: DoubleArray, min: DoubleArray, target: Double) {
        val sumMin = min.sum(); val prefSlack = pref.sum() - sumMin; val slack = target - sumMin
        for (c in widths.indices) widths[c] = min[c] + when {
            slack <= 0 -> 0.0
            prefSlack > 0 -> slack * (pref[c] - min[c]) / prefSlack
            else -> slack / widths.size
        }
    }

    private fun measureCell(cell: BlockBox): Pair<Double, Double> {
        val (p, m) = measureContent(cell)
        val pad = cell.style.paddingLeftPt + cell.style.paddingRightPt + cell.style.borderLeft.effective + cell.style.borderRight.effective
        return (p + pad) to (m + pad)
    }

    private fun measureContent(box: LayoutBox): Pair<Double, Double> = when (box) {
        is TextBlockBox -> measureRuns(box.runs)
        is BlockBox -> {
            var p = 0.0; var m = 0.0
            for (c in box.children) {
                val (cp, cm) = measureContent(c)
                val pad = if (c is BlockBox) c.style.paddingLeftPt + c.style.paddingRightPt else 0.0
                if (cp + pad > p) p = cp + pad
                if (cm + pad > m) m = cm + pad
            }
            p to m
        }
        else -> 0.0 to 0.0 // images/nested tables: sized by their column
    }

    /** Unwrapped content width (longest line) and minimum width (longest word). */
    private fun measureRuns(runs: List<InlineRun>): Pair<Double, Double> {
        var line = 0.0; var maxLine = 0.0; var word = 0.0; var maxWord = 0.0
        fun endLine() { if (line > maxLine) maxLine = line; line = 0.0 }
        fun endWord() { if (word > maxWord) maxWord = word; word = 0.0 }
        for (run in runs) {
            if (run.hardBreak) { endWord(); endLine(); continue }
            for (ch in run.text) when {
                ch == '\n' -> { endWord(); endLine() }
                ch == '\r' -> {}
                ch.isWhitespace() -> { line += FontMetrics.advancePt(' ', run.fontSizePt, run.bold, run.italic, run.family); endWord() }
                else -> { val w = FontMetrics.advancePt(ch, run.fontSizePt, run.bold, run.italic, run.family); line += w; word += w }
            }
        }
        endWord(); endLine()
        return maxLine to maxWord
    }

    // ---- inline layout -------------------------------------------------------

    private fun layoutInline(
        runs: List<InlineRun>, contentW: Double, contentLeft: Double, topY: Double,
        style: ComputedStyle, marker: String?, markerColor: RgbColor,
    ): List<PositionedLine> {
        val preserve = style.whiteSpace != WhiteSpaceMode.NORMAL && style.whiteSpace != WhiteSpaceMode.NOWRAP
        val cellLines = wrap(tokenize(runs), contentW, preserve)
        val out = ArrayList<PositionedLine>(cellLines.size)
        var y = topY
        cellLines.forEachIndexed { i, cells ->
            val maxFs = cells.maxOfOrNull { it.fontSize }?.takeIf { it > 0.0 } ?: style.fontSizePt
            val lineHeight = style.lineHeightPt ?: maxFs * 1.4
            val ascent = maxFs * 0.8

            val (lineWidth, interiorSpaces) = measure(cells)
            val firstIndent = if (i == 0) style.textIndentPt else 0.0
            val slack = (contentW - lineWidth - firstIndent).coerceAtLeast(0.0)
            val justify = style.textAlign == TextAlign.JUSTIFY && i != cellLines.lastIndex && interiorSpaces > 0
            val extraPerSpace = if (justify) slack / interiorSpaces else 0.0
            val alignOffset = when {
                justify -> 0.0
                style.textAlign == TextAlign.RIGHT -> slack
                style.textAlign == TextAlign.CENTER -> slack / 2
                else -> 0.0
            }
            val xStart = contentLeft + firstIndent + alignOffset

            val placed = ArrayList<PlacedRun>()
            if (i == 0 && marker != null) markerRun(marker, style.fontSizePt, contentLeft, markerColor)?.let(placed::add)
            placed.addAll(placeRuns(cells, xStart, extraPerSpace))
            out.add(PositionedLine(placed, y, lineHeight, ascent))
            y += lineHeight
        }
        if (out.isEmpty()) {
            val h = style.lineHeightPt ?: style.fontSizePt * 1.4
            out.add(PositionedLine(emptyList(), topY, h, style.fontSizePt * 0.8))
        }
        return out
    }

    /** Line content width (trailing spaces excluded) + interior space count (for justify). */
    private fun measure(cells: List<Cell>): Pair<Double, Int> {
        var last = cells.size - 1
        while (last >= 0 && cells[last].ch == ' ') last--
        var w = 0.0; var spaces = 0
        for (k in 0..last) { w += cells[k].width; if (cells[k].ch == ' ') spaces++ }
        return w to spaces
    }

    private fun markerRun(marker: String, fontSize: Double, contentLeft: Double, color: RgbColor): PlacedRun? {
        val spec = FontSpec(FontFamily.Serif, bold = false, italic = false)
        var w = 0.0
        val glyphs = ArrayList<TextGlyph>(marker.length)
        for (ch in marker) { glyphs.add(glyph(ch, spec)); w += FontMetrics.advancePt(ch, fontSize) }
        if (glyphs.isEmpty()) return null
        val x = (contentLeft - w - 0.4 * fontSize).coerceAtLeast(0.0)
        return PlacedRun(glyphs, x, fontSize, spec, color)
    }

    private class Cell(
        val ch: Char, val width: Double, val fontSize: Double,
        val spec: FontSpec, val color: RgbColor, val shift: Double, val underline: Boolean,
        val face: EmbeddedFace? = null, val gid: Int = -1,
    )

    private sealed class Token {
        class Word(val cells: List<Cell>, val width: Double) : Token()
        class Space(val width: Double) : Token()
        object Break : Token()
    }

    private fun tokenize(runs: List<InlineRun>): List<Token> {
        val tokens = ArrayList<Token>()
        var word = ArrayList<Cell>()
        var wordW = 0.0
        fun endWord() { if (word.isNotEmpty()) { tokens.add(Token.Word(word, wordW)); word = ArrayList(); wordW = 0.0 } }
        for (run in runs) {
            if (run.hardBreak) { endWord(); tokens.add(Token.Break); continue }
            val fs = run.fontSizePt
            val shift = when (run.valign) {
                CssVAlign.SUPER -> fs * 0.33
                CssVAlign.SUB -> -fs * 0.16
                CssVAlign.BASELINE -> 0.0
            }
            val spec = fontSpec(run.family, run.bold, run.italic)
            val face = fonts.match(run.fontFamilyName, run.bold, run.italic)
            for (ch in run.text) when {
                ch == '\n' -> { endWord(); tokens.add(Token.Break) }
                ch == '\r' -> {}
                ch.isWhitespace() -> {
                    endWord()
                    val sw = if (face != null) face.advance1000(face.gidFor(' '.code)) * fs / 1000.0
                    else FontMetrics.advancePt(' ', fs, run.bold, run.italic, run.family)
                    tokens.add(Token.Space(sw))
                }
                else -> {
                    if (face != null) {
                        val gid = face.gidFor(ch.code)
                        val w = face.advance1000(gid) * fs / 1000.0
                        word.add(Cell(ch, w, fs, spec, run.color, shift, run.underline, face, gid)); wordW += w
                    } else {
                        val w = FontMetrics.advancePt(ch, fs, run.bold, run.italic, run.family)
                        word.add(Cell(ch, w, fs, spec, run.color, shift, run.underline)); wordW += w
                    }
                }
            }
        }
        endWord()
        return tokens
    }

    private fun wrap(tokens: List<Token>, avail: Double, preserve: Boolean): List<List<Cell>> {
        val lines = ArrayList<List<Cell>>()
        var line = ArrayList<Cell>()
        var lineW = 0.0
        var pendingSpace = 0.0
        fun commit() { lines.add(line); line = ArrayList(); lineW = 0.0; pendingSpace = 0.0 }
        for (tok in tokens) when (tok) {
            is Token.Break -> commit()
            is Token.Space -> when {
                preserve -> { line.add(spacer(tok.width)); lineW += tok.width }
                line.isNotEmpty() -> pendingSpace += tok.width
            }
            is Token.Word -> {
                if (line.isNotEmpty() && lineW + pendingSpace + tok.width > avail) commit()
                if (line.isNotEmpty() && pendingSpace > 0.0) { line.add(spacer(pendingSpace)); lineW += pendingSpace }
                pendingSpace = 0.0
                line.addAll(tok.cells); lineW += tok.width
            }
        }
        if (line.isNotEmpty() || lines.isEmpty()) lines.add(line)
        return lines
    }

    private fun spacer(width: Double) = Cell(' ', width, 0.0, EMPTY_SPEC, BLACK, 0.0, false)

    private fun placeRuns(cells: List<Cell>, xStart: Double, extraPerSpace: Double): List<PlacedRun> {
        val out = ArrayList<PlacedRun>()
        var x = xStart
        var i = 0
        while (i < cells.size) {
            val c = cells[i]
            if (c.ch == ' ') { x += c.width + extraPerSpace; i++; continue }
            val startX = x
            val spec = c.spec; val fs = c.fontSize; val col = c.color; val sh = c.shift; val ul = c.underline; val face = c.face
            val glyphs = ArrayList<TextGlyph>()
            while (i < cells.size && cells[i].ch != ' ' && samePaint(cells[i], spec, fs, col, sh, ul, face)) {
                glyphs.add(glyphFor(cells[i])); x += cells[i].width; i++
            }
            out.add(PlacedRun(glyphs, startX, fs, spec, col, sh, ul, hasOutlines = face != null, unitsPerEm = face?.unitsPerEm ?: 1000))
        }
        return out
    }

    private fun samePaint(c: Cell, spec: FontSpec, fs: Double, col: RgbColor, sh: Double, ul: Boolean, face: EmbeddedFace?): Boolean =
        c.spec == spec && c.fontSize == fs && c.color == col && c.shift == sh && c.underline == ul && c.face === face

    private fun glyphFor(c: Cell): TextGlyph {
        val face = c.face ?: return glyph(c.ch, c.spec)
        return TextGlyph(
            byteOffset = 0, byteCount = 1, gid = c.gid, text = c.ch.toString(),
            advanceWidth = face.advance1000(c.gid).toDouble(), outline = face.outline(c.gid), isWordSpace = c.ch == ' ',
        )
    }

    private fun fontSpec(family: GenericFont, bold: Boolean, italic: Boolean) = FontSpec(
        when (family) {
            GenericFont.MONO -> FontFamily.Monospace
            GenericFont.SANS -> FontFamily.SansSerif
            GenericFont.SERIF -> FontFamily.Serif
        },
        bold, italic,
    )

    private fun glyph(ch: Char, spec: FontSpec): TextGlyph {
        val fam = when (spec.family) {
            FontFamily.Monospace -> GenericFont.MONO
            FontFamily.SansSerif -> GenericFont.SANS
            FontFamily.Serif -> GenericFont.SERIF
        }
        return TextGlyph(
            byteOffset = 0, byteCount = 1, gid = -1, text = ch.toString(),
            advanceWidth = FontMetrics.advance1000(ch.code, spec.bold, spec.italic, fam).toDouble(),
            outline = null, isWordSpace = ch == ' ',
        )
    }

    private companion object {
        val BLACK = RgbColor(0.0, 0.0, 0.0)
        val EMPTY_SPEC = FontSpec(FontFamily.Serif, bold = false, italic = false)
    }
}
