package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.KiteRole
import io.github.yuroyami.kitepdf.core.KiteReadingItem
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The accessibility reading order: roles, order, and what stays out of it. */
class ReadingOrderTest {

    private fun order(body: String): List<KiteReadingItem> =
        EpubDocument.open(
            EpubFixtures.epub(body, extraEntries = listOf("OEBPS/cat.bmp" to EpubFixtures.bmp2x1())),
        ).pages.flatMap { it.readingOrder() }

    @Test
    fun headings_carry_their_level() {
        val items = order("<h1>Part One</h1><p>Body text</p><h3>A sub</h3>")
        assertEquals(KiteRole.HEADING, items[0].role)
        assertEquals(1, items[0].headingLevel)
        assertEquals("Part One", items[0].text)
        assertEquals(KiteRole.TEXT, items[1].role)
        assertEquals(KiteRole.HEADING, items[2].role)
        assertEquals(3, items[2].headingLevel)
    }

    @Test
    fun list_items_quotes_and_code_are_named() {
        val items = order("<ul><li>one</li></ul><blockquote>quoted</blockquote><pre>code here</pre>")
        assertEquals(
            listOf(KiteRole.LIST_ITEM, KiteRole.QUOTE, KiteRole.CODE),
            items.map { it.role },
        )
    }

    @Test
    fun each_item_has_the_box_of_its_words() {
        val long = (1..30).joinToString(" ") { "word$it" }
        val page = EpubDocument.open(
            EpubFixtures.epub(
                """<h1>Part One</h1><p>$long</p><p style="transform: translateX(40px)">Moved words</p>""" +
                    """<p>Text <img src="cat.bmp" alt="inline cat"/></p>""" +
                    """<img src="cat.bmp" alt="block cat" style="display: block; width: 60px; height: 30px"/>""",
                extraEntries = listOf("OEBPS/cat.bmp" to EpubFixtures.bmp2x1()),
            ),
        ).pages.first()
        val items = page.readingOrder()
        val blocks = page.textContent().blocks
        assertTrue(blocks[1].lines.size > 1, "the long paragraph wraps")
        val boxes = blocks.map { block -> block.lines.map { it.bounds }.reduce { a, b -> a.union(b) } }
        assertEquals(boxes, items.filter { it.role != KiteRole.IMAGE }.map { it.bounds }, "each text item's box is its block's")
        assertTrue(boxes[2].left > boxes[1].left + 20, "the moved paragraph's box moves with it")
        // Each picture's box is where the page draws it.
        val images = items.filter { it.role == KiteRole.IMAGE }
        assertEquals(listOf("inline cat", "block cat"), images.map { it.text })
        assertBoxesDrawn(page, images)
    }

    @Test
    fun an_inline_picture_on_a_vertical_page_has_the_box_it_is_drawn_in() {
        for (mode in listOf("vertical-rl", "vertical-lr")) {
            val page = EpubDocument.open(
                EpubFixtures.epub(
                    // A vertical page paints no transform, so the box does not move either.
                    """<style>html{writing-mode:$mode}</style><p style="transform: translateX(30px)">縦書き<img src="cat.bmp" alt="inline cat" style="width: 40px; height: 20px"/></p>""",
                    extraEntries = listOf("OEBPS/cat.bmp" to EpubFixtures.bmp2x1()),
                ),
            ).pages.first()
            assertBoxesDrawn(page, page.readingOrder().filter { it.role == KiteRole.IMAGE }, mode)
        }
    }

    /** Checks that [images] have the display boxes of the pictures [page] draws, top first. */
    private fun assertBoxesDrawn(page: EpubPage, images: List<KiteReadingItem>, label: String = "") {
        val drawn = RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Image>().map { call ->
            // The page draws y up, and each image fills the unit square of its matrix.
            val corners = listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0)
            val xs = corners.map { (x, y) -> call.ctm.transformX(x, y) }
            val ys = corners.map { (x, y) -> page.displayHeight - call.ctm.transformY(x, y) }
            KiteRectangle(xs.min(), ys.min(), xs.max(), ys.max())
        }
        assertEquals(images.size, drawn.size, "$label: one draw for each picture")
        for ((item, box) in images.sortedBy { it.bounds?.bottom }.zip(drawn.sortedBy { it.bottom })) {
            val bounds = assertNotNull(item.bounds, "$label: ${item.text} has no box")
            for ((got, want) in listOf(bounds.left to box.left, bounds.bottom to box.bottom, bounds.right to box.right, bounds.top to box.top)) {
                assertEquals(want, got, 0.01, "$label: ${item.text} is at $bounds, drawn at $box")
            }
        }
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
        val image = items.single { it.role == KiteRole.IMAGE }
        assertEquals("a sleeping cat", image.text)
    }

    @Test
    fun a_decorative_image_is_left_out() {
        val items = order("""<p>text<img src="cat.bmp" alt=""/></p>""")
        assertTrue(items.none { it.role == KiteRole.IMAGE }, "alt='' means decoration")
    }

    @Test
    fun a_page_break_marker_is_named_as_one() {
        val items = order("""<p>before</p><div epub:type="pagebreak">42</div><p>after</p>""")
        val br = items.single { it.role == KiteRole.PAGE_BREAK }
        assertEquals("42", br.text)
        assertEquals("pagebreak", br.sourceType)
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
        assertEquals(List(3) { KiteRole.TEXT }, items.take(3).map { it.role })
        assertEquals(KiteRole.HEADING, items[3].role)
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
        assertTrue(items.any { it.sourceType == "footnote" }, "got: $items")
    }
}
