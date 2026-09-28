package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.GsubGlyph
import io.github.yuroyami.kitepdf.core.font.OpenTypeGsub
import io.github.yuroyami.kitepdf.svg.SvgImage

import io.github.yuroyami.kitepdf.epub.css.ComputedStyle
import io.github.yuroyami.kitepdf.epub.css.CssBackground
import io.github.yuroyami.kitepdf.epub.css.CssClear
import io.github.yuroyami.kitepdf.epub.css.CssFloat
import io.github.yuroyami.kitepdf.epub.css.CssPosition
import io.github.yuroyami.kitepdf.epub.css.CssVAlign
import io.github.yuroyami.kitepdf.epub.css.DecorationLine
import io.github.yuroyami.kitepdf.epub.css.Display
import io.github.yuroyami.kitepdf.epub.css.Direction
import io.github.yuroyami.kitepdf.epub.css.FlexAlign
import io.github.yuroyami.kitepdf.epub.css.FlexBasis
import io.github.yuroyami.kitepdf.epub.css.FlexDirection
import io.github.yuroyami.kitepdf.epub.css.FlexJustify
import io.github.yuroyami.kitepdf.epub.css.FlexStyle
import io.github.yuroyami.kitepdf.epub.css.FlexWrap
import io.github.yuroyami.kitepdf.epub.css.GridLine
import io.github.yuroyami.kitepdf.epub.css.GridSize
import io.github.yuroyami.kitepdf.epub.css.GridStyle
import io.github.yuroyami.kitepdf.epub.css.GridTrack
import io.github.yuroyami.kitepdf.epub.css.GridTracks
import io.github.yuroyami.kitepdf.epub.css.ObjectFit
import io.github.yuroyami.kitepdf.epub.css.GenericFont
import io.github.yuroyami.kitepdf.epub.css.TextAlign
import io.github.yuroyami.kitepdf.epub.css.WhiteSpaceMode
import io.github.yuroyami.kitepdf.core.text.Bidi
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.text.Hyphenator
import io.github.yuroyami.kitepdf.core.KiteLineEnd
import kotlin.math.roundToInt

// GSUB ligature features, applied required-first: Arabic lam-alef (`rlig`) then
// discretionary Latin ligatures like fi/fl (`liga`).

/**
 * Positions the [LayoutBox] tree in document space (x from content-left, y down).
 * Resolves each block's box model: margin / border / padding / width (auto-fill,
 * explicit, max-width). Collapses adjacent sibling vertical margins, stacks
 * children, and lays inline content into line boxes honouring `text-align`
 * (incl. **justify**), first-line `text-indent`, `line-height`, and vertical-align.
 * Anonymous [TextBlockBox]es carry no box decorations (their parent [BlockBox]
 * owns the margin/border/padding/background).
 */
internal class BoxLayout(
    private val loadImage: (String) -> KiteImageData? = { null },
    private val loadSvg: (String) -> SvgImage? = { null },
    private val maxImageHeight: Double = Double.MAX_VALUE,
    private val fonts: FontRegistry = FontRegistry.EMPTY,
    /**
     * BCP-47 language tag of the spine document being laid out (its own
     * `xml:lang`/`lang`, else the OPF `dc:language`); selects the hyphenation
     * pattern set, so each spine item can hyphenate in its own language.
     * Null/unknown languages hyphenate with the en-US set, preserving the old
     * behaviour.
     */
    private val language: String? = null,
    /**
     * Reader-settings multiplier on every line's height (leading), applied at
     * consumption so authored and default (`normal`) line heights both scale
     * and inherited computed values are never scaled twice. Ascent is
     * untouched: extra height is leading, as readers expect.
     */
    private val lineHeightScale: Double = 1.0,
    /**
     * Vertical writing (`writing-mode: vertical-rl`). Layout keeps its
     * logical axes (the "width" passed to [layout] is the page content
     * HEIGHT). Upright glyphs use the face's `vmtx` advance (else 1em), while
     * replaced images keep physical width/height and map them to logical axes.
     */
    private val vertical: Boolean = false,
) {
    private val hyphenator by lazy { Hyphenator.forLanguage(language) ?: Hyphenator.enUs() }

    /** Logical pen advance of [gid] in 1/1000 em, honouring vertical mode for upright glyphs. */
    private fun penAdvance1000(face: EmbeddedFace, gid: Int, cp: Int): Int =
        if (vertical && FontMetrics.isWide(cp)) face.advanceHeight1000(gid) ?: 1000
        else face.advance1000(gid)

    /**
     * A float's exclusion band: text lines whose y-range overlaps it shorten
     * their available width on the float's side. One document-wide list
     * (bands are y-ranged, so unrelated regions are unaffected).
     * Simplifications, per the audit: same-side floats stack downward (never
     * side by side), floats do not escape their containing block's height,
     * and pagination treats a float as one unbreakable unit.
     */
    private class FloatBand(val left: Boolean, val xStart: Double, val xEnd: Double, val yTop: Double, val yBottom: Double)

    private val activeFloats = ArrayList<FloatBand>()

    /**
     * The rectangle an out-of-flow box measures its insets against: the padding
     * box of the nearest positioned ancestor, or the page for `position: fixed`
     * and for absolute boxes with no positioned ancestor at all. Filled once the
     * owning box knows its own size, which is why placement is deferred.
     */
    private class AbsContainingBlock {
        var left = 0.0; var top = 0.0; var width = 0.0; var height = 0.0
    }

    private class PendingAbs(val box: LayoutBox, val cb: AbsContainingBlock)

    private val pendingAbs = ArrayList<PendingAbs>()
    private val pageCb = AbsContainingBlock()
    private var currentCb = pageCb

    /**
     * Lay out [root] to fill [contentWidth]; returns total document height.
     * [contentHeight] is the page box that `position: fixed` and bottom insets
     * measure against; without it the laid-out document height stands in.
     */
    fun layout(root: BlockBox, contentWidth: Double, contentHeight: Double? = null): Double {
        activeFloats.clear()
        pendingAbs.clear()
        pageCb.left = 0.0; pageCb.top = 0.0
        pageCb.width = contentWidth; pageCb.height = contentHeight ?: 0.0
        currentCb = pageCb
        layoutBlock(root, xLeft = 0.0, availWidth = contentWidth, topY = 0.0)
        if (contentHeight == null) pageCb.height = root.borderBoxHeight
        flushAbs(pageCb)
        applyRelativeOffsets(root)
        return root.borderBoxHeight
    }

    /**
     * `position: relative` is a pure paint-time offset (CSS: the box's flow
     * position and its siblings are unaffected), applied after layout to the
     * box and its whole subtree. `left` wins over `right`, `top` over `bottom`.
     */
    private fun applyRelativeOffsets(box: LayoutBox) {
        // A TextBlockBox is the anonymous inline container and borrows its
        // parent's style, so shifting it too would apply the offset twice.
        if (box !is TextBlockBox && box.style.position == CssPosition.RELATIVE) {
            val s = box.style
            val dx = s.leftPt ?: s.rightPt?.let { -it } ?: 0.0
            val dy = s.topPt ?: s.bottomPt?.let { -it } ?: 0.0
            if (dx != 0.0 || dy != 0.0) shiftSubtree(box, dx, dy)
        }
        when (box) {
            is BlockBox -> for (c in box.children) applyRelativeOffsets(c)
            is TableBox -> for (r in box.rows) for (cell in r.cells) applyRelativeOffsets(cell)
            else -> {}
        }
    }

    private fun shiftSubtree(box: LayoutBox, dx: Double, dy: Double) {
        box.x += dx; box.y += dy
        when (box) {
            is BlockBox -> for (c in box.children) shiftSubtree(c, dx, dy)
            is TableBox -> for (r in box.rows) shiftSubtree(r, dx, dy)
            is TableRowBox -> for (c in box.cells) shiftSubtree(c, dx, dy)
            is TextBlockBox -> for (ln in box.lines) {
                ln.yTop += dy
                for (r in ln.runs) r.x += dx
                for (im in ln.images) im.x += dx
            }
            is ImageBox -> {}
        }
    }

    /**
     * Lays out [box] at [topY] in a column [availWidth] wide from [xLeft]. A flex container that
     * places [box] as its item gives [forcedWidth], the border-box width it resolved; the box then
     * starts at [xLeft] and takes that width whatever its own width and margins say (#33).
     */
    private fun layoutBlock(box: BlockBox, xLeft: Double, availWidth: Double, topY: Double, forcedWidth: Double? = null) {
        val s = box.style
        // A positioned box is the containing block for its out-of-flow
        // descendants, so it opens one and fills it in once its size is known.
        val savedCb = currentCb
        val ownCb = if (s.position != CssPosition.STATIC) AbsContainingBlock() else null
        if (ownCb != null) currentCb = ownCb
        val bL = s.borderLeft.effective; val bR = s.borderRight.effective
        val extra = s.marginLeftPt + s.marginRightPt + bL + bR + s.paddingLeftPt + s.paddingRightPt
        var contentW = s.widthPt ?: (availWidth - extra)
        s.maxWidthPt?.let { if (contentW > it) contentW = it }
        s.minWidthPt?.let { if (contentW < it) contentW = it } // min wins over max
        // An embedded document's default width never pushes it past its column (#40).
        if (box.embed != null) contentW = contentW.coerceAtMost(availWidth - extra)
        if (forcedWidth != null) contentW = forcedWidth - (bL + s.paddingLeftPt + s.paddingRightPt + bR)
        contentW = contentW.coerceAtLeast(0.0)

        box.borderBoxWidth = bL + s.paddingLeftPt + contentW + s.paddingRightPt + bR
        val leftMargin = when {
            forcedWidth != null -> 0.0
            s.marginLeftAuto && s.marginRightAuto -> maxOf(0.0, (availWidth - box.borderBoxWidth) / 2)
            else -> s.marginLeftPt
        }
        box.x = xLeft + leftMargin
        box.y = topY
        val contentLeft = box.x + bL + s.paddingLeftPt
        val contentTop = box.y + s.borderTop.effective + s.paddingTopPt

        var cursorY = contentTop
        var prevBottom = 0.0
        var first = true
        val floatsBefore = activeFloats.size
        // A flex or grid container places its children as items instead of stacking them (#33, #35).
        val itemLayout = s.display == Display.FLEX || s.display == Display.GRID
        val flexChildren = if (itemLayout) emptyList() else box.children
        if (itemLayout) {
            val definite = s.heightPt?.let { h -> h.coerceAtMost(s.maxHeightPt ?: h).coerceAtLeast(s.minHeightPt ?: 0.0) }
            cursorY += if (s.display == Display.GRID) layoutGrid(box, contentLeft, contentW, contentTop, definite)
            else layoutFlex(box, contentLeft, contentW, contentTop, definite)
        }
        for (child in flexChildren) {
            // Out-of-flow (position:absolute/fixed): queued now, placed once its
            // containing block knows its own size. It never advances the
            // normal-flow cursor. Fixed-layout pages use this to overlay panels.
            val pos = if (child is TextBlockBox) CssPosition.STATIC else child.style.position
            if (pos == CssPosition.ABSOLUTE || pos == CssPosition.FIXED) {
                pendingAbs.add(PendingAbs(child, if (pos == CssPosition.FIXED) pageCb else currentCb))
                continue
            }
            // clear: the flow cursor drops below matching floats before this
            // child lays out (margin collapse across clearance not modelled).
            if (child.style.clear != CssClear.NONE) {
                cursorY = maxOf(cursorY, clearY(child.style.clear))
            }
            // float:left/right leaves the flow: it lays out against the content
            // edge at the current y, registers an exclusion band that shortens
            // overlapping text lines, and does not advance the flow cursor.
            if (child.style.cssFloat != CssFloat.NONE && child !is TableRowBox) {
                val topMargin = if (child is BlockBox) child.style.marginTopPt else 0.0
                placeFloat(child, contentLeft, contentW, cursorY + topMargin)
                continue
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
        // A float may extend below the last in-flow child; grow the block to
        // contain it so pagination never splits content across a float (CSS
        // lets floats overflow their block, a deliberate simplification here).
        for (k in floatsBefore until activeFloats.size) {
            cursorY = maxOf(cursorY, activeFloats[k].yBottom)
        }

        val natural = cursorY - contentTop
        var contentH = natural
        s.heightPt?.let { contentH = it }
        s.maxHeightPt?.let { if (contentH > it) contentH = it }
        s.minHeightPt?.let { if (contentH < it) contentH = it } // min wins over max
        // Reflow safety: a declared height may GROW a box but never clip flowed
        // content. Clipping would make following siblings (and the next spine)
        // overlap the overflow at pagination, silently losing book content
        // (html,body{height:100%} is everywhere in real EPUB CSS).
        if (contentH < natural) contentH = natural
        box.borderBoxHeight = s.borderTop.effective + s.paddingTopPt + contentH + s.paddingBottomPt + s.borderBottom.effective

        if (ownCb != null) {
            // The padding box, which is what CSS measures absolute insets against.
            ownCb.left = box.x + bL
            ownCb.top = box.y + s.borderTop.effective
            ownCb.width = s.paddingLeftPt + contentW + s.paddingRightPt
            ownCb.height = box.borderBoxHeight - s.borderTop.effective - s.borderBottom.effective
            currentCb = savedCb
            flushAbs(ownCb)
        }
    }

    /** Place every queued out-of-flow box whose containing block is [cb]. */
    private fun flushAbs(cb: AbsContainingBlock) {
        val saved = currentCb
        currentCb = cb
        while (true) {
            val i = pendingAbs.indexOfFirst { it.cb === cb }
            if (i < 0) break
            layoutAbsolute(pendingAbs.removeAt(i).box, cb)
        }
        currentCb = saved
    }

    /**
     * Place a position:absolute/fixed box against [cb]. Width comes from `width`,
     * else from `left`+`right`, else from what is left of the containing block.
     * `right`/`bottom` alone need the laid-out size, so they shift afterwards.
     * Out of flow, so it never moves sibling content.
     */
    private fun layoutAbsolute(box: LayoutBox, cb: AbsContainingBlock) {
        val s = box.style
        val extra = s.marginLeftPt + s.marginRightPt +
            s.borderLeft.effective + s.borderRight.effective + s.paddingLeftPt + s.paddingRightPt
        val left = s.leftPt
        val right = s.rightPt
        val contentW = s.widthPt
            ?: (cb.width - (left ?: 0.0) - (right ?: 0.0) - extra).coerceAtLeast(0.0)
        val x = cb.left + (left ?: 0.0)
        val y = cb.top + (s.topPt ?: 0.0)
        layoutChild(box, x, if (box is BlockBox) contentW + extra else contentW, y)

        // top AND bottom with no height: the insets set the height.
        if (s.topPt != null && s.bottomPt != null && s.heightPt == null) {
            val h = (cb.height - s.topPt!! - s.bottomPt!!).coerceAtLeast(0.0)
            if (h > box.borderBoxHeight) box.borderBoxHeight = h
        }
        var dx = 0.0
        var dy = 0.0
        if (left == null && right != null) dx = (cb.left + cb.width - right - box.borderBoxWidth) - box.x
        if (s.topPt == null && s.bottomPt != null) {
            dy = (cb.top + cb.height - s.bottomPt!! - box.borderBoxHeight) - box.y
        }
        if (dx != 0.0 || dy != 0.0) shiftSubtree(box, dx, dy)
    }

    /** y below every float matching [clear] (the current cursor if none do). */
    private fun clearY(clear: CssClear): Double {
        var y = 0.0
        for (f in activeFloats) {
            val matches = clear == CssClear.BOTH || (clear == CssClear.LEFT && f.left) || (clear == CssClear.RIGHT && !f.left)
            if (matches && f.yBottom > y) y = f.yBottom
        }
        return y
    }

    /**
     * Lay out a floated child against the left/right content edge at [topY]
     * and register its exclusion band. Width: an image floats at its natural
     * draw size; a block floats at its explicit CSS width, else half the
     * content width (a shrink-to-fit approximation, commented simplification).
     */
    private fun placeFloat(child: LayoutBox, contentLeft: Double, contentW: Double, topY: Double) {
        val s = child.style
        when (child) {
            is ImageBox -> layoutImage(child, contentLeft, contentW, topY)
            is BlockBox -> {
                val extra = s.marginLeftPt + s.marginRightPt + s.borderLeft.effective +
                    s.borderRight.effective + s.paddingLeftPt + s.paddingRightPt
                val budget = (s.widthPt?.plus(extra) ?: (contentW / 2)).coerceAtMost(contentW)
                layoutBlock(child, contentLeft, budget, topY)
            }
            is TableBox -> layoutTable(child, contentLeft, contentW, topY)
            else -> return
        }
        // Same-side floats stack downward, never side by side: drop this one
        // below any band it would overlap on its own side.
        val isLeft = s.cssFloat == CssFloat.LEFT
        var y = child.y
        for (f in activeFloats) {
            if (f.left == isLeft && y < f.yBottom && (y + child.borderBoxHeight) > f.yTop) y = f.yBottom
        }
        val x = if (isLeft) contentLeft else contentLeft + contentW - child.borderBoxWidth
        shiftSubtree(child, x - child.x, y - child.y)
        activeFloats.add(FloatBand(isLeft, child.x, child.x + child.borderBoxWidth, child.y, child.bottom))
    }

    /**
     * Left/right insets carved out of `[contentLeft, contentLeft+contentW]`
     * by floats overlapping the y-range [yTop, yBottom].
     */
    private fun floatInsets(contentLeft: Double, contentW: Double, yTop: Double, yBottom: Double): Pair<Double, Double> {
        var left = 0.0
        var right = 0.0
        for (f in activeFloats) {
            if (f.yBottom <= yTop || f.yTop >= yBottom) continue
            if (f.left) left = maxOf(left, (f.xEnd - contentLeft).coerceAtLeast(0.0))
            else right = maxOf(right, (contentLeft + contentW - f.xStart).coerceAtLeast(0.0))
        }
        return left to right
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
        // viewport and paints as vectors; raster images decode to a KiteImageData.
        val svg = box.svg ?: if (box.zipPath.endsWith(".svg", true)) loadSvg(box.zipPath)?.also { box.svg = it } else null
        val intrinsicW: Double; val intrinsicH: Double
        val media = box.media
        if (svg != null) {
            intrinsicW = svg.width; intrinsicH = svg.height
        } else {
            val img = if (box.zipPath.isEmpty()) null else loadImage(box.zipPath)
            if (img != null && img.width > 0 && img.height > 0) {
                box.image = img
                intrinsicW = img.width.toDouble(); intrinsicH = img.height.toDouble()
            } else if (media != null) {
                // A media element without a poster keeps its room: 16:9 for a video, a 40 pt bar
                // for an audio player, at the content width unless the element says otherwise (#29).
                if (media.kind == EpubMediaKind.VIDEO) { intrinsicW = 16.0; intrinsicH = 9.0 } else { intrinsicW = contentW; intrinsicH = 40.0 }
            } else if (box.embed != null) {
                // A frame keeps the room of a browser's frame unless it says otherwise (#40).
                intrinsicW = EMBED_DEFAULT_WIDTH_PT; intrinsicH = EMBED_DEFAULT_HEIGHT_PT
            } else {
                box.x = contentLeft; box.y = topY; box.borderBoxWidth = 0.0; box.borderBoxHeight = 0.0; return
            }
        }
        val aspect = intrinsicH / intrinsicW
        // Honour explicit CSS width/height (then the HTML width/height attributes),
        // deriving the missing dimension from the intrinsic aspect ratio; fall back
        // to full content width. Scale down proportionally past max-width / content /
        // max-height so the image never overflows its column.
        val st = box.style
        // Fixed margins narrow the room; auto ones only place the image (CSS 2.1, 10.3.3).
        val mL = if (st.marginLeftAuto) 0.0 else st.marginLeftPt
        val mR = if (st.marginRightAuto) 0.0 else st.marginRightPt
        // CSS 2.1, 8.1: border and padding surround a replaced element's content too (#101).
        val inset = imageInset(st)
        val room = (contentW - mL - mR - inset.inlineStart - inset.inlineEnd).coerceAtLeast(1.0)
        val blockRoom = (maxImageHeight - inset.blockStart - inset.blockEnd).coerceAtLeast(1.0)
        // CSS Writing Modes 4, 7.2: replaced width/height remain physical in
        // vertical flow. Only their inline/block allocation swaps (#100).
        val physicalRoomW = if (vertical) blockRoom else room
        val physicalRoomH = if (vertical) room else blockRoom
        val ew = box.style.widthPt ?: box.attrWidth
        val eh = box.style.heightPt ?: box.attrHeight
        var w = ew ?: (eh?.let { it / aspect } ?: if (box.embed != null) intrinsicW else physicalRoomW)
        var h = eh ?: (w * aspect)
        // object-fit: contain. When both dimensions are fixed, letterbox the image to
        // preserve its aspect ratio inside the box (default `fill` stretches to w×h).
        if (ew != null && eh != null && box.style.objectFit == ObjectFit.CONTAIN) {
            val scale = minOf(ew / intrinsicW, eh / intrinsicH)
            w = intrinsicW * scale; h = intrinsicH * scale
        }
        val cap = minOf(box.style.maxWidthPt ?: Double.MAX_VALUE, physicalRoomW)
        if (w > cap) { val s = cap / w; w = cap; h *= s }
        // Style clamps (proportional), then the hard page-height cap last.
        box.style.maxHeightPt?.let { if (h > it) { val k = it / h; h = it; w *= k } }
        box.style.minWidthPt?.let { if (w < it) { val k = it / w; w = it; h *= k } }
        box.style.minHeightPt?.let { if (h < it) { val k = it / h; h = it; w *= k } }
        if (h > physicalRoomH) { val s = physicalRoomH / h; h = physicalRoomH; w *= s }
        box.drawWidth = w; box.drawHeight = h
        val inlineSize = (if (vertical) h else w) + inset.inlineStart + inset.inlineEnd
        val blockSize = (if (vertical) w else h) + inset.blockStart + inset.blockEnd
        box.x = contentLeft + imageOffset(st, (contentW - inlineSize).coerceAtLeast(0.0), mL, mR)
        box.y = topY
        box.borderBoxWidth = inlineSize; box.borderBoxHeight = blockSize
    }

    /**
     * Where a block image sits across its line (CSS 2.1, 10.3.3, #171): at the
     * start edge plus its margin, centred only when both margins are auto. An
     * inline `<svg>` lifted into its own box still follows `text-align`.
     */
    private fun imageOffset(s: ComputedStyle, leftover: Double, mL: Double, mR: Double): Double = when {
        s.display == Display.INLINE || s.display == Display.INLINE_BLOCK -> when (resolvedAlign(s)) {
            TextAlign.RIGHT -> leftover
            TextAlign.CENTER -> leftover / 2
            TextAlign.JUSTIFY -> if (s.direction == Direction.RTL) leftover else 0.0
            else -> 0.0
        }
        s.marginLeftAuto && s.marginRightAuto -> leftover / 2
        s.marginLeftAuto || s.direction == Direction.RTL -> leftover - mR
        else -> mL
    }.coerceIn(0.0, leftover)

    // ---- flex layout ---------------------------------------------------------

    /**
     * One flex item while its container lays it out (#33). The sizes are border-box sizes on the
     * main axis. The margins are those at the main-start and main-end sides, an auto one as 0.
     */
    private class FlexItem(val box: LayoutBox, val flex: FlexStyle) {
        var marginStart = 0.0
        var marginEnd = 0.0
        var autoStart = false
        var autoEnd = false
        var base = 0.0
        var min = 0.0
        var max = Double.MAX_VALUE
        var hypo = 0.0
        var target = 0.0
        var frozen = false
        /** The distance from the item's border-box top to its first baseline. */
        var baseline = 0.0
        val outer: Double get() = marginStart + target + marginEnd
    }

    /**
     * Places the children of the flex container [box] as flex items (CSS Flexible Box Layout 1,
     * 9) in its content box, [contentW] wide at [contentLeft] and [contentTop]. [definiteHeight] is
     * the container's content height when its style fixes one. Returns the content height the
     * items need.
     */
    private fun layoutFlex(box: BlockBox, contentLeft: Double, contentW: Double, contentTop: Double, definiteHeight: Double?): Double {
        val items = ArrayList<FlexItem>()
        for (child in box.children) {
            val pos = if (child is TextBlockBox) CssPosition.STATIC else child.style.position
            if (pos == CssPosition.ABSOLUTE || pos == CssPosition.FIXED) {
                pendingAbs.add(PendingAbs(child, if (pos == CssPosition.FIXED) pageCb else currentCb))
                continue
            }
            if (child is TableRowBox) continue
            // An anonymous item around loose text has the initial flex values, not its container's.
            items += FlexItem(child, if (child is TextBlockBox) FlexStyle() else child.style.flex)
        }
        // `order` moves an item on the screen, a stable sort keeping the source order among equals (5.4).
        items.sortBy { it.flex.order }
        if (items.isEmpty()) return 0.0
        return if (box.style.flex.row) flexRow(box, items, contentLeft, contentW, contentTop, definiteHeight)
        else flexColumn(box, items, contentLeft, contentW, contentTop, definiteHeight)
    }

    private fun flexRow(box: BlockBox, items: List<FlexItem>, contentLeft: Double, contentW: Double, contentTop: Double, definiteHeight: Double?): Double {
        val fs = box.style.flex
        // Main-start is the right side in a right-to-left row and in a left-to-right reversed one.
        val fromRight = (box.style.direction == Direction.RTL) != (fs.direction == FlexDirection.ROW_REVERSE)
        val gap = fs.columnGap
        for (item in items) {
            val b = item.box
            val s = b.style
            val anonymous = b is TextBlockBox
            val left = if (anonymous || s.marginLeftAuto) 0.0 else s.marginLeftPt
            val right = if (anonymous || s.marginRightAuto) 0.0 else s.marginRightPt
            item.marginStart = if (fromRight) right else left
            item.marginEnd = if (fromRight) left else right
            item.autoStart = !anonymous && (if (fromRight) s.marginRightAuto else s.marginLeftAuto)
            item.autoEnd = !anonymous && (if (fromRight) s.marginLeftAuto else s.marginRightAuto)
            val insets = horizontalInsets(b)
            val width = if (anonymous || b is ImageBox) null else s.widthPt
            // 9.2.3: the flex base size, from flex-basis, else from the width, else from the content.
            item.base = when (val basis = item.flex.basis) {
                is FlexBasis.Length -> basis.pt + insets
                is FlexBasis.Percent -> basis.fraction * contentW + insets
                FlexBasis.Content -> maxContentWidth(b, contentW)
                FlexBasis.Auto -> width?.let { it + insets } ?: maxContentWidth(b, contentW)
            }
            // 4.5: an item does not shrink below its content, or below its width when that is smaller.
            var min = if (!anonymous && s.minWidthPt != null) s.minWidthPt + insets else minContentWidth(b, contentW)
            if (width != null && (anonymous || s.minWidthPt == null)) min = minOf(min, width + insets)
            item.min = min
            item.max = maxOf(min, if (!anonymous && s.maxWidthPt != null) s.maxWidthPt + insets else Double.MAX_VALUE)
            item.hypo = item.base.coerceIn(item.min, item.max)
        }
        val lines = flexLines(items, contentW, gap, fs.wrap != FlexWrap.NOWRAP)

        // Each line resolves its lengths, then lays its items out at them.
        class Line(val items: List<FlexItem>) { var cross = 0.0; var baseline = 0.0 }
        val laid = lines.map { line ->
            resolveFlexibleLengths(line, contentW - gap * (line.size - 1))
            val l = Line(line)
            for (item in line) {
                layoutFlexItem(item.box, contentLeft, contentTop, item.target)
                // An image keeps its own size, which can be less than the room it was given.
                item.target = item.box.borderBoxWidth
                item.baseline = firstBaseline(item.box) ?: item.box.borderBoxHeight
            }
            // 9.4: the line is as tall as its tallest item, baseline items aligned on their baselines.
            val aligned = line.filter { alignOf(it, fs) == FlexAlign.BASELINE }
            l.baseline = aligned.maxOfOrNull { marginTop(it.box) + it.baseline } ?: 0.0
            l.cross = line.maxOf { item ->
                if (alignOf(item, fs) == FlexAlign.BASELINE) l.baseline - item.baseline + item.box.borderBoxHeight + marginBottom(item.box)
                else marginTop(item.box) + item.box.borderBoxHeight + marginBottom(item.box)
            }
            l
        }
        // A single line fills a container whose height is fixed (9.4, step 8).
        if (laid.size == 1 && definiteHeight != null) laid[0].cross = maxOf(laid[0].cross, definiteHeight)
        val rowGap = fs.rowGap
        val used = laid.sumOf { it.cross } + rowGap * (laid.size - 1)
        // 8.4: align-content shares out the height that a fixed container has left over.
        var lineTop = contentTop
        var between = 0.0
        if (definiteHeight != null && laid.size > 1) {
            val free = definiteHeight - used
            val (offset, spacing) = distribute(fs.alignContent, free, laid.size)
            lineTop += offset
            between = spacing
            if (fs.alignContent == FlexJustify.STRETCH && free > 0.0) for (l in laid) l.cross += free / laid.size
        }
        val order = if (fs.wrap == FlexWrap.WRAP_REVERSE) laid.asReversed() else laid
        for (l in order) {
            placeRowLine(l.items, fs, fromRight, contentLeft, contentW, gap, lineTop, l.cross, l.baseline)
            lineTop += l.cross + rowGap + between
        }
        return if (definiteHeight != null) maxOf(definiteHeight, used) else used
    }

    /** Places the items of one row line along the line and across it. */
    private fun placeRowLine(
        line: List<FlexItem>, fs: FlexStyle, fromRight: Boolean,
        contentLeft: Double, contentW: Double, gap: Double, lineTop: Double, cross: Double, baseline: Double,
    ) {
        var free = contentW - line.sumOf { it.outer } - gap * (line.size - 1)
        // 8.1: auto margins take the free space before justify-content does.
        val autos = line.sumOf { (if (it.autoStart) 1 else 0) + (if (it.autoEnd) 1 else 0) }
        val autoShare = if (free > 0.0 && autos > 0) free / autos else 0.0
        if (autoShare > 0.0) free = 0.0
        val justify = when (fs.justify) {
            FlexJustify.LEFT -> if (fromRight) FlexJustify.END else FlexJustify.START
            FlexJustify.RIGHT -> if (fromRight) FlexJustify.START else FlexJustify.END
            else -> fs.justify
        }
        val (offset, between) = distribute(justify, free, line.size)
        var m = offset
        for (item in line) {
            val b = item.box
            m += item.marginStart + (if (item.autoStart) autoShare else 0.0)
            val x = if (fromRight) contentLeft + contentW - m - item.target else contentLeft + m
            m += item.target + item.marginEnd + (if (item.autoEnd) autoShare else 0.0) + gap + between
            val top = marginTop(b)
            val outerCross = top + b.borderBoxHeight + marginBottom(b)
            val y = lineTop + when (alignOf(item, fs)) {
                FlexAlign.STRETCH -> {
                    // A box whose height is auto grows to the line, and its content stays at its top.
                    // An image keeps its aspect ratio instead of stretching.
                    if (b !is ImageBox && (b is TextBlockBox || b.style.heightPt == null)) {
                        b.borderBoxHeight = maxOf(b.borderBoxHeight, cross - top - marginBottom(b))
                    }
                    top
                }
                FlexAlign.END -> cross - outerCross + top
                FlexAlign.CENTER -> (cross - outerCross) / 2 + top
                FlexAlign.BASELINE -> baseline - item.baseline
                else -> top
            }
            shiftSubtree(b, x - b.x, y - b.y)
        }
    }

    private fun flexColumn(box: BlockBox, items: List<FlexItem>, contentLeft: Double, contentW: Double, contentTop: Double, definiteHeight: Double?): Double {
        val fs = box.style.flex
        val rtl = box.style.direction == Direction.RTL
        val gap = fs.rowGap
        for (item in items) {
            val b = item.box
            val s = b.style
            val anonymous = b is TextBlockBox
            val left = if (anonymous || s.marginLeftAuto) 0.0 else s.marginLeftPt
            val right = if (anonymous || s.marginRightAuto) 0.0 else s.marginRightPt
            val room = (contentW - left - right).coerceAtLeast(0.0)
            // The cross size: the whole width when the item stretches, else the width its content asks.
            val width = when {
                !anonymous && b !is ImageBox && s.widthPt != null -> s.widthPt + horizontalInsets(b)
                alignOf(item, fs) == FlexAlign.STRETCH -> room
                else -> maxContentWidth(b, room).coerceAtMost(room)
            }
            layoutFlexItem(b, contentLeft, contentTop, width)
            item.marginStart = marginTop(b)
            item.marginEnd = marginBottom(b)
            val insets = verticalInsets(b)
            val content = b.borderBoxHeight
            item.base = when (val basis = item.flex.basis) {
                is FlexBasis.Length -> basis.pt + insets
                is FlexBasis.Percent -> definiteHeight?.let { basis.fraction * it + insets } ?: content
                else -> if (!anonymous && b !is ImageBox && s.heightPt != null) s.heightPt + insets else content
            }
            // Laid-out content is never cut, so an item never shrinks below it.
            item.min = content
            item.max = maxOf(content, if (!anonymous && s.maxHeightPt != null) s.maxHeightPt + insets else Double.MAX_VALUE)
            item.hypo = item.base.coerceIn(item.min, item.max)
        }
        val mainSize = definiteHeight ?: (items.sumOf { it.marginStart + it.hypo + it.marginEnd } + gap * (items.size - 1))
        resolveFlexibleLengths(items, mainSize - gap * (items.size - 1))
        for (item in items) item.box.borderBoxHeight = maxOf(item.box.borderBoxHeight, item.target)
        val free = mainSize - items.sumOf { it.outer } - gap * (items.size - 1)
        val justify = when (fs.justify) {
            FlexJustify.LEFT, FlexJustify.RIGHT -> FlexJustify.START
            else -> fs.justify
        }
        val (offset, between) = distribute(justify, free, items.size)
        var m = offset
        for (item in items) {
            val b = item.box
            m += item.marginStart
            val y = if (fs.reverse) contentTop + mainSize - m - item.target else contentTop + m
            m += item.target + item.marginEnd + gap + between
            val left = if (b is TextBlockBox || b.style.marginLeftAuto) 0.0 else b.style.marginLeftPt
            val right = if (b is TextBlockBox || b.style.marginRightAuto) 0.0 else b.style.marginRightPt
            val slack = contentW - left - b.borderBoxWidth - right
            // Across a column, start is the left side, or the right one in a right-to-left container.
            val x = contentLeft + left + when (alignOf(item, fs)) {
                FlexAlign.CENTER -> slack / 2
                FlexAlign.END -> if (rtl) 0.0 else slack
                FlexAlign.STRETCH -> 0.0
                else -> if (rtl) slack else 0.0
            }
            shiftSubtree(b, x - b.x, y - b.y)
        }
        return maxOf(mainSize, items.sumOf { it.outer } + gap * (items.size - 1))
    }

    /** The items in lines no longer than [room] with [gap] between them, or in one line when they do not wrap (9.3). */
    private fun flexLines(items: List<FlexItem>, room: Double, gap: Double, wrap: Boolean): List<List<FlexItem>> {
        if (!wrap) return listOf(items)
        val lines = ArrayList<List<FlexItem>>()
        var line = ArrayList<FlexItem>()
        var used = 0.0
        for (item in items) {
            val outer = item.marginStart + item.hypo + item.marginEnd
            if (line.isNotEmpty() && used + gap + outer > room + FLEX_EPSILON) {
                lines += line
                line = ArrayList()
                used = 0.0
            }
            used += if (line.isEmpty()) outer else gap + outer
            line += item
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    /**
     * 9.7: grows or shrinks the [items] of one line to fill [room], by their grow and their
     * shrink scaled by base size, freezing each item that meets its min or max until none moves.
     */
    private fun resolveFlexibleLengths(items: List<FlexItem>, room: Double) {
        val growing = items.sumOf { it.marginStart + it.hypo + it.marginEnd } < room
        for (item in items) {
            item.target = item.hypo
            val factor = if (growing) item.flex.grow else item.flex.shrink
            item.frozen = factor == 0.0 || (growing && item.base > item.hypo) || (!growing && item.base < item.hypo)
        }
        fun freeSpace() = room - items.sumOf { it.marginStart + it.marginEnd + if (it.frozen) it.target else it.base }
        val initialFree = freeSpace()
        repeat(items.size + 1) {
            val open = items.filter { !it.frozen }
            if (open.isEmpty()) return
            var free = freeSpace()
            // A sum of factors below 1 hands out only that share of the space (9.7, step 4b).
            val factors = open.sumOf { if (growing) it.flex.grow else it.flex.shrink }
            if (factors < 1.0 && kotlin.math.abs(initialFree * factors) < kotlin.math.abs(free)) free = initialFree * factors
            val scaled = open.sumOf { it.flex.shrink * it.base }
            for (item in open) {
                item.target = when {
                    growing -> item.base + free * item.flex.grow / factors
                    scaled > 0.0 -> item.base + free * item.flex.shrink * item.base / scaled
                    else -> item.base
                }
            }
            var violation = 0.0
            val clamped = HashMap<FlexItem, Double>()
            for (item in open) {
                val c = item.target.coerceIn(item.min, item.max)
                violation += c - item.target
                clamped[item] = c
            }
            for (item in open) {
                val c = clamped.getValue(item)
                val freeze = when {
                    violation == 0.0 -> true
                    violation > 0.0 -> c > item.target
                    else -> c < item.target
                }
                item.target = c
                if (freeze) item.frozen = true
            }
        }
    }

    /**
     * Where the first of [count] items or lines starts, and the extra space between two of them,
     * when [justify] shares out [free]. Space that is not there falls back to the start (8.2).
     */
    private fun distribute(justify: FlexJustify, free: Double, count: Int): Pair<Double, Double> = when (justify) {
        FlexJustify.END -> free to 0.0
        FlexJustify.CENTER -> free / 2 to 0.0
        FlexJustify.SPACE_BETWEEN -> if (free > 0.0 && count > 1) 0.0 to free / (count - 1) else 0.0 to 0.0
        FlexJustify.SPACE_AROUND -> if (free > 0.0) free / count / 2 to free / count else 0.0 to 0.0
        FlexJustify.SPACE_EVENLY -> if (free > 0.0) free / (count + 1) to free / (count + 1) else 0.0 to 0.0
        else -> 0.0 to 0.0
    }

    private fun alignOf(item: FlexItem, container: FlexStyle): FlexAlign =
        item.flex.alignSelf.takeIf { it != FlexAlign.AUTO } ?: container.alignItems

    /** Lays [b] out as a flex item with its border box [width] wide from [left] at [top]. */
    private fun layoutFlexItem(b: LayoutBox, left: Double, top: Double, width: Double) {
        when (b) {
            is BlockBox -> layoutBlock(b, left, width, top, forcedWidth = width)
            is TextBlockBox -> layoutTextBlock(b, left, width, top)
            is ImageBox -> {
                // The image's own margins would narrow and move it, so they sit outside the room it gets.
                val s = b.style
                val mL = if (s.marginLeftAuto) 0.0 else s.marginLeftPt
                val mR = if (s.marginRightAuto) 0.0 else s.marginRightPt
                layoutImage(b, left - mL, width + mL + mR, top)
            }
            is TableBox -> layoutTable(b, left, width, top)
            is TableRowBox -> {}
        }
    }

    /**
     * The border-box width [b] takes when nothing wraps its text, its max-content width. [room]
     * is the width an image or a table is measured in.
     */
    private fun maxContentWidth(b: LayoutBox, room: Double): Double = when (b) {
        is TextBlockBox -> textMaxContent(b)
        is BlockBox -> {
            val s = b.style
            s.widthPt?.let { it + horizontalInsets(b) } ?: run {
                fun outer(c: LayoutBox) = maxContentWidth(c, room) + horizontalMargins(c)
                val inner = if (s.display == Display.FLEX && s.flex.row) {
                    val shown = b.children.filter { it !is TableRowBox && (it is TextBlockBox || (it.style.position != CssPosition.ABSOLUTE && it.style.position != CssPosition.FIXED)) }
                    shown.sumOf(::outer) + s.flex.columnGap * (shown.size - 1).coerceAtLeast(0)
                } else {
                    b.children.maxOfOrNull(::outer) ?: 0.0
                }
                inner + horizontalInsets(b)
            }
        }
        is ImageBox -> {
            layoutImage(b, 0.0, room, 0.0)
            val s = b.style
            val sized = s.widthPt != null || s.heightPt != null || b.attrWidth != null || b.attrHeight != null
            // Without a size of its own, an image's content is its natural size, a CSS pixel (0.75 pt)
            // for an image pixel, where a block image alone would fill its column (#35).
            val natural = if (sized) null else (b.svg?.width ?: b.image?.width?.toDouble())?.times(0.75)
            if (natural == null) b.borderBoxWidth else minOf(b.borderBoxWidth, natural + horizontalInsets(b))
        }
        is TableBox -> room
        is TableRowBox -> 0.0
    }

    /**
     * The border-box width the content of [b] cannot go below without breaking a word, its
     * min-content width. The item's own width is not part of it (4.5).
     */
    private fun minContentWidth(b: LayoutBox, room: Double): Double = when (b) {
        is TextBlockBox -> measureContent(b).second
        is BlockBox -> measureContent(b).second + horizontalInsets(b)
        is ImageBox -> maxContentWidth(b, room)
        else -> 0.0
    }

    /** The longest line of [box]'s text, laid out with room enough that no line wraps. */
    private fun textMaxContent(box: TextBlockBox): Double {
        val lines = layoutInline(box.runs, FLEX_UNBOUNDED, 0.0, 0.0, box.style, box.marker, box.markerColor)
        var widest = 0.0
        for ((i, ln) in lines.withIndex()) {
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (r in ln.runs) { lo = minOf(lo, r.x); hi = maxOf(hi, r.x + r.paintWidth) }
            for (im in ln.images) { lo = minOf(lo, im.x); hi = maxOf(hi, im.x + im.width) }
            if (hi > lo) widest = maxOf(widest, hi - lo + if (i == 0) box.style.textIndentPt.coerceAtLeast(0.0) else 0.0)
        }
        // A hair more than the text keeps the final layout from breaking it on a rounding difference.
        return if (widest > 0.0) widest + FLEX_EPSILON else 0.0
    }

    /** The distance from [b]'s border-box top to its first line's baseline, or null when it has no line. */
    private fun firstBaseline(b: LayoutBox): Double? = when (b) {
        is TextBlockBox -> b.lines.firstOrNull()?.let { it.yTop + it.ascent - b.y }
        is BlockBox -> b.children.firstNotNullOfOrNull { c -> firstBaseline(c)?.let { it + c.y - b.y } }
        else -> null
    }

    private fun horizontalInsets(b: LayoutBox): Double = if (b is TextBlockBox) 0.0 else b.style.let {
        it.borderLeft.effective + it.paddingLeftPt + it.paddingRightPt + it.borderRight.effective
    }

    private fun verticalInsets(b: LayoutBox): Double = if (b is TextBlockBox) 0.0 else b.style.let {
        it.borderTop.effective + it.paddingTopPt + it.paddingBottomPt + it.borderBottom.effective
    }

    private fun horizontalMargins(b: LayoutBox): Double = if (b is TextBlockBox) 0.0 else b.style.let {
        (if (it.marginLeftAuto) 0.0 else it.marginLeftPt) + (if (it.marginRightAuto) 0.0 else it.marginRightPt)
    }

    private fun marginTop(b: LayoutBox): Double = if (b is TextBlockBox) 0.0 else b.style.marginTopPt

    private fun marginBottom(b: LayoutBox): Double = if (b is TextBlockBox) 0.0 else b.style.marginBottomPt

    // ---- grid layout ---------------------------------------------------------

    /** One grid item while its container lays it out: its area, in tracks counted from 0 (#35). */
    private class GridItem(val box: LayoutBox, val grid: GridStyle, val flex: FlexStyle) {
        var col = -1
        var colSpan = 1
        var row = -1
        var rowSpan = 1
    }

    /** One track while its container sizes it: its base size, its growth limit and its sizing functions. */
    private class GridTrackSize(val size: GridTrack) {
        var base = 0.0
        var limit = 0.0
        val fr: Double get() = (size.max as? GridSize.Fraction)?.fr ?: 0.0
        val flexible: Boolean get() = size.max is GridSize.Fraction
        val content: Boolean get() = size.min.intrinsic
    }

    /**
     * Places the children of the grid container [box] in its grid (CSS Grid Layout 1): it places
     * the items (8.5), sizes the columns, lays each item out at its area's width, sizes the rows
     * from the items (11), and aligns each item in its area. Returns the content height it needs.
     */
    private fun layoutGrid(box: BlockBox, contentLeft: Double, contentW: Double, contentTop: Double, definiteHeight: Double?): Double {
        val s = box.style
        val g = s.grid
        val colGap = s.flex.columnGap
        val rowGap = s.flex.rowGap
        val items = ArrayList<GridItem>()
        for (child in box.children) {
            val pos = if (child is TextBlockBox) CssPosition.STATIC else child.style.position
            if (pos == CssPosition.ABSOLUTE || pos == CssPosition.FIXED) {
                pendingAbs.add(PendingAbs(child, if (pos == CssPosition.FIXED) pageCb else currentCb))
                continue
            }
            if (child is TableRowBox) continue
            // Loose text is an anonymous item with the initial values, not its container's.
            val anonymous = child is TextBlockBox
            items += GridItem(child, if (anonymous) GridStyle() else child.style.grid, if (anonymous) FlexStyle() else child.style.flex)
        }
        // order-modified document order places the items (8.5).
        items.sortBy { it.flex.order }
        if (items.isEmpty()) return definiteHeight ?: 0.0
        val columns = expandTracks(g.columns, contentW, colGap)
        val rows = expandTracks(g.rows, definiteHeight, rowGap)
        placeGridItems(items, columns.size, rows.size)
        val colCount = maxOf(columns.size, items.maxOf { it.col + it.colSpan })
        val rowCount = maxOf(rows.size, items.maxOf { it.row + it.rowSpan })
        val cols = List(colCount) { GridTrackSize(columns.getOrNull(it) ?: g.autoColumns) }
        sizeGridColumns(cols, items, contentW, colGap, stretch = s.flex.justify == FlexJustify.STRETCH)
        val (colStart, colBetween) = distribute(s.flex.justify, contentW - cols.sumOf { it.base } - colGap * (colCount - 1), colCount)
        val colX = DoubleArray(colCount)
        run {
            var x = colStart
            for (i in 0 until colCount) { colX[i] = x; x += cols[i].base + colGap + colBetween }
        }
        fun areaWidth(item: GridItem) = (item.col until item.col + item.colSpan).sumOf { cols[it].base } + (colGap + colBetween) * (item.colSpan - 1)

        // Each item lays out at its area's width, or at its own width when it does not stretch.
        for (item in items) {
            val b = item.box
            val left = if (b is TextBlockBox || b.style.marginLeftAuto) 0.0 else b.style.marginLeftPt
            val right = if (b is TextBlockBox || b.style.marginRightAuto) 0.0 else b.style.marginRightPt
            val room = (areaWidth(item) - left - right).coerceAtLeast(0.0)
            val own = if (b is TextBlockBox || b is ImageBox) null else b.style.widthPt
            val width = when {
                own != null -> own + horizontalInsets(b)
                justifyOf(item, g) == FlexAlign.STRETCH -> room
                else -> maxContentWidth(b, room).coerceAtMost(room)
            }
            layoutFlexItem(b, contentLeft, contentTop, width)
        }

        val rowTracks = List(rowCount) { GridTrackSize(rows.getOrNull(it) ?: g.autoRows) }
        sizeGridRows(rowTracks, items, definiteHeight, rowGap, stretch = s.flex.alignContent == FlexJustify.STRETCH)
        val used = rowTracks.sumOf { it.base } + rowGap * (rowCount - 1)
        val (rowStart, rowBetween) = if (definiteHeight != null) distribute(s.flex.alignContent, definiteHeight - used, rowCount) else 0.0 to 0.0
        val rowY = DoubleArray(rowCount)
        run {
            var y = rowStart
            for (i in 0 until rowCount) { rowY[i] = y; y += rowTracks[i].base + rowGap + rowBetween }
        }

        val rtl = s.direction == Direction.RTL
        for (item in items) {
            val b = item.box
            val areaW = areaWidth(item)
            val areaH = (item.row until item.row + item.rowSpan).sumOf { rowTracks[it].base } + (rowGap + rowBetween) * (item.rowSpan - 1)
            val anonymous = b is TextBlockBox
            val mL = if (anonymous || b.style.marginLeftAuto) 0.0 else b.style.marginLeftPt
            val mR = if (anonymous || b.style.marginRightAuto) 0.0 else b.style.marginRightPt
            val mT = marginTop(b)
            val mB = marginBottom(b)
            val slackX = areaW - mL - b.borderBoxWidth - mR
            val dx = mL + when (justifyOf(item, g)) {
                FlexAlign.CENTER -> slackX / 2
                FlexAlign.END -> slackX
                else -> 0.0
            }
            // Columns run from the right edge in a right-to-left container.
            val x = if (rtl) contentLeft + contentW - colX[item.col] - dx - b.borderBoxWidth else contentLeft + colX[item.col] + dx
            val align = item.flex.alignSelf.takeIf { it != FlexAlign.AUTO } ?: s.flex.alignItems
            if (align == FlexAlign.STRETCH && b !is ImageBox && (anonymous || b.style.heightPt == null)) {
                b.borderBoxHeight = maxOf(b.borderBoxHeight, areaH - mT - mB)
            }
            val slackY = areaH - mT - b.borderBoxHeight - mB
            val dy = mT + when (align) {
                FlexAlign.CENTER -> slackY / 2
                FlexAlign.END -> slackY
                else -> 0.0
            }
            shiftSubtree(b, x - b.x, contentTop + rowY[item.row] + dy - b.y)
        }
        return if (definiteHeight != null) maxOf(definiteHeight, used) else used
    }

    private fun justifyOf(item: GridItem, container: GridStyle): FlexAlign {
        val own = item.grid.justifySelf.takeIf { it != FlexAlign.AUTO } ?: container.justifyItems
        // An image keeps its own size across its area (6.6, a replaced element's normal is start).
        return if (own == FlexAlign.STRETCH && item.box is ImageBox) FlexAlign.START else own
    }

    /**
     * The explicit tracks of [tracks], with an auto-fill group repeated as often as it fits
     * [room] with [gap] between, at least once. Without a room the group appears once (7.2.3.2).
     */
    private fun expandTracks(tracks: GridTracks, room: Double?, gap: Double): List<GridTrack> {
        if (tracks.fill.isEmpty()) return tracks.tracks
        fun fixed(t: GridTrack): Double? {
            val min = (t.min as? GridSize.Fixed)?.pt ?: (t.min as? GridSize.Percent)?.let { p -> room?.let { p.fraction * it } }
            val max = (t.max as? GridSize.Fixed)?.pt ?: (t.max as? GridSize.Percent)?.let { p -> room?.let { p.fraction * it } }
            return min ?: max
        }
        val others = tracks.tracks.sumOf { fixed(it) ?: 0.0 } + gap * tracks.tracks.size
        val group = tracks.fill.sumOf { fixed(it) ?: 0.0 }
        val count = if (room == null || group <= 0.0) 1 else {
            // n groups take n * group + (n * size - 1) gaps within the room left by the other tracks.
            val one = group + gap * tracks.fill.size
            ((room - others + gap) / one).toInt().coerceIn(1, GRID_MAX_TRACKS / tracks.fill.size.coerceAtLeast(1))
        }
        val expanded = ArrayList<GridTrack>()
        expanded.addAll(tracks.tracks.subList(0, tracks.fillAt.coerceAtMost(tracks.tracks.size)))
        repeat(count) { expanded.addAll(tracks.fill) }
        expanded.addAll(tracks.tracks.subList(tracks.fillAt.coerceAtMost(tracks.tracks.size), tracks.tracks.size))
        return expanded
    }

    /**
     * 8.5: gives every item its area. Items with a row and a column go first, then items with a
     * row, then the rest in order, row by row from a cursor that never goes back (sparse packing).
     */
    private fun placeGridItems(items: List<GridItem>, explicitCols: Int, explicitRows: Int) {
        // A line number counts from 1 at the start, and from -1 at the end of the explicit grid.
        fun index(n: Int, explicit: Int): Int = (if (n > 0) n - 1 else explicit + 1 + n).coerceIn(0, GRID_MAX_TRACKS - 1)
        fun resolve(start: GridLine, end: GridLine, explicit: Int): Pair<Int, Int> = when {
            start is GridLine.Line && end is GridLine.Line -> {
                val a = index(start.n, explicit)
                val b = index(end.n, explicit)
                if (a == b) a to 1 else minOf(a, b) to kotlin.math.abs(b - a)
            }
            start is GridLine.Line && end is GridLine.Span -> index(start.n, explicit) to end.n
            start is GridLine.Line -> index(start.n, explicit) to 1
            end is GridLine.Line && start is GridLine.Span -> (index(end.n, explicit) - start.n).coerceAtLeast(0) to start.n
            end is GridLine.Line -> (index(end.n, explicit) - 1).coerceAtLeast(0) to 1
            start is GridLine.Span -> -1 to start.n
            end is GridLine.Span -> -1 to end.n
            else -> -1 to 1
        }
        for (item in items) {
            val (c, cs) = resolve(item.grid.columnStart, item.grid.columnEnd, explicitCols)
            val (r, rs) = resolve(item.grid.rowStart, item.grid.rowEnd, explicitRows)
            item.col = c; item.colSpan = cs.coerceIn(1, GRID_MAX_TRACKS)
            item.row = r; item.rowSpan = rs.coerceIn(1, GRID_MAX_TRACKS)
        }
        // The columns auto-placement wraps at: the explicit ones, or as many as a fixed item or a span needs.
        val cols = maxOf(explicitCols, items.maxOf { if (it.col >= 0) it.col + it.colSpan else it.colSpan }, 1)
        val taken = HashSet<Long>()
        fun key(r: Int, c: Int) = r.toLong() * GRID_MAX_TRACKS + c
        fun free(r: Int, c: Int, w: Int, h: Int): Boolean {
            if (c + w > cols && c > 0) return false
            for (dr in 0 until h) for (dc in 0 until w) if (key(r + dr, c + dc) in taken) return false
            return true
        }
        fun take(item: GridItem) {
            for (dr in 0 until item.rowSpan) for (dc in 0 until item.colSpan) taken += key(item.row + dr, item.col + dc)
        }
        for (item in items) if (item.row >= 0 && item.col >= 0) take(item)
        for (item in items) if (item.row >= 0 && item.col < 0) {
            // A full row sends the item to a column after the explicit ones.
            item.col = (0 until cols).firstOrNull { c -> c + item.colSpan <= cols && free(item.row, c, item.colSpan, item.rowSpan) } ?: cols
            take(item)
        }
        var cursorRow = 0
        var cursorCol = 0
        for (item in items) {
            if (item.row >= 0) continue
            if (item.col >= 0) {
                // A fixed column: the next row at or after the cursor where the area is free.
                if (item.col < cursorCol) cursorRow++
                var r = cursorRow
                while (!free(r, item.col, item.colSpan, item.rowSpan) && r < GRID_MAX_TRACKS) r++
                item.row = r
                cursorRow = r
                cursorCol = item.col
            } else {
                var r = cursorRow
                var c = cursorCol
                while (r < GRID_MAX_TRACKS) {
                    if (c + item.colSpan > cols && c > 0) { r++; c = 0; continue }
                    if (free(r, c, item.colSpan, item.rowSpan)) break
                    c++
                }
                item.row = r
                item.col = c
                cursorRow = r
                cursorCol = c + item.colSpan
            }
            take(item)
        }
    }

    /**
     * 11.4 to 11.8, for the columns: fixed sizes first, then the content of the items in each
     * content-sized track, then free space up to each track's limit, then the fractions, and
     * last, when [stretch] allows it, the auto tracks share what is left.
     */
    private fun sizeGridColumns(tracks: List<GridTrackSize>, items: List<GridItem>, width: Double, gap: Double, stretch: Boolean) {
        fun fixed(size: GridSize): Double? = when (size) {
            is GridSize.Fixed -> size.pt
            is GridSize.Percent -> size.fraction * width
            else -> null
        }
        for (t in tracks) {
            t.base = fixed(t.size.min) ?: 0.0
            t.limit = fixed(t.size.max) ?: 0.0
        }
        for (item in items) {
            val b = item.box
            val margins = horizontalMargins(b)
            val own = if (b is TextBlockBox || b is ImageBox) null else b.style.widthPt
            val minContent = (own?.let { it + horizontalInsets(b) } ?: minContentWidth(b, width)) + margins
            val maxContent = maxContentWidth(b, width) + margins
            val span = tracks.subList(item.col, item.col + item.colSpan)
            val gaps = gap * (item.colSpan - 1)
            // A track's minimum grows to the content's min-content, its limit to the max-content.
            val growBase = span.filter { it.content }
            val needBase = when (span.firstOrNull { it.content }?.size?.min) {
                GridSize.MaxContent -> maxContent
                else -> minContent
            } - gaps - span.sumOf { it.base }
            if (needBase > 0.0 && growBase.isNotEmpty()) for (t in growBase) t.base += needBase / growBase.size
            val growLimit = span.filter { !it.flexible && it.size.max.intrinsic }
            val needLimit = (if (growLimit.any { it.size.max is GridSize.MinContent }) minContent else maxContent) - gaps - span.sumOf { maxOf(it.base, it.limit) }
            if (needLimit > 0.0 && growLimit.isNotEmpty()) for (t in growLimit) t.limit = maxOf(t.base, t.limit) + needLimit / growLimit.size
        }
        for (t in tracks) if (t.limit < t.base) t.limit = t.base
        val room = width - gap * (tracks.size - 1)
        // 11.6: free space grows each track that is not a fraction up to its limit.
        repeat(tracks.size + 1) {
            val free = room - tracks.sumOf { it.base }
            val open = tracks.filter { !it.flexible && it.base < it.limit }
            if (free <= FLEX_EPSILON || open.isEmpty()) return@repeat
            val share = free / open.size
            for (t in open) t.base = minOf(t.limit, t.base + share)
        }
        // 11.7: the fractions share what the other tracks leave, but none goes below its base.
        val flexible = tracks.filter { it.flexible && it.fr > 0.0 }
        if (flexible.isNotEmpty()) {
            val frozen = HashSet<GridTrackSize>()
            repeat(flexible.size + 1) {
                val open = flexible.filter { it !in frozen }
                if (open.isEmpty()) return@repeat
                val left = room - tracks.filter { !it.flexible || it in frozen }.sumOf { it.base } - tracks.filter { it.flexible && it.fr == 0.0 }.sumOf { it.base }
                val unit = (left / open.sumOf { it.fr }).coerceAtLeast(0.0)
                val under = open.filter { it.fr * unit < it.base }
                if (under.isEmpty()) {
                    for (t in open) t.base = t.fr * unit
                    return@repeat
                }
                frozen += under
            }
        } else if (stretch) {
            // 11.8: with no fraction, the auto tracks share the rest.
            val free = room - tracks.sumOf { it.base }
            val auto = tracks.filter { it.size.max is GridSize.Auto }
            if (free > 0.0 && auto.isNotEmpty()) for (t in auto) t.base += free / auto.size
        }
    }

    /** The rows, from their fixed sizes and the heights of the items laid out in them. */
    private fun sizeGridRows(tracks: List<GridTrackSize>, items: List<GridItem>, height: Double?, gap: Double, stretch: Boolean) {
        fun fixed(size: GridSize): Double? = when (size) {
            is GridSize.Fixed -> size.pt
            is GridSize.Percent -> height?.let { size.fraction * it }
            else -> null
        }
        for (t in tracks) {
            t.base = fixed(t.size.min) ?: 0.0
            t.limit = fixed(t.size.max) ?: 0.0
        }
        // A row whose size is not fixed takes the tallest item in it; a spanning item shares its extra height.
        // A fixed row grows too when nothing else can, instead of letting its item overlap the next row,
        // as a declared block height grows for its content. CSS lets the item overflow there.
        for (item in items.sortedBy { it.rowSpan }) {
            val outer = marginTop(item.box) + item.box.borderBoxHeight + marginBottom(item.box)
            val span = tracks.subList(item.row, item.row + item.rowSpan)
            val sized = span.filter { fixed(it.size.min) == null || fixed(it.size.max) == null }.ifEmpty { span }
            val need = outer - span.sumOf { it.base } - gap * (item.rowSpan - 1)
            if (need > 0.0 && sized.isNotEmpty()) for (t in sized) t.base += need / sized.size
        }
        if (height == null) return
        val room = height - gap * (tracks.size - 1)
        val flexible = tracks.filter { it.flexible && it.fr > 0.0 }
        val free = room - tracks.sumOf { it.base }
        if (free <= 0.0) return
        if (flexible.isNotEmpty()) {
            // A fraction row takes its share of what the others leave, and never less than its content.
            val unit = (room - tracks.filter { !it.flexible }.sumOf { it.base }) / flexible.sumOf { it.fr }
            for (t in flexible) t.base = maxOf(t.base, t.fr * unit)
        } else if (stretch) {
            val auto = tracks.filter { it.size.max is GridSize.Auto }
            if (auto.isNotEmpty()) for (t in auto) t.base += free / auto.size
        }
    }

    /** `start` and `end` resolve against the text direction; `left` and `right` never flip (#169). */
    private fun resolvedAlign(style: ComputedStyle): TextAlign = when (style.textAlign) {
        TextAlign.START -> if (style.direction == Direction.RTL) TextAlign.RIGHT else TextAlign.LEFT
        TextAlign.END -> if (style.direction == Direction.RTL) TextAlign.LEFT else TextAlign.RIGHT
        else -> style.textAlign
    }

    // ---- tables --------------------------------------------------------------

    private fun layoutTable(box: TableBox, contentLeft: Double, availWidth: Double, topY: Double) {
        val s = box.style
        val bL = s.borderLeft.effective; val bR = s.borderRight.effective
        // border-spacing (or the cellspacing hint) separates cells; collapse zeroes it.
        val spacing = if (s.borderCollapse) 0.0 else s.borderSpacingPt.coerceAtLeast(0.0)
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
        val gutters = spacing * (cols + 1)
        val availCells = (avail - gutters).coerceAtLeast(0.0)

        val widths = DoubleArray(cols)
        val target = s.widthPt?.let { (it - gutters).coerceAtLeast(0.0) }
        if (s.tableLayoutFixed) {
            fixedColumnWidths(box, cols, widths, target ?: availCells)
        } else {
            autoColumnWidths(box, allCells, cols, widths, availCells, target)
        }

        val tableW = widths.sum() + gutters
        layoutTableGrid(box, cols, widths, spacing, tableW, contentLeft, availWidth, topY)
    }

    /**
     * `table-layout: fixed`: `<col>` widths win, then the first row's declared
     * cell widths, then the rest is split equally. Cell content is never
     * measured, which is the point of the mode.
     */
    private fun fixedColumnWidths(box: TableBox, cols: Int, widths: DoubleArray, total: Double) {
        val pinned = BooleanArray(cols)
        for ((c, w) in box.colWidths) if (c in 0 until cols) { widths[c] = w; pinned[c] = true }
        for (cell in box.rows.firstOrNull()?.cells.orEmpty()) {
            val cs = cell.style
            val w = cs.widthPt ?: continue
            // CSS sizes a column from the declared width PLUS the cell's own
            // padding and border, so the content still fits inside the column.
            val outer = w + cs.paddingLeftPt + cs.paddingRightPt +
                cs.borderLeft.effective + cs.borderRight.effective
            val share = outer / cell.colspan
            for (c in cell.gridCol until (cell.gridCol + cell.colspan).coerceAtMost(cols)) {
                if (!pinned[c]) { widths[c] = share; pinned[c] = true }
            }
        }
        val free = (0 until cols).filter { !pinned[it] }
        val used = widths.sum()
        if (free.isNotEmpty()) {
            val each = ((total - used) / free.size).coerceAtLeast(0.0)
            for (c in free) widths[c] = each
        } else if (used in 0.0..total && used > 0.0) {
            // Every column is declared but they do not fill the table: widen
            // them proportionally rather than leaving a gap on the right.
            val k = total / used
            for (c in 0 until cols) widths[c] *= k
        }
    }

    /** The default: measure every cell, then distribute the available width. */
    private fun autoColumnWidths(
        box: TableBox,
        allCells: List<BlockBox>,
        cols: Int,
        widths: DoubleArray,
        availCells: Double,
        target: Double?,
    ) {
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
        // <col>/<colgroup> widths pin their columns before distribution.
        for ((c, w) in box.colWidths) if (c in 0 until cols) { colPref[c] = w; colMin[c] = w }

        val totalPref = colPref.sum(); val totalMin = colMin.sum()
        when {
            target != null && target > totalMin -> distribute(widths, colPref, colMin, target)
            totalPref <= availCells -> for (c in 0 until cols) widths[c] = colPref[c]
            totalMin <= availCells -> {
                val slack = availCells - totalMin; val prefSlack = totalPref - totalMin
                for (c in 0 until cols) widths[c] = colMin[c] + (if (prefSlack > 0) slack * (colPref[c] - colMin[c]) / prefSlack else slack / cols)
            }
            else -> for (c in 0 until cols) widths[c] = colMin[c]
        }
        // Pins are hard: re-assert them after distribution and move the delta
        // onto the unpinned columns (distribution slack must not leak into a
        // pinned column).
        val free = (0 until cols).filter { it !in box.colWidths }
        if (box.colWidths.isNotEmpty()) {
            var delta = 0.0
            for ((c, w) in box.colWidths) if (c in 0 until cols) { delta += widths[c] - w; widths[c] = w }
            if (free.isNotEmpty() && delta != 0.0) {
                val add = delta / free.size
                for (c in free) widths[c] = (widths[c] + add).coerceAtLeast(0.0)
            }
        }
    }

    /** Place rows and cells once the column widths are settled. */
    private fun layoutTableGrid(
        box: TableBox,
        cols: Int,
        widths: DoubleArray,
        spacing: Double,
        tableW: Double,
        contentLeft: Double,
        availWidth: Double,
        topY: Double,
    ) {
        val s = box.style
        val bL = s.borderLeft.effective; val bR = s.borderRight.effective
        val allCells = box.rows.flatMap { it.cells }
        val rowCount = box.rows.size
        // Column left offsets INCLUDING the leading + interior gutters.
        val colX = DoubleArray(cols + 1)
        colX[0] = spacing
        for (c in 0 until cols) colX[c + 1] = colX[c] + widths[c] + spacing
        fun spanWidth(col: Int, span: Int): Double {
            var w = 0.0
            for (c in col until (col + span).coerceAtMost(cols)) w += widths[c]
            return w + spacing * (span - 1).coerceAtLeast(0)
        }

        box.borderBoxWidth = bL + s.paddingLeftPt + tableW + s.paddingRightPt + bR
        val leftMargin = if (s.marginLeftAuto && s.marginRightAuto) maxOf(0.0, (availWidth - box.borderBoxWidth) / 2) else s.marginLeftPt
        box.x = contentLeft + leftMargin; box.y = topY
        val tContentLeft = box.x + bL + s.paddingLeftPt
        val tContentTop = box.y + s.borderTop.effective + s.paddingTopPt

        // Pass A: lay cells at their column width to measure heights.
        for (cell in allCells) {
            layoutBlock(cell, tContentLeft + colX[cell.gridCol], spanWidth(cell.gridCol, cell.colspan), tContentTop)
        }
        // Row heights: single-row cells set the base; rowspan cells top up their last spanned row.
        val rowHeight = DoubleArray(rowCount)
        for (cell in allCells) if (cell.rowspan == 1 && cell.borderBoxHeight > rowHeight[cell.gridRow]) rowHeight[cell.gridRow] = cell.borderBoxHeight
        for (cell in allCells) if (cell.rowspan > 1) {
            val last = (cell.gridRow + cell.rowspan - 1).coerceAtMost(rowCount - 1)
            val cur = (cell.gridRow..last).sumOf { rowHeight[it] }
            if (cell.borderBoxHeight > cur) rowHeight[last] += cell.borderBoxHeight - cur
        }
        // Row top offsets INCLUDING the leading + interior gutters.
        val rowY = DoubleArray(rowCount + 1)
        rowY[0] = spacing
        for (r in 0 until rowCount) rowY[r + 1] = rowY[r] + rowHeight[r] + spacing
        for ((r, row) in box.rows.withIndex()) {
            row.x = tContentLeft; row.y = tContentTop + rowY[r]; row.borderBoxWidth = tableW; row.borderBoxHeight = rowHeight[r]
        }

        // Pass B: place cells at their final y, stretch to their spanned rows,
        // then apply the cell's vertical-align by shifting its CONTENT down
        // within the stretched box (backgrounds/borders stay full-cell).
        for (cell in allCells) {
            layoutBlock(cell, tContentLeft + colX[cell.gridCol], spanWidth(cell.gridCol, cell.colspan), tContentTop + rowY[cell.gridRow])
            val natural = cell.borderBoxHeight
            val lastRow = (cell.gridRow + cell.rowspan).coerceAtMost(rowCount)
            cell.borderBoxHeight = (rowY[lastRow] - spacing) - rowY[cell.gridRow]
            val slack = cell.borderBoxHeight - natural
            if (slack > 0) {
                val factor = when (cell.style.verticalAlign) {
                    CssVAlign.MIDDLE -> 0.5
                    CssVAlign.BOTTOM -> 1.0
                    else -> 0.0 // top (and baseline approximated as top)
                }
                if (factor > 0) for (c in cell.children) shiftSubtree(c, 0.0, slack * factor)
            }
        }

        val cellsBottom = rowY[rowCount] // includes the trailing gutter
        box.borderBoxHeight = s.borderTop.effective + s.paddingTopPt + cellsBottom + s.paddingBottomPt + s.borderBottom.effective
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
            for (cp in codePointsOf(run.text)) when {
                cp == '\n'.code -> { endWord(); endLine() }
                cp == '\r'.code -> {}
                isWhitespace(cp) -> { line += FontMetrics.advancePt(' '.code, run.fontSizePt, run.bold, run.italic, run.family); endWord() }
                else -> { val w = FontMetrics.advancePt(cp, run.fontSizePt, run.bold, run.italic, run.family); line += w; word += w }
            }
        }
        endWord(); endLine()
        return maxLine to maxWord
    }

    // ---- inline layout -------------------------------------------------------

    /**
     * How many characters of the block's text each of [lines] stands for, in code points: from
     * its first character to the next line's. A space dropped at a break counts for the line
     * before it and an added hyphen for none, so the lines add up to the text of [runs] in every
     * layout, and a reading position survives a change of font or width (#434).
     */
    private fun sourceLengths(lines: List<List<Cell>>, runs: List<InlineRun>): IntArray {
        val total = runs.sumOf { codePointCount(it.text) }
        val starts = IntArray(lines.size)
        var next = total
        for (i in lines.indices.reversed()) {
            val first = lines[i].minOfOrNull { if (it.src >= 0) it.src else Int.MAX_VALUE } ?: Int.MAX_VALUE
            starts[i] = minOf(first, next)
            next = starts[i]
        }
        if (starts.isNotEmpty()) starts[0] = 0
        return IntArray(lines.size) { i -> (if (i + 1 < lines.size) starts[i + 1] else total) - starts[i] }
    }

    private fun layoutInline(
        runs: List<InlineRun>, contentW: Double, contentLeft: Double, topY: Double,
        style: ComputedStyle, marker: String?, markerColor: RgbColor,
    ): List<PositionedLine> {
        val preserve = style.whiteSpace != WhiteSpaceMode.NORMAL && style.whiteSpace != WhiteSpaceMode.NOWRAP
        val baseLevel = if (style.direction == Direction.RTL) 1 else 0
        val align = resolvedAlign(style)
        // Float exclusions: per-line widths use an estimated constant line
        // height (the authored one, without ruby/image growth), so the widths
        // the wrapper saw and the x-offsets painted below stay consistent.
        // Ruby- or image-grown lines may drift from a band's true bottom edge
        // by that growth; a deliberate approximation.
        val estH = (style.lineHeightPt ?: style.fontSizePt * 1.4) * lineHeightScale
        val hasFloats = activeFloats.isNotEmpty()
        fun insetsFor(i: Int): Pair<Double, Double> =
            if (!hasFloats) 0.0 to 0.0
            else floatInsets(contentLeft, contentW, topY + i * estH, topY + (i + 1) * estH)
        val availAt: ((Int) -> Double)? = if (!hasFloats) null else { i ->
            val (l, r) = insetsFor(i)
            (contentW - l - r).coerceAtLeast(1.0)
        }
        val ends = ArrayList<KiteLineEnd>()
        val cellLines = wrap(
            tokenize(runs, style.hyphensAuto, contentW, bidiLevels(runs, baseLevel), inlineImageRoom(style)), contentW, preserve, availAt,
            // Negative (hanging) indents keep today's behaviour: only a
            // positive indent eats into the first line's budget.
            firstLineIndent = style.textIndentPt.coerceAtLeast(0.0),
            ends = ends,
        )
        val lengths = sourceLengths(cellLines, runs)
        val visualLines = bidiLines(cellLines, baseLevel) // logical → visual order (UAX #9)
        val out = ArrayList<PositionedLine>(cellLines.size)
        var y = topY
        visualLines.forEachIndexed { i, cells ->
            val maxFs = cells.maxOfOrNull { it.fontSize }?.takeIf { it > 0.0 } ?: style.fontSizePt
            // A line carrying ruby grows by the reading's ascent: the base text
            // drops within the line so the overlay fits inside the line box.
            val rubyBaseFs = cells.filter { it.rubyGroup >= 0 }.maxOfOrNull { it.fontSize } ?: 0.0
            val rubyExtra = rubyBaseFs * RUBY_SIZE * 0.8
            var lineHeight = (style.lineHeightPt ?: maxFs * 1.4) * lineHeightScale + rubyExtra
            var ascent = maxFs * 0.8 + rubyExtra
            // An inline image grows the line: its bottom sits on the baseline,
            // so the ascent must cover the image height (descent unchanged).
            val imgH = cells.maxOfOrNull { if (vertical) it.imageWidth else it.imageHeight } ?: 0.0
            if (imgH > ascent) {
                lineHeight += imgH - ascent
                ascent = imgH
            }

            val (leftInset, rightInset) = insetsFor(i)
            val lineAvail = (contentW - leftInset - rightInset).coerceAtLeast(1.0)
            val (lineWidth, interiorSpaces) = measure(cells)
            val firstIndent = if (i == 0) style.textIndentPt else 0.0
            val slack = (lineAvail - lineWidth - firstIndent).coerceAtLeast(0.0)
            val justify = align == TextAlign.JUSTIFY && i != cellLines.lastIndex && interiorSpaces > 0
            val extraPerSpace = if (justify) slack / interiorSpaces else 0.0
            // Spaceless CJK lines justify between characters: with no interior
            // spaces to stretch, the slack spreads across the inter-cell gaps
            // (JIS-style inter-character expansion). Latin-only spaceless lines
            // (one long word) are left ragged, as every real reader does.
            if (align == TextAlign.JUSTIFY && i != cellLines.lastIndex && interiorSpaces == 0) {
                justifyCjk(cells, slack)
            }
            val alignOffset = when {
                justify -> 0.0
                align == TextAlign.RIGHT -> slack
                align == TextAlign.CENTER -> slack / 2
                else -> 0.0
            }
            val xStart = contentLeft + leftInset + firstIndent + alignOffset

            val placed = ArrayList<PlacedRun>()
            val images = ArrayList<PlacedImage>()
            if (i == 0 && marker != null) {
                val rtl = style.direction == Direction.RTL
                markerRun(marker, style.fontSizePt, contentLeft, contentLeft + contentW, rtl, markerColor)?.let(placed::add)
            }
            placed.addAll(placeRuns(cells, xStart, extraPerSpace, images))
            out.add(PositionedLine(placed, y, lineHeight, ascent, images, lengths[i], ends[i]))
            y += lineHeight
        }
        if (out.isEmpty()) {
            val h = (style.lineHeightPt ?: style.fontSizePt * 1.4) * lineHeightScale
            out.add(PositionedLine(emptyList(), topY, h, style.fontSizePt * 0.8))
        }
        return out
    }

    /**
     * The bidi level of each char of each run, or null when a left-to-right paragraph has nothing
     * that reorders (#323). UAX #9 resolves each paragraph as a whole, before shaping, so that a
     * bracket mirrors by its own level. A forced line break ends a paragraph, as in browsers. An
     * inline image stands for U+FFFC.
     */
    private fun bidiLevels(runs: List<InlineRun>, baseLevel: Int): List<IntArray>? {
        if (baseLevel == 0 && runs.none { it.imageSrc == null && reorders(it.text) }) return null
        val out = runs.map { IntArray(if (it.imageSrc != null) 1 else it.text.length) { baseLevel } }
        val cps = ArrayList<Int>()
        val owners = ArrayList<Long>()
        fun resolve() {
            if (cps.isEmpty()) return
            val levels = Bidi.resolveLevels(cps.toIntArray(), baseLevel)
            for ((k, owner) in owners.withIndex()) {
                val levelsOfRun = out[(owner ushr 32).toInt()]
                val at = owner.toInt()
                levelsOfRun[at] = levels[k]
                if (cps[k] >= 0x10000) levelsOfRun[at + 1] = levels[k]
            }
            cps.clear()
            owners.clear()
        }
        for ((r, run) in runs.withIndex()) {
            when {
                run.hardBreak -> resolve()
                run.imageSrc != null -> { cps += 0xFFFC; owners += r.toLong() shl 32 }
                else -> {
                    var at = 0
                    while (at < run.text.length) {
                        val cp = codePointAt(run.text, at)
                        if (cp == '\n'.code) resolve() else if (cp != '\r'.code) { cps += cp; owners += (r.toLong() shl 32) or at.toLong() }
                        at += charCount(cp)
                    }
                }
            }
        }
        resolve()
        return out
    }

    /** True when [text] has a character that can move in a left-to-right paragraph. */
    private fun reorders(text: String): Boolean {
        var at = 0
        while (at < text.length) {
            val cp = codePointAt(text, at)
            if (Bidi.classify(cp) in REORDERING) return true
            at += charCount(cp)
        }
        return false
    }

    /** Reorders each line from logical to visual order with the levels of its cells, by rules L1 and L2 (#323). */
    private fun bidiLines(lines: List<List<Cell>>, baseLevel: Int): List<List<Cell>> = lines.map { line ->
        if (baseLevel == 0 && line.all { it.level == 0 }) line
        else {
            val levels = Bidi.lineLevels(IntArray(line.size) { line[it].cp }, IntArray(line.size) { line[it].level }, baseLevel)
            Bidi.reorderVisually(levels).map { line[it] }
        }
    }

    /**
     * Inter-character justification for a spaceless line with at least two
     * CJK cells: distribute [slack] evenly over the gaps between content
     * cells by folding it into each cell's advance, exactly the mechanism
     * letter-spacing and kerning use, so wrap width and drawn pen agree.
     */
    private fun justifyCjk(cells: List<Cell>, slack: Double) {
        if (slack <= 0.0) return
        var last = cells.size - 1
        while (last >= 0 && cells[last].cp == ' '.code) last--
        if (last < 1) return
        if (cells.subList(0, last + 1).count { FontMetrics.isWide(it.cp) } < 2) return
        val extra = slack / last // `last` = gap count between content cells
        for (k in 0 until last) {
            val c = cells[k]
            if (c.fontSize <= 0.0) continue
            val e1000 = (extra / c.fontSize * 1000.0).roundToInt()
            c.kernAfter1000 += e1000
            c.width += e1000 * c.fontSize / 1000.0
        }
    }

    /** Line content width (trailing spaces excluded) + interior space count (for justify). */
    private fun measure(cells: List<Cell>): Pair<Double, Int> {
        var last = cells.size - 1
        while (last >= 0 && cells[last].cp == ' '.code) last--
        var w = 0.0; var spaces = 0
        for (k in 0..last) {
            w += cells[k].width + cells[k].padBefore + cells[k].padAfter
            if (cells[k].cp == ' '.code) spaces++
        }
        return w to spaces
    }

    /** A list marker hangs outside the start edge: left of the text, or right of it in right-to-left text (#167). */
    private fun markerRun(
        marker: String, fontSize: Double, contentLeft: Double, contentRight: Double, rtl: Boolean, color: RgbColor,
    ): PlacedRun? {
        val spec = FontSpec(KiteFontFamily.Serif, bold = false, italic = false)
        var w = 0.0
        val glyphs = ArrayList<TextGlyph>(marker.length)
        for (cp in codePointsOf(marker)) { glyphs.add(glyph(cp, spec)); w += FontMetrics.advancePt(cp, fontSize) }
        if (glyphs.isEmpty()) return null
        val gap = 0.4 * fontSize
        val x = if (rtl) contentRight + gap else (contentLeft - w - gap).coerceAtLeast(0.0)
        return PlacedRun(glyphs, x, fontSize, spec, color)
    }

    /** A cell that paints an image rather than a glyph. */
    private val Cell.isImage: Boolean get() = imageHeight > 0.0 && (image != null || svgImage != null)

    private class Cell(
        // The character of the cell, as a code point: a character outside the BMP is one cell (#319).
        val cp: Int, var width: Double, val fontSize: Double,
        val spec: FontSpec, val color: RgbColor, val shift: Double, val underline: DecorationLine?,
        val face: EmbeddedFace? = null, var gid: Int = -1,
        // Kerning to the next glyph (1/1000 em), folded into this glyph's advance.
        var kernAfter1000: Int = 0,
        // GPOS mark attachment offset in font design units (0 for non-marks).
        var glyphXOffset: Double = 0.0, var glyphYOffset: Double = 0.0,
        // Ruby: the group id + reading shared by every cell of one <ruby> base.
        val rubyGroup: Int = -1, val rubyText: String? = null,
        // Link target when inside <a href> (see InlineRun.href).
        val href: String? = null,
        // The pronunciation of the element the character belongs to (see InlineRun.speech).
        val speech: SpeechHint? = null,
        // The ids of the elements around the character (see InlineRun.ids).
        val ids: List<String> = emptyList(),
        // Inline image cell (cp = U+FFFC): natural draw size + decoded
        // payload. `width` is the pen advance (justification may stretch it);
        // `imageWidth` is what the image actually draws at.
        val imageWidth: Double = 0.0,
        val imageHeight: Double = 0.0,
        val image: KiteImageData? = null,
        val svgImage: SvgImage? = null,
        val imageAlt: String? = null,
        val imageObjectFit: ObjectFit = ObjectFit.FILL,
        // The image's file in the archive; empty for an <svg> written in the chapter.
        val imageZipPath: String = "",
        // How many glyphs a ligature cell replaced; 1 for everything else.
        var ligComponents: Int = 1,
        // The text of a cell that stands for several characters, such as a ligature; null means [cp].
        var text: String? = null,
        // A hidden default ignorable, such as a ZWJ: it draws nothing and takes no advance.
        var invisible: Boolean = false,
        // Envelope padding when the reading is wider than its base (pt). Only the
        // group's first/last cells carry it; it widens wrap/measure and the pen
        // walk in placeRuns without entering the glyph advance stream.
        var padBefore: Double = 0.0, var padAfter: Double = 0.0,
        val lineThrough: DecorationLine? = null,
        val backgroundColor: CssBackground? = null,
        // The bidi level of the character, from [bidiLevels]; odd for right to left.
        val level: Int = 0,
        // Where the cell's character sits in the block's text, in code points. A ligature keeps
        // its first character's. -1 for a space and for a cell the layout adds, such as a
        // hyphen, so a line starts at its first other character.
        var src: Int = -1,
    )

    private sealed class Token {
        class Word(val cells: List<Cell>, val width: Double, val hyphenPoints: List<Int> = emptyList()) : Token()
        class Space(val cell: Cell) : Token() {
            val width: Double get() = cell.width
        }
        object Break : Token()
    }

    /**
     * How tall a horizontal inline image may be: the page's content height, less what its line
     * adds below the image, so the image and its line fit one page (#426). An inline image sits on
     * the baseline, so its line is the image plus the part of the line box below the baseline.
     */
    private fun inlineImageRoom(style: ComputedStyle): Double {
        if (maxImageHeight == Double.MAX_VALUE) return Double.MAX_VALUE
        val lineHeight = (style.lineHeightPt ?: style.fontSizePt * 1.4) * lineHeightScale
        val below = (lineHeight - style.fontSizePt * 0.8).coerceAtLeast(0.0)
        return (maxImageHeight - below).coerceAtLeast(1.0)
    }

    private fun tokenize(
        runs: List<InlineRun>,
        hyphensAuto: Boolean,
        contentW: Double = Double.MAX_VALUE,
        levels: List<IntArray>? = null,
        imageRoom: Double = Double.MAX_VALUE,
    ): List<Token> {
        val tokens = ArrayList<Token>()
        var word = ArrayList<Cell>()
        var wordW = 0.0
        var softHyphens = ArrayList<Int>()
        // The next character's place in the block's text; it counts what draws nothing too.
        var srcAt = 0
        fun endWord() {
            if (word.isNotEmpty()) {
                val ligated = shapeWord(word)   // GSUB: joining forms, ligatures, contextual substitutions
                positionMarks(word)             // GPOS mark-to-base attachment
                kernWord(word)
                // Skip hyphenation when a ligature collapsed cells (soft-hyphen indices
                // + the reconstructed word text would no longer line up), and for ruby
                // bases (a hyphen inside a ruby-annotated base is never wanted).
                val isRuby = word.first().rubyGroup >= 0
                val pts = if (ligated || isRuby) emptyList() else {
                    val s = LinkedHashSet(softHyphens)
                    // Hyphenation points index chars, so a word outside the BMP is not hyphenated.
                    if (hyphensAuto && word.size >= 5 && word.all { it.cp < 0x10000 && it.cp.toChar().isLetter() }) {
                        val text = buildString { word.forEach { append(it.cp.toChar()) } }
                        s.addAll(hyphenator.hyphenate(text))
                    }
                    s.sorted()
                }
                // A reading wider than its base pads the base's envelope
                // symmetrically so the overlay never collides with neighbours.
                if (isRuby) {
                    val rt = word.first().rubyText
                    if (rt != null) {
                        val rubyW = rubyGlyphs(rt, word.first()).width
                        val baseW = word.sumOf { it.width }
                        if (rubyW > baseW) {
                            val pad = (rubyW - baseW) / 2
                            word.first().padBefore += pad
                            word.last().padAfter += pad
                        }
                    }
                }
                val w = word.sumOf { it.width + it.padBefore + it.padAfter }
                val last = tokens.lastOrNull()
                // Kinsoku no-break-after: a word following an opener token
                // (e.g. （word) merges into it; hyphen indices shift past the
                // opener prefix.
                if (last is Token.Word && last.cells.isNotEmpty() && isOpener(last.cells.last().cp)) {
                    tokens[tokens.lastIndex] =
                        Token.Word(last.cells + word, last.width + w, pts.map { it + last.cells.size })
                } else {
                    tokens.add(Token.Word(word, w, pts))
                }
                word = ArrayList(); wordW = 0.0; softHyphens = ArrayList()
            }
        }
        for ((r, run) in runs.withIndex()) {
            val levelsOfRun = levels?.get(r)
            if (run.hardBreak) { endWord(); tokens.add(Token.Break); continue }
            // Inline image: one unbreakable single-cell token, sized from CSS
            // width/height (or the HTML attributes, both already in points),
            // else the intrinsic pixel size at 0.75pt/px, capped to the
            // content width. Undecodable images are skipped like block ones.
            if (run.imageSrc != null) {
                endWord()
                val src = srcAt
                srcAt += codePointCount(run.text)
                val svg = run.imageSvg ?: if (run.imageSrc.endsWith(".svg", true)) loadSvg(run.imageSrc) else null
                val img = if (svg == null) loadImage(run.imageSrc) else null
                val iw: Double; val ih: Double
                when {
                    svg != null && svg.width > 0 && svg.height > 0 -> { iw = svg.width; ih = svg.height }
                    img != null && img.width > 0 && img.height > 0 -> { iw = img.width.toDouble(); ih = img.height.toDouble() }
                    else -> continue
                }
                var w = run.imageCssW ?: run.imageCssH?.let { it * iw / ih } ?: (iw * 0.75)
                var h = run.imageCssH ?: (w * ih / iw)
                if (vertical) {
                    if (h > contentW) { w *= contentW / h; h = contentW }
                    if (w > maxImageHeight) { h *= maxImageHeight / w; w = maxImageHeight }
                } else {
                    if (w > contentW) { h *= contentW / w; w = contentW }
                    // The block axis gets a budget too, so a tall image is scaled to the page (#426).
                    if (h > imageRoom) { w *= imageRoom / h; h = imageRoom }
                }
                val inlineSize = if (vertical) h else w
                val cell = Cell(
                    0xFFFC, inlineSize, run.fontSizePt, fontSpec(run.family, run.bold, run.italic),
                    run.color, 0.0, null,
                    href = run.href, imageWidth = w, imageHeight = h, image = img, svgImage = svg,
                    imageAlt = run.imageAlt, imageObjectFit = run.imageObjectFit, imageZipPath = run.imageSrc,
                    level = levelsOfRun?.get(0) ?: 0, src = src,
                )
                tokens.add(Token.Word(listOf(cell), inlineSize))
                continue
            }
            // Never mix ruby groups (or ruby and plain text) inside one word: the
            // group must stay one unbreakable token with a single overlay.
            if (word.isNotEmpty() && word.last().rubyGroup != run.rubyGroup) endWord()
            val fs = run.fontSizePt
            val shift = when (run.valign) {
                CssVAlign.SUPER -> fs * 0.33
                CssVAlign.SUB -> -fs * 0.16
                // TOP/MIDDLE/BOTTOM are cell alignments, not inline shifts.
                else -> 0.0
            }
            val spec = fontSpec(run.family, run.bold, run.italic)
            val face = run.fontFamilyNames.firstNotNullOfOrNull { fonts.match(it, run.bold, run.italic) }
            fun cellFor(cp: Int, level: Int, src: Int): Cell {
                // font-variant: small-caps. Prefer the face's real `smcp` glyph;
                // otherwise synthesize: the UPPERCASE form at 0.8x size (the cell
                // then carries the uppercase char, a documented extraction quirk).
                var c = cp
                var cellFs = fs
                var smcpGid = -1
                if (run.smallCaps && CaseMapping.isLowercase(cp)) {
                    val g0 = face?.gidFor(cp) ?: 0
                    val s = if (g0 != 0) face!!.substSingle("smcp", g0) else 0
                    if (g0 != 0 && s != g0) smcpGid = s
                    else { c = CaseMapping.uppercase(cp); cellFs = fs * SMALL_CAPS_SCALE }
                }
                // Per-glyph fallback: a codepoint missing from the matched face
                // (cmap -> gid 0, `.notdef`) must not paint tofu. Try any other
                // registered face that has it; failing that, the generic
                // FontMetrics cell (face null) rides the system-font path.
                // Mixed-face words degrade gracefully: the shaping passes skip
                // multi-face words and placeRuns splits runs on a face change.
                val f = when {
                    face == null -> null
                    smcpGid >= 0 -> face
                    face.gidFor(c) != 0 -> face
                    else -> fonts.fallbackFor(c, run.bold, run.italic)
                }
                val cell = if (f != null) {
                    val gid = if (smcpGid >= 0) smcpGid else f.gidFor(c)
                    Cell(c, penAdvance1000(f, gid, c) * cellFs / 1000.0, cellFs, spec, run.color, shift, run.underline, f, gid,
                        rubyGroup = run.rubyGroup, rubyText = run.rubyText, href = run.href, speech = run.speech, ids = run.ids,
                        lineThrough = run.lineThrough, backgroundColor = run.backgroundColor, level = level, src = src)
                } else {
                    Cell(c, FontMetrics.advancePt(c, cellFs, run.bold, run.italic, run.family), cellFs, spec, run.color, shift, run.underline,
                        rubyGroup = run.rubyGroup, rubyText = run.rubyText, href = run.href, speech = run.speech, ids = run.ids,
                        lineThrough = run.lineThrough, backgroundColor = run.backgroundColor, level = level, src = src)
                }
                // letter-spacing: added to every glyph advance, kept in sync
                // between the wrap width and the drawn advance (like kerning).
                if (run.letterSpacingPt != 0.0) {
                    val ls1000 = (run.letterSpacingPt / cellFs * 1000.0).roundToInt()
                    cell.kernAfter1000 += ls1000
                    cell.width += ls1000 * cellFs / 1000.0
                }
                return cell
            }
            var at = 0
            while (at < run.text.length) {
                val cp = codePointAt(run.text, at)
                val level = levelsOfRun?.get(at) ?: 0
                val src = srcAt++
                at += charCount(cp)
                when {
                    cp == '\n'.code -> { endWord(); tokens.add(Token.Break) }
                    cp == '\r'.code -> {}
                    cp == 0x00AD -> softHyphens.add(word.size) // soft hyphen: a break point, drawn only if used
                    isWhitespace(cp) -> {
                        endWord()
                        val sw = if (face != null) face.advance1000(face.gidFor(' '.code)) * fs / 1000.0
                        else FontMetrics.advancePt(' '.code, fs, run.bold, run.italic, run.family)
                        // word-spacing adds to spaces; letter-spacing to every advance.
                        tokens.add(Token.Space(Cell(
                            ' '.code, sw + run.wordSpacingPt + run.letterSpacingPt, fs, spec, run.color, shift, run.underline,
                            href = run.href, speech = run.speech, ids = run.ids, lineThrough = run.lineThrough, backgroundColor = run.backgroundColor, level = level,
                        )))
                    }
                    // Ruby bases do not split per CJK char: the whole base is one token.
                    FontMetrics.isWide(cp) && run.rubyGroup < 0 -> {
                        // CJK ideographs break per character; kinsoku merges: a closer
                        // stays with the char before it, and anything after an opener
                        // stays with the opener (an opener must not end a line).
                        endWord()
                        val cell = cellFor(cp, level, src)
                        val last = tokens.lastOrNull()
                        val bindsBack = last is Token.Word && last.cells.isNotEmpty() &&
                            (isCloser(cp) || isOpener(last.cells.last().cp))
                        if (bindsBack) {
                            val lw = last as Token.Word
                            tokens[tokens.lastIndex] = Token.Word(lw.cells + cell, lw.width + cell.width)
                        } else {
                            tokens.add(Token.Word(listOf(cell), cell.width))
                        }
                    }
                    else -> { val c = cellFor(cp, level, src); word.add(c); wordW += c.width }
                }
            }
        }
        endWord()
        return tokens
    }

    /**
     * Runs a word through the GSUB table of its face (#211): the features HarfBuzz applies by
     * default for the script of the word, in its stages, with the joining forms of Arabic
     * across the whole word. Cells of one paint, link and ruby group shape together. Returns
     * true when the cells no longer stand one for each character, which turns hyphenation
     * off for the word.
     */
    private fun shapeWord(cells: MutableList<Cell>): Boolean {
        val face = cells.firstOrNull()?.face ?: return false
        if (cells.any { it.face !== face }) return false
        // A font without GSUB still has its syllables reordered, as HarfBuzz reorders them.
        val gsub = face.gsub ?: OpenTypeGsub.EMPTY
        val cps = IntArray(cells.size) { cells[it].cp }
        val script = TextShaper.script(cps, gsub)
        val forms = if (ArabicJoining.hasArabic(cps)) ArabicJoining.forms(cps) else null
        val optionalLigatures = cells.none { it.kernAfter1000 != 0 }
        val out = ArrayList<Cell>(cells.size)
        var changed = false
        var start = 0
        while (start < cells.size) {
            var end = start + 1
            while (end < cells.size && shapesWith(cells[start], cells[end])) end++
            val run = cells.subList(start, end)
            if (run.any { it.gid < 0 }) {
                out.addAll(run)
            } else {
                val runForms = forms?.copyOfRange(start, end)
                val glyphs = TextShaper.shape(
                    face, gsub, script, cps.copyOfRange(start, end), IntArray(run.size) { run[it].gid }, runForms, optionalLigatures,
                    BooleanArray(run.size) { run[it].level % 2 == 1 },
                )
                if (rebuild(run, glyphs, face, out)) changed = true
            }
            start = end
        }
        if (changed) { cells.clear(); cells.addAll(out) }
        return changed
    }

    /** Cells that GSUB may join: a ligature never spans a change of paint, link or ruby group. */
    private fun shapesWith(a: Cell, b: Cell): Boolean =
        samePaint(a, b) && a.href == b.href && a.speech === b.speech && a.ids === b.ids && a.rubyGroup == b.rubyGroup

    /**
     * Appends the cells of the shaped [glyphs] of [run] to [out]. A glyph that still stands for
     * its own character keeps its cell. Otherwise the first glyph of each cluster carries the
     * text of every character in the cluster, and the other glyphs of it carry none, so the
     * text of the page stays the text of the book (#314). True when the cells changed shape.
     */
    private fun rebuild(run: List<Cell>, glyphs: List<GsubGlyph>, face: EmbeddedFace, out: MutableList<Cell>): Boolean {
        if (glyphs.size == run.size && glyphs.indices.all { glyphs[it].cluster == it }) {
            for ((k, g) in glyphs.withIndex()) {
                val c = run[k]
                if (g.shaperData and TextShaper.INVISIBLE != 0) {
                    c.gid = g.gid; c.invisible = true; c.width = 0.0
                } else if (g.gid != c.gid) {
                    c.gid = g.gid
                    c.width = (penAdvance1000(face, g.gid, c.cp) + c.kernAfter1000) * c.fontSize / 1000.0
                }
                out.add(c)
            }
            return false
        }
        for ((j, g) in glyphs.withIndex()) {
            val first = j == 0 || glyphs[j - 1].cluster != g.cluster
            var next = run.size
            for (k in j + 1 until glyphs.size) if (glyphs[k].cluster > g.cluster) { next = glyphs[k].cluster; break }
            val base = run[g.cluster]
            val text = if (first) (g.cluster until next).joinToString("") { k -> run[k].text ?: CharText.of(run[k].cp) } else ""
            // Letter-spacing rides on the first glyph of the cluster, as it did on its character.
            val spacing = if (first) base.kernAfter1000 else 0
            out.add(
                Cell(
                    base.cp, (penAdvance1000(face, g.gid, base.cp) + spacing) * base.fontSize / 1000.0, base.fontSize,
                    base.spec, base.color, base.shift, base.underline, face, g.gid, kernAfter1000 = spacing,
                    rubyGroup = base.rubyGroup, rubyText = base.rubyText, href = base.href, speech = base.speech, ids = base.ids,
                    lineThrough = base.lineThrough, backgroundColor = base.backgroundColor, level = base.level,
                    src = base.src,
                ).also {
                    it.ligComponents = g.components; it.text = text
                    if (g.shaperData and TextShaper.INVISIBLE != 0) { it.invisible = true; it.width = 0.0 }
                },
            )
        }
        return true
    }

    /**
     * GPOS mark positioning: attach each combining mark by its anchor offset
     * (font units), correcting for how far the pen has advanced since the base.
     * Marks are zero-advance, so several land on one base.
     *
     * Three attachments, tried in the order a shaper would: onto the mark
     * already sitting there (type 6, which is what stacks two diacritics rather
     * than overprinting them), onto a ligature component (type 5), then onto
     * the base letter (type 4).
     *
     * A ligature's marks all attach to its LAST component. The ligature matcher
     * needs its components adjacent, so any mark it kept was written after the
     * whole ligature, and that is where a reader expects it.
     */
    private fun positionMarks(cells: List<Cell>) {
        var base: Cell? = null
        var advSinceBase = 0.0 // font units from the base origin to the current pen
        // The last attached mark, and its drawn origin relative to the base.
        var stacked: Cell? = null
        var stackedX = 0.0
        var stackedY = 0.0
        for (c in cells) {
            val face = c.face
            val b = base
            if (face != null && b != null && b.face === face && c.gid >= 0) {
                val prev = stacked
                val off = (if (prev != null) face.markStackOffset(prev.gid, c.gid) else null)
                    ?.let { (stackedX + it.first) to (stackedY + it.second) }
                    ?: (if (b.ligComponents > 1) face.markLigatureOffset(b.gid, c.gid, b.ligComponents - 1) else null)
                    ?: face.markOffset(b.gid, c.gid)
                if (off != null) {
                    c.glyphXOffset = off.first - advSinceBase
                    c.glyphYOffset = off.second
                    advSinceBase += face.advanceRaw(c.gid) // usually 0 for a mark
                    stacked = c; stackedX = off.first; stackedY = off.second
                    continue // still attached to the same base
                }
            }
            base = c
            stacked = null
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
            // Accumulate: the cell may already carry letter-spacing.
            if (k != 0) { a.kernAfter1000 += k; a.width += k * a.fontSize / 1000.0 }
        }
    }

    /**
     * [availAt] (when given) supplies each line's available width by line
     * index, the float-exclusion path. Null keeps the single-width fast
     * path, byte-identical to the pre-float behaviour.
     */
    private fun wrap(
        tokens: List<Token>,
        avail: Double,
        preserve: Boolean,
        availAt: ((Int) -> Double)? = null,
        /** `text-indent` of the block's first line; that line's budget shrinks to match. */
        firstLineIndent: Double = 0.0,
        /** Receives how each line ends, one entry per line, for copied text (#438). */
        ends: MutableList<KiteLineEnd>? = null,
    ): List<List<Cell>> {
        val lines = ArrayList<List<Cell>>()
        var line = ArrayList<Cell>()
        var lineW = 0.0
        var pendingSpaces = ArrayList<Cell>()
        fun commit(end: KiteLineEnd) {
            lines.add(line); ends?.add(end)
            line = ArrayList(); lineW = 0.0; pendingSpaces = ArrayList()
        }
        // Placement shifts the first line by the indent, so the budget must
        // shrink by the same amount or the packed line overflows the content
        // edge by up to the indent width (issue #6). Composes with float
        // insets because it subtracts AFTER availAt.
        fun lineAvail(): Double {
            val base = availAt?.invoke(lines.size) ?: avail
            return if (lines.isEmpty()) (base - firstLineIndent).coerceAtLeast(1.0) else base
        }
        for (tok in tokens) when (tok) {
            is Token.Break -> commit(KiteLineEnd.HARD)
            is Token.Space -> when {
                preserve -> { line.add(tok.cell); lineW += tok.width }
                line.isNotEmpty() -> pendingSpaces.add(tok.cell)
            }
            is Token.Word -> {
                var cells: List<Cell> = tok.cells
                var points: List<Int> = tok.hyphenPoints
                val spaces = pendingSpaces
                var space = spaces.sumOf { it.width }
                pendingSpaces = ArrayList()
                while (true) {
                    val leading = if (line.isNotEmpty()) space else 0.0
                    val w = cells.sumOf { it.width + it.padBefore + it.padAfter }
                    if (lineW + leading + w <= lineAvail() || (line.isEmpty() && points.isEmpty())) {
                        if (line.isNotEmpty() && space > 0.0) { line.addAll(spaces); lineW += space }
                        line.addAll(cells); lineW += w
                        break
                    }
                    // Largest hyphenation point whose prefix + hyphen still fits the current line.
                    var split = -1
                    var splitHyphenW = 0.0
                    val budget = lineAvail() - lineW - leading
                    for (p in points) {
                        if (p <= 0 || p >= cells.size) continue
                        val hw = hyphenWidth(cells[p - 1])
                        if (cells.subList(0, p).sumOf { it.width } + hw <= budget) { split = p; splitHyphenW = hw } else break
                    }
                    if (split > 0) {
                        if (line.isNotEmpty() && space > 0.0) { line.addAll(spaces); lineW += space }
                        val prefix = cells.subList(0, split)
                        line.addAll(prefix); lineW += prefix.sumOf { it.width }
                        line.add(hyphenCell(cells[split - 1])); lineW += splitHyphenW
                        commit(KiteLineEnd.HYPHEN)
                        cells = cells.subList(split, cells.size)
                        points = points.mapNotNull { if (it > split) it - split else null }
                        space = 0.0
                    } else if (line.isEmpty()) {
                        line.addAll(cells); lineW += w // unsplittable + nothing before: overflow rather than loop
                        break
                    } else {
                        // The spaces before the word, dropped here or kept on the line, are the break.
                        val atSpace = spaces.isNotEmpty() || line.last().cp == ' '.code
                        commit(if (atSpace) KiteLineEnd.SPACE else KiteLineEnd.NONE); space = 0.0 // retry on a fresh line
                    }
                }
            }
        }
        if (line.isNotEmpty() || lines.isEmpty()) { lines.add(line); ends?.add(KiteLineEnd.HARD) }
        return lines
    }

    private fun hyphenWidth(c: Cell): Double = c.face?.let { it.advance1000(it.gidFor('-'.code)) * c.fontSize / 1000.0 }
        ?: FontMetrics.advancePt('-'.code, c.fontSize, c.spec.bold, c.spec.italic, genericOf(c.spec))

    private fun hyphenCell(c: Cell): Cell {
        val face = c.face
        return Cell(
            '-'.code, hyphenWidth(c), c.fontSize, c.spec, c.color, c.shift, c.underline, face, face?.gidFor('-'.code) ?: -1,
            href = c.href, speech = c.speech, ids = c.ids, lineThrough = c.lineThrough, backgroundColor = c.backgroundColor, level = c.level,
        )
    }

    private fun genericOf(spec: FontSpec): GenericFont = when (spec.family) {
        KiteFontFamily.Monospace -> GenericFont.MONO
        KiteFontFamily.SansSerif -> GenericFont.SANS
        else -> GenericFont.SERIF
    }

    private fun placeRuns(
        cells: List<Cell>,
        xStart: Double,
        extraPerSpace: Double,
        imageSink: MutableList<PlacedImage>? = null,
    ): List<PlacedRun> {
        val out = ArrayList<PlacedRun>()
        var x = xStart
        var i = 0
        // Ruby envelope tracking: a group's overlay is centered over
        // [groupStart, x-at-group-end], which spans every run of the group (a
        // group may split into several runs on a face change) plus its padding.
        // Groups are assumed contiguous on the line; bidi reordering of a ruby
        // base inside RTL text is out of scope.
        var openGroup = -1
        var groupStart = 0.0
        var groupCell: Cell? = null
        fun closeGroup(end: Double) {
            val gc = groupCell
            if (openGroup >= 0 && gc?.rubyText != null) out.add(rubyRun(gc, groupStart, end))
            openGroup = -1; groupCell = null
        }
        while (i < cells.size) {
            val c = cells[i]
            if (c.cp == ' '.code) {
                closeGroup(x)
                val width = c.width + extraPerSpace
                if (c.underline != null || c.lineThrough != null || c.backgroundColor != null) {
                    out.add(PlacedRun(
                        emptyList(), x, c.fontSize, c.spec, c.color, c.shift, c.underline,
                        lineThrough = c.lineThrough, backgroundColor = c.backgroundColor, paintWidth = width,
                    ))
                }
                x += width; i++; continue
            }
            // Inline image cell: emit a PlacedImage and advance the pen.
            if (c.isImage) {
                closeGroup(x)
                imageSink?.add(
                    PlacedImage(x + c.padBefore, c.imageWidth, c.imageHeight, c.image, c.svgImage, c.imageAlt, c.imageObjectFit, c.imageZipPath),
                )
                x += c.padBefore + c.width + c.padAfter
                i++
                continue
            }
            if (c.rubyGroup != openGroup) closeGroup(x)
            if (c.rubyGroup >= 0 && openGroup < 0) { openGroup = c.rubyGroup; groupStart = x; groupCell = c }
            x += c.padBefore
            val startX = x
            val spec = c.spec; val fs = c.fontSize; val col = c.color; val sh = c.shift; val ul = c.underline; val face = c.face
            val glyphs = ArrayList<TextGlyph>()
            // An image cell always ends a text run, even when glued to a word (#99).
            while (i < cells.size && cells[i].cp != ' '.code && !cells[i].isImage && cells[i].rubyGroup == c.rubyGroup &&
                cells[i].href == c.href && cells[i].speech === c.speech && cells[i].ids === c.ids && samePaint(cells[i], c)
            ) {
                glyphs.add(glyphFor(cells[i])); x += cells[i].width + cells[i].padAfter; i++
            }
            out.add(PlacedRun(
                glyphs, startX, fs, spec, col, sh, ul,
                hasOutlines = face != null, unitsPerEm = face?.unitsPerEm ?: 1000,
                href = c.href, speech = c.speech, ids = c.ids, lineThrough = c.lineThrough, backgroundColor = c.backgroundColor,
                paintWidth = x - startX,
            ))
        }
        closeGroup(x)
        return out
    }

    /** The reading overlay for one ruby group, centered over its base envelope. */
    private fun rubyRun(base: Cell, envStart: Double, envEnd: Double): PlacedRun {
        val r = rubyGlyphs(base.rubyText!!, base)
        val x = ((envStart + envEnd) / 2 - r.width / 2).coerceAtLeast(0.0)
        return PlacedRun(
            r.glyphs, x, base.fontSize * RUBY_SIZE, base.spec, base.color,
            // Reading baseline sits on the base text's ascent line: the overlay's
            // own ascent then exactly fills the rubyExtra the line grew by.
            baselineShift = base.fontSize * 0.8,
            underline = null,
            hasOutlines = r.face != null, unitsPerEm = r.face?.unitsPerEm ?: 1000,
            isAnnotation = true,
        )
    }

    private class RubyGlyphs(val glyphs: List<TextGlyph>, val width: Double, val face: EmbeddedFace?)

    /**
     * Shape the reading at [RUBY_SIZE] of the base size. Single-face
     * simplification: the base's own face when it covers the whole reading,
     * else any registered face that does, else the generic system-font path.
     */
    private fun rubyGlyphs(reading: String, base: Cell): RubyGlyphs {
        val fs = base.fontSize * RUBY_SIZE
        val cps = codePointsOf(reading)
        val face = base.face?.takeIf { f -> cps.all { f.gidFor(it) != 0 } }
            ?: fonts.coveringAll(cps)
        var w = 0.0
        val glyphs = ArrayList<TextGlyph>(cps.size)
        for (cp in cps) {
            if (face != null) {
                val gid = face.gidFor(cp)
                val adv = penAdvance1000(face, gid, cp).toDouble()
                glyphs.add(TextGlyph(0, 1, gid, CharText.of(cp), adv, face.outline(gid), cp == ' '.code))
                w += adv * fs / 1000.0
            } else {
                val g = glyph(cp, base.spec)
                glyphs.add(g)
                w += g.advanceWidth * fs / 1000.0
            }
        }
        return RubyGlyphs(glyphs, w, face)
    }

    private fun samePaint(c: Cell, other: Cell): Boolean =
        c.spec == other.spec && c.fontSize == other.fontSize && c.color == other.color &&
            c.shift == other.shift && c.underline == other.underline && c.face === other.face &&
            c.lineThrough == other.lineThrough && c.backgroundColor == other.backgroundColor

    private fun glyphFor(c: Cell): TextGlyph {
        val face = c.face ?: return glyph(c.cp, c.spec).let { g ->
            // Generic cells fold letter-spacing (kernAfter1000) into the drawn
            // advance the same way embedded-face cells do below.
            if (c.kernAfter1000 != 0) g.copy(advanceWidth = g.advanceWidth + c.kernAfter1000) else g
        }
        return TextGlyph(
            byteOffset = 0, byteCount = 1, gid = c.gid, text = c.text ?: CharText.of(c.cp),
            // Pair kerning to the next glyph is folded into this glyph's advance so
            // the drawn pen movement matches the wrap width.
            advanceWidth = if (c.invisible) 0.0 else (penAdvance1000(face, c.gid, c.cp) + c.kernAfter1000).toDouble(),
            outline = face.outline(c.gid), isWordSpace = c.cp == ' '.code,
            xOffset = c.glyphXOffset, yOffset = c.glyphYOffset,
        )
    }

    private fun fontSpec(family: GenericFont, bold: Boolean, italic: Boolean) = FontSpec(
        when (family) {
            GenericFont.MONO -> KiteFontFamily.Monospace
            GenericFont.SANS -> KiteFontFamily.SansSerif
            GenericFont.SERIF -> KiteFontFamily.Serif
        },
        bold, italic,
    )

    private fun glyph(cp: Int, spec: FontSpec): TextGlyph {
        val fam = when (spec.family) {
            KiteFontFamily.Monospace -> GenericFont.MONO
            KiteFontFamily.SansSerif -> GenericFont.SANS
            KiteFontFamily.Serif -> GenericFont.SERIF
        }
        return TextGlyph(
            byteOffset = 0, byteCount = 1, gid = -1, text = CharText.of(cp),
            advanceWidth = FontMetrics.advance1000(cp, spec.bold, spec.italic, fam).toDouble(),
            outline = null, isWordSpace = cp == ' '.code,
        )
    }

    /** Whitespace, which no character outside the BMP is. */
    private fun isWhitespace(cp: Int): Boolean = cp < 0x10000 && cp.toChar().isWhitespace()

    /** CJK closing punctuation that must not start a line (kinsoku, no-break-before). */
    private fun isCloser(cp: Int): Boolean = cp in CJK_CLOSERS

    /** CJK opening punctuation that must not end a line (kinsoku, no-break-after). */
    private fun isOpener(cp: Int): Boolean = cp in CJK_OPENERS

    private companion object {
        /** A width no line of text reaches, to measure text that does not wrap (#33). */
        const val FLEX_UNBOUNDED = 1.0e6

        /** Room for a rounding difference between a measure and the layout after it, in points (#33). */
        const val FLEX_EPSILON = 0.01

        /** The most tracks a grid has on either axis, which bounds a hostile line number or span (#35). */
        const val GRID_MAX_TRACKS = 1000

        val BLACK = RgbColor(0.0, 0.0, 0.0)
        val EMPTY_SPEC = FontSpec(KiteFontFamily.Serif, bold = false, italic = false)
        /** Ruby reading size as a fraction of its base's font size. */
        const val RUBY_SIZE = 0.5
        /** Synthesized small-caps size (uppercase form scaled down). */
        const val SMALL_CAPS_SCALE = 0.8
        /** The bidi classes that can move a character in a left-to-right paragraph. */
        val REORDERING = setOf(Bidi.R, Bidi.AL, Bidi.AN, Bidi.RLE, Bidi.RLO, Bidi.RLI, Bidi.FSI)
        // JIS X 4051 no-break-before set (matching MuPDF's kinsoku table):
        // closing punctuation, plus the small kana and sound/iteration marks
        // that bind to the preceding character.
        val CJK_CLOSERS = setOf(
            0x3001, 0x3002, // 、 。
            0xFF0C, 0xFF0E, 0xFF01, 0xFF1F, 0xFF1B, 0xFF1A, // fullwidth , . ! ? ; :
            0x300D, 0x300F, 0x3011, 0x3015, 0x3009, 0x300B, 0x3017, // 」 』 】 〕 〉 》 〗
            0xFF09, 0xFF3D, 0xFF5D, // ） ］ ｝
            0x3005, // 々 ideographic iteration mark
            0x309D, 0x309E, 0x30FD, 0x30FE, // ゝ ゞ ヽ ヾ kana iteration marks
            0x30FC, // ー prolonged sound mark
            0x3041, 0x3043, 0x3045, 0x3047, 0x3049, // small ぁぃぅぇぉ
            0x3063, 0x3083, 0x3085, 0x3087, 0x308E, // small っゃゅょゎ
            0x30A1, 0x30A3, 0x30A5, 0x30A7, 0x30A9, // small ァィゥェォ
            0x30C3, 0x30E3, 0x30E5, 0x30E7, 0x30EE, // small ッャュョヮ
            0x30F5, 0x30F6, // small ヵヶ
        )
        // JIS X 4051 no-break-after set: an opener binds to what follows it.
        val CJK_OPENERS = setOf(
            0x300C, 0x300E, 0x3010, 0x3014, 0x3008, 0x300A, 0x3016, // 「 『 【 〔 〈 《 〖
            0xFF08, 0xFF3B, 0xFF5B, // （ ［ ｛
        )
    }
}

/** True for a track size that the content decides (CSS Grid Layout 1, 7.2). */
private val GridSize.intrinsic: Boolean
    get() = this is GridSize.Auto || this is GridSize.MinContent || this is GridSize.MaxContent
