package io.github.yuroyami.kitepdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-25: [KiteDocument.metadata] and [KiteDocument.outline] have empty
 * defaults, so a minimal third-party implementation keeps compiling
 * (and viewers can read them without downcasting).
 */
class KiteDocumentDefaultsTest {

    private class MinimalDocument : KiteDocument {
        override val pageCount: Int get() = 0
        override val pages: List<KitePage> get() = emptyList()
    }

    @Test
    fun minimal_implementation_gets_empty_defaults() {
        val doc: KiteDocument = MinimalDocument()
        assertNull(doc.metadata.title)
        assertTrue(doc.metadata.authors.isEmpty())
        assertNull(doc.metadata.language)
        assertTrue(doc.outline.isEmpty())
    }

    @Test
    fun outline_item_holds_a_tree() {
        val leaf = KiteOutlineItem("Section", pageIndex = 3)
        val root = KiteOutlineItem("Chapter", pageIndex = 1, children = listOf(leaf))
        assertEquals("Chapter", root.title)
        assertEquals(1, root.pageIndex)
        assertEquals("Section", root.children.single().title)
        assertNull(KiteOutlineItem("Unresolved", pageIndex = null).pageIndex)
    }
}
