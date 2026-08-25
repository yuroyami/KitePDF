package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The accessibility reading order: roles, order, and what stays out of it. */
class ReadingOrderTest {

    private fun order(body: String): List<EpubReadingItem> =
        EpubDocument.open(
            EpubFixtures.epub(body, extraEntries = listOf("OEBPS/cat.bmp" to EpubFixtures.bmp2x1())),
        ).pages.flatMap { it.readingOrder() }

    @Test
    fun headings_carry_their_level() {
        val items = order("<h1>Part One</h1><p>Body text</p><h3>A sub</h3>")
        assertEquals(EpubRole.HEADING, items[0].role)
        assertEquals(1, items[0].headingLevel)
        assertEquals("Part One", items[0].text)
        assertEquals(EpubRole.TEXT, items[1].role)
        assertEquals(EpubRole.HEADING, items[2].role)
        assertEquals(3, items[2].headingLevel)
    }

    @Test
    fun list_items_quotes_and_code_are_named() {
        val items = order("<ul><li>one</li></ul><blockquote>quoted</blockquote><pre>code here</pre>")
        assertEquals(
            listOf(EpubRole.LIST_ITEM, EpubRole.QUOTE, EpubRole.CODE),
            items.map { it.role },
        )
    }

    @Test
    fun the_order_follows_the_document() {
        val items = order("<p>first</p><h2>second</h2><p>third</p>")
        assertEquals(listOf("first", "second", "third"), items.map { it.text })
    }

    @Test
    fun aria_hidden_content_is_not_announced() {
        val items = order("""<p>said</p><p aria-hidden="true">unsaid</p>""")
        assertEquals(listOf("said"), items.map { it.text })
    }

    @Test
    fun a_presentation_role_is_not_announced() {
        val items = order("""<p>said</p><p role="presentation">unsaid</p>""")
        assertEquals(listOf("said"), items.map { it.text })
    }

    @Test
    fun an_aria_label_replaces_the_text() {
        val items = order("""<p aria-label="the real words">xyz</p>""")
        assertEquals("the real words", items.single().text)
    }

    @Test
    fun an_image_announces_its_alt() {
        val items = order("""<p><img src="cat.bmp" alt="a sleeping cat"/></p>""")
        val image = items.single { it.role == EpubRole.IMAGE }
        assertEquals("a sleeping cat", image.text)
    }

    @Test
    fun a_decorative_image_is_left_out() {
        val items = order("""<p>text<img src="cat.bmp" alt=""/></p>""")
        assertTrue(items.none { it.role == EpubRole.IMAGE }, "alt='' means decoration")
    }

    @Test
    fun a_page_break_marker_is_named_as_one() {
        val items = order("""<p>before</p><div epub:type="pagebreak">42</div><p>after</p>""")
        val br = items.single { it.role == EpubRole.PAGE_BREAK }
        assertEquals("42", br.text)
        assertEquals("pagebreak", br.epubType)
    }

    @Test
    fun a_footnote_keeps_its_epub_type() {
        val items = order("""<aside epub:type="footnote"><p>the note</p></aside>""")
        assertTrue(items.any { it.epubType == "footnote" }, "got: $items")
    }
}
