package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the blob URL store of a book keeps, and for how long (#533). */
class BlobUrlStoreTest {

    private val origin = "epub://0123456789abcdef"

    private fun KiteXmlNode.Element.copy(parent: KiteXmlNode.Element? = null): KiteXmlNode.Element {
        val out = KiteXmlNode.Element(tag, LinkedHashMap(attrs))
        out.parent = parent
        for (c in children) {
            out.children += when (c) {
                is KiteXmlNode.Element -> c.copy(out)
                is KiteXmlNode.Text -> KiteXmlNode.Text(c.text)
            }
        }
        return out
    }

    private fun tree(vararg srcs: String) = KiteXmlNode.Element(
        "body", emptyMap(),
        srcs.map { KiteXmlNode.Element("img", mapOf("src" to it)) }.toMutableList<KiteXmlNode>(),
    )

    @Test
    fun a_url_names_its_blob_by_the_url_parser_and_without_its_fragment() {
        val store = BlobUrlStore()
        val url = store.create(origin, 0, byteArrayOf(1, 2), "Image/SVG+XML; charset=utf-8")
        assertTrue(Regex("blob:epub://0123456789abcdef/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(url), url)
        assertNotEquals(url, store.create(origin, 0, byteArrayOf(1, 2), ""), "each blob has a URL of its own")
        assertEquals(2, store.resolve("$url#view")?.bytes?.size, "a fragment does not change the blob")
        assertEquals("image/svg+xml", store.resolve("BLOB:" + url.removePrefix("blob:"))?.essence, "nor does the case of the scheme")
        assertTrue(store.isLive(url))
        assertNull(store.resolve("blob:$origin/00000000-0000-4000-8000-000000000000"))
        assertNull(store.resolve("OEBPS/image.svg"))
    }

    @Test
    fun a_revoked_url_lasts_until_its_call_ends_and_then_only_where_the_tree_names_it() {
        val store = BlobUrlStore()
        val shown = store.create(origin, 0, byteArrayOf(1), "image/png")
        val dropped = store.create(origin, 0, byteArrayOf(2), "image/png")
        store.revoke(shown)
        store.revoke(dropped)
        assertFalse(store.isLive(shown), "the URL parser no longer finds it")
        assertTrue(store.resolve(shown) != null && store.resolve(dropped) != null, "but the change the call makes may still load it")

        store.settle(0, tree(shown))
        assertEquals(1, store.resolve(shown)?.bytes?.single()?.toInt(), "the tree's image stays")
        assertNull(store.resolve(dropped), "a blob nothing names goes")

        store.settle(0, null)
        assertTrue(store.resolve(shown) != null, "a call that changes nothing keeps the tree as it was")
        store.settle(0, tree())
        assertNull(store.resolve(shown), "a tree that no longer names it lets it go")
    }

    @Test
    fun a_chapter_that_closes_revokes_what_its_scripts_made_and_keeps_what_its_tree_shows() {
        val store = BlobUrlStore()
        val first = store.create(origin, 0, byteArrayOf(1), "")
        val shown = store.create(origin, 0, byteArrayOf(2), "")
        val other = store.create(origin, 1, byteArrayOf(3), "")
        store.settle(0, tree("$shown#a", "url(blob:nothing)"))
        store.closeChapter(0)
        assertNull(store.resolve(first))
        assertFalse(store.isLive(shown))
        assertTrue(store.resolve(shown) != null, "the laid out tree still shows it")
        assertTrue(store.isLive(other), "another chapter's blob lives on")
    }

    @Test
    fun a_font_loads_from_a_blob_url_that_a_style_sheet_at_a_blob_url_imports() {
        val doc = EpubDocument.open(EpubFixtures.epub("<p>fif</p>"))
        val font = doc.blobUrls.create(origin, 0, EpubFixtures.ligatureTtf(), "font/ttf")
        val sheet = doc.blobUrls.create(origin, 0, "@font-face{font-family:'L';src:url($font)}p{font-family:'L'}".encodeToByteArray(), "text/css")
        val tree = doc.sourceChapterTree(0).copy()
        tree.children += KiteXmlNode.Element("style", emptyMap()).also {
            it.parent = tree
            it.children += KiteXmlNode.Text("@import url($sheet);")
        }
        doc.replaceChapterTree(0, tree)
        doc.blobUrls.settle(0, tree)
        val glyphs = doc.pages.flatMap { page -> RecordingCanvas().also { page.renderTo(it) }.calls }
            .filterIsInstance<RecordingCanvas.Call.Glyphs>().flatMap { it.glyphs }
        assertEquals(listOf(3 to "fi", 1 to "f"), glyphs.map { it.gid to it.text }, "the fi ligature of the font from the blob")
    }
}
