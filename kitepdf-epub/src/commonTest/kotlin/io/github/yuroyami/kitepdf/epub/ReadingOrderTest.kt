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
    fun a_pronunciation_splits_the_text_and_covers_only_its_own_words() {
        val items = order(
            """<body ssml:alphabet="ipa"><p>The name <span ssml:ph="ŋwiən">Nguyen</span> is common.</p>""" +
                """<h2 ssml:ph="ˈtʃiːz">Cheese</h2>""" +
                """<p>Say <span ssml:alphabet="x-sampa" ssml:ph="t@'meItoU">tomato</span> twice.</p></body>""",
        )
        assertEquals(listOf("The name", "Nguyen", "is common.", "Cheese", "Say", "tomato", "twice."), items.map { it.text })
        assertEquals(listOf(null, "ŋwiən", null, "ˈtʃiːz", null, "t@'meItoU", null), items.map { it.pronunciation })
        // The alphabet comes from the element, or else from the nearest element above it.
        assertEquals(listOf(null, "ipa", null, "ipa", null, "x-sampa", null), items.map { it.alphabet })
        // The pieces of a block keep its role, and a heading keeps its level.
        assertEquals(List(3) { EpubRole.TEXT }, items.take(3).map { it.role })
        assertEquals(EpubRole.HEADING, items[3].role)
        assertEquals(2, items[3].headingLevel)
        // A span that ends inside a word still ends there.
        val glued = order("""<p><span ssml:ph="ŋwiən">Nguyen</span>'s book.</p>""")
        assertEquals(listOf("Nguyen" to "ŋwiən", "'s book." to null), glued.map { it.text to it.pronunciation })
    }

    @Test
    fun a_pronunciation_that_wraps_stays_one_item_and_a_label_keeps_its_block_whole() {
        val long = (1..12).joinToString(" ") { "word$it" }
        val items = order("""<p>Start <span ssml:ph="x">$long</span> end.</p><p aria-label="Spoken label">A <span ssml:ph="y">name</span> here.</p>""")
        assertEquals(listOf("Start", long, "end.", "Spoken label"), items.map { it.text })
        assertEquals(listOf(null, "x", null, null), items.map { it.pronunciation })
    }

    @Test
    fun the_metadata_lists_the_pronunciation_lexicons_of_the_manifest() {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            <manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
              <item id="en" href="speech/en.pls" media-type="application/pls+xml"/><item id="css" href="s.css" media-type="text/css"/></manifest>
            <spine><itemref idref="c1"/></spine></package>"""
        val doc = EpubDocument.open(
            EpubFixtures.storedZip(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                    "OEBPS/c1.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>x</p></body></html>""".encodeToByteArray(),
                ),
            ),
        )
        assertEquals(listOf("OEBPS/speech/en.pls"), doc.epubMetadata.pronunciationLexicons)
    }

    @Test
    fun a_footnote_keeps_its_epub_type() {
        val items = order("""<aside epub:type="footnote"><p>the note</p></aside>""")
        assertTrue(items.any { it.epubType == "footnote" }, "got: $items")
    }
}
