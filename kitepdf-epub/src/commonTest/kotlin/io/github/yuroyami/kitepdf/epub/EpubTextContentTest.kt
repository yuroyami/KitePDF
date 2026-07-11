package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-62: EPUB structured text + search. Extraction rebuilds line text (with the
 * collapsed inter-word spaces restored from pen gaps), blocks group lines by
 * their owning paragraph, and search matches across line breaks with
 * hyphen-rejoin, yielding display-space quads.
 */
class EpubTextContentTest {

    private fun open(body: String, pageWidth: Double = 400.0): EpubDocument =
        EpubDocument.open(EpubFixtures.epub(body), EpubSettings(pageWidth = pageWidth, pageHeight = 640.0))
           

    @Test
    fun extraction_restores_words_and_blocks() {
        val doc = open("<body><p>Hello brave new world</p><p>Second paragraph here</p></body>")
        val text = doc.pages[0].textContent()
        assertEquals(2, text.blocks.size, "one block per paragraph")
        assertEquals("Hello brave new world", text.blocks[0].lines.single().text)
        assertEquals("Second paragraph here", text.blocks[1].lines.single().text)
        assertEquals("Hello brave new world\n\nSecond paragraph here", text.plainText)
    }

    @Test
    fun char_edges_align_with_text() {
        val doc = open("<body><p>abc</p></body>")
        val line = doc.pages[0].textContent().blocks[0].lines.single()
        assertEquals(line.text.length + 1, line.charEdges.size)
        for (i in 0 until line.charEdges.size - 1) {
            assertTrue(line.charEdges[i] < line.charEdges[i + 1], "edges strictly increase")
        }
    }

    @Test
    fun search_finds_within_a_line_case_insensitively() {
        val doc = open("<body><p>Hello brave new world</p></body>")
        val hits = doc.search("BRAVE NEW").toList()
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].pageIndex)
        assertEquals("brave new", hits[0].text)
        assertEquals(1, hits[0].quads.size, "single-line match yields one quad")
    }

    @Test
    fun search_crosses_a_line_break_as_a_space() {
        // Narrow page: "alpha beta gamma delta" wraps mid-phrase.
        val doc = open("<body><p>alpha beta gamma delta</p></body>", pageWidth = 180.0)
        val lines = doc.pages[0].textContent().blocks[0].lines
        assertTrue(lines.size >= 2, "precondition: the paragraph wrapped (got ${lines.size} line(s))")
        val l1 = lines[0].text
        val phrase = l1.substringAfterLast(' ') + " " + lines[1].text.substringBefore(' ')
        val hits = doc.search(phrase).toList()
        assertEquals(1, hits.size, "phrase across the break: '$phrase'")
        assertEquals(2, hits[0].quads.size, "one quad per line touched")
    }

    @Test
    fun search_rejoins_a_hyphenated_break() {
        // The soft hyphen (U+00AD) offers a break point; at this width the word
        // splits and the line ends with a hyphen.
        val doc = open("<body><p>xxxx com­pression</p></body>", pageWidth = 170.0)
        val lines = doc.pages[0].textContent().blocks[0].lines
        assertTrue(lines.any { it.text.endsWith("-") }, "precondition: hyphenated break (lines: ${lines.map { it.text }})")
        val hits = doc.search("compression").toList()
        assertEquals(1, hits.size, "hyphen halves rejoin for matching")
        assertTrue(hits[0].quads.size >= 2, "match spans both lines")
    }

    @Test
    fun quads_lie_inside_the_page_box() {
        val doc = open("<body><p>alpha beta gamma delta epsilon zeta eta theta</p></body>", pageWidth = 220.0)
        val hits = doc.search("a").toList()
        assertTrue(hits.isNotEmpty())
        for (hit in hits) for (q in hit.quads) {
            assertTrue(q.left >= 0 && q.right <= 220.0 && q.left < q.right, "x extent inside page: $q")
            // Display-space convention: y-min in bottom, y-max in top.
            assertTrue(q.bottom >= 0 && q.top <= 640.0 && q.bottom < q.top, "y extent inside page: $q")
        }
    }

    @Test
    fun ruby_readings_are_excluded_from_extraction() {
        val doc = open("<body><p>その<ruby>漢字<rt>かんじ</rt></ruby>です</p></body>")
        val text = doc.pages[0].textContent().plainText
        assertTrue("漢字" in text)
        assertFalse("かんじ" in text, "the ruby overlay is decoration, not reading text")
        assertTrue(doc.search("かんじ").toList().isEmpty())
    }
}
