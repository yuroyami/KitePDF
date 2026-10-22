package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A reading aloud gives the element it reads the book's `media:active-class` and the chapter's
 * root the `media:playback-active-class`, and speaks a clip without audio from the element's
 * text (EPUB Media Overlays 3.3, W3C tests mol-css, mol-tts_single and mol-tts_multi, #525).
 */
class NarrationMarkTest {

    private val styles = """<style>
        .active-item { background-color: rgb(13, 146, 95); color: rgb(241, 241, 220); }
        .rendered-with-mo { color: rgb(158, 158, 158); }
        </style>"""

    private val classes = """<meta property="media:active-class">active-item</meta>
        <meta property="media:playback-active-class">rendered-with-mo</meta>"""

    /** A book of two chapters, the first with [head] and spans `a` and `b`, the second with [head] and a span `c`. */
    private fun book(head: String = styles, metadata: String = classes, first: String = """<span id="a">Alpha</span> <span id="b">Beta</span>"""): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">$metadata</metadata>
            <manifest>
              <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
              <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
            </manifest>
            <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""
        fun xhtml(body: String) = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head>$head</head><body>$body</body></html>""".encodeToByteArray()
        return EpubDocument.open(
            EpubFixtures.storedZip(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                    "OEBPS/c1.xhtml" to xhtml("<p>$first</p>"),
                    "OEBPS/c2.xhtml" to xhtml("""<p><span id="c">Gamma</span></p>"""),
                ),
            ),
            EpubSettings(pageWidth = 300.0, pageHeight = 200.0, margin = 20.0),
        )
    }

    private val green = RgbColor(13 / 255.0, 146 / 255.0, 95 / 255.0)
    private val light = RgbColor(241 / 255.0, 241 / 255.0, 220 / 255.0)
    private val grey = RgbColor(158 / 255.0, 158 / 255.0, 158 / 255.0)
    private val black = RgbColor(0.0, 0.0, 0.0)

    private fun calls(doc: EpubDocument, chapter: Int) = RecordingCanvas().also { doc.page(KiteLocation(chapter, 0)).renderTo(it) }.calls

    /** The colour each word of [chapter] is drawn in. */
    private fun colors(doc: EpubDocument, chapter: Int): Map<String, RgbColor> =
        calls(doc, chapter).filterIsInstance<RecordingCanvas.Call.Glyphs>()
            .flatMap { run -> run.text.split(' ').filter { it.isNotBlank() }.map { it to run.color } }.toMap()

    private fun greenFills(doc: EpubDocument, chapter: Int) =
        calls(doc, chapter).filterIsInstance<RecordingCanvas.Call.Fill>().count { it.color == green }

    @Test
    fun the_element_read_gets_the_active_class_and_the_document_the_playback_class() {
        val doc = book()
        assertEquals(mapOf("Alpha" to black, "Beta" to black), colors(doc, 0))
        assertTrue(doc.markNarration("OEBPS/c1.xhtml#a", playing = true))
        assertEquals(mapOf("Alpha" to light, "Beta" to grey), colors(doc, 0))
        assertEquals(1, greenFills(doc, 0))
        // Paused: the element keeps its mark, and the document loses the playback class.
        doc.markNarration("OEBPS/c1.xhtml#a", playing = false)
        assertEquals(mapOf("Alpha" to light, "Beta" to black), colors(doc, 0))
        // The next element takes the mark from the last one.
        doc.markNarration("OEBPS/c1.xhtml#b", playing = true)
        assertEquals(mapOf("Alpha" to grey, "Beta" to light), colors(doc, 0))
        // Another chapter takes it too, and the first chapter shows as it was.
        doc.markNarration("OEBPS/c2.xhtml#c", playing = true)
        assertEquals(mapOf("Alpha" to black, "Beta" to black), colors(doc, 0))
        assertEquals(mapOf("Gamma" to light), colors(doc, 1))
        doc.markNarration(null, playing = false)
        assertEquals(mapOf("Gamma" to black), colors(doc, 1))
        assertEquals(0, greenFills(doc, 1))
    }

    @Test
    fun a_class_the_document_gave_itself_stays() {
        val doc = book(first = """<span id="a" class="active-item">Alpha</span> <span id="b">Beta</span>""")
        assertEquals(light, colors(doc, 0)["Alpha"])
        doc.markNarration("OEBPS/c1.xhtml#a", playing = false)
        doc.markNarration("OEBPS/c1.xhtml#b", playing = false)
        assertEquals(mapOf("Alpha" to light, "Beta" to light), colors(doc, 0))
        doc.markNarration(null, playing = false)
        assertEquals(mapOf("Alpha" to light, "Beta" to black), colors(doc, 0))
    }

    @Test
    fun a_chapter_whose_rules_do_not_use_the_classes_keeps_its_layout() {
        val doc = book(head = "")
        val before = doc.chapterChanges.value
        assertFalse(doc.markNarration("OEBPS/c1.xhtml#a", playing = true))
        assertEquals(before, doc.chapterChanges.value)
        // A book without the classes marks nothing either.
        val plain = book(metadata = "")
        assertFalse(plain.markNarration("OEBPS/c1.xhtml#a", playing = true))
        assertEquals(mapOf("Alpha" to black, "Beta" to black), colors(plain, 0))
    }

    @Test
    fun the_reading_order_of_an_element_holds_its_text_and_pronunciation() {
        val doc = book(
            first = """<span id="a">Call me <span ssml:ph="ˈɪʃmeɪl" ssml:alphabet="ipa" xmlns:ssml="http://www.w3.org/2001/10/synthesis">Ishmael</span>.</span> <span id="b">Some years ago.</span>""",
        )
        val items = doc.readingOrderOf("OEBPS/c1.xhtml#a")
        assertEquals(listOf("Call me", "Ishmael", "."), items.map { it.text })
        assertEquals("ˈɪʃmeɪl", items[1].pronunciation)
        assertEquals(listOf("Some years ago."), doc.readingOrderOf("OEBPS/c1.xhtml#b").map { it.text })
        assertTrue(doc.readingOrderOf("OEBPS/c1.xhtml#nowhere").isEmpty())
        assertTrue(doc.readingOrderOf("OEBPS/missing.xhtml#a").isEmpty())
    }
}
