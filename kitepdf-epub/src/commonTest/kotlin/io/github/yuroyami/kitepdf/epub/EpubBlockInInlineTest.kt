package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CSS 2.1, 9.2.1.1: an inline element containing block-level children splits
 * around them. Issue #4: footnote targets shaped <span id><div>note</div></span>
 * used to flatten into the enclosing block and anchor to its top.
 */
class EpubBlockInInlineTest {

    private fun filler(n: Int): String =
        (1..n).joinToString("") { "<p>filler paragraph number $it with some words</p>" }

    @Test
    fun a_span_wrapping_blocks_anchors_to_the_note_not_the_document_start() {
        val doc = EpubDocument.open(
            EpubFixtures.epubMultiSpine(
                listOf(
                    """<p>see <a href="chapter2.xhtml#id33">[2]</a></p>""",
                    filler(60) +
                        """<span id="id33"><div class="title5"><p>2</p></div><p>the note body</p></span>""",
                ),
            ),
        )
        val start = assertNotNull(doc.pageIndexOfHref("OEBPS/chapter2.xhtml"))
        val note = assertNotNull(doc.pageIndexOfHref("OEBPS/chapter2.xhtml#id33"))
        assertEquals(doc.pageCount - 1, note, "the note sits on the last page, not at the start (start=$start)")
    }

    @Test
    fun control_a_block_level_id_resolves_past_the_start() {
        val doc = EpubDocument.open(
            EpubFixtures.epubMultiSpine(
                listOf(
                    """<p>see <a href="chapter2.xhtml#id33">[2]</a></p>""",
                    filler(60) + """<div id="id33"><p>2</p><p>the note body</p></div>""",
                ),
            ),
        )
        val note = assertNotNull(doc.pageIndexOfHref("OEBPS/chapter2.xhtml#id33"))
        assertEquals(doc.pageCount - 1, note)
    }

    @Test
    fun control_an_inline_span_with_text_only_anchors_to_its_block() {
        val doc = EpubDocument.open(
            EpubFixtures.epubMultiSpine(
                listOf(
                    """<p>see <a href="chapter2.xhtml#id33">[2]</a></p>""",
                    filler(60) + """<p>note <span id="id33">marker</span> here</p>""",
                ),
            ),
        )
        val note = assertNotNull(doc.pageIndexOfHref("OEBPS/chapter2.xhtml#id33"))
        assertEquals(doc.pageCount - 1, note)
    }

    @Test
    fun blocks_inside_a_span_stay_separate_blocks() {
        val doc = EpubDocument.open(
            EpubFixtures.epubMultiSpine(
                listOf("""<span id="id33"><div><p>AAA</p></div><p>BBB</p></span>"""),
            ),
        )
        val blocks = doc.pages[0].textContent().blocks
        assertEquals(2, blocks.size, "AAA and BBB stay separate blocks, not one flattened run")
    }

    @Test
    fun text_around_a_hoisted_block_stays_in_the_paragraph_flow() {
        // The wrapper is a <div>, not a <p>: the HTML parser's implied close
        // (CLOSES_P) would end a <p> at the inner <div>, restructuring the DOM
        // before layout ever sees a block inside an inline.
        val doc = EpubDocument.open(
            EpubFixtures.epubMultiSpine(
                listOf("""<div>alpha <span>beta <div>DELTA</div> gamma</span> omega</div>"""),
            ),
        )
        val blocks = doc.pages[0].textContent().blocks
        assertEquals(3, blocks.size, "anonymous block, hoisted block, anonymous block")
        assertEquals("alpha beta", blocks[0].lines.joinToString(" ") { it.text })
        assertEquals("DELTA", blocks[1].lines.joinToString(" ") { it.text })
        assertEquals("gamma omega", blocks[2].lines.joinToString(" ") { it.text })
    }

    @Test
    fun a_link_wrapping_a_block_keeps_linking_its_text_after_the_block() {
        // <div> wrapper for the same reason as above: a <p> would be closed by
        // the parser at the inner <div> and the link path under test vanishes.
        val doc = EpubDocument.open(
            EpubFixtures.epubMultiSpine(
                listOf(
                    """<div><a href="chapter2.xhtml#z">pre <div>MID</div> post</a></div>""",
                    """<h2 id="z">Z</h2>""",
                ),
            ),
        )
        val links = doc.pages[0].links
        assertEquals(2, links.size, "pre and post each carry a link rect around the hoisted block")
        assertTrue(links.all { it.href == "OEBPS/chapter2.xhtml#z" })
    }

    @Test
    fun control_an_id_with_no_following_block_attaches_to_its_enclosing_block() {
        val doc = EpubDocument.open(
            EpubFixtures.epubMultiSpine(
                listOf(
                    """<p>see <a href="chapter2.xhtml#tail">[t]</a></p>""",
                    filler(60) + """<p>x <span id="tail">tail text</span></p>""",
                ),
            ),
        )
        val note = assertNotNull(doc.pageIndexOfHref("OEBPS/chapter2.xhtml#tail"))
        assertEquals(doc.pageCount - 1, note)
    }

    @Test
    fun a_deeply_nested_inline_chain_still_hoists_its_block() {
        val doc = EpubDocument.open(
            EpubFixtures.epubMultiSpine(
                listOf(
                    """<p>see <a href="chapter2.xhtml#deep">[d]</a></p>""",
                    filler(60) + """<em><span id="deep"><div>DEEP NOTE</div></span></em>""",
                ),
            ),
        )
        val note = assertNotNull(doc.pageIndexOfHref("OEBPS/chapter2.xhtml#deep"))
        assertEquals(doc.pageCount - 1, note)
    }

    @Test
    fun an_id_before_text_and_a_block_anchors_at_the_block() {
        val doc = EpubDocument.open(
            EpubFixtures.epubMultiSpine(
                listOf(
                    """<p>see <a href="chapter2.xhtml#tb">[t]</a></p>""",
                    filler(60) + """<span id="tb">marker text <div>NOTE BLOCK</div></span>""",
                ),
            ),
        )
        val note = assertNotNull(doc.pageIndexOfHref("OEBPS/chapter2.xhtml#tb"))
        assertEquals(doc.pageCount - 1, note)
    }
}
