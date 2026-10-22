package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What a script layer needs from the layout (#41): the element under a tap, and a chapter laid
 * out again from a tree its scripts changed, with the pages it gains or loses.
 */
class ScriptedTreeTest {

    /**
     * A scripted fixed-layout page of 300 by 200 CSS pixels, so 225 by 150 points: a red band 60
     * pixels tall across the top, and a button at (10, 100), 120 by 40.
     */
    private fun scriptedPage(): EpubDocument {
        val xhtml = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">
            <head><meta name="viewport" content="width=300, height=200"/><script>var ready = true;</script></head>
            <body style="margin:0">
            <h1 id="band" style="margin:0;height:60px;background:rgb(255,0,0);color:rgb(255,0,0)">Band</h1>
            <button id="go" style="display:block;position:absolute;left:10px;top:100px;width:120px;height:40px;margin:0">Go</button>
            </body></html>""".trimIndent()
        return EpubDocument.open(fixedBook(xhtml, scripted = true))
    }

    private fun fixedBook(xhtml: String, scripted: Boolean): ByteArray {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val properties = if (scripted) """ properties="scripted"""" else ""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">x</dc:identifier><meta property="rendition:layout">pre-paginated</meta></metadata>
            <manifest><item id="p" href="page.xhtml" media-type="application/xhtml+xml"$properties/></manifest>
            <spine><itemref idref="p"/></spine></package>"""
        return EpubFixtures.storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/page.xhtml" to xhtml.encodeToByteArray(),
            ),
        )
    }

    private fun KiteXmlNode.Element.copy(parent: KiteXmlNode.Element? = null): KiteXmlNode.Element {
        val out = KiteXmlNode.Element(tag, LinkedHashMap(attrs))
        out.parent = parent
        for (c in children) {
            out.children += when (c) {
                is KiteXmlNode.Element -> c.copy(out)
                is KiteXmlNode.Text -> KiteXmlNode.Text(c.text)
                is KiteXmlNode.Comment -> KiteXmlNode.Comment(c.text)
            }
        }
        return out
    }

    private fun KiteXmlNode.Element.find(predicate: (KiteXmlNode.Element) -> Boolean): KiteXmlNode.Element? {
        if (predicate(this)) return this
        for (c in children) if (c is KiteXmlNode.Element) c.find(predicate)?.let { return it }
        return null
    }

    private fun KiteXmlNode.Element.byId(id: String): KiteXmlNode.Element = checkNotNull(find { it.attrs["id"] == id }) { "no #$id" }

    /** [el] with [attrs] in place of its own, in a copy of the tree. */
    private fun KiteXmlNode.Element.withAttrs(attrs: Map<String, String>): KiteXmlNode.Element {
        val parent = checkNotNull(parent)
        val out = KiteXmlNode.Element(tag, attrs, children)
        out.parent = parent
        parent.children[parent.children.indexOf(this)] = out
        return out
    }

    private fun fills(page: EpubPage) = RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Fill>()

    @Test
    fun a_tap_finds_the_innermost_element_under_it() {
        val page = scriptedPage().page(KiteLocation(0, 0))
        // The button's centre, (70, 120) CSS pixels.
        assertEquals("go", page.elementAt(52.5, 90.0)?.attrs?.get("id"))
        // The band holds its text at the left, so its middle is the heading's own box.
        assertEquals("band", page.elementAt(112.5, 22.5)?.attrs?.get("id"))
        // Below both nothing is painted: the body ends with the band, and the button is out of its flow.
        assertNull(page.elementAt(200.0, 140.0))
    }

    @Test
    fun a_chapter_without_scripts_keeps_no_elements() {
        val doc = EpubDocument.open(EpubFixtures.epub("<p>Hello world</p>"))
        val page = doc.page(KiteLocation(0, 0))
        val text = page.textContent().blocks.first().lines.first().bounds
        assertNull(page.elementAt((text.left + text.right) / 2, (text.bottom + text.top) / 2))
    }

    @Test
    fun a_new_tree_paints_on_the_same_page_object() {
        val doc = scriptedPage()
        val page = doc.page(KiteLocation(0, 0))
        val changes = doc.chapterChanges.value
        assertTrue(fills(page).any { it.color.r > 0.9 && it.color.b < 0.1 }, "the band is red first")

        val tree = doc.chapterTree(0).copy()
        val band = tree.byId("band")
        band.withAttrs(band.attrs + ("style" to "margin:0;height:60px;background:rgb(0,0,255)"))
        doc.replaceChapterTree(0, tree)

        assertSame(page, doc.page(KiteLocation(0, 0)), "the viewer's caches keep their key")
        assertEquals(1, page.chapterVersion)
        assertEquals(changes + 1, doc.chapterChanges.value)
        val after = fills(page)
        assertTrue(after.any { it.color.b > 0.9 && it.color.r < 0.1 }, "the band is blue now")
        assertFalse(after.any { it.color.r > 0.9 && it.color.b < 0.1 }, "and red no more")
        assertEquals("go", page.elementAt(52.5, 90.0)?.attrs?.get("id"), "a tap finds the elements of the new tree")
    }

    @Test
    fun a_chapter_gains_and_loses_pages_with_its_tree() {
        val body = "<script>var x = 1;</script>" + (1..3).joinToString("") { "<p>Paragraph $it of the chapter.</p>" }
        val doc = EpubDocument.open(EpubFixtures.epub(body), EpubSettings(pageWidth = 300.0, pageHeight = 200.0, margin = 20.0))
        val before = doc.pageCountIn(0)
        val first = doc.page(KiteLocation(0, 0))

        val grown = doc.chapterTree(0).copy()
        val bodyEl = checkNotNull(grown.find { it.tag == "body" })
        repeat(40) { i ->
            val p = KiteXmlNode.Element("p", emptyMap())
            p.parent = bodyEl
            p.children += KiteXmlNode.Text("Added paragraph $i, long enough to take a line of its own.")
            bodyEl.children += p
        }
        doc.replaceChapterTree(0, grown)
        val more = doc.pageCountIn(0)
        assertTrue(more > before + 3, "40 paragraphs add pages: $before, then $more")
        assertSame(first, doc.page(KiteLocation(0, 0)), "a page still there keeps its object")
        val last = doc.page(KiteLocation(0, more - 1))
        assertTrue("Added paragraph 39" in last.textContent().plainText, "the last page shows the last paragraph")
        assertEquals(more, doc.knownPageCount)

        val shrunk = doc.chapterTree(0).copy()
        val shrunkBody = checkNotNull(shrunk.find { it.tag == "body" })
        shrunkBody.children.retainAll { it !is KiteXmlNode.Element || it.tag == "script" }
        doc.replaceChapterTree(0, shrunk)
        // A chapter that scripts run in keeps a page with nothing on it, so its scripts can fill it again.
        assertEquals(1, doc.pageCountIn(0))
        // A viewer may still draw a page past the new end until it has the new count: it shows nothing.
        val calls = RecordingCanvas().also { last.renderTo(it) }.calls
        assertTrue(calls.none { it is RecordingCanvas.Call.Glyphs }, "a page past the end is blank")
    }

    @Test
    fun another_document_over_the_book_lays_the_new_tree_out_when_it_needs_it() {
        val body = "<script>var x = 1;</script><p>One short paragraph.</p>"
        val doc = EpubDocument.open(EpubFixtures.epub(body), EpubSettings(pageWidth = 300.0, pageHeight = 200.0, margin = 20.0))
        val other = doc.withFontSize(doc.fontSize * 2)
        assertEquals(1, other.pageCountIn(0))

        val tree = doc.chapterTree(0).copy()
        val bodyEl = checkNotNull(tree.find { it.tag == "body" })
        repeat(30) { i ->
            val p = KiteXmlNode.Element("p", emptyMap())
            p.parent = bodyEl
            p.children += KiteXmlNode.Text("Added paragraph $i.")
            bodyEl.children += p
        }
        doc.replaceChapterTree(0, tree)

        assertFalse(other.isChapterReady(0), "the other layout is stale")
        assertTrue(other.pageCountIn(0) > 1, "and lays the chapter out again from the new tree")
        assertTrue(other.isChapterReady(0))
        assertNotNull(other.page(KiteLocation(0, 1)))
    }
}
