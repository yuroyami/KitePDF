package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The outline's page numbers follow a chapter laid out again (#888). */
class OutlineRelayoutTest {

    @Test
    fun a_target_that_grows_a_chapter_moves_the_later_entries() {
        val bodies = listOf(
            "<style>#grow:target { height: 2400px; }</style><h1 id=\"head0\">One</h1><p id=\"grow\">One paragraph.</p><p>After target.</p>",
            "<h1 id=\"head1\">Two</h1><p>Chapter two.</p>",
        )
        val doc = EpubDocument.open(EpubFixtures.epubWithToc(2, bodies), EpubSettings(pageWidth = 300.0, pageHeight = 200.0, margin = 20.0))
        doc.pages.toList()
        assertEquals(doc.pageIndexOf(KiteLocation(1, 0)), doc.outline[1].pageIndex)
        val before = doc.pageIndexOf(KiteLocation(1, 0))
        doc.setFragment(0, "grow")
        doc.pages.toList()
        val after = doc.pageIndexOf(KiteLocation(1, 0))
        assertTrue(after != null && before != null && after > before, "the fixture grows chapter 0: $before to $after")
        assertEquals(after, doc.outline[1].pageIndex, "the second entry follows its chapter")
    }
}
