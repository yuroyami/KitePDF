package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.ComputedStyle
import io.github.yuroyami.kitepdf.epub.css.CssFloat
import io.github.yuroyami.kitepdf.epub.css.CssPosition
import io.github.yuroyami.kitepdf.epub.css.Display

/** Everything to paint on one page: its document-space top plus the boxes on it. */
internal class PageRender(
    /** Document-space y of the page's top edge; subtract to map into page space. */
    val startY: Double,
    val lines: List<PositionedLine>,
    val images: List<ImageBox>,
    /** Boxes with a background or visible border intersecting this page. */
    val decoBoxes: List<LayoutBox>,
    /** This page's dimensions, per-page so fixed-layout spreads size independently. */
    val pageWidth: Double,
    val pageHeight: Double,
    val margin: Double,
    /**
     * Vertical writing: the layout's logical block axis maps to
     * physical columns advancing right-to-left, and its inline axis runs down
     * the page. [startY] is then the logical block offset of the page's FIRST
     * (rightmost) column.
     */
    val vertical: Boolean = false,
    /** Vertical writing whose columns advance LEFT to right (`vertical-lr`). */
    val verticalLr: Boolean = false,
    /** Blocks inside a link, clickable as a whole, that reach onto this page. */
    val linkBoxes: List<LayoutBox> = emptyList(),
    /** Boxes of embedded documents that reach onto this page (#40). */
    val embedBoxes: List<LayoutBox> = emptyList(),
    /** Boxes that paint through a group or a clip and reach onto this page (#28). */
    val effectBoxes: List<LayoutBox> = emptyList(),
    /** The boxes of elements that reach onto this page, in a chapter that scripts run in, for a tap to find (#41). */
    val hitBoxes: List<LayoutBox> = emptyList(),
)

/**
 * Slices a positioned box tree into pages. Content units (lines, images) fill
 * pages greedily and never split. Honours page-break control on every block
 * around a unit, not only the text block that owns a line (CSS Fragmentation 3,
 * 3.1 and 3.2, #423): a forced `break-before`/`break-after` starts a new page;
 * `break-inside: avoid` moves a block whole to the next page when it fits there;
 * and orphans/widows (min 2) keep a paragraph from leaving a single dangling
 * line at a page edge.
 * Background/border boxes attach to every page they intersect (clipped when
 * painted).
 */
internal object Paginator {

    private const val ORPHANS = 2
    private const val WIDOWS = 2

    /**
     * Fixed-layout: one spine document = one page at its declared viewport size, with
     * NO reflow splitting (the content is authored to fit). Collects the whole tree
     * onto a single [PageRender].
     */
    fun paginateFixed(root: BlockBox, pageWidth: Double, pageHeight: Double, hits: Boolean = false): PageRender {
        numberPaintOrder(root)
        val lines = ArrayList<PositionedLine>()
        val images = ArrayList<ImageBox>()
        val deco = ArrayList<LayoutBox>()
        val links = ArrayList<LayoutBox>()
        val embeds = ArrayList<LayoutBox>()
        val effects = ArrayList<LayoutBox>()
        val hitBoxes = if (hits) ArrayList<LayoutBox>() else null
        collect(root, lines, images, deco, links, embeds, effects, hitBoxes)
        return PageRender(
            0.0, lines, images, deco, pageWidth, pageHeight, margin = 0.0, linkBoxes = links, embedBoxes = embeds, effectBoxes = effects,
            hitBoxes = hitBoxes.orEmpty(),
        )
    }

    fun paginate(
        root: BlockBox, pageWidth: Double, pageHeight: Double, margin: Double,
        vertical: Boolean = false,
        verticalLr: Boolean = false,
        /** Keep the boxes of elements for a tap to find, in a chapter that scripts run in (#41). */
        hits: Boolean = false,
    ): List<PageRender> {
        // In vertical mode pages are sliced along the logical block axis too,
        // but the per-page budget is the physical page WIDTH (columns).
        val pageContentHeight = (if (vertical) pageWidth else pageHeight) - 2 * margin
        numberPaintOrder(root)
        val lines = ArrayList<PositionedLine>()
        val images = ArrayList<ImageBox>()
        val deco = ArrayList<LayoutBox>()
        val links = ArrayList<LayoutBox>()
        val embeds = ArrayList<LayoutBox>()
        val effects = ArrayList<LayoutBox>()
        val hitBoxes = if (hits) ArrayList<LayoutBox>() else null
        collect(root, lines, images, deco, links, embeds, effects, hitBoxes)

        // In tree order, each unit with the blocks around it, so a break on any of them applies:
        // before the first unit of a block and after its last one.
        val ordered = ArrayList<Unit_>()
        gatherUnits(root, emptyList(), ordered)
        val firstOf = HashMap<LayoutBox, Unit_>()
        val lastOf = HashMap<LayoutBox, Unit_>()
        for (u in ordered) for (b in u.chain) {
            if (b !in firstOf) firstOf[b] = u
            lastOf[b] = u
        }
        for ((b, u) in firstOf) {
            if (b.style.breakBefore || b.forcedBreakBefore) u.breakBefore = true
            if (b.forcedBreakBefore) u.columnStart = true
        }
        for ((b, u) in lastOf) if (b.style.breakAfter) u.breakAfter = true
        val units = ordered.sortedBy { it.top }

        val starts = ArrayList<Double>()
        val buckets = ArrayList<ArrayList<Unit_>>()
        var curStart = 0.0
        var cur = ArrayList<Unit_>()
        var forceNext = false

        fun page(nextStart: Double, carry: List<Unit_>) {
            starts.add(curStart); buckets.add(cur)
            cur = ArrayList(carry); curStart = nextStart
        }

        for (u in units) {
            val forcedBefore = forceNext || u.breakBefore
            forceNext = false
            when {
                cur.isEmpty() -> {
                    // Only the first page starts above its first unit: every later page starts at
                    // one. When the margins, borders and padding above that unit push it past the
                    // page's end, the page starts at the unit instead, as a margin at a page break
                    // is dropped (CSS Fragmentation 3, 5.2, #442).
                    // Page-tall columns line up with the pages only when their page starts at them (#34).
                    if (u.bottom > curStart + pageContentHeight || u.columnStart) curStart = u.top
                    cur.add(u)
                }
                forcedBefore -> { page(u.top, emptyList()); cur.add(u) }
                u.bottom > curStart + pageContentHeight -> {
                    val pull = pullback(u, cur, pageContentHeight)
                    val moved = ArrayList<Unit_>()
                    repeat(pull) { moved.add(0, cur.removeAt(cur.lastIndex)) }
                    page(moved.firstOrNull()?.top ?: u.top, moved)
                    cur.add(u)
                }
                else -> cur.add(u)
            }
            if (u.breakAfter) forceNext = true
        }
        starts.add(curStart); buckets.add(cur)

        // Drop a trailing empty page: content ending exactly on a page boundary or a
        // final break-after would otherwise emit a spurious unit-less last page.
        while (buckets.size > 1 && buckets.last().isEmpty()) {
            buckets.removeAt(buckets.lastIndex); starts.removeAt(starts.lastIndex)
        }

        return starts.indices.map { p ->
            val start = starts[p]; val end = start + pageContentHeight
            val us = buckets[p]
            PageRender(
                startY = start,
                lines = us.mapNotNull { it.line },
                images = us.mapNotNull { it.image },
                decoBoxes = deco.filter { it.y < end && it.bottom > start },
                pageWidth = pageWidth, pageHeight = pageHeight, margin = margin,
                vertical = vertical,
                verticalLr = verticalLr,
                linkBoxes = links.filter { it.y < end && it.bottom > start },
                // An embed is on the pages that its own units landed on. A page's band reaches past
                // the top of the next page when a box moved there whole, so a box that only meets
                // the band is not on this page (#41).
                embedBoxes = embeds.filter { e -> us.any { it.owner === e || e in it.chain } },
                effectBoxes = effects.filter { it.y < end && it.bottom > start },
                hitBoxes = hitBoxes?.filter { it.y < end && it.bottom > start }.orEmpty(),
            )
        }
    }

    /** How many trailing units of [u]'s block to push to the next page (widows/orphans/avoid). */
    private fun pullback(u: Unit_, cur: List<Unit_>, pageContentHeight: Double): Int {
        // break-inside: avoid on a block around the unit, outermost first: the block's units on
        // this page move with it when the whole block fits a page and something precedes it here.
        for (b in u.chain) {
            // A flex or grid container keeps its items together, as break-inside: avoid does (#33, #35).
            // So does a set of columns, which fits a page whenever the layout did not split it (#34).
            val atomic = b.style.display == Display.FLEX || b.style.display == Display.GRID || b.inColumns
            if (!(b.style.breakInsideAvoid || atomic) || b is TextBlockBox) continue
            var onPage = 0
            var k = cur.lastIndex
            while (k >= 0 && b in cur[k].chain) { onPage++; k-- }
            if (onPage in 1 until cur.size && b.borderBoxHeight <= pageContentHeight) return onPage
        }
        // The layout already placed the lines of a set of columns page by page, so none moves back (#34).
        if (u.chain.any { it.inColumns }) return 0
        val line = u.line ?: return 0
        val owner = u.owner as? TextBlockBox ?: return 0
        var onPage = 0
        var k = cur.lastIndex
        while (k >= 0 && cur[k].owner === owner) { onPage++; k-- }
        // Nothing precedes this block on the page. Pulling would just leave it blank.
        if (onPage >= cur.size) return 0

        if (owner.style.breakInsideAvoid) return if (owner.borderBoxHeight <= pageContentHeight) onPage else 0

        var pull = if (onPage in 1 until ORPHANS) onPage else 0
        val widows = u.ownerCount - line.ownerIndex // this line + the rest go to the next page
        if (widows in 1 until WIDOWS) pull = maxOf(pull, minOf(onPage, WIDOWS - widows))
        return pull
    }

    private class Unit_(
        val top: Double, val bottom: Double,
        val line: PositionedLine?, val image: ImageBox?,
        val owner: LayoutBox, val ownerIndex: Int, val ownerCount: Int,
        /** The boxes around the unit, from the root down to [owner]. */
        val chain: List<LayoutBox>,
    ) {
        /** A block this unit starts forces a page break before it. */
        var breakBefore = false

        /** A block this unit ends forces a page break after it. */
        var breakAfter = false

        /** This unit starts page-tall columns, so its page starts at its top (#34). */
        var columnStart = false
    }

    /** The lines and images under [box], in tree order, each with the boxes around it. */
    private fun gatherUnits(box: LayoutBox, around: List<LayoutBox>, out: MutableList<Unit_>) {
        val chain = around + box
        when (box) {
            is BlockBox -> {
                // An HTML object's document shows over its whole box, so the box is one unit, as
                // an image is, and its fallback stays on the page it starts (#41).
                if (box.embed != null) out.add(Unit_(box.y, box.bottom, null, null, box, 0, 1, chain))
                for (c in box.children) gatherUnits(c, chain, out)
            }
            is TableBox -> for (r in box.rows) for (cell in r.cells) gatherUnits(cell, chain + r, out)
            is TableRowBox -> {}
            is TextBlockBox -> for (l in box.lines) l.owner?.let { o ->
                out.add(Unit_(l.yTop, l.yTop + l.height, l, null, o, l.ownerIndex, o.lines.size, chain))
            }
            is ImageBox -> out.add(Unit_(box.y, box.bottom, null, box, box, 0, 1, chain))
        }
    }

    private fun collect(
        box: LayoutBox, lines: ArrayList<PositionedLine>, images: ArrayList<ImageBox>,
        deco: ArrayList<LayoutBox>, links: ArrayList<LayoutBox>, embeds: ArrayList<LayoutBox>, effects: ArrayList<LayoutBox>,
        hits: ArrayList<LayoutBox>?,
    ) {
        if (box.linkHref != null) links.add(box)
        if (hits != null && box.source != null) hits.add(box)
        if (box.embed != null) embeds.add(box)
        if (box.hasEffects) effects.add(box)
        when (box) {
            is BlockBox -> {
                if (decorated(box.style)) deco.add(box)
                // A column rule paints with its block's background and border (#34).
                for (rule in box.columnRules) { rule.decoRank = box.decoRank; deco.add(rule) }
                for (c in box.children) collect(c, lines, images, deco, links, embeds, effects, hits)
            }
            is TableBox -> {
                if (decorated(box.style)) deco.add(box)
                for (r in box.rows) { if (decorated(r.style)) deco.add(r); for (cell in r.cells) collect(cell, lines, images, deco, links, embeds, effects, hits) }
            }
            is TableRowBox -> {}
            is TextBlockBox -> lines.addAll(box.lines)
            // An image paints its own background and border (CSS 2.1, 14.2, #101).
            is ImageBox -> { if (decorated(box.style)) deco.add(box); images.add(box) }
        }
    }

    /**
     * Numbers the paint of every box and line under [root] in the order of CSS 2.1, Appendix E
     * (#172). In each stacking context: the positioned boxes with a negative `z-index`, the
     * backgrounds and borders of the blocks in tree order, the floats, the lines and block images
     * in tree order, the positioned boxes with `z-index` auto or 0, and those with a positive one.
     * A float or a positioned box paints as a whole at its place in that order, and a text block
     * always belongs to the flow of its block, whose style it shares.
     */
    private fun numberPaintOrder(root: LayoutBox) {
        var rank = 0
        fun children(b: LayoutBox): List<LayoutBox> = when (b) {
            is BlockBox -> b.children
            is TableBox -> b.rows
            is TableRowBox -> b.cells
            else -> emptyList()
        }
        fun group(box: LayoutBox) {
            box.decoRank = rank++
            val negative = ArrayList<LayoutBox>()
            val zero = ArrayList<LayoutBox>()
            val positive = ArrayList<LayoutBox>()
            val blocks = ArrayList<LayoutBox>()
            val floats = ArrayList<LayoutBox>()
            val flow = ArrayList<Any>()
            fun content(b: LayoutBox) {
                if (b is TextBlockBox) flow.addAll(b.lines) else if (b is ImageBox) flow.add(b)
            }
            fun walk(b: LayoutBox) {
                for (c in children(b)) {
                    val z = c.style.zIndex ?: 0
                    when {
                        c is TextBlockBox -> content(c)
                        c.style.position != CssPosition.STATIC -> (if (z < 0) negative else if (z > 0) positive else zero) += c
                        c.style.cssFloat != CssFloat.NONE -> floats += c
                        // An opacity below 1, like a clip of the overflow here, paints the box as one group,
                        // in the layer of a positioned box with z-index 0 (CSS Color 4, 14; #28).
                        c.hasEffects -> zero += c
                        else -> { blocks += c; content(c); walk(c) }
                    }
                }
            }
            content(box)
            walk(box)
            for (c in negative.sortedBy { it.style.zIndex }) group(c)
            for (b in blocks) b.decoRank = rank++
            for (f in floats) group(f)
            for (item in flow) if (item is PositionedLine) item.paintRank = rank++ else (item as ImageBox).contentRank = rank++
            for (c in zero) group(c)
            for (c in positive.sortedBy { it.style.zIndex }) group(c)
            box.lastRank = rank - 1
        }
        group(root)
    }

    private fun decorated(s: ComputedStyle): Boolean =
        s.backgroundColor != null || s.shadows.isNotEmpty() || s.backgroundLayer != null ||
            s.borderTop.effective > 0 || s.borderRight.effective > 0 ||
            s.borderBottom.effective > 0 || s.borderLeft.effective > 0
}
