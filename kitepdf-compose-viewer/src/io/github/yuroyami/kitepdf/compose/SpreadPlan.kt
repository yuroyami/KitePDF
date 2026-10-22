package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yuroyami.kitepdf.PageLayout
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPageSpread
import io.github.yuroyami.kitepdf.epub.EpubSpread

/** Where a page asks to sit in a spread. */
internal enum class SpreadSide { LEFT, RIGHT, ALONE }

/**
 * The pages of each spread of [KiteDocLayout.Spread], in reading order: one or two slots each
 * (#37). [sides] holds, for each spread, the side its one page asked for, so that it sits there
 * with the other half empty, or null for a page that sits in the middle (#504).
 */
internal class SpreadPlan(val spreads: List<IntArray>, private val sides: List<SpreadSide?> = emptyList()) {
    private val spreadOfSlot = IntArray(spreads.sumOf { it.size }).also { index ->
        spreads.forEachIndexed { spread, slots -> for (slot in slots) index[slot] = spread }
    }

    val size: Int get() = spreads.size

    /** The spread that shows [slot]. A slot past either end gives the spread at that end. */
    fun spreadOf(slot: Int): Int = if (spreadOfSlot.isEmpty()) 0 else spreadOfSlot[slot.coerceIn(0, spreadOfSlot.lastIndex)]

    /** The first page of [spread] in reading order. */
    fun firstPageOf(spread: Int): Int = spreads.getOrNull(spread)?.first() ?: 0

    /** The side the page of a spread of one page sits on, [SpreadSide.LEFT] or [SpreadSide.RIGHT], or null for the middle. */
    fun sideOf(spread: Int): SpreadSide? = sides.getOrNull(spread)
}

/** The plan a spread layout shows now. Snapshot state, so the pager counts the new plan's spreads. */
internal class SpreadPlans(initial: SpreadPlan) {
    var current: SpreadPlan by mutableStateOf(initial)
}

/**
 * Pairs [count] pages in reading order. A page that asks for a side opens or closes a spread on
 * that side, one that asks to be alone gets a spread of its own, and the others fill the next
 * free side. The first side of a spread is the left one, or the right one when [rightToLeft]. A
 * page that asked for a side and found no partner keeps that side (#504).
 */
internal fun pairSpreads(count: Int, rightToLeft: Boolean, sideOf: (Int) -> SpreadSide?): SpreadPlan {
    val firstSide = if (rightToLeft) SpreadSide.RIGHT else SpreadSide.LEFT
    val spreads = ArrayList<IntArray>()
    val sides = ArrayList<SpreadSide?>()
    fun add(slots: IntArray, side: SpreadSide? = null) {
        spreads += slots
        sides += side
    }
    // A page on the first side, waiting for a partner, and the side it asked for, if it asked.
    var open = -1
    var openSide: SpreadSide? = null
    fun close() {
        if (open >= 0) add(intArrayOf(open), openSide)
        open = -1
        openSide = null
    }
    for (slot in 0 until count) {
        when (val side = sideOf(slot)) {
            SpreadSide.ALONE -> {
                close()
                add(intArrayOf(slot))
            }
            firstSide -> {
                close()
                open = slot
                openSide = side
            }
            // A page on the second side closes the open spread, or shows alone with nothing before it.
            null -> if (open >= 0) {
                add(intArrayOf(open, slot))
                open = -1
                openSide = null
            } else {
                open = slot
            }
            else -> if (open >= 0) {
                add(intArrayOf(open, slot))
                open = -1
                openSide = null
            } else {
                add(intArrayOf(slot), side)
            }
        }
    }
    close()
    return SpreadPlan(spreads, sides)
}

/**
 * The spreads of [state]'s strip under [layout], paired as the document declares: an EPUB
 * chapter's `rendition:spread` and page-spread properties, and a PDF's `/PageLayout`. In a
 * portrait viewport, a chapter that asks for spreads in landscape only shows its pages alone.
 */
internal fun spreadPlan(state: KiteDocViewState, layout: KiteDocLayout.Spread, landscape: Boolean): SpreadPlan {
    val document = state.document
    val items = state.items
    val pdfFirstSide = (document as? PdfDocument)?.let { pdfFirstPageSide(it) }
    return pairSpreads(items.size, rightToLeft = layout.reverseLayout) { slot ->
        if (slot == 0 && layout.firstPageAlone) return@pairSpreads SpreadSide.ALONE
        val location = (items[slot] as? DocItem.Page)?.location ?: return@pairSpreads null
        when (document) {
            is EpubDocument -> epubSide(document, location.chapter, location.page, landscape)
            is PdfDocument -> pdfFirstSide.takeIf { slot == 0 }
            else -> null
        }
    }
}

/** The side page [page] of [chapter] asks for in a spread, from the chapter's rendition properties. */
internal fun epubSide(document: EpubDocument, chapter: Int, page: Int, landscape: Boolean): SpreadSide? {
    val rendition = runCatching { document.renditionOf(chapter) }.getOrNull() ?: return null
    when (rendition.spread) {
        EpubSpread.NONE -> return SpreadSide.ALONE
        EpubSpread.LANDSCAPE -> if (!landscape) return SpreadSide.ALONE
        EpubSpread.AUTO, EpubSpread.BOTH -> Unit
    }
    if (page != 0) return null
    return when (rendition.pageSpread) {
        EpubPageSpread.LEFT -> SpreadSide.LEFT
        EpubPageSpread.RIGHT -> SpreadSide.RIGHT
        EpubPageSpread.CENTER -> SpreadSide.ALONE
        null -> null
    }
}

/** The side of the first page, from ISO 32000-1 table 28: odd pages on the left or on the right. */
private fun pdfFirstPageSide(document: PdfDocument): SpreadSide? = when (runCatching { document.pageLayout }.getOrNull()) {
    PageLayout.TwoPageLeft, PageLayout.TwoColumnLeft -> SpreadSide.LEFT
    PageLayout.TwoPageRight, PageLayout.TwoColumnRight -> SpreadSide.RIGHT
    else -> null
}
