package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Runtime re-flow: changing reader settings (font size, margin, page size) via
 * [EpubDocument.withSettings] re-lays-out from the cached parse instead of
 * re-opening the book.
 */
class ReflowTest {

    private val longBook = EpubFixtures.epub("<p>" + "word ".repeat(400) + "</p>", uniqueId = "urn:x:reflow")

    @Test
    fun larger_font_paginates_into_more_pages() {
        val small = EpubDocument.open(longBook, fontSize = 10.0)
        assertNotNull(small)
        val large = small.withFontSize(24.0)
        assertTrue(
            large.pageCount > small.pageCount,
            "bigger font should need more pages (small=${small.pageCount}, large=${large.pageCount})",
        )
    }

    @Test
    fun narrower_page_wraps_into_at_least_as_many_pages() {
        val wide = EpubDocument.open(longBook, pageWidth = 600.0, pageHeight = 800.0)
        assertNotNull(wide)
        val narrow = wide.withPageSize(220.0, 800.0)
        assertTrue(narrow.pageCount >= wide.pageCount, "narrower column wraps more (wide=${wide.pageCount}, narrow=${narrow.pageCount})")
    }

    @Test
    fun withSettings_reuses_the_parse_and_keeps_metadata() {
        val doc = EpubDocument.open(longBook)
        assertNotNull(doc)
        val reflowed = doc.withMargin(8.0)
        // Same ParsedEpub backs both, so metadata is the very same instance.
        assertSame(doc.epubMetadata, reflowed.epubMetadata, "re-flow must reuse the parse, not re-parse")
        assertSame(doc.tableOfContents, reflowed.tableOfContents)
        assertEquals(8.0, reflowed.settings.margin)
        assertEquals(doc.settings.fontSize, reflowed.settings.fontSize, "only margin changed")
    }
}
