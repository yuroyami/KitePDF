package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.ComputedStyle
import io.github.yuroyami.kitepdf.epub.css.CssPosition
import io.github.yuroyami.kitepdf.epub.css.CssVAlign
import io.github.yuroyami.kitepdf.epub.css.Direction
import io.github.yuroyami.kitepdf.epub.css.ObjectFit
import io.github.yuroyami.kitepdf.epub.css.GenericFont
import io.github.yuroyami.kitepdf.epub.css.TextAlign
import io.github.yuroyami.kitepdf.epub.css.WhiteSpaceMode
import io.github.yuroyami.kitepdf.text.Bidi
import io.github.yuroyami.kitepdf.font.FontFamily
import io.github.yuroyami.kitepdf.font.FontSpec
import io.github.yuroyami.kitepdf.font.TextGlyph
import io.github.yuroyami.kitepdf.render.ImageXObject
import io.github.yuroyami.kitepdf.render.RgbColor
import io.github.yuroyami.kitepdf.text.Hyphenator

// GSUB ligature features, applied required-first: Arabic lam-alef (`rlig`) then
// discretionary Latin ligatures like fi/fl (`liga`).
private val LIG_FEATURES = listOf("rlig", "liga")

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
    private val loadSvg: (String) -> SvgImage? = { null },
    private val maxImageHeight: Double = Double.MAX_VALUE,
    private val fonts: FontRegistry = FontRegistry.EMPTY,
) {
    private val hyphenator by lazy { Hyphenator.enUs() }

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
            // Out-of-flow (position:absolute/fixed): place at its insets relative to
            // this containing block; it does NOT advance the normal-flow cursor. Used
            // mainly by fixed-layout pages to overlay panels/captions.
            val pos = (child as? BlockBox)?.style?.position
            if (pos == CssPosition.ABSOLUTE || pos == CssPosition.FIXED) {
                layoutAbsolute(child, contentLeft, contentTop, contentW); continue
            }
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

    /**
     * Place a position:absolute/fixed block at its `left`/`top` insets within the
     * containing block's content box, sizing from `width` (or `left`+`right`), then
     * lay out its subtree there. Out of flow, so it never shifts sibling content.
     */
    private fun layoutAbsolute(box: BlockBox, cbLeft: Double, cbTop: Double, cbWidth: Double) {
        val s = box.style
        val extra = s.borderLeft.effective + s.borderRight.effective + s.paddingLeftPt + s.paddingRightPt
        val contentW = s.widthPt
            ?: if (s.leftPt != null && s.rightPt != null) (cbWidth - s.leftPt - s.rightPt - extra).coerceAtLeast(0.0)
            else (cbWidth - extra).coerceAtLeast(0.0)
        val x = cbLeft + (s.leftPt ?: 0.0)
        val y = cbTop + (s.topPt ?: 0.0)
        layoutBlock(box, x, contentW + extra, y)
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
        // SVG (inline <svg> preset, or a .svg file reference) sizes from its intrinsic
        // viewport and paints as vectors; raster images decode to an ImageXObject.
        val svg = box.svg ?: if (box.zipPath.endsWith(".svg", true)) loadSvg(box.zipPath)?.also { box.svg = it } else null
        val intrinsicW: Double; val intrinsicH: Double
        if (svg != null) {
            intrinsicW = svg.width; intrinsicH = svg.height
        } else {
            val img = loadImage(box.zipPath)
            if (img == null || img.width <= 0 || img.height <= 0) {
                box.x = contentLeft; box.y = topY; box.borderBoxWidth = 0.0; box.borderBoxHeight = 0.0; return
            }
            box.image = img
            intrinsicW = img.width.toDouble(); intrinsicH = img.height.toDouble()
        }
        val aspect = intrinsicH / intrinsicW
        // Honour explicit CSS width/height (then the HTML width/height attributes),
        // deriving the missing dimension from the intrinsic aspect ratio; fall back
        // to full content width. Scale down proportionally past max-width / content /
        // max-height so the image never overflows its column.
        val ew = box.style.widthPt ?: box.attrWidth
        val eh = box.style.heightPt ?: box.attrHeight
        var w = ew ?: (eh?.let { it / aspect } ?: contentW)
        var h = eh ?: (w * aspect)
        // object-fit: contain — when both dimensions are fixed, letterbox the image to
        // preserve its aspect ratio inside the box (default `fill` stretches to w×h).
        if (ew != null && eh != null && box.style.objectFit == ObjectFit.CONTAIN) {
            val scale = minOf(ew / intrinsicW, eh / intrinsicH)
            w = intrinsicW * scale; h = intrinsicH * scale
        }
        val cap = minOf(box.style.maxWidthPt ?: Double.MAX_VALUE, contentW)
        if (w > cap) { val s = cap / w; w = cap; h *= s }
        if (h > maxImageHeight) { val s = maxImageHeight / h; h = maxImageHeight; w *= s }
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
        val baseLevel = if (style.direction == Direction.RTL) 1 else 0
        // `start` alignment resolves to the base direction's edge.
        val align = if (style.direction == Direction.RTL && style.textAlign == TextAlign.LEFT) TextAlign.RIGHT else style.textAlign
        val cellLines = wrap(tokenize(runs, style.hyphensAuto), contentW, preserve)
        val out = ArrayList<PositionedLine>(cellLines.size)
        var y = topY
        cellLines.forEachIndexed { i, logical ->
            val cells = bidiReorder(logical, baseLevel) // logical → visual order (UAX #9 L2)
            val maxFs = cells.maxOfOrNull { it.fontSize }?.takeIf { it > 0.0 } ?: style.fontSizePt
            val lineHeight = style.lineHeightPt ?: maxFs * 1.4
            val ascent = maxFs * 0.8

            val (lineWidth, interiorSpaces) = measure(cells)
            val firstIndent = if (i == 0) style.textIndentPt else 0.0
            val slack = (contentW - lineWidth - firstIndent).coerceAtLeast(0.0)
            val justify = align == TextAlign.JUSTIFY && i != cellLines.lastIndex && interiorSpaces > 0
            val extraPerSpace = if (justify) slack / interiorSpaces else 0.0
            val alignOffset = when {
                justify -> 0.0
                align == TextAlign.RIGHT -> slack
                align == TextAlign.CENTER -> slack / 2
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

    /** Reorder a line's cells from logical to visual order (bidi L2); identity for pure-LTR lines. */
    private fun bidiReorder(cells: List<Cell>, baseLevel: Int): List<Cell> {
        if (cells.isEmpty()) return cells
        if (baseLevel == 0 && cells.none { val c = Bidi.classify(it.ch.code); c == Bidi.R || c == Bidi.AL }) return cells
        val cps = IntArray(cells.size) { cells[it].ch.code }
        val order = Bidi.reorderVisually(Bidi.resolveLevels(cps, baseLevel))
        return order.map { cells[it] }
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
        val ch: Char, var width: Double, val fontSize: Double,
        val spec: FontSpec, val color: RgbColor, val shift: Double, val underline: Boolean,
        val face: EmbeddedFace? = null, var gid: Int = -1,
        // Kerning to the next glyph (1/1000 em), folded into this glyph's advance.
        var kernAfter1000: Int = 0,
        // GPOS mark attachment offset in font design units (0 for non-marks).
        var glyphXOffset: Double = 0.0, var glyphYOffset: Double = 0.0,
    )

    private sealed class Token {
        class Word(val cells: List<Cell>, val width: Double, val hyphenPoints: List<Int> = emptyList()) : Token()
        class Space(val width: Double) : Token()
        object Break : Token()
    }

    private fun tokenize(runs: List<InlineRun>, hyphensAuto: Boolean): List<Token> {
        val tokens = ArrayList<Token>()
        var word = ArrayList<Cell>()
        var wordW = 0.0
        var softHyphens = ArrayList<Int>()
        fun endWord() {
            if (word.isNotEmpty()) {
                shapeArabic(word)               // contextual joining (1:1 gid remap)
                val ligated = applyLigatures(word) // liga/rlig (may collapse cells)
                positionMarks(word)             // GPOS mark-to-base attachment
                kernWord(word)
                // Skip hyphenation when a ligature collapsed cells (soft-hyphen indices
                // + the reconstructed word text would no longer line up).
                val pts = if (ligated) emptyList() else {
                    val s = LinkedHashSet(softHyphens)
                    if (hyphensAuto && word.size >= 5 && word.all { it.ch.isLetter() }) {
                        val text = buildString { word.forEach { append(it.ch) } }
                        s.addAll(hyphenator.hyphenate(text))
                    }
                    s.sorted()
                }
                tokens.add(Token.Word(word, word.sumOf { it.width }, pts))
                word = ArrayList(); wordW = 0.0; softHyphens = ArrayList()
            }
        }
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
            fun cellFor(ch: Char): Cell {
                // Per-glyph fallback: a codepoint missing from the matched face
                // (cmap -> gid 0, `.notdef`) must not paint tofu. Try any other
                // registered face that has it; failing that, the generic
                // FontMetrics cell (face null) rides the system-font path.
                // Mixed-face words degrade gracefully: the shaping passes skip
                // multi-face words and placeRuns splits runs on a face change.
                val f = when {
                    face == null -> null
                    face.gidFor(ch.code) != 0 -> face
                    else -> fonts.fallbackFor(ch.code, run.bold, run.italic)
                }
                return if (f != null) {
                    val gid = f.gidFor(ch.code)
                    Cell(ch, f.advance1000(gid) * fs / 1000.0, fs, spec, run.color, shift, run.underline, f, gid)
                } else {
                    Cell(ch, FontMetrics.advancePt(ch, fs, run.bold, run.italic, run.family), fs, spec, run.color, shift, run.underline)
                }
            }
            for (ch in run.text) when {
                ch == '\n' -> { endWord(); tokens.add(Token.Break) }
                ch == '\r' -> {}
                ch.code == 0x00AD -> softHyphens.add(word.size) // soft hyphen: a break point, drawn only if used
                ch.isWhitespace() -> {
                    endWord()
                    val sw = if (face != null) face.advance1000(face.gidFor(' '.code)) * fs / 1000.0
                    else FontMetrics.advancePt(' ', fs, run.bold, run.italic, run.family)
                    tokens.add(Token.Space(sw))
                }
                FontMetrics.isWide(ch.code) -> {
                    // CJK ideographs break per character; a closing punctuation stays with the char before it.
                    endWord()
                    val cell = cellFor(ch)
                    val last = tokens.lastOrNull()
                    if (isCloser(ch.code) && last is Token.Word) {
                        tokens[tokens.lastIndex] = Token.Word(last.cells + cell, last.width + cell.width)
                    } else {
                        tokens.add(Token.Word(listOf(cell), cell.width))
                    }
                }
                else -> { val c = cellFor(ch); word.add(c); wordW += c.width }
            }
        }
        endWord()
        return tokens
    }

    /**
     * Arabic contextual joining: remap each letter's glyph to its initial/medial/
     * final/isolated form via the matching GSUB feature. 1:1, so cell count and
     * hyphenation indices are unchanged. Single-face words only (the common case).
     */
    private fun shapeArabic(cells: MutableList<Cell>) {
        val face = cells.firstOrNull()?.face ?: return
        if (!face.hasArabicJoining || cells.any { it.face !== face }) return
        val cps = IntArray(cells.size) { cells[it].ch.code }
        if (!ArabicJoining.hasArabic(cps)) return
        val forms = ArabicJoining.forms(cps)
        for (i in cells.indices) {
            val c = cells[i]
            if (c.gid < 0) continue
            val newGid = face.substSingle(ArabicJoining.feature(forms[i]), c.gid)
            if (newGid != c.gid) {
                c.gid = newGid
                c.width = face.advance1000(newGid) * c.fontSize / 1000.0
            }
        }
    }

    /**
     * Apply GSUB ligatures (`rlig` then `liga`) greedily, longest match first,
     * collapsing a run of component glyphs into one ligature cell. Returns true if
     * anything changed. Single-face words only.
     */
    private fun applyLigatures(cells: MutableList<Cell>): Boolean {
        val face = cells.firstOrNull()?.face ?: return false
        if (cells.any { it.face !== face }) return false
        var changed = false
        var i = 0
        while (i < cells.size) {
            val first = cells[i]
            if (first.gid < 0) { i++; continue }
            val rule = LIG_FEATURES.firstNotNullOfOrNull { feat ->
                face.ligatures(feat, first.gid)?.firstOrNull { r ->
                    i + r.rest.size < cells.size &&
                        r.rest.indices.all { j -> cells[i + 1 + j].gid == r.rest[j] }
                }
            }
            if (rule != null) {
                val ligGid = rule.lig
                val lig = Cell(
                    first.ch, face.advance1000(ligGid) * first.fontSize / 1000.0, first.fontSize,
                    first.spec, first.color, first.shift, first.underline, face, ligGid,
                )
                repeat(rule.rest.size + 1) { cells.removeAt(i) }
                cells.add(i, lig)
                changed = true
            }
            i++
        }
        return changed
    }

    /**
     * GPOS mark-to-base positioning: attach each combining mark to the current base
     * glyph via its anchor offset (font units), correcting for how far the pen has
     * advanced since the base. Marks are zero-advance, so several stack on one base.
     */
    private fun positionMarks(cells: List<Cell>) {
        var base: Cell? = null
        var advSinceBase = 0.0 // font units from the base origin to the current pen
        for (c in cells) {
            val face = c.face
            val b = base
            if (face != null && b != null && b.face === face && c.gid >= 0) {
                val off = face.markOffset(b.gid, c.gid)
                if (off != null) {
                    c.glyphXOffset = off.first - advSinceBase
                    c.glyphYOffset = off.second
                    advSinceBase += face.advanceRaw(c.gid) // usually 0 for a mark
                    continue // still attached to the same base
                }
            }
            base = c
            advSinceBase = if (face != null && c.gid >= 0) face.advanceRaw(c.gid).toDouble() else 0.0
        }
    }

    /**
     * Apply horizontal kerning between adjacent same-face glyphs in a word: fold
     * the pair adjustment into the left glyph's advance (both its wrap [Cell.width]
     * and its drawn [Cell.kernAfter1000], kept in sync).
     */
    private fun kernWord(cells: List<Cell>) {
        for (i in 0 until cells.size - 1) {
            val a = cells[i]; val b = cells[i + 1]
            val face = a.face ?: continue
            if (face !== b.face || a.gid < 0 || b.gid < 0) continue
            val k = face.kern1000(a.gid, b.gid)
            if (k != 0) { a.kernAfter1000 = k; a.width += k * a.fontSize / 1000.0 }
        }
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
                var cells: List<Cell> = tok.cells
                var points: List<Int> = tok.hyphenPoints
                var space = pendingSpace
                pendingSpace = 0.0
                while (true) {
                    val leading = if (line.isNotEmpty()) space else 0.0
                    val w = cells.sumOf { it.width }
                    if (lineW + leading + w <= avail || (line.isEmpty() && points.isEmpty())) {
                        if (line.isNotEmpty() && space > 0.0) { line.add(spacer(space)); lineW += space }
                        line.addAll(cells); lineW += w
                        break
                    }
                    // Largest hyphenation point whose prefix + hyphen still fits the current line.
                    var split = -1
                    var splitHyphenW = 0.0
                    val budget = avail - lineW - leading
                    for (p in points) {
                        if (p <= 0 || p >= cells.size) continue
                        val hw = hyphenWidth(cells[p - 1])
                        if (cells.subList(0, p).sumOf { it.width } + hw <= budget) { split = p; splitHyphenW = hw } else break
                    }
                    if (split > 0) {
                        if (line.isNotEmpty() && space > 0.0) { line.add(spacer(space)); lineW += space }
                        val prefix = cells.subList(0, split)
                        line.addAll(prefix); lineW += prefix.sumOf { it.width }
                        line.add(hyphenCell(cells[split - 1])); lineW += splitHyphenW
                        commit()
                        cells = cells.subList(split, cells.size)
                        points = points.mapNotNull { if (it > split) it - split else null }
                        space = 0.0
                    } else if (line.isEmpty()) {
                        line.addAll(cells); lineW += w // unsplittable + nothing before: overflow rather than loop
                        break
                    } else {
                        commit(); space = 0.0 // retry on a fresh line
                    }
                }
            }
        }
        if (line.isNotEmpty() || lines.isEmpty()) lines.add(line)
        return lines
    }

    private fun spacer(width: Double) = Cell(' ', width, 0.0, EMPTY_SPEC, BLACK, 0.0, false)

    private fun hyphenWidth(c: Cell): Double = c.face?.let { it.advance1000(it.gidFor('-'.code)) * c.fontSize / 1000.0 }
        ?: FontMetrics.advancePt('-', c.fontSize, c.spec.bold, c.spec.italic, genericOf(c.spec))

    private fun hyphenCell(c: Cell): Cell {
        val face = c.face
        return if (face != null) Cell('-', hyphenWidth(c), c.fontSize, c.spec, c.color, c.shift, c.underline, face, face.gidFor('-'.code))
        else Cell('-', hyphenWidth(c), c.fontSize, c.spec, c.color, c.shift, c.underline)
    }

    private fun genericOf(spec: FontSpec): GenericFont = when (spec.family) {
        FontFamily.Monospace -> GenericFont.MONO
        FontFamily.SansSerif -> GenericFont.SANS
        else -> GenericFont.SERIF
    }

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
            // Pair kerning to the next glyph is folded into this glyph's advance so
            // the drawn pen movement matches the wrap width.
            advanceWidth = (face.advance1000(c.gid) + c.kernAfter1000).toDouble(),
            outline = face.outline(c.gid), isWordSpace = c.ch == ' ',
            xOffset = c.glyphXOffset, yOffset = c.glyphYOffset,
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

    /** CJK closing punctuation that must not start a line (basic kinsoku, no-break-before). */
    private fun isCloser(cp: Int): Boolean = cp in CJK_CLOSERS

    private companion object {
        val BLACK = RgbColor(0.0, 0.0, 0.0)
        val EMPTY_SPEC = FontSpec(FontFamily.Serif, bold = false, italic = false)
        val CJK_CLOSERS = setOf(
            0x3001, 0x3002, // 、 。
            0xFF0C, 0xFF0E, 0xFF01, 0xFF1F, 0xFF1B, 0xFF1A, // fullwidth , . ! ? ; :
            0x300D, 0x300F, 0x3011, 0x3015, 0x3009, 0x300B, 0x3017, // 」 』 】 〕 〉 》 〗
            0xFF09, 0xFF3D, 0xFF5D, // ） ］ ｝
        )
    }
}
