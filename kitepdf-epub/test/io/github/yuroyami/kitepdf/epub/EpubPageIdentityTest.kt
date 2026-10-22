package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * One page object per location, for the life of the document. The viewer keys
 * its bitmap cache and its state producers on the page object, so a fresh
 * object per call is a cache miss and a re-raster on every recomposition.
 */
class EpubPageIdentityTest {

    private fun book(): EpubDocument = EpubDocument.open(
        EpubFixtures.epubMultiSpine(
            List(3) { c -> (0 until 10).joinToString("") { "<p>c$c p$it words to fill a line</p>" } },
        ),
        EpubSettings(pageWidth = 200.0, pageHeight = 200.0),
    )

    @Test
    fun the_same_location_answers_the_same_page_object() {
        val doc = book()
        val here = KiteLocation(1, 0)
        assertSame(doc.page(here), doc.page(here))
    }

    @Test
    fun the_page_list_and_the_location_lookup_share_objects() {
        val doc = book()
        val all = doc.pages
        for ((i, page) in all.withIndex()) {
            assertSame(page, doc.page(assertNotNull(doc.locationOf(i))), "page $i")
        }
        assertSame(all[0], doc.pages[0], "the list is rebuilt but its objects are not")
    }
}
