package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KitePage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Issue #5 foundation: a chapter's placeholder and its future first page
 * share one key, so Compose's keyed anchoring carries the reader across the
 * placeholder-becomes-pages moment. The strip is memoized per publication so
 * every pager callback reads one consistent version.
 */
class DocItemKeyTest {

    private class TwoChapterDoc : KiteDocument {
        private val ready = BooleanArray(2)
        override val chapterCount = 2
        override fun isChapterReady(chapter: Int) = ready.getOrElse(chapter) { false }
        override fun prepareChapter(chapter: Int) { if (chapter in ready.indices) ready[chapter] = true }
        override fun pageCountIn(chapter: Int) = if (isChapterReady(chapter)) 3 else 0
        override val pageCount get() = 6
        override val pages: List<KitePage> get() = error("not used")
        override fun page(location: KiteLocation): KitePage = error("not used")
    }

    @Test
    fun a_gap_and_its_first_page_share_one_key() {
        assertEquals(DocItem.Page(KiteLocation(5, 0)).key, DocItem.ChapterGap(5).key)
        assertEquals("p5.0", DocItem.ChapterGap(5).key)
    }

    @Test
    fun keys_stay_unique_across_a_landing() {
        val doc = TwoChapterDoc()
        doc.prepareChapter(0)
        val before = buildItems(doc).map { it.key }
        assertEquals(before.size, before.distinct().size, "duplicate key in $before")
        doc.prepareChapter(1)
        val after = buildItems(doc).map { it.key }
        assertEquals(after.size, after.distinct().size, "duplicate key in $after")
        // The gap's key survived as the first page's key.
        assertTrue("p1.0" in before && "p1.0" in after)
    }

    @Test
    fun the_strip_is_memoized_per_publication() {
        val doc = TwoChapterDoc().also { it.prepareChapter(0) }
        val state = KiteDocViewState(doc, initialPage = 0)
        assertSame(state.items, state.items, "same epoch must reuse the built strip")
        doc.prepareChapter(1)
        val stale = state.items
        state.onChapterReady()
        assertNotSame(stale, state.items, "a publication must rebuild the strip")
        assertEquals(6, state.items.size)
    }
}
