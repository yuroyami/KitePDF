package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.ComputedStyle

/** Everything to paint on one page: its document-space top plus the boxes on it. */
internal class PageRender(
    /** Document-space y of the page's top edge; subtract to map into page space. */
    val startY: Double,
    val lines: List<PositionedLine>,
    val images: List<ImageBox>,
    /** Boxes with a background or visible border intersecting this page. */
    val decoBoxes: List<LayoutBox>,
)

/**
 * Slices a positioned box tree into pages. Content units (lines, images) fill
 * pages greedily and never split. Honours page-break control: a forced
 * `break-before`/`break-after` starts a new page; `break-inside: avoid` moves a
 * block whole to the next page when it fits there; and orphans/widows (min 2)
 * keep a paragraph from leaving a single dangling line at a page edge.
 * Background/border boxes attach to every page they intersect (clipped when
 * painted).
 */
internal object Paginator {

    private const val ORPHANS = 2
    private const val WIDOWS = 2

    fun paginate(root: BlockBox, pageContentHeight: Double): List<PageRender> {
        val lines = ArrayList<PositionedLine>()
        val images = ArrayList<ImageBox>()
        val deco = ArrayList<LayoutBox>()
        collect(root, lines, images, deco)

        val units = ArrayList<Unit_>()
        for (l in lines) l.owner?.let { o -> units.add(Unit_(l.yTop, l.yTop + l.height, l, null, o, l.ownerIndex, o.lines.size)) }
        for (im in images) units.add(Unit_(im.y, im.bottom, null, im, im, 0, 1))
        units.sortBy { it.top }

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
            val forcedBefore = forceNext || (u.line != null && u.ownerIndex == 0 && u.owner.style.breakBefore)
            forceNext = false
            when {
                cur.isEmpty() -> cur.add(u)
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
            if (u.line != null && u.ownerIndex == u.ownerCount - 1 && u.owner.style.breakAfter) forceNext = true
        }
        starts.add(curStart); buckets.add(cur)

        return starts.indices.map { p ->
            val start = starts[p]; val end = start + pageContentHeight
            val us = buckets[p]
            PageRender(
                startY = start,
                lines = us.mapNotNull { it.line },
                images = us.mapNotNull { it.image },
                decoBoxes = deco.filter { it.y < end && it.bottom > start },
            )
        }
    }

    /** How many trailing units of [u]'s block to push to the next page (widows/orphans/avoid). */
    private fun pullback(u: Unit_, cur: List<Unit_>, pageContentHeight: Double): Int {
        val line = u.line ?: return 0
        val owner = u.owner as? TextBlockBox ?: return 0
        var onPage = 0
        var k = cur.lastIndex
        while (k >= 0 && cur[k].owner === owner) { onPage++; k-- }
        // Nothing precedes this block on the page — pulling would just leave it blank.
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
    )

    private fun collect(box: LayoutBox, lines: ArrayList<PositionedLine>, images: ArrayList<ImageBox>, deco: ArrayList<LayoutBox>) {
        when (box) {
            is BlockBox -> { if (decorated(box.style)) deco.add(box); for (c in box.children) collect(c, lines, images, deco) }
            is TableBox -> {
                if (decorated(box.style)) deco.add(box)
                for (r in box.rows) { if (decorated(r.style)) deco.add(r); for (cell in r.cells) collect(cell, lines, images, deco) }
            }
            is TableRowBox -> {}
            is TextBlockBox -> lines.addAll(box.lines)
            is ImageBox -> images.add(box)
        }
    }

    private fun decorated(s: ComputedStyle): Boolean =
        s.backgroundColor != null ||
            s.borderTop.effective > 0 || s.borderRight.effective > 0 ||
            s.borderBottom.effective > 0 || s.borderLeft.effective > 0
}
