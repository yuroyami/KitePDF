package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteLocation
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import kotlin.coroutines.coroutineContext

/**
 * One slot in the strip the viewer scrolls through.
 *
 * A ready chapter contributes one [Page] per page. A chapter that is not laid
 * out yet contributes a single [ChapterGap], so the reader sees a page-shaped
 * placeholder instead of the book jumping into existence all at once.
 *
 * [key] is what Compose anchors on. It must not change when the list around it
 * changes, or a chapter landing above the reader would shove the page they are
 * looking at off screen.
 */
internal sealed interface DocItem {

    val key: String

    /** The reading position of this slot: a page's own, or the first page of a placeholder's chapter. */
    val anchor: KiteLocation

    data class Page(val location: KiteLocation) : DocItem {
        override val key: String get() = "p${location.chapter}.${location.page}"
        override val anchor: KiteLocation get() = location
    }

    data class ChapterGap(val chapter: Int) : DocItem {
        // Deliberately the SAME key as the chapter's future first page: the
        // gap and page zero never coexist, and sharing the key lets keyed
        // containers carry a waiting reader onto the landed chapter.
        override val key: String get() = "p$chapter.0"
        override val anchor: KiteLocation get() = KiteLocation(chapter, 0)
    }
}

/** The strip for [document] as it stands right now. */
internal fun buildItems(document: KiteDocument): List<DocItem> {
    val chapters = document.chapterCount
    if (chapters <= 1 && document.isChapterReady(0)) {
        // The common case (PDF, or a book fully laid out): every page, no gaps.
        return List(document.pageCountIn(0)) { DocItem.Page(KiteLocation(0, it)) }
    }
    return buildList {
        for (c in 0 until chapters) {
            if (!document.isChapterReady(c)) {
                add(DocItem.ChapterGap(c))
                continue
            }
            val pages = document.pageCountIn(c)
            for (p in 0 until pages) add(DocItem.Page(KiteLocation(c, p)))
        }
    }
}

/**
 * The order chapters get laid out in: the one being read first, then outward in
 * both directions, so scrolling either way meets ready pages.
 */
internal fun loadOrder(chapterCount: Int, around: Int): List<Int> {
    if (chapterCount <= 0) return emptyList()
    val centre = around.coerceIn(0, chapterCount - 1)
    val out = ArrayList<Int>(chapterCount)
    out.add(centre)
    var step = 1
    while (out.size < chapterCount) {
        val after = centre + step
        val before = centre - step
        if (after < chapterCount) out.add(after)
        if (before >= 0) out.add(before)
        step++
    }
    return out
}

/**
 * Runs [block] where Compose state may be written, and returns its result.
 *
 * Layout happens on the raster pool, and a coroutine that came back from
 * `withContext` can resume on that pool when the caller's context has no
 * dispatcher of its own. Snapshot state must not be written from there, so wait
 * for a frame first: that always resumes on the composition's thread, inside
 * the frame, before it recomposes and measures.
 */
internal suspend fun <T> onComposeThread(block: () -> T): T =
    if (coroutineContext[MonotonicFrameClock] != null) withFrameNanos { block() } else block()
