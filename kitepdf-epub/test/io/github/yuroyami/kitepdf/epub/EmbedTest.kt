package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A scripted chapter says it is scripted, and an inline frame or an HTML object keeps a box that
 * the page lists. They gave no signal and left no box, so an app could not hand them to a web
 * engine (#40).
 */
class EmbedTest {

    /** A book of [bodies], one chapter each, with [manifest] items and the [properties] of each chapter's item. */
    private fun book(
        bodies: List<String>,
        manifest: String = "",
        properties: List<String?> = emptyList(),
        heads: List<String> = emptyList(),
    ): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val items = bodies.indices.joinToString("") { i ->
            val props = properties.getOrNull(i)?.let { """ properties="$it"""" }.orEmpty()
            """<item id="c$i" href="c$i.xhtml" media-type="application/xhtml+xml"$props/>"""
        }
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            <manifest>$items$manifest</manifest>
            <spine>${bodies.indices.joinToString("") { """<itemref idref="c$it"/>""" }}</spine></package>"""
        val chapters = bodies.mapIndexed { i, body ->
            "OEBPS/c$i.xhtml" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head>${heads.getOrNull(i).orEmpty()}</head><body>$body</body></html>""".encodeToByteArray()
        }
        return EpubDocument.open(
            EpubFixtures.storedZip(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                ) + chapters,
            ),
            EpubSettings(pageWidth = 400.0, pageHeight = 600.0, margin = 36.0),
        )
    }

    private fun page(doc: EpubDocument) = doc.page(KiteLocation(0, 0))

    private val KiteRectangle.w get() = right - left
    private val KiteRectangle.h get() = top - bottom

    @Test
    fun a_chapter_is_scripted_by_its_manifest_item_or_by_a_script_element() {
        val doc = book(
            listOf("<p>Marked.</p>", "<p>Has a script.</p>", "<p>Plain.</p>", "<p><!-- <script>x()</script> --></p>"),
            properties = listOf("scripted", null, null, null),
            heads = listOf("", """<script type="text/javascript">var x = 1 &lt; 2;</script>""", "", ""),
        )
        assertTrue(doc.isScripted(0), "the manifest property")
        assertTrue(doc.isScripted(1), "a script element")
        assertFalse(doc.isScripted(2))
        assertFalse(doc.isScripted(3), "a script inside a comment is not a script element")
        assertEquals(listOf(0, 1), doc.scriptedChapters)
        assertFalse(doc.isChapterReady(1), "the query laid the chapter out")
    }

    @Test
    fun an_inline_frame_keeps_its_box_between_the_lines_and_paints_nothing() {
        val doc = book(listOf("""<p>Before.</p><iframe id="quiz" src="quiz.xhtml" width="400" height="200"></iframe><p>After.</p>"""))
        val embed = page(doc).embeds.single()
        assertEquals(EpubEmbedKind.FRAME, embed.kind)
        assertEquals("OEBPS/quiz.xhtml", embed.href)
        assertEquals("quiz", embed.id)
        // 400 by 200 CSS pixels are 300 by 150 points, at the page margin plus the body's 1em.
        assertEquals(300.0, embed.rect.w, 0.5)
        assertEquals(150.0, embed.rect.h, 0.5)
        assertEquals(48.0, embed.rect.left, 0.5)
        val lines = page(doc).textContent().blocks.flatMap { it.lines }
        val before = lines.single { "Before" in it.text }.bounds
        val after = lines.single { "After" in it.text }.bounds
        assertTrue(before.top <= embed.rect.bottom + 0.5, "the frame overlaps the line before it: $before, ${embed.rect}")
        assertTrue(after.bottom >= embed.rect.top - 0.5, "the frame overlaps the line after it: $after, ${embed.rect}")
        // The frame paints nothing: the page paints what the same page without it paints.
        fun paints(d: EpubDocument) = RecordingCanvas().also { page(d).renderTo(it) }.calls
            .filter { it is RecordingCanvas.Call.Fill || it is RecordingCanvas.Call.Stroke || it is RecordingCanvas.Call.Image }.size
        assertEquals(paints(book(listOf("<p>Before.</p><p>After.</p>"))), paints(doc), "the frame painted something")
    }

    @Test
    fun a_frame_that_sets_no_size_is_300_by_150_pixels_and_a_frame_in_inline_content_gets_its_own_block() {
        // A frame in a paragraph, and one in a span inside it, which the inline walk meets.
        for (body in listOf(
            """<p>Text <iframe src="https://example.com/quiz"></iframe> more text.</p>""",
            """<p>Text <span>in a span <iframe src="https://example.com/quiz"></iframe> still</span> more text.</p>""",
        )) {
            val doc = book(listOf(body))
            val embed = page(doc).embeds.single()
            assertEquals(225.0, embed.rect.w, 0.5)
            assertEquals(112.5, embed.rect.h, 0.5)
            assertEquals("https://example.com/quiz", embed.href, "a URL stays a URL")
            assertTrue("more text." in page(doc).textContent().plainText)
        }
    }

    @Test
    fun an_html_object_keeps_its_box_and_paints_its_fallback_children() {
        val doc = book(
            listOf(
                """<object id="w" data="widget.xhtml" type="application/xhtml+xml" width="200" height="100"><p>Fallback words.</p></object>""" +
                    """<object data="app"><p>Second fallback.</p></object>""" +
                    """<object data="picture.png" type="image/png"><p>Image fallback.</p></object>""",
            ),
            manifest = """<item id="a" href="app" media-type="text/html"/>""",
        )
        val embeds = page(doc).embeds
        assertEquals(2, embeds.size, "an image object is not an embedded document")
        val widget = embeds[0]
        assertEquals(EpubEmbedKind.OBJECT, widget.kind)
        assertEquals("OEBPS/widget.xhtml", widget.href)
        assertEquals("application/xhtml+xml", widget.type)
        assertEquals("w", widget.id)
        assertEquals(150.0, widget.rect.w, 0.5)
        assertEquals(75.0, widget.rect.h, 0.5)
        assertEquals("text/html", embeds[1].type, "the type comes from the manifest")
        val text = page(doc).textContent()
        val fallback = text.blocks.flatMap { it.lines }.single { "Fallback words." in it.text }.bounds
        assertTrue(fallback.left >= widget.rect.left - 0.5 && fallback.bottom >= widget.rect.bottom - 0.5 && fallback.top <= widget.rect.top + 0.5,
            "the fallback is not in the object's box: $fallback, ${widget.rect}")
        assertTrue("Second fallback." in text.plainText)
        assertTrue("Image fallback." in text.plainText, "the image object lost its fallback")
    }

    @Test
    fun an_object_grows_to_hold_a_fallback_taller_than_its_box() {
        val words = (1..12).joinToString("") { "<p>Line $it of the fallback.</p>" }
        val embed = page(book(listOf("""<object data="w.xhtml" type="text/html" height="20">$words</object>"""))).embeds.single()
        assertTrue(embed.rect.h > 100.0, "the fallback was cut to the box: ${embed.rect}")
    }

    @Test
    fun a_wide_object_stays_in_its_column() {
        // The column is 400 points less two 36 point margins and the body's two 12 point margins.
        val embed = page(book(listOf("""<object data="w.xhtml" type="text/html" width="1000"><p>x</p></object>"""))).embeds.single()
        assertEquals(304.0, embed.rect.w, 0.5)
    }

    @Test
    fun noscript_content_renders() {
        val doc = book(listOf("""<script>document.write("x")</script><noscript><p>Shown without script.</p></noscript>"""))
        assertTrue("Shown without script." in page(doc).textContent().plainText)
    }

    @Test
    fun a_frame_pushed_to_the_next_page_is_listed_there_alone() {
        // The paragraph leaves too little room for the frame, which moves whole to the next page.
        val doc = book(listOf("""<p style="height: 400pt">Tall.</p><iframe src="quiz.xhtml" width="400" height="200"></iframe>"""))
        assertEquals(2, doc.pageCountIn(0))
        assertTrue(doc.page(KiteLocation(0, 0)).embeds.isEmpty(), "the frame is listed on the page it left")
        val embed = doc.page(KiteLocation(0, 1)).embeds.single()
        assertEquals(150.0, embed.rect.h, 0.5)
    }

    @Test
    fun an_html_object_moves_whole_to_the_next_page() {
        // A web engine shows the object's document in place of its fallback, so the box does not split (#41).
        val doc = book(
            listOf(
                """<p style="height: 400pt">Tall.</p><object data="quiz.xhtml" type="application/xhtml+xml" width="400" height="200"><p>Fallback one.</p><p>Fallback two.</p></object>""",
            ),
        )
        assertEquals(2, doc.pageCountIn(0))
        assertTrue(doc.page(KiteLocation(0, 0)).embeds.isEmpty(), "the object is cut at the end of the first page")
        val second = doc.page(KiteLocation(0, 1))
        assertEquals(150.0, second.embeds.single().rect.h, 0.5)
        assertTrue(second.embeds.single().isWhole)
        val text = second.textContent().blocks.flatMap { it.lines }.joinToString(" ") { it.text }
        assertTrue("Fallback one." in text && "Fallback two." in text, "the fallback stays with its box: $text")
    }

    @Test
    fun a_chapter_of_one_object_without_fallback_keeps_its_page() {
        val doc = book(listOf("""<object data="quiz.xhtml" type="application/xhtml+xml"></object>"""))
        assertEquals(1, doc.pageCountIn(0))
        assertEquals("OEBPS/quiz.xhtml", page(doc).embeds.single().href)
    }

    @Test
    fun a_chapter_names_the_path_of_its_document() {
        val doc = book(listOf("<p>One.</p>", "<p>Two.</p>"))
        assertEquals("OEBPS/c0.xhtml", doc.chapterPath(0))
        assertEquals("OEBPS/c1.xhtml", doc.chapterPath(1))
        assertEquals("application/xhtml+xml", doc.resourceType(doc.chapterPath(1)))
    }

    @Test
    fun an_object_taller_than_a_page_goes_on_and_says_it_is_cut() {
        // A fallback of forty paragraphs goes on to the next page, and the box with it.
        val fallback = (1..40).joinToString("") { "<p>Fallback $it.</p>" }
        val doc = book(listOf("""<object data="quiz.xhtml" type="application/xhtml+xml" width="400" height="1000">$fallback</object>"""))
        assertTrue(doc.pageCountIn(0) >= 2)
        assertFalse(doc.page(KiteLocation(0, 0)).embeds.single().isWhole)
        assertFalse(doc.page(KiteLocation(0, 1)).embeds.single().isWhole)
    }
}
