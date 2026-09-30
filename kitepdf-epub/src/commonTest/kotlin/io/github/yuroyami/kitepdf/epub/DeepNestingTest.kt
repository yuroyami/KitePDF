package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Markup that nests far deeper than the stack of a thread can follow. The book still opens,
 * the deep text is on the page, and so is the text after it (#450).
 */
class DeepNestingTest {

    private val settings = EpubSettings(pageWidth = 400.0, pageHeight = 600.0, margin = 36.0)

    private fun nest(open: String, close: String, depth: Int, inner: String) = open.repeat(depth) + inner + close.repeat(depth)

    /** A book with one chapter, and the manifest items and zip entries that a test adds. */
    private fun book(
        body: String,
        manifest: String = "",
        chapterAttrs: String = "",
        spineAttrs: String = "",
        extra: List<Pair<String, ByteArray>> = emptyList(),
    ): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            <manifest><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"$chapterAttrs/>$manifest</manifest>
            <spine$spineAttrs><itemref idref="c1"/></spine></package>"""
        val chapter = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>"""
        return EpubDocument.open(
            EpubFixtures.storedZip(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                    "OEBPS/chapter1.xhtml" to chapter.encodeToByteArray(),
                ) + extra,
            ),
            settings,
        )
    }

    /** Lays out and draws the chapter [body], then checks that its deep text and the text after it are there. */
    private fun assertLaysOut(body: String) {
        val doc = book(body)
        val pages = (0 until doc.pageCountIn(0)).joinToString("\n") {
            val page = doc.page(KiteLocation(0, it))
            page.renderTo(RecordingCanvas())
            page.textContent().plainText
        }
        // A narrow box breaks the line between the two words.
        val text = pages.replace(Regex("\\s+"), " ")
        val deep = text.indexOf("deep words")
        assertTrue(deep >= 0, "the deep text is laid out")
        assertTrue(text.indexOf("After.") > deep, "the text after the deep element is laid out")
    }

    @Test
    fun deeply_nested_blocks_lay_out_with_the_text_after_them() =
        assertLaysOut(nest("<div>", "</div>", 2_000, "deep words") + "<p>After.</p>")

    @Test
    fun deeply_nested_inline_elements_lay_out_with_the_text_after_them() =
        assertLaysOut("<p>" + nest("<span>", "</span>", 2_000, "deep words") + "</p><p>After.</p>")

    @Test
    fun a_deep_mix_of_blocks_lists_and_inline_elements_lays_out_with_the_text_after_it() =
        assertLaysOut(nest("<section><ul><li><span><em>", "</em></span></li></ul></section>", 300, "deep words") + "<p>After.</p>")

    @Test
    fun deeply_nested_grid_and_flex_containers_lay_out_with_the_text_after_them() =
        assertLaysOut(nest("""<div style="display:flex"><div style="display:grid">""", "</div></div>", 500, "deep words") + "<p>After.</p>")

    @Test
    fun deeply_nested_tables_keep_the_text_of_the_cells_past_the_limit() {
        // The limit falls on another part of the table for each count of elements around the tables.
        for (pad in 0..3) assertLaysOut(
            "<div>".repeat(pad) + nest("<table><tr><td><div>", "</div></td></tr></table>", 12, "deep words") + "</div>".repeat(pad) + "<p>After.</p>",
        )
    }

    @Test
    fun svg_and_mathml_nested_deep_inside_a_chapter_do_not_stop_the_layout() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20">""" +
            nest("<g>", "</g>", 10_000, """<rect width="5" height="5"/>""") + "</svg>"
        val math = """<math xmlns="http://www.w3.org/1998/Math/MathML">""" + nest("<mrow>", "</mrow>", 10_000, "<mi>x</mi>") + "</math>"
        assertLaysOut("<p>deep words</p>$svg<p>$math</p><p>After.</p>")
    }

    /** How many levels the first branch of the table of contents has. A loop, so the test cannot overflow. */
    private fun levels(doc: EpubDocument): Int {
        var entry = doc.tableOfContents.entries.firstOrNull() ?: return 0
        var levels = 1
        while (entry.children.isNotEmpty()) { entry = entry.children.first(); levels++ }
        return levels
    }

    @Test
    fun a_navigation_document_nested_far_too_deep_still_gives_a_table_of_contents() {
        val nav = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc">""" +
            nest("""<ol><li><a href="chapter1.xhtml">Entry</a>""", "</li></ol>", 1_000, "") + "</nav></body></html>"
        val doc = book(
            "<p>x</p>",
            manifest = """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""",
            extra = listOf("OEBPS/nav.xhtml" to nav.encodeToByteArray()),
        )
        assertEquals("Entry", doc.tableOfContents.entries.single().label)
        assertTrue(levels(doc) in 2..HtmlParser.MAX_DEPTH, "the table of contents has ${levels(doc)} levels")
    }

    @Test
    fun an_ncx_nested_far_too_deep_still_gives_a_table_of_contents() {
        val ncx = """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>""" +
            nest("""<navPoint><navLabel><text>Entry</text></navLabel><content src="chapter1.xhtml"/>""", "</navPoint>", 2_000, "") + "</navMap></ncx>"
        val doc = book(
            "<p>x</p>",
            manifest = """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""",
            spineAttrs = """ toc="ncx"""",
            extra = listOf("OEBPS/toc.ncx" to ncx.encodeToByteArray()),
        )
        assertEquals("Entry", doc.tableOfContents.entries.single().label)
        assertTrue(levels(doc) in 2..HtmlParser.MAX_DEPTH, "the table of contents has ${levels(doc)} levels")
    }

    @Test
    fun a_media_overlay_nested_far_too_deep_still_gives_its_clip() {
        val smil = """<?xml version="1.0"?><smil xmlns="http://www.w3.org/ns/SMIL" version="3.0"><body>""" +
            nest("<seq>", "</seq>", 20_000, """<par id="deep"><text src="chapter1.xhtml#a"/><audio src="a.mp3"/></par>""") + "</body></smil>"
        val doc = book(
            """<p id="a">x</p>""",
            manifest = """<item id="ov" href="chapter1.smil" media-type="application/smil+xml"/>""",
            chapterAttrs = """ media-overlay="ov"""",
            extra = listOf("OEBPS/chapter1.smil" to smil.encodeToByteArray()),
        )
        assertEquals(listOf("deep"), assertNotNull(doc.mediaOverlayOf(0)).clips.map { it.id })
    }
}
