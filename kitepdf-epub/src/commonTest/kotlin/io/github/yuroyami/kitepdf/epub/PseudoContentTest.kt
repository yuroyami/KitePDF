package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-65: `::before` / `::after` generated content. String literals (with CSS
 * escapes) and `attr(x)` render; `counter()`/`url()` values leave the rule
 * inert; other pseudo-elements are still dropped without breaking the sheet.
 */
class PseudoContentTest {

    /**
     * Extracted plain text (spaces restored by T-62's extraction; the raw
     * glyph stream never contains space glyphs). A render pass still runs so
     * pseudo content is also proven paintable.
     */
    private fun drawnText(body: String, css: String): String {
        val doc = EpubDocument.open(EpubFixtures.epub("<body><style>$css</style>$body</body>"))
           
        for (page in doc.pages) RecordingCanvas().also { page.renderTo(it) }
        return doc.pages.joinToString("\n") { it.textContent().plainText }
    }

    @Test
    fun before_and_after_inject_around_inline_content() {
        val text = drawnText(
            "<p>He said <q>hello</q> softly</p>",
            """q::before{content:"\201C"} q::after{content:"\201D"}""",
        )
        assertTrue("“hello”" in text, "quote marks wrap the q content (got: $text)")
    }

    @Test
    fun before_prepends_to_a_block_element() {
        val text = drawnText("<h2>Title</h2>", """h2::before{content:"Chapter "}""")
        assertTrue("Chapter Title" in text, "got: $text")
    }

    @Test
    fun attr_reads_the_originating_elements_attribute() {
        val text = drawnText(
            """<p title="Note">body</p>""",
            """p::after{content:" [" attr(title) "]"}""",
        )
        assertTrue("body [Note]" in text, "got: $text")
    }

    @Test
    fun counter_and_url_values_are_inert() {
        val base = drawnText("<h2>Title</h2>", "")
        assertEquals(base, drawnText("<h2>Title</h2>", """h2::before{content:counter(ch) ". "}"""))
        assertEquals(base, drawnText("<h2>Title</h2>", """h2::before{content:url(x.png)}"""))
        assertEquals(base, drawnText("<h2>Title</h2>", """h2::before{content:none}"""))
    }

    @Test
    fun single_colon_legacy_syntax_works() {
        val text = drawnText("<h2>Title</h2>", """h2:before{content:"* "}""")
        assertTrue("* Title" in text, "got: $text")
    }

    @Test
    fun other_pseudo_elements_stay_dropped_without_breaking_the_rule_group() {
        // ::first-line is dropped; the plain selector in the same group survives.
        val text = drawnText("<p>alpha</p>", """p::first-line{color:red}""")
        assertTrue("alpha" in text)
    }

    @Test
    fun block_display_pseudo_becomes_its_own_line() {
        val doc = EpubDocument.open(
            EpubFixtures.epub(
                """<body><style>p::before{content:"HEAD";display:block}</style><p>body text</p></body>""",
            ),
        )
        val blocks = doc.pages[0].textContent().blocks
        assertEquals(2, blocks.size, "the block pseudo is its own text block")
        assertEquals("HEAD", blocks[0].lines.single().text)
        assertTrue(blocks[1].lines.first().text.startsWith("body"))
    }

    @Test
    fun pseudo_content_is_searchable_and_extractable() {
        val doc = EpubDocument.open(
            EpubFixtures.epub(
                """<body><style>h2::before{content:"Chapter "}</style><h2>One</h2></body>""",
            ),
        )
        assertTrue(doc.search("Chapter One").toList().isNotEmpty())
    }

    @Test
    fun later_rule_of_equal_specificity_wins() {
        val text = drawnText("<h2>T</h2>", """h2::before{content:"a "} h2::before{content:"b "}""")
        assertTrue("b T" in text, "got: $text")
        assertFalse("a " in text)
    }
}
