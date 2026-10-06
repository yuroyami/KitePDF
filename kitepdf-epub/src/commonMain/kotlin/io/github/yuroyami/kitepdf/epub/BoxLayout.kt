package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

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
import io.github.yuroyami.kitepdf.epub.css.Edge
import io.github.yuroyami.kitepdf.epub.css.Emphasis
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
import io.github.yuroyami.kitepdf.epub.css.LineBreak
import io.github.yuroyami.kitepdf.epub.css.ObjectFit
import io.github.yuroyami.kitepdf.epub.css.GenericFont
import io.github.yuroyami.kitepdf.epub.css.TextAlign
import io.github.yuroyami.kitepdf.epub.css.TextOrientation
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
 * Vertical writing: offset (in em) from the mapped baseline axis to the em-box centre an upright
 * glyph is centred on, assuming the nominal 0.88/0.12 ascent/descent split: (0.88 - 0.12) / 2.
 */
internal const val UPRIGHT_CENTER = 0.38

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
     * A null language hyphenates with the en-US set; a language with no
     * bundled set is not hyphenated, since English breaks would be wrong
     * breaks in it (#615).
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
    /** Whether an image's path names an SVG, by its name or by the type of a blob it names (#533). */
    private val isSvg: (String) -> Boolean = ::namesSvg,
) {
    private val hyphenator by lazy { if (language.isNullOrBlank()) Hyphenator.enUs() else Hyphenator.forLanguage(language) }

    /** Logical pen advance of [gid] in 1/1000 em, honouring vertical mode for upright glyphs. */
    private fun penAdvance1000(face: EmbeddedFace, gid: Int, cp: Int, orientation: TextOrientation = TextOrientation.MIXED): Int =
        if (upright(cp, orientation)) face.advanceHeight1000(gid) ?: 1000
        else face.advance1000(gid)

    /** Whether [cp] stands upright in this layout's text: vertical text, as [orientation] says (#508). */
    private fun upright(cp: Int, orientation: TextOrientation): Boolean = vertical && when (orientation) {
        TextOrientation.MIXED -> FontMetrics.isWide(cp)
        TextOrientation.UPRIGHT -> true
        TextOrientation.SIDEWAYS -> false
    }

    /** True for a letter that stands upright where text-orientation alone puts it: it takes an em down the column (#508). */
    private fun uprightOnlyByStyle(cp: Int, orientation: TextOrientation): Boolean =
        upright(cp, orientation) && !FontMetrics.isWide(cp)

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
        val run = start(root, contentWidth, contentHeight)
        while (!run.step()) Unit
        return root.borderBoxHeight
    }

    /**
     * [layout] as a [Run] of steps, each the next child of a block in normal flow or the end of a
     * block, so that a caller can stop between two steps and go on later (#389). Until the run
     * ends, this layout serves no other.
     */
    fun start(root: BlockBox, contentWidth: Double, contentHeight: Double? = null): Run {
        activeFloats.clear()
        pendingAbs.clear()
        pageCb.left = 0.0; pageCb.top = 0.0
        pageCb.width = contentWidth; pageCb.height = contentHeight ?: 0.0
        currentCb = pageCb
        return Run(root, contentHeight)
    }

    /** One layout of [start], a step at a time. */
    inner class Run internal constructor(private val root: BlockBox, private val contentHeight: Double?) {
        private val frames = arrayListOf(BlockFrame(root, xLeft = 0.0, availWidth = pageCb.width, topY = 0.0, forcedWidth = null))

        /** Takes the next step, and returns true once the layout is done. */
        fun step(): Boolean {
            if (frames.isEmpty()) return true
            stepFrames(frames)
            if (frames.isNotEmpty()) return false
            if (contentHeight == null) pageCb.height = root.borderBoxHeight
            flushAbs(pageCb)
            applyRelativeOffsets(root)
            return true
        }
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
                for (m in ln.maths) m.x += dx
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
        val frames = arrayListOf(BlockFrame(box, xLeft, availWidth, topY, forcedWidth))
        while (frames.isNotEmpty()) stepFrames(frames)
    }

    /**
     * One step of [frames]: the next child of the innermost block, or the end of that block. A
     * block child in normal flow opens a frame of its own instead of a call, so a block nested a
     * hundred deep costs no stack, and a [Run] can stop between any two steps (#389).
     */
    private fun stepFrames(frames: ArrayList<BlockFrame>) {
        val frame = frames.last()
        if (frame.next < frame.children.size) {
            frame.place(frame.children[frame.next++])?.let(frames::add)
            return
        }
        frames.removeAt(frames.lastIndex)
        frame.close()
        frames.lastOrNull()?.landed(frame.box)
    }

    /**
     * A block whose children are laying out: what the box model resolved when it opened, and the
     * flow cursor its children move down. [layoutBlock] runs one to its end; a [Run] keeps one for
     * each block between the root and the child it is at.
     */
    private inner class BlockFrame(val box: BlockBox, xLeft: Double, availWidth: Double, topY: Double, forcedWidth: Double?) {
        private val s = box.style
        // A positioned box is the containing block for its out-of-flow
        // descendants, so it opens one and fills it in once its size is known.
        private val savedCb = currentCb
        private val ownCb = if (s.position != CssPosition.STATIC) AbsContainingBlock() else null
        private val bL = s.borderLeft.effective
        private val contentW: Double
        private val contentLeft: Double
        private val contentTop: Double
        private var cursorY: Double
        private var prevBottom = 0.0
        private var first = true
        private val floatsBefore: Int

        /** The children that stack in this block's flow, and the index of the next one. */
        val children: List<LayoutBox>
        var next = 0

        /** The bottom margin of the block child whose own frame is open above this one. */
        private var openChildBottom = 0.0

        init {
            if (ownCb != null) currentCb = ownCb
            val bR = s.borderRight.effective
            val extra = s.marginLeftPt + s.marginRightPt + bL + bR + s.paddingLeftPt + s.paddingRightPt
            var width = s.widthPt ?: (availWidth - extra)
            s.maxWidthPt?.let { if (width > it) width = it }
            s.minWidthPt?.let { if (width < it) width = it } // min wins over max
            // An embedded document's default width never pushes it past its column (#40).
            if (box.embed != null) width = width.coerceAtMost(availWidth - extra)
            if (forcedWidth != null) width = forcedWidth - (bL + s.paddingLeftPt + s.paddingRightPt + bR)
            contentW = width.coerceAtLeast(0.0)

            box.borderBoxWidth = bL + s.paddingLeftPt + contentW + s.paddingRightPt + bR
            val leftMargin = when {
                forcedWidth != null -> 0.0
                s.marginLeftAuto && s.marginRightAuto -> maxOf(0.0, (availWidth - box.borderBoxWidth) / 2)
                else -> s.marginLeftPt
            }
            box.x = xLeft + leftMargin
            box.y = topY
            contentLeft = box.x + bL + s.paddingLeftPt
            contentTop = box.y + s.borderTop.effective + s.paddingTopPt

            cursorY = contentTop
            floatsBefore = activeFloats.size
            // A flex or grid container places its children as items instead of stacking them (#33, #35),
            // and a block with columns moves them into its columns (#34).
            val itemLayout = s.display == Display.FLEX || s.display == Display.GRID
            val gap = if (s.flex.columnGapNormal) s.fontSizePt else s.flex.columnGap
            val columnCount = if (itemLayout || vertical) 1 else s.columns.countIn(contentW, gap)
            children = if (itemLayout || columnCount > 1) emptyList() else box.children
            if (itemLayout) {
                val definite = s.heightPt?.let { h -> h.coerceAtMost(s.maxHeightPt ?: h).coerceAtLeast(s.minHeightPt ?: 0.0) }
                cursorY += if (s.display == Display.GRID) layoutGrid(box, contentLeft, contentW, contentTop, definite)
                else layoutFlex(box, contentLeft, contentW, contentTop, definite)
            } else if (columnCount > 1) {
                cursorY += layoutColumns(box, contentLeft, contentW, contentTop, columnCount, gap)
            }
        }

        /**
         * Places [child] in the flow. A block child in normal flow comes back as the frame that
         * lays it out, and [landed] moves the cursor below it once that frame has closed.
         */
        fun place(child: LayoutBox): BlockFrame? {
            // Out-of-flow (position:absolute/fixed): queued now, placed once its
            // containing block knows its own size. It never advances the
            // normal-flow cursor. Fixed-layout pages use this to overlay panels.
            val pos = if (child is TextBlockBox) CssPosition.STATIC else child.style.position
            if (pos == CssPosition.ABSOLUTE || pos == CssPosition.FIXED) {
                pendingAbs.add(PendingAbs(child, if (pos == CssPosition.FIXED) pageCb else currentCb))
                return null
            }
            // An anonymous text box shares its block's style, so the block's own clear and float
            // must not apply to it again (#454).
            val anonymous = child is TextBlockBox
            // clear: the flow cursor drops below matching floats before this
            // child lays out (margin collapse across clearance not modelled).
            if (!anonymous && child.style.clear != CssClear.NONE) {
                cursorY = maxOf(cursorY, clearY(child.style.clear))
            }
            // float:left/right leaves the flow: it lays out against the content
            // edge at the current y, registers an exclusion band that shortens
            // overlapping text lines, and does not advance the flow cursor.
            if (!anonymous && child.style.cssFloat != CssFloat.NONE && child !is TableRowBox) {
                val topMargin = if (child is BlockBox) child.style.marginTopPt else 0.0
                placeFloat(child, contentLeft, contentW, cursorY + topMargin)
                return null
            }
            // Anonymous text boxes carry no margins; real block children do.
            val topMargin = if (child is BlockBox) child.style.marginTopPt else 0.0
            val botMargin = if (child is BlockBox) child.style.marginBottomPt else 0.0
            val gap = if (first) topMargin else maxOf(prevBottom, topMargin)
            if (child is BlockBox) {
                openChildBottom = botMargin
                return BlockFrame(child, contentLeft, contentW, cursorY + gap, forcedWidth = null)
            }
            layoutChild(child, contentLeft, contentW, cursorY + gap)
            cursorY = child.bottom
            prevBottom = botMargin
            first = false
            return null
        }

        /** Moves the cursor below [child], the block child of [place] whose frame has closed. */
        fun landed(child: BlockBox) {
            cursorY = child.bottom
            prevBottom = openChildBottom
            first = false
        }

        /** Sizes the block around its laid-out children and places what waited on its size. */
        fun close() {
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
        val ew = box.style.widthPt ?: box.attrWidth
        val eh = box.style.heightPt ?: box.attrHeight
        // A remote picture whose box is sized on both sides keeps that box whether or not its
        // bytes have landed, and the page paints it once they have (#38).
        val remoteBox = if (box.svg == null && isRemoteUrl(box.zipPath) && ew != null && eh != null && ew > 0.0 && eh > 0.0) ew to eh else null
        // SVG (inline <svg> preset, or a .svg file reference) sizes from its intrinsic
        // viewport and paints as vectors; raster images decode to a KiteImageData.
        val svg = box.svg ?: if (remoteBox == null && isSvg(box.zipPath)) loadSvg(box.zipPath)?.also { box.svg = it } else null
        val intrinsicW: Double; val intrinsicH: Double
        val media = box.media
        if (svg != null) {
            intrinsicW = svg.width; intrinsicH = svg.height
        } else {
            val img = if (box.zipPath.isEmpty() || remoteBox != null) null else loadImage(box.zipPath)
            if (img != null && img.width > 0 && img.height > 0) {
                box.image = img
                intrinsicW = img.width.toDouble(); intrinsicH = img.height.toDouble()
            } else if (remoteBox != null) {
                intrinsicW = remoteBox.first; intrinsicH = remoteBox.second
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
        // to the intrinsic size. Scale down proportionally past max-width / content /
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
        // object-fit fits the picture into this box when it paints, and leaves the box as it is (CSS Images 3, 4.5, #490).
        // Without a size of its own, a picture takes its intrinsic size, a CSS pixel (0.75 pt) for
        // each of its pixels or an SVG's user units, block or floated as inline (CSS 2.1, 10.3.2).
        // An SVG with a ratio and no size, and a media element, fill the room (#569).
        val natural = when {
            box.embed != null -> intrinsicW
            svg != null -> if (svg.hasIntrinsicSize) svg.width * 0.75 else null
            media == null && box.image != null -> intrinsicW * 0.75
            else -> null
        }
        var w = ew ?: (eh?.let { it / aspect } ?: natural ?: physicalRoomW)
        var h = eh ?: (w * aspect)
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
            // for an image pixel, where an SVG with only a viewBox would fill its column (#35, #569).
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

    // ---- multi-column layout ---------------------------------------------------

    /**
     * A piece of a set of columns that moves into a column whole: a line, a block image, or a
     * box kept together (#34). [move] shifts it and all it holds.
     */
    private class ColumnUnit(val top: Double, val bottom: Double, val move: (Double, Double) -> Unit) {
        var dx = 0.0
        var dy = 0.0
    }

    /**
     * Lays the children of [box] out in [count] columns with [gap] between them (CSS Multi-column
     * Layout 1). Each run of children between two that span the columns lays out in one column's
     * width, then moves into balanced columns. Returns the content height the columns take.
     */
    private fun layoutColumns(box: BlockBox, contentLeft: Double, contentW: Double, contentTop: Double, count: Int, gap: Double): Double {
        val colW = ((contentW - gap * (count - 1)) / count).coerceAtLeast(1.0)
        box.inColumns = true
        box.columnRules.clear()
        var y = contentTop
        var run = ArrayList<LayoutBox>()
        fun flush() {
            if (run.isNotEmpty()) y = columnRun(box, run, contentLeft, colW, gap, count, y)
            run = ArrayList()
        }
        for (child in box.children) {
            val pos = if (child is TextBlockBox) CssPosition.STATIC else child.style.position
            if (pos == CssPosition.ABSOLUTE || pos == CssPosition.FIXED) {
                pendingAbs.add(PendingAbs(child, if (pos == CssPosition.FIXED) pageCb else currentCb))
                continue
            }
            if (child !is TextBlockBox && child.style.columns.spanAll) {
                flush()
                // 6.1: a spanning element takes the whole width, between two sets of columns.
                layoutChild(child, contentLeft, contentW, y + marginTop(child))
                y = child.bottom + marginBottom(child)
            } else {
                run += child
            }
        }
        flush()
        return y - contentTop
    }

    /**
     * Lays [children] out in one column [colW] wide at [top], then moves them into [count]
     * columns. They balance (7.1) when they fit a page. A longer run starts a page, fills whole
     * pages of columns, and balances the last one, so the reading order stays column after column.
     * Returns the bottom of the columns.
     */
    private fun columnRun(box: BlockBox, children: List<LayoutBox>, left: Double, colW: Double, gap: Double, count: Int, top: Double): Double {
        var cursor = top
        var prevBottom = 0.0
        var first = true
        for (child in children) {
            val topMargin = marginTop(child)
            layoutChild(child, left, colW, cursor + if (first) topMargin else maxOf(prevBottom, topMargin))
            cursor = child.bottom
            prevBottom = marginBottom(child)
            first = false
        }
        val units = ArrayList<ColumnUnit>()
        val holders = ArrayList<Pair<LayoutBox, ColumnUnit>>()
        for (child in children) columnUnits(child, units, holders)
        if (units.isEmpty()) return cursor + prevBottom
        val pageHeight = maxImageHeight
        val longRun = columnsNeeded(units, 0, pageHeight) > count
        // A run that crosses a page starts one, so each page holds whole columns.
        if (longRun) children.first().forcedBreakBefore = true
        var i = 0
        var chunkTop = units.first().top
        var bottom = chunkTop
        while (i < units.size) {
            // Each page of columns is a page tall, and the last one balances.
            val height = if (columnsNeeded(units, i, pageHeight) > count) pageHeight else balancedHeight(units, i, count)
            var used = 0
            var chunkBottom = chunkTop
            for (col in 0 until count) {
                if (i >= units.size) break
                val colTop = units[i].top
                val start = i
                while (i < units.size && (i == start || units[i].bottom - colTop <= height + FLEX_EPSILON)) {
                    val u = units[i]
                    u.dx = col * (colW + gap)
                    u.dy = chunkTop + (u.top - colTop) - u.top
                    u.move(u.dx, u.dy)
                    chunkBottom = maxOf(chunkBottom, u.bottom + u.dy)
                    i++
                }
                used++
            }
            box.style.columns.rule?.let { rule ->
                for (col in 1 until used) {
                    val x = left + col * (colW + gap) - gap / 2 - rule.width / 2
                    columnRule(box, rule, x, chunkTop, chunkBottom - chunkTop)
                }
            }
            bottom = chunkBottom
            if (i < units.size) chunkTop += pageHeight
        }
        // A block that holds units moved with its first one, so its place and its anchors follow them.
        for ((holder, firstUnit) in holders) { holder.x += firstUnit.dx; holder.y += firstUnit.dy }
        return bottom
    }

    /** The units of [b], in order: its lines, or [b] whole when it is kept together. */
    private fun columnUnits(b: LayoutBox, out: MutableList<ColumnUnit>, holders: MutableList<Pair<LayoutBox, ColumnUnit>>) {
        when (b) {
            is TextBlockBox -> for (line in b.lines) out += ColumnUnit(line.yTop, line.yTop + line.height) { dx, dy ->
                line.yTop += dy
                for (r in line.runs) r.x += dx
                for (im in line.images) im.x += dx
                for (m in line.maths) m.x += dx
            }
            is BlockBox -> if (keptTogether(b)) {
                out += ColumnUnit(b.y, b.bottom) { dx, dy -> shiftSubtree(b, dx, dy) }
            } else {
                val before = out.size
                for (c in b.children) columnUnits(c, out, holders)
                if (out.size > before) holders += b to out[before]
            }
            is TableRowBox -> {}
            else -> out += ColumnUnit(b.y, b.bottom) { dx, dy -> shiftSubtree(b, dx, dy) }
        }
    }

    /** True for a block that moves into a column whole: one that paints a box, keeps together or lays out its own items. */
    private fun keptTogether(b: BlockBox): Boolean {
        val s = b.style
        val painted = s.backgroundColor != null || s.backgroundLayers.isNotEmpty() || s.shadows.isNotEmpty() ||
            s.borderTop.effective > 0 || s.borderRight.effective > 0 || s.borderBottom.effective > 0 || s.borderLeft.effective > 0
        return painted || s.breakInsideAvoid || b.hasEffects || b.linkHref != null || b.embed != null || b.inColumns ||
            s.display == Display.FLEX || s.display == Display.GRID
    }

    /** How many columns of [height] the units from [from] fill, never splitting a unit. */
    private fun columnsNeeded(units: List<ColumnUnit>, from: Int, height: Double): Int {
        if (from >= units.size) return 0
        var columns = 1
        var colTop = units[from].top
        for (k in from + 1 until units.size) {
            val u = units[k]
            if (u.bottom - colTop > height + FLEX_EPSILON) {
                columns++
                colTop = u.top
            }
        }
        return columns
    }

    /** The least column height at which the units from [from] fit [count] columns (7.1). */
    private fun balancedHeight(units: List<ColumnUnit>, from: Int, count: Int): Double {
        val total = units.last().bottom - units[from].top
        var lo = total / count
        var hi = total
        if (columnsNeeded(units, from, lo) <= count) return lo
        repeat(40) {
            val mid = (lo + hi) / 2
            if (columnsNeeded(units, from, mid) <= count) hi = mid else lo = mid
        }
        return hi
    }

    /** Adds a rule [height] tall at [x] and [top] to [box]'s columns, as a box with a left border. */
    private fun columnRule(box: BlockBox, rule: Edge, x: Double, top: Double, height: Double) {
        if (height <= 0.0) return
        val style = ComputedStyle.initial(box.style.fontSizePt).copy(borderLeft = rule)
        box.columnRules += BlockBox(style, emptyList()).also {
            it.x = x
            it.y = top
            it.borderBoxWidth = rule.width
            it.borderBoxHeight = height
        }
    }

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
    private fun resolvedAlign(style: ComputedStyle): TextAlign = resolvedAlign(style.textAlign, style.direction)

    private fun resolvedAlign(align: TextAlign, direction: Direction): TextAlign = when (align) {
        TextAlign.START -> if (direction == Direction.RTL) TextAlign.RIGHT else TextAlign.LEFT
        TextAlign.END -> if (direction == Direction.RTL) TextAlign.LEFT else TextAlign.RIGHT
        else -> align
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
                cp == 0x200B -> endWord()
                // A no-break space holds a word together; the other spaces end one and hang past the line (#577).
                isGlueSpace(cp) -> { val w = gapWidthPt(cp, run); line += w; word += w }
                isFixedSpace(cp) -> { line += gapWidthPt(cp, run); endWord() }
                isWhitespace(cp) -> { line += FontMetrics.advancePt(' '.code, run.fontSizePt, run.bold, run.italic, run.family); endWord() }
                else -> {
                    val w = FontMetrics.advancePt(cp, run.fontSizePt, run.bold, run.italic, run.family); line += w; word += w
                    if (run.lineBreak == LineBreak.ANYWHERE) endWord()
                }
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
        val rtl = baseLevel == 1
        val align = resolvedAlign(style)
        // The last line, and a line that a forced break ends, align as text-align-last says, and
        // with its auto as text-align does, but at the start where that justifies (#508, #573).
        val lastAlign = style.textAlignLast?.let { resolvedAlign(it, style.direction) }
            ?: if (align == TextAlign.JUSTIFY) resolvedAlign(TextAlign.START, style.direction) else align
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
            // Emphasis marks make room as ruby does: over the line, or under it (CSS Text Decoration 3, 3.3, #508).
            val marked = cells.filter { it.emphasis != null && takesMark(it.cp) }
            val marksOver = marked.filter { marksOver(it) }.maxOfOrNull { it.fontSize * EMPHASIS_SIZE * 0.8 } ?: 0.0
            val marksUnder = marked.filter { !marksOver(it) }.maxOfOrNull { it.fontSize * EMPHASIS_SIZE } ?: 0.0
            val rubyExtra = rubyBaseFs * RUBY_SIZE * 0.8 + marksOver
            var lineHeight = (style.lineHeightPt ?: maxFs * 1.4) * lineHeightScale + rubyExtra + marksUnder
            var ascent = maxFs * 0.8 + rubyExtra
            // An inline image grows the line: its bottom sits on the baseline,
            // so the ascent must cover the image height (descent unchanged).
            val imgH = cells.maxOfOrNull { if (vertical) it.imageWidth else it.imageHeight } ?: 0.0
            if (imgH > ascent) {
                lineHeight += imgH - ascent
                ascent = imgH
            }
            // A formula grows the line by its height above the baseline and its depth below it (#32).
            val mathAscent = cells.maxOfOrNull { it.math?.ascent ?: 0.0 } ?: 0.0
            val mathDescent = cells.maxOfOrNull { it.math?.descent ?: 0.0 } ?: 0.0
            if (mathAscent > ascent) {
                lineHeight += mathAscent - ascent
                ascent = mathAscent
            }
            if (mathDescent > lineHeight - ascent) lineHeight = ascent + mathDescent

            val (leftInset, rightInset) = insetsFor(i)
            val lineAvail = (contentW - leftInset - rightInset).coerceAtLeast(1.0)
            val (lineWidth, interiorSpaces) = measure(cells)
            val firstIndent = if (i == 0) style.textIndentPt else 0.0
            val slack = (lineAvail - lineWidth - firstIndent).coerceAtLeast(0.0)
            val lastOfRun = i == cellLines.lastIndex || ends.getOrNull(i) == KiteLineEnd.HARD
            val lineAlign = if (lastOfRun) lastAlign else align
            val justify = lineAlign == TextAlign.JUSTIFY && interiorSpaces > 0
            val extraPerSpace = if (justify) slack / interiorSpaces else 0.0
            // Spaceless CJK lines justify between characters: with no interior
            // spaces to stretch, the slack spreads across the inter-cell gaps
            // (JIS-style inter-character expansion). Latin-only spaceless lines
            // (one long word) are left ragged, as every real reader does.
            val stretched = justify ||
                lineAlign == TextAlign.JUSTIFY && interiorSpaces == 0 && justifyCjk(cells, slack)
            // A justified line that cannot stretch sits at the start edge, the right one in
            // right-to-left text (#572).
            val alignOffset = when {
                stretched -> 0.0
                lineAlign == TextAlign.RIGHT -> slack
                lineAlign == TextAlign.CENTER -> slack / 2
                lineAlign == TextAlign.JUSTIFY && rtl -> slack
                else -> 0.0
            }
            // The first line's indent sits at its start edge, which is the right one in right-to-left
            // text, where the slack before the line already leaves it room (#572).
            val xStart = contentLeft + leftInset + (if (rtl) 0.0 else firstIndent) + alignOffset

            val placed = ArrayList<PlacedRun>()
            val images = ArrayList<PlacedImage>()
            val maths = ArrayList<PlacedMath>()
            if (i == 0 && marker != null) {
                markerRun(marker, style.fontSizePt, contentLeft, contentLeft + contentW, rtl, markerColor)?.let(placed::add)
            }
            placed.addAll(placeRuns(cells, xStart, extraPerSpace, images, maths))
            out.add(PositionedLine(placed, y, lineHeight, ascent, images, lengths[i], ends[i], maths))
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
     * True when the line now fills its width.
     */
    private fun justifyCjk(cells: List<Cell>, slack: Double): Boolean {
        if (slack <= 0.0) return true
        var last = cells.size - 1
        while (last >= 0 && isGap(cells[last].cp)) last--
        if (last < 1) return false
        if (cells.subList(0, last + 1).count { FontMetrics.isWide(it.cp) } < 2) return false
        val extra = slack / last // `last` = gap count between content cells
        for (k in 0 until last) {
            val c = cells[k]
            if (c.fontSize <= 0.0) continue
            val e1000 = (extra / c.fontSize * 1000.0).roundToInt()
            c.kernAfter1000 += e1000
            c.width += e1000 * c.fontSize / 1000.0
        }
        return true
    }

    /**
     * How many of [cells] to keep on a line [budget] wide when the word breaks between its
     * characters (#574): the most that fit, ending between two characters that [joined] does not
     * hold together, and, when [atLeastOne], one character at least. Zero when nothing fits or
     * the word has no such place.
     */
    private fun longestFit(
        cells: List<Cell>,
        budget: Double,
        atLeastOne: Boolean = true,
        joined: (Int, Int) -> Boolean = ::tiedToPrevious,
    ): Int {
        var best = 0
        var width = 0.0
        for (k in 1 until cells.size) {
            width += cells[k - 1].width + cells[k - 1].padBefore + cells[k - 1].padAfter
            if (joined(cells[k - 1].cp, cells[k].cp)) continue
            if (width > budget) return if (best == 0 && atLeastOne) k else best
            best = k
        }
        return best
    }

    /**
     * True when `word-break: break-all` may not break between [prev] and [cp] (#508): one grapheme
     * cluster, a closing or joining mark that must not start a line, or an opening one that must
     * not end it (UAX 14, LB 13, 16, 19 and 21), as [rule] has them. `line-break: anywhere` keeps
     * only the grapheme clusters whole.
     */
    private fun heldTogether(prev: Int, cp: Int, rule: LineBreak = LineBreak.AUTO): Boolean =
        if (rule == LineBreak.ANYWHERE) tiedToPrevious(prev, cp)
        else tiedToPrevious(prev, cp) || isCloser(cp, rule) || isOpener(prev, rule) || cp in NO_BREAK_BEFORE ||
            prev in NO_BREAK_AFTER || isGlueSpace(cp) || isGlueSpace(prev)

    /** True when no break may fall between [prev] and [cp]: they make one grapheme cluster. */
    private fun tiedToPrevious(prev: Int, cp: Int): Boolean =
        Normalizer.isMark(cp) || cp == 0x200D || prev == 0x200D || cp == 0x200C ||
            cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F

    /**
     * Line content width (trailing spaces excluded, as they hang) + interior word separator count
     * (for justify). A no-break space stretches like a space; the fixed-width spaces do not (#577).
     */
    private fun measure(cells: List<Cell>): Pair<Double, Int> {
        var last = cells.size - 1
        while (last >= 0 && isGap(cells[last].cp)) last--
        var w = 0.0; var spaces = 0
        for (k in 0..last) {
            w += cells[k].width + cells[k].padBefore + cells[k].padAfter
            if (isWordSeparator(cells[k].cp)) spaces++
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

    /** A cell that paints an image rather than a glyph, or a remote one whose page paints it once it lands (#38). */
    private val Cell.isImage: Boolean get() = imageHeight > 0.0 && (image != null || svgImage != null || isRemoteUrl(imageZipPath))

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
        // The element the character belongs to, in a chapter that scripts run in (see InlineRun.element).
        val element: KiteXmlNode.Element? = null,
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
        // A formula cell (cp = U+FFFC): its layout and what the reading order says for it (#32).
        val math: MathBox? = null,
        val mathText: String = "",
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
        val overline: DecorationLine? = null,
        val emphasis: Emphasis? = null,
        val backgroundColor: CssBackground? = null,
        // The bidi level of the character, from [bidiLevels]; odd for right to left.
        val level: Int = 0,
        // Where the cell's character sits in the block's text, in code points. A ligature keeps
        // its first character's. -1 for a space and for a cell the layout adds, such as a
        // hyphen, so a line starts at its first other character.
        var src: Int = -1,
        // text-orientation of the character's element, which vertical text reads (#508).
        val orientation: TextOrientation = TextOrientation.MIXED,
        // A tate-chu-yoko composition (cp = U+FFFC, text its characters): what it draws (#508).
        val combined: Combined? = null,
    )

    /** A tate-chu-yoko composition's glyphs at their own advances across the column, in [face] or the generic font. */
    private class Combined(val glyphs: List<TextGlyph>, val face: EmbeddedFace?)

    private sealed class Token {
        /**
         * [overflowWrap]: the word may break between two characters when it cannot fit a line of
         * its own (#574). [breakAll]: a line may break between two of its letters wherever it
         * ends, `word-break: break-all` or `line-break: anywhere` (#508).
         */
        class Word(
            val cells: List<Cell>,
            val width: Double,
            val hyphenPoints: List<Int> = emptyList(),
            val overflowWrap: Boolean = false,
            val breakAll: Boolean = false,
            /** The `line-break` of the word's text, which says where [breakAll] may break it (#508). */
            val lineBreak: LineBreak = LineBreak.AUTO,
        ) : Token()
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

    /**
     * A tate-chu-yoko composition (CSS Writing Modes 3, 9.1, #508): [text] side by side in one cell
     * an em long down the column, which [placeRuns] makes a run of its own and the painter squeezes
     * into an em across the column. It takes the run's face when that has every character, else a
     * face that does. Letter-spacing goes after it, as after one character, and not inside it.
     */
    private fun combinedCell(run: InlineRun, text: String, face: EmbeddedFace?, spec: FontSpec, shift: Double, level: Int, src: Int): Cell {
        val fs = run.fontSizePt
        val cps = codePointsOf(text)
        val f = face?.let { own -> own.takeIf { cps.all { own.gidFor(it) != 0 } } ?: fonts.coveringAll(cps) }
        val glyphs = cps.map { cp ->
            if (f == null) glyph(cp, spec)
            else f.gidFor(cp).let { gid -> TextGlyph(0, 1, gid, CharText.of(cp), f.advance1000(gid).toDouble(), f.outline(gid), cp == ' '.code) }
        }
        val spacing = if (run.letterSpacingPt != 0.0 && fs > 0.0) (run.letterSpacingPt / fs * 1000.0).roundToInt() else 0
        return Cell(
            0xFFFC, fs + spacing * fs / 1000.0, fs, spec, run.color, shift, run.underline,
            kernAfter1000 = spacing,
            rubyGroup = run.rubyGroup, rubyText = run.rubyText, href = run.href, speech = run.speech, ids = run.ids,
            element = run.element, text = text,
            lineThrough = run.lineThrough, overline = run.overline, emphasis = run.emphasis, backgroundColor = run.backgroundColor,
            level = level, src = src, combined = Combined(glyphs, f),
        )
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
        // Whether a run that the word's characters come from lets it break anywhere (#574).
        var wordWraps = false
        // Whether a run that the word's characters come from lets a line break between its letters (#508).
        var wordBreaksAll = false
        // The `line-break` of the word's characters (#508).
        var wordLineBreak = LineBreak.AUTO
        // The next character's place in the block's text; it counts what draws nothing too.
        var srcAt = 0
        fun endWord() {
            if (word.isNotEmpty()) {
                // Letters stood upright one per em neither join nor kern (#508).
                val stacked = word.any { uprightOnlyByStyle(it.cp, it.orientation) }
                val ligated = !stacked && shapeWord(word) // GSUB: joining forms, ligatures, contextual substitutions
                positionMarks(word)             // GPOS mark-to-base attachment
                if (!stacked) kernWord(word)
                // Skip hyphenation when a ligature collapsed cells (soft-hyphen indices
                // + the reconstructed word text would no longer line up), and for ruby
                // bases (a hyphen inside a ruby-annotated base is never wanted).
                val isRuby = word.first().rubyGroup >= 0
                // break-all applies no hyphenation (CSS Text 3, 5.2).
                val pts = if (ligated || isRuby || wordBreaksAll) emptyList() else {
                    val s = LinkedHashSet(softHyphens)
                    val h = hyphenator
                    if (hyphensAuto && h != null) s.addAll(hyphenPoints(word, h))
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
                if (last is Token.Word && last.cells.isNotEmpty() && isOpener(last.cells.last().cp, last.lineBreak)) {
                    tokens[tokens.lastIndex] =
                        Token.Word(
                            last.cells + word, last.width + w, pts.map { it + last.cells.size },
                            last.overflowWrap || wordWraps, last.breakAll || wordBreaksAll, wordLineBreak,
                        )
                } else {
                    tokens.add(Token.Word(word, w, pts, wordWraps, wordBreaksAll, wordLineBreak))
                }
                word = ArrayList(); wordW = 0.0; softHyphens = ArrayList(); wordWraps = false; wordBreaksAll = false
                wordLineBreak = LineBreak.AUTO
            }
        }
        // The last run that a tate-chu-yoko composition has taken (#508).
        var combinedTo = -1
        for ((r, run) in runs.withIndex()) {
            if (r <= combinedTo) continue
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
                // A remote picture sized on both sides keeps its room without its bytes (#38).
                val cssW = run.imageCssW
                val cssH = run.imageCssH
                val remoteBox = run.imageSvg == null && isRemoteUrl(run.imageSrc) && cssW != null && cssH != null && cssW > 0.0 && cssH > 0.0
                val svg = run.imageSvg ?: if (!remoteBox && isSvg(run.imageSrc)) loadSvg(run.imageSrc) else null
                val img = if (svg == null && !remoteBox) loadImage(run.imageSrc) else null
                val iw: Double; val ih: Double
                when {
                    remoteBox && cssW != null && cssH != null -> { iw = cssW; ih = cssH }
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
                    href = run.href, element = run.element, imageWidth = w, imageHeight = h, image = img, svgImage = svg,
                    imageAlt = run.imageAlt, imageObjectFit = run.imageObjectFit, imageZipPath = run.imageSrc,
                    level = levelsOfRun?.get(0) ?: 0, src = src,
                )
                tokens.add(Token.Word(listOf(cell), inlineSize))
                continue
            }
            // A formula: one unbreakable cell as wide as its layout (#32).
            if (run.math != null) {
                endWord()
                val src = srcAt
                srcAt += codePointCount(run.text)
                val box = MathLayout(run.family, run.fontSizePt).layout(run.math)
                tokens.add(
                    Token.Word(
                        listOf(
                            Cell(
                                0xFFFC, box.width, run.fontSizePt, fontSpec(run.family, run.bold, run.italic), run.color, 0.0, null,
                                href = run.href, element = run.element, math = box, mathText = run.math.readingText,
                                level = levelsOfRun?.get(0) ?: 0, src = src,
                            ),
                        ),
                        box.width,
                    ),
                )
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
            // Tate-chu-yoko: the text of one element, which may come as several runs, is one cell an
            // em long down the column, set as an ideograph is between its neighbours. A space or a
            // line break at either end of it is dropped (#508).
            if (vertical && run.combineGroup >= 0) {
                var end = r
                while (end + 1 < runs.size && runs[end + 1].combineGroup == run.combineGroup &&
                    !runs[end + 1].hardBreak && runs[end + 1].imageSrc == null && runs[end + 1].math == null
                ) end++
                val all = buildString { for (k in r..end) append(runs[k].text) }
                val lead = all.length - all.trimStart(' ', '\n', '\r').length
                val core = all.trim(' ', '\n', '\r').filter { it != '\n' && it != '\r' }
                if (core.isNotEmpty()) {
                    val cell = combinedCell(run, core, face, spec, shift, levelsOfRun?.getOrNull(lead) ?: 0, srcAt + lead)
                    srcAt += codePointCount(all)
                    combinedTo = end
                    if (run.rubyGroup >= 0) {
                        // A ruby base stays one word.
                        word.add(cell); wordW += cell.width
                    } else {
                        endWord()
                        val last = tokens.lastOrNull()
                        if (last is Token.Word && last.cells.isNotEmpty() && isOpener(last.cells.last().cp, last.lineBreak)) {
                            tokens[tokens.lastIndex] = Token.Word(last.cells + cell, last.width + cell.width, lineBreak = run.lineBreak)
                        } else {
                            tokens.add(Token.Word(listOf(cell), cell.width, lineBreak = run.lineBreak))
                        }
                    }
                    continue
                }
            }
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
                    Cell(c, penAdvance1000(f, gid, c, run.textOrientation) * cellFs / 1000.0, cellFs, spec, run.color, shift, run.underline, f, gid,
                        rubyGroup = run.rubyGroup, rubyText = run.rubyText, href = run.href, speech = run.speech, ids = run.ids,
                        element = run.element,
                        lineThrough = run.lineThrough, overline = run.overline, emphasis = run.emphasis, backgroundColor = run.backgroundColor, level = level, src = src,
                        orientation = run.textOrientation)
                } else {
                    // An upright letter of the generic font takes one em down the column (#508).
                    val advance = if (uprightOnlyByStyle(c, run.textOrientation)) cellFs
                    else FontMetrics.advancePt(c, cellFs, run.bold, run.italic, run.family)
                    Cell(c, advance, cellFs, spec, run.color, shift, run.underline,
                        rubyGroup = run.rubyGroup, rubyText = run.rubyText, href = run.href, speech = run.speech, ids = run.ids,
                        element = run.element,
                        lineThrough = run.lineThrough, overline = run.overline, emphasis = run.emphasis, backgroundColor = run.backgroundColor, level = level, src = src,
                        orientation = run.textOrientation)
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
            // A space that keeps its width: the face's own advance for it, else its width by Unicode (#577).
            // It holds the face's space glyph, so a word around a no-break space still shapes and kerns.
            fun spaceCell(cp: Int, level: Int, src: Int): Cell {
                val own = face?.gidFor(cp) ?: 0
                val em = spaceEm(cp)
                val stand = spaceStandIn(cp)
                val w = when {
                    face != null && own != 0 -> penAdvance1000(face, own, cp) * fs / 1000.0
                    em != null -> em * fs
                    face != null -> face.advance1000(face.gidFor(stand)) * fs / 1000.0
                    else -> FontMetrics.advancePt(stand, fs, run.bold, run.italic, run.family)
                }
                // word-spacing adds to the no-break space, a word separator; letter-spacing to every advance.
                val spacing = run.letterSpacingPt + if (isWordSeparator(cp)) run.wordSpacingPt else 0.0
                return Cell(
                    cp, w + spacing, fs, spec, run.color, shift, run.underline, face, face?.gidFor(' '.code) ?: -1,
                    rubyGroup = run.rubyGroup, rubyText = run.rubyText, href = run.href, speech = run.speech, ids = run.ids,
                    element = run.element,
                    lineThrough = run.lineThrough, overline = run.overline, emphasis = run.emphasis, backgroundColor = run.backgroundColor, level = level, src = src,
                )
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
                    // A zero-width space is a place to break and nothing more (#577).
                    cp == 0x200B -> endWord()
                    // A no-break space joins the characters on its two sides into one word (#577).
                    isGlueSpace(cp) -> {
                        val c = spaceCell(cp, level, src); word.add(c); wordW += c.width
                        wordWraps = wordWraps || run.overflowWrap
                        wordBreaksAll = wordBreaksAll || run.breakAll || run.lineBreak == LineBreak.ANYWHERE
                        wordLineBreak = run.lineBreak
                    }
                    // The other spaces keep their width and end the word they follow: a line may break
                    // after one, and one at the end of a line hangs past it (#577).
                    isFixedSpace(cp) -> {
                        endWord()
                        val c = spaceCell(cp, level, src)
                        val last = tokens.lastOrNull()
                        if (last is Token.Word) {
                            tokens[tokens.lastIndex] = Token.Word(last.cells + c, last.width + c.width, last.hyphenPoints, last.overflowWrap, last.breakAll)
                        } else {
                            tokens.add(Token.Word(listOf(c), c.width))
                        }
                    }
                    isWhitespace(cp) -> {
                        endWord()
                        val sw = if (face != null) face.advance1000(face.gidFor(' '.code)) * fs / 1000.0
                        else FontMetrics.advancePt(' '.code, fs, run.bold, run.italic, run.family)
                        // word-spacing adds to spaces; letter-spacing to every advance.
                        tokens.add(Token.Space(Cell(
                            ' '.code, sw + run.wordSpacingPt + run.letterSpacingPt, fs, spec, run.color, shift, run.underline,
                            href = run.href, speech = run.speech, ids = run.ids, element = run.element,
                            lineThrough = run.lineThrough, overline = run.overline, backgroundColor = run.backgroundColor, level = level,
                        )))
                    }
                    // keep-all: CJK letters join the word like Latin ones. A break may still come
                    // before an opener and after a closer (#508).
                    FontMetrics.isWide(cp) && run.rubyGroup < 0 && run.keepAll && run.lineBreak != LineBreak.ANYWHERE -> {
                        if (isOpener(cp, run.lineBreak)) endWord()
                        val c = cellFor(cp, level, src); word.add(c); wordW += c.width
                        wordWraps = wordWraps || run.overflowWrap
                        wordLineBreak = run.lineBreak
                        if (isCloser(cp, run.lineBreak)) endWord()
                    }
                    // Ruby bases do not split per CJK char: the whole base is one token.
                    FontMetrics.isWide(cp) && run.rubyGroup < 0 -> {
                        // CJK ideographs break per character; kinsoku merges: a closer
                        // stays with the char before it, and anything after an opener
                        // stays with the opener (an opener must not end a line).
                        endWord()
                        val cell = cellFor(cp, level, src)
                        val last = tokens.lastOrNull()
                        // Which marks bind is the `line-break` rule of the text (#508).
                        val bindsBack = last is Token.Word && last.cells.isNotEmpty() &&
                            (isCloser(cp, run.lineBreak) || isOpener(last.cells.last().cp, last.lineBreak))
                        if (bindsBack) {
                            val lw = last as Token.Word
                            tokens[tokens.lastIndex] = Token.Word(lw.cells + cell, lw.width + cell.width, lineBreak = run.lineBreak)
                        } else {
                            tokens.add(Token.Word(listOf(cell), cell.width, lineBreak = run.lineBreak))
                        }
                    }
                    else -> {
                        val c = cellFor(cp, level, src); word.add(c); wordW += c.width
                        wordWraps = wordWraps || run.overflowWrap
                        // line-break: anywhere breaks a word wherever its line ends, as break-all does (#508).
                        wordBreaksAll = wordBreaksAll || run.breakAll || run.lineBreak == LineBreak.ANYWHERE
                        wordLineBreak = run.lineBreak
                    }
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
                    c.width = (penAdvance1000(face, g.gid, c.cp, c.orientation) + c.kernAfter1000) * c.fontSize / 1000.0
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
                    base.cp, (penAdvance1000(face, g.gid, base.cp, base.orientation) + spacing) * base.fontSize / 1000.0, base.fontSize,
                    base.spec, base.color, base.shift, base.underline, face, g.gid, kernAfter1000 = spacing,
                    rubyGroup = base.rubyGroup, rubyText = base.rubyText, href = base.href, speech = base.speech, ids = base.ids,
                    element = base.element,
                    lineThrough = base.lineThrough, overline = base.overline, emphasis = base.emphasis, backgroundColor = base.backgroundColor, level = base.level,
                    src = base.src, orientation = base.orientation,
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
                    // Spaces at the end of the word may hang past the end of the line (#577).
                    val hang = cells.asReversed().takeWhile { isGap(it.cp) }.sumOf { it.width }
                    if (lineW + leading + w - hang <= lineAvail() || (line.isEmpty() && points.isEmpty() && !tok.overflowWrap && !tok.breakAll)) {
                        if (line.isNotEmpty() && space > 0.0) { line.addAll(spaces); lineW += space }
                        line.addAll(cells); lineW += w
                        break
                    }
                    // break-all: as many letters as fit the rest of the line, or, on a line of its own,
                    // one letter at least; a closing mark stays with the letter before it (#508).
                    if (tok.breakAll) {
                        val cut = longestFit(cells, lineAvail() - lineW - leading, line.isEmpty()) { a, b -> heldTogether(a, b, tok.lineBreak) }
                        if (cut > 0) {
                            if (line.isNotEmpty() && space > 0.0) { line.addAll(spaces); lineW += space }
                            val prefix = cells.subList(0, cut)
                            line.addAll(prefix); lineW += prefix.sumOf { it.width + it.padBefore + it.padAfter }
                            commit(KiteLineEnd.NONE)
                            cells = cells.subList(cut, cells.size)
                            points = points.mapNotNull { if (it > cut) it - cut else null }
                            space = 0.0
                            continue
                        }
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
                        // overflow-wrap: a word that cannot fit a line of its own breaks after as many
                        // characters as fit, one at least, with no hyphen (#574).
                        val cut = if (tok.overflowWrap) longestFit(cells, lineAvail()) else 0
                        if (cut == 0) {
                            line.addAll(cells); lineW += w // unsplittable + nothing before: overflow rather than loop
                            break
                        }
                        val prefix = cells.subList(0, cut)
                        line.addAll(prefix); lineW += prefix.sumOf { it.width + it.padBefore + it.padAfter }
                        commit(KiteLineEnd.NONE)
                        cells = cells.subList(cut, cells.size)
                        points = points.mapNotNull { if (it > cut) it - cut else null }
                        space = 0.0
                    } else {
                        // The spaces before the word, dropped here or kept on the line, are the break.
                        val atSpace = spaces.isNotEmpty() || isGap(line.last().cp)
                        commit(if (atSpace) KiteLineEnd.SPACE else KiteLineEnd.NONE); space = 0.0 // retry on a fresh line
                    }
                }
            }
        }
        if (line.isNotEmpty() || lines.isEmpty()) { lines.add(line); ends?.add(KiteLineEnd.HARD) }
        return lines
    }

    /**
     * The hyphenation points of [word], as cell indices: those of the letters between its leading
     * and trailing punctuation, so that `Krankenhaus,` and `«Bonjour»` hyphenate (#618). A letter
     * here is also a combining mark, such as an Indic vowel sign (#617), and an apostrophe or a
     * joiner may stand between two. Points index chars, so a word outside the BMP is not hyphenated.
     */
    private fun hyphenPoints(word: List<Cell>, hyphenator: Hyphenator): List<Int> {
        if (word.any { it.cp >= 0x10000 }) return emptyList()
        var from = 0
        var to = word.size
        while (from < to && !isWordLetter(word[from].cp)) from++
        while (to > from && !isWordLetter(word[to - 1].cp)) to--
        if (to - from < 5) return emptyList()
        for (i in from until to) {
            if (!isWordLetter(word[i].cp) && word[i].cp !in WORD_JOINERS) return emptyList()
        }
        val text = buildString { for (i in from until to) append(word[i].cp.toChar()) }
        return hyphenator.hyphenate(text).map { it + from }
    }

    private fun isWordLetter(cp: Int): Boolean {
        val c = cp.toChar()
        return c.isLetter() || when (c.category) {
            CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK -> true
            else -> false
        }
    }

    private fun hyphenWidth(c: Cell): Double = c.face?.let { it.advance1000(it.gidFor('-'.code)) * c.fontSize / 1000.0 }
        ?: FontMetrics.advancePt('-'.code, c.fontSize, c.spec.bold, c.spec.italic, genericOf(c.spec))

    private fun hyphenCell(c: Cell): Cell {
        val face = c.face
        return Cell(
            '-'.code, hyphenWidth(c), c.fontSize, c.spec, c.color, c.shift, c.underline, face, face?.gidFor('-'.code) ?: -1,
            href = c.href, speech = c.speech, ids = c.ids, element = c.element,
            lineThrough = c.lineThrough, overline = c.overline, emphasis = c.emphasis, backgroundColor = c.backgroundColor, level = c.level, orientation = c.orientation,
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
        mathSink: MutableList<PlacedMath>? = null,
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
        // The spaces since the last run, a formula or an image, for the line's text (#576).
        var spaces = 0
        var spacesWidth = 0.0
        fun closeGroup(end: Double) {
            val gc = groupCell
            if (openGroup >= 0 && gc?.rubyText != null) out.add(rubyRun(gc, groupStart, end))
            openGroup = -1; groupCell = null
        }
        while (i < cells.size) {
            val c = cells[i]
            if (isGap(c.cp)) {
                closeGroup(x)
                val width = c.width + if (isWordSeparator(c.cp)) extraPerSpace else 0.0
                if (c.underline != null || c.lineThrough != null || c.overline != null || c.backgroundColor != null) {
                    out.add(PlacedRun(
                        emptyList(), x, c.fontSize, c.spec, c.color, c.shift, c.underline,
                        lineThrough = c.lineThrough, overline = c.overline, backgroundColor = c.backgroundColor, paintWidth = width, element = c.element,
                    ))
                }
                spaces++; spacesWidth += width
                x += width; i++; continue
            }
            // A formula cell: emit a PlacedMath and advance the pen (#32).
            if (c.math != null) {
                closeGroup(x)
                mathSink?.add(PlacedMath(x + c.padBefore, c.math, c.color, c.mathText))
                x += c.padBefore + c.width + c.padAfter
                spaces = 0; spacesWidth = 0.0
                i++
                continue
            }
            // Inline image cell: emit a PlacedImage and advance the pen.
            if (c.isImage) {
                closeGroup(x)
                imageSink?.add(
                    PlacedImage(x + c.padBefore, c.imageWidth, c.imageHeight, c.image, c.svgImage, c.imageAlt, c.imageObjectFit, c.imageZipPath, c.element),
                )
                x += c.padBefore + c.width + c.padAfter
                spaces = 0; spacesWidth = 0.0
                i++
                continue
            }
            if (c.rubyGroup != openGroup) closeGroup(x)
            if (c.rubyGroup >= 0 && openGroup < 0) { openGroup = c.rubyGroup; groupStart = x; groupCell = c }
            x += c.padBefore
            val startX = x
            val comb = c.combined
            if (comb != null) {
                // A composition is a run of its own: its glyphs as drawn, and spread over its em for
                // the page text, so a search or a selection finds them inside it (#508).
                val natural = comb.glyphs.sumOf { it.advanceWidth }
                val n = comb.glyphs.size
                val spread = comb.glyphs.mapIndexed { k, g ->
                    val share = if (natural > 0.0) g.advanceWidth * 1000.0 / natural else 1000.0 / n
                    g.copy(advanceWidth = share + if (k == n - 1) c.kernAfter1000 else 0)
                }
                out.add(PlacedRun(
                    spread, startX, c.fontSize, c.spec, c.color, c.shift, c.underline,
                    hasOutlines = comb.face != null, unitsPerEm = comb.face?.unitsPerEm ?: 1000,
                    href = c.href, speech = c.speech, ids = c.ids, lineThrough = c.lineThrough, overline = c.overline, backgroundColor = c.backgroundColor,
                    paintWidth = c.width, element = c.element, spacesBefore = spaces, spacesWidth = spacesWidth,
                    combined = comb.glyphs,
                ))
                // One emphasis mark for the composition, as for one character.
                if (c.emphasis != null && codePointsOf(c.text ?: "").any { takesMark(it) }) markRun(c, startX + c.fontSize / 2)?.let(out::add)
                x += c.width + c.padAfter; i++
                spaces = 0; spacesWidth = 0.0
                continue
            }
            val spec = c.spec; val fs = c.fontSize; val col = c.color; val sh = c.shift; val ul = c.underline; val face = c.face
            val glyphs = ArrayList<TextGlyph>()
            val marks = ArrayList<PlacedRun>()
            // An image cell always ends a text run, even when glued to a word (#99).
            while (i < cells.size && !isGap(cells[i].cp) && !cells[i].isImage && cells[i].math == null && cells[i].combined == null && cells[i].rubyGroup == c.rubyGroup &&
                cells[i].href == c.href && cells[i].speech === c.speech && cells[i].ids === c.ids && cells[i].element === c.element &&
                samePaint(cells[i], c) && cells[i].orientation == c.orientation
            ) {
                glyphs.add(glyphFor(cells[i]))
                // One mark over each letter a ligature stands for.
                val n = cells[i].ligComponents
                if (cells[i].emphasis != null && takesMark(cells[i].cp)) {
                    for (k in 0 until n) markRun(cells[i], x + cells[i].width * (k + 0.5) / n)?.let(marks::add)
                }
                x += cells[i].width + cells[i].padAfter; i++
            }
            out.add(PlacedRun(
                glyphs, startX, fs, spec, col, sh, ul,
                hasOutlines = face != null, unitsPerEm = face?.unitsPerEm ?: 1000,
                href = c.href, speech = c.speech, ids = c.ids, lineThrough = c.lineThrough, overline = c.overline, backgroundColor = c.backgroundColor,
                paintWidth = x - startX, element = c.element, spacesBefore = spaces, spacesWidth = spacesWidth,
                orientation = c.orientation,
            ))
            out.addAll(marks)
            spaces = 0; spacesWidth = 0.0
        }
        closeGroup(x)
        return out
    }

    /** Whether [c]'s emphasis marks go over its line, or right of its column, the line-over side. */
    private fun marksOver(c: Cell): Boolean = c.emphasis?.position?.let { if (vertical) it.right else it.over } ?: true

    /**
     * The emphasis mark over one letter (CSS Text Decoration 3, 3, #508), drawn as the letter's ruby
     * would be: at half its size, in its face when the face has the mark, centred on [centre], over
     * or under the line, or right or left of the column, and outside the letter's own ruby. Down a
     * column the mark stands upright and its em clears the letter's em box.
     */
    private fun markRun(c: Cell, centre: Double): PlacedRun? {
        val e = c.emphasis ?: return null
        val mark = e.style.mark(vertical && c.orientation != TextOrientation.SIDEWAYS)
        if (mark.isEmpty()) return null
        val r = rubyGlyphs(mark, c)
        val m = c.fontSize * EMPHASIS_SIZE
        val over = marksOver(c)
        val ruby = if (over && c.rubyText != null) c.fontSize * RUBY_SIZE else 0.0
        val lift = when {
            vertical && over -> c.fontSize * (UPRIGHT_CENTER + 0.5) + m * (0.5 - UPRIGHT_CENTER) + ruby
            vertical -> -c.fontSize * 0.2 - m * (0.5 + UPRIGHT_CENTER)
            over -> c.fontSize * 0.8 + ruby
            else -> -c.fontSize * 0.2 - m * 0.8
        }
        return PlacedRun(
            r.glyphs, centre - r.width / 2, m, c.spec, e.color ?: c.color,
            baselineShift = c.shift + lift,
            hasOutlines = r.face != null, unitsPerEm = r.face?.unitsPerEm ?: 1000,
            isAnnotation = true, orientation = TextOrientation.UPRIGHT,
        )
    }

    /**
     * Whether [cp] takes an emphasis mark (CSS Text Decoration 3, 3.1): not a separator, a control,
     * a format or an unassigned character, nor a combining mark, which shares the mark of its base,
     * nor punctuation other than the symbols the spec names.
     */
    private fun takesMark(cp: Int): Boolean = cp in MARKED_PUNCTUATION || when (GeneralCategory.of(cp)) {
        CharCategory.SPACE_SEPARATOR, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR,
        CharCategory.CONTROL, CharCategory.FORMAT, CharCategory.UNASSIGNED, CharCategory.SURROGATE,
        CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK,
        CharCategory.DASH_PUNCTUATION, CharCategory.START_PUNCTUATION, CharCategory.END_PUNCTUATION,
        CharCategory.CONNECTOR_PUNCTUATION, CharCategory.OTHER_PUNCTUATION,
        CharCategory.INITIAL_QUOTE_PUNCTUATION, CharCategory.FINAL_QUOTE_PUNCTUATION,
        -> false
        else -> true
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
            c.lineThrough == other.lineThrough && c.overline == other.overline && c.backgroundColor == other.backgroundColor

    private fun glyphFor(c: Cell): TextGlyph {
        // A letter stood upright by text-orientation advances an em down the column and sits in the
        // middle of it: xOffset moves it by half of what its own width leaves of that em (#508).
        val stacked = uprightOnlyByStyle(c.cp, c.orientation)
        val face = c.face ?: return glyph(c.cp, c.spec).let { g ->
            // Generic cells fold letter-spacing (kernAfter1000) into the drawn
            // advance the same way embedded-face cells do below.
            if (stacked) g.copy(advanceWidth = 1000.0 + c.kernAfter1000, xOffset = (1000.0 - g.advanceWidth) / 2)
            else if (c.kernAfter1000 != 0) g.copy(advanceWidth = g.advanceWidth + c.kernAfter1000) else g
        }
        val pen = penAdvance1000(face, c.gid, c.cp, c.orientation)
        val centring = if (stacked) (pen - face.advance1000(c.gid)) / 2.0 * face.unitsPerEm / 1000.0 else 0.0
        return TextGlyph(
            byteOffset = 0, byteCount = 1, gid = c.gid, text = c.text ?: CharText.of(c.cp),
            // Pair kerning to the next glyph is folded into this glyph's advance so
            // the drawn pen movement matches the wrap width.
            advanceWidth = if (c.invisible) 0.0 else (pen + c.kernAfter1000).toDouble(),
            outline = face.outline(c.gid), isWordSpace = c.cp == ' '.code,
            xOffset = c.glyphXOffset + centring, yOffset = c.glyphYOffset,
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

    /** A space that holds the characters on its two sides on one line: no-break, narrow no-break and figure (#577). */
    private fun isGlueSpace(cp: Int): Boolean = cp == 0xA0 || cp == 0x202F || cp == 0x2007

    /** A space separator other than U+0020 that keeps its width and lets a line break after it (#577). */
    private fun isFixedSpace(cp: Int): Boolean =
        cp == 0x1680 || (cp in 0x2000..0x200A && cp != 0x2007) || cp == 0x205F || cp == 0x3000

    /** A cell that draws nothing and only moves the pen: U+0020 and the other space separators (#577). */
    private fun isGap(cp: Int): Boolean = cp == ' '.code || isGlueSpace(cp) || isFixedSpace(cp)

    /** A space that justification stretches and word-spacing widens (CSS Text 3, 7.1 and 8.1). */
    private fun isWordSeparator(cp: Int): Boolean = cp == ' '.code || cp == 0xA0

    /** The width of a space of [isGap] in em, for a face that lacks it; null for one as wide as [spaceStandIn]. */
    private fun spaceEm(cp: Int): Double? = when (cp) {
        0x2000, 0x2002 -> 0.5
        0x2001, 0x2003, 0x3000 -> 1.0
        0x2004 -> 1.0 / 3
        0x2005, 0x1680 -> 0.25
        0x2006 -> 1.0 / 6
        0x2009, 0x202F -> 0.2
        0x200A -> 0.1
        0x205F -> 4.0 / 18
        else -> null
    }

    /** The character whose width a space of [isGap] takes when [spaceEm] has none: a digit, a full stop or a space. */
    private fun spaceStandIn(cp: Int): Int = when (cp) {
        0x2007 -> '0'.code
        0x2008 -> '.'.code
        else -> ' '.code
    }

    /** The width of a space of [isGap] in [run] by the generic metrics, for the min-content measure. */
    private fun gapWidthPt(cp: Int, run: InlineRun): Double =
        spaceEm(cp)?.let { it * run.fontSizePt } ?: FontMetrics.advancePt(spaceStandIn(cp), run.fontSizePt, run.bold, run.italic, run.family)

    /**
     * A CJK mark that must not start a line under [rule] (kinsoku, no-break-before; CSS Text 3,
     * 5.3): the closing marks always, the centered ones, iteration marks and suffixes unless
     * `loose`, and the small kana and the CJK hyphens only for `strict`.
     */
    private fun isCloser(cp: Int, rule: LineBreak = LineBreak.AUTO): Boolean = when (rule) {
        LineBreak.ANYWHERE -> false
        LineBreak.LOOSE -> cp in CJK_CLOSERS
        LineBreak.NORMAL -> cp in CJK_CLOSERS || cp in CJK_CENTERED || cp in CJK_ITERATION || cp in CJK_POSTFIXES
        LineBreak.STRICT, LineBreak.AUTO ->
            cp in CJK_CLOSERS || cp in CJK_CENTERED || cp in CJK_ITERATION || cp in CJK_POSTFIXES || cp in CJK_SMALL_KANA ||
                cp in CJK_HYPHENS
    }

    /** A CJK mark that must not end a line under [rule] (kinsoku, no-break-after): openings, and prefixes unless `loose`. */
    private fun isOpener(cp: Int, rule: LineBreak = LineBreak.AUTO): Boolean = when (rule) {
        LineBreak.ANYWHERE -> false
        LineBreak.LOOSE -> cp in CJK_OPENERS
        else -> cp in CJK_OPENERS || cp in CJK_PREFIXES
    }

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
        /** An emphasis mark's size as a fraction of its letter's (CSS Text Decoration 3, 3.1). */
        const val EMPHASIS_SIZE = 0.5
        /**
         * The punctuation that takes an emphasis mark all the same (CSS Text Decoration 3, 3.1):
         * number sign, percent and per mille signs, ampersand, at sign, section and pilcrow signs,
         * the reversed pilcrow, the swung dash and the part alternation mark.
         */
        val MARKED_PUNCTUATION = setOf(
            0x23, 0x25, 0x2030, 0x2031, 0x066A, 0x0609, 0x060A, 0x26, 0x204A, 0x40, 0xA7, 0xB6, 0x204B, 0x2053, 0x303D,
        )
        /** Synthesized small-caps size (uppercase form scaled down). */
        const val SMALL_CAPS_SCALE = 0.8
        /** The bidi classes that can move a character in a left-to-right paragraph. */
        val REORDERING = setOf(Bidi.R, Bidi.AL, Bidi.AN, Bidi.RLE, Bidi.RLO, Bidi.RLI, Bidi.FSI)
        /** Marks a line does not start with: closing, stops, quotes and hyphens (UAX 14, LB 13, 16, 19, 21). */
        val NO_BREAK_BEFORE = setOf(
            '.'.code, ','.code, ';'.code, ':'.code, '!'.code, '?'.code, ')'.code, ']'.code, '}'.code, '%'.code,
            '\''.code, '"'.code, '-'.code, 0x2019, 0x201D, 0x2018, 0x201C, 0x00BB, 0x00AB, 0x2010, 0x2013, 0x2026,
        )

        /** Marks a line does not end with: openings and quotes (UAX 14, LB 14, 19). */
        val NO_BREAK_AFTER = setOf(
            '('.code, '['.code, '{'.code, '\''.code, '"'.code, 0x2018, 0x201C, 0x2019, 0x201D, 0x00AB, 0x00BB, '$'.code,
        )

        // JIS X 4051 no-break-before set, split by the `line-break` rule that lets each part start
        // a line (CSS Text 3, 5.3, #508). Together they are MuPDF's kinsoku table and a little more.
        // Closing punctuation: no line starts with it under any rule but `anywhere`.
        val CJK_CLOSERS = setOf(
            0x3001, 0x3002, // 、 。
            0xFF0C, 0xFF0E, // fullwidth , .
            0x300D, 0x300F, 0x3011, 0x3015, 0x3009, 0x300B, 0x3017, // 」 』 】 〕 〉 》 〗
            0xFF09, 0xFF3D, 0xFF5D, // ） ］ ｝
        )
        // Centered punctuation, which `loose` lets start a line.
        val CJK_CENTERED = setOf(
            0x30FB, 0xFF65, // ・ ･
            0xFF1A, 0xFF1B, 0xFF01, 0xFF1F, // fullwidth : ; ! ?
            0x203C, 0x2047, 0x2048, 0x2049, // ‼ ⁇ ⁈ ⁉
        )
        // Iteration marks, which `loose` lets start a line.
        val CJK_ITERATION = setOf(
            0x3005, 0x303B, // 々 〻
            0x309D, 0x309E, 0x30FD, 0x30FE, // ゝ ゞ ヽ ヾ
        )
        // Wide suffixes (line breaking class PO), which `loose` lets start a line.
        val CJK_POSTFIXES = setOf(0xFF05, 0xFFE0, 0x2103) // ％ ￠ ℃
        // Small kana and the prolonged sound mark (class CJ), which only `strict` keeps off the start.
        val CJK_SMALL_KANA = setOf(
            0x30FC, // ー prolonged sound mark
            0x3041, 0x3043, 0x3045, 0x3047, 0x3049, // small ぁぃぅぇぉ
            0x3063, 0x3083, 0x3085, 0x3087, 0x308E, 0x3095, 0x3096, // small っゃゅょゎゕゖ
            0x30A1, 0x30A3, 0x30A5, 0x30A7, 0x30A9, // small ァィゥェォ
            0x30C3, 0x30E3, 0x30E5, 0x30E7, 0x30EE, // small ッャュョヮ
            0x30F5, 0x30F6, // small ヵヶ
        ) + (0x31F0..0x31FF) // small katakana extensions ㇰ to ㇿ
        // The CJK hyphens, which only `strict` keeps off the start.
        val CJK_HYPHENS = setOf(0x301C, 0x30A0) // 〜 ゠
        // What may stand between two letters of a word that hyphenates: the apostrophes, which the
        // French, Italian and Ukrainian patterns know, and the joiners of the Indic scripts (#618).
        val WORD_JOINERS = setOf(0x0027, 0x2019, 0x200C, 0x200D)
        // JIS X 4051 no-break-after set: an opener binds to what follows it.
        val CJK_OPENERS = setOf(
            0x300C, 0x300E, 0x3010, 0x3014, 0x3008, 0x300A, 0x3016, // 「 『 【 〔 〈 《 〖
            0xFF08, 0xFF3B, 0xFF5B, // （ ［ ｛
        )
        // Wide prefixes (class PR), which bind to what follows them unless `loose`.
        val CJK_PREFIXES = setOf(0xFF04, 0xFFE1, 0xFFE5) // ＄ ￡ ￥
    }
}

/** True for a track size that the content decides (CSS Grid Layout 1, 7.2). */
private val GridSize.intrinsic: Boolean
    get() = this is GridSize.Auto || this is GridSize.MinContent || this is GridSize.MaxContent
