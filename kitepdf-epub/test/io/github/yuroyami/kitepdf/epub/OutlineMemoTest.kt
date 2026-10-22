package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A book's outline is one list for each state of the book, so a panel that reads it on every
 * recomposition neither builds it nor flattens it again. It is built again once the book is laid
 * out and its entries know their pages (#391).
 */
class OutlineMemoTest {

    @Test
    fun the_outline_is_built_once_for_each_state_of_the_book() {
        val doc = EpubDocument.open(EpubFixtures.epubWithToc(chapters = 4))
        val early = doc.outline
        assertSame(early, doc.outline, "a second read before the layout gives the same list")
        assertNull(early.first().pageIndex, "no page is known before the layout")
        doc.pageCount // lays out the whole book
        val laidOut = doc.outline
        assertNotSame(early, laidOut, "the laid-out book builds the list again")
        assertNotNull(laidOut.first().pageIndex, "its entries know their pages")
        assertSame(laidOut, doc.outline, "and keeps it")
    }
}
