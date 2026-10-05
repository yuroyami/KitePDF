package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A resource that the engine cannot render shows its manifest fallback, and an `epub:switch`
 * shows one branch. The fallback attribute was never read, and a switch painted every branch (#27).
 */
class FallbackTest {

    /** A book with [manifest] items, the spine [spine] of ids, and [files] under OEBPS. */
    private fun book(manifest: String, spine: List<String>, files: List<Pair<String, ByteArray>>): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            <manifest>$manifest</manifest><spine>${spine.joinToString("") { """<itemref idref="$it"/>""" }}</spine></package>"""
        return EpubDocument.open(
            EpubFixtures.storedZip(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                ) + files.map { (name, bytes) -> "OEBPS/$name" to bytes },
            ),
            EpubSettings(pageWidth = 400.0, pageHeight = 600.0),
        ) ?: error("the book did not open")
    }

    private fun xhtml(body: String) =
        """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>$body</body></html>""".encodeToByteArray()

    private fun opf(items: List<OpfItem>) = OpfPackage("", items, emptyList(), null, null, null, null, emptyList(), null, emptyList(), null)

    @Test
    fun a_chain_ends_at_a_cycle_and_at_sixteen_hops() {
        val cycle = opf(listOf(OpfItem("a", "a", null, null, "b"), OpfItem("b", "b", null, null, "c"), OpfItem("c", "c", null, null, "a")))
        assertEquals(listOf("a", "b", "c"), cycle.fallbackChain("a").map { it.id })
        val long = opf(List(40) { OpfItem("i$it", "i$it", null, null, "i${it + 1}") })
        assertEquals(MAX_FALLBACK_HOPS + 1, long.fallbackChain("i0").size)
        assertEquals(listOf("x"), opf(listOf(OpfItem("x", "x", null, null, "missing"))).fallbackChain("x").map { it.id })
    }

    @Test
    fun a_foreign_spine_item_renders_its_fallback_document() {
        val doc = book(
            manifest = """<item id="foreign" href="page.bin" media-type="application/x-foreign" fallback="html"/>
                <item id="html" href="page.xhtml" media-type="application/xhtml+xml"/>""",
            spine = listOf("foreign"),
            files = listOf("page.bin" to ByteArray(16) { 7 }, "page.xhtml" to xhtml("<p>The fallback text.</p>")),
        )
        assertEquals("The fallback text.", doc.page(KiteLocation(0, 0)).textContent().plainText.trim())
    }

    /** A disk image that starts with a zeroed sector, as `pub-foreign_bad-fallback`'s does. */
    private val diskImage = ByteArray(4096) { if (it < 1024) 0 else (it * 31 + 7).toByte() }

    /** The first bytes of a Photoshop file. */
    private val photoshop = "8BPS".encodeToByteArray() + ByteArray(60) { 1 }

    @Test
    fun a_spine_item_whose_chain_holds_no_content_document_leaves_the_spine() {
        // EPUB 3.3, manifest fallbacks: the chain has nothing to show, so the reader skips the
        // item rather than laying out its bytes as text (#518).
        val doc = book(
            manifest = """<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                <item id="dmg" href="foo.dmg" media-type="application/octet-stream" fallback="psd"/>
                <item id="psd" href="bar.psd" media-type="image/psd"/>
                <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>""",
            spine = listOf("c1", "dmg", "c2"),
            files = listOf(
                "c1.xhtml" to xhtml("<p>One.</p>"), "foo.dmg" to diskImage, "bar.psd" to photoshop,
                "c2.xhtml" to xhtml("<p>Two.</p>"),
            ),
        )
        assertEquals(2, doc.chapterCount)
        assertEquals(listOf("One.", "Two."), (0 until doc.chapterCount).map { doc.page(KiteLocation(it, 0)).textContent().plainText.trim() })
    }

    @Test
    fun a_book_of_foreign_bytes_alone_does_not_open() {
        assertFailsWith<EpubFormatException> {
            book(
                manifest = """<item id="dmg" href="foo.dmg" media-type="application/octet-stream" fallback="psd"/>
                    <item id="psd" href="bar.psd" media-type="image/psd"/>""",
                spine = listOf("dmg"),
                files = listOf("foo.dmg" to diskImage, "bar.psd" to photoshop),
            )
        }
    }

    @Test
    fun a_mislabelled_xhtml_file_in_the_spine_still_renders() {
        val utf16 = "\uFEFF<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Sixteen.</p></body></html>"
        val doc = book(
            manifest = """<item id="a" href="a.bin" media-type="application/octet-stream"/>
                <item id="b" href="b.xml" media-type="application/xml"/>
                <item id="c" href="c.htm"/>""",
            spine = listOf("a", "b", "c"),
            files = listOf(
                "a.bin" to xhtml("<p>Eight.</p>"),
                "b.xml" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "\n  ".encodeToByteArray() + xhtml("<p>Marked.</p>"),
                "c.htm" to utf16.flatMap { listOf((it.code shr 8).toByte(), it.code.toByte()) }.toByteArray(),
            ),
        )
        assertEquals(listOf("Eight.", "Marked.", "Sixteen."), (0 until doc.chapterCount).map { doc.page(KiteLocation(it, 0)).textContent().plainText.trim() })
    }

    @Test
    fun markup_is_told_from_other_bytes_by_its_first_character() {
        fun utf16(s: String, bigEndian: Boolean) = s.flatMap {
            val hi = (it.code shr 8).toByte()
            val lo = it.code.toByte()
            if (bigEndian) listOf(hi, lo) else listOf(lo, hi)
        }.toByteArray()
        val markup = " \r\n\t<html/>"
        assertTrue(ParsedEpub.looksLikeMarkup(markup.encodeToByteArray()))
        assertTrue(ParsedEpub.looksLikeMarkup(utf16(markup, bigEndian = true)), "UTF-16BE")
        assertTrue(ParsedEpub.looksLikeMarkup(utf16(markup, bigEndian = false)), "UTF-16LE")
        assertTrue(ParsedEpub.looksLikeMarkup(utf16("\uFEFF" + markup, bigEndian = false)), "UTF-16LE with a byte order mark")
        assertFalse(ParsedEpub.looksLikeMarkup(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())), "JPEG")
        assertFalse(ParsedEpub.looksLikeMarkup(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)), "PNG")
        assertFalse(ParsedEpub.looksLikeMarkup(diskImage), "a zeroed sector")
        assertFalse(ParsedEpub.looksLikeMarkup(photoshop), "Photoshop")
        assertFalse(ParsedEpub.looksLikeMarkup("plain words".encodeToByteArray()))
        assertFalse(ParsedEpub.looksLikeMarkup("   ".encodeToByteArray()))
        assertFalse(ParsedEpub.looksLikeMarkup(ByteArray(0)))
        assertFalse(ParsedEpub.looksLikeMarkup(null))
    }

    @Test
    fun an_image_that_does_not_decode_draws_its_fallback() {
        val doc = book(
            manifest = """<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                <item id="pic" href="pic.jxl" media-type="image/jxl" fallback="bmp"/>
                <item id="bmp" href="pic.bmp" media-type="image/bmp"/>""",
            spine = listOf("c1"),
            files = listOf(
                "c1.xhtml" to xhtml("""<p><img src="pic.jxl" width="20" height="10" alt=""/></p>"""),
                "pic.jxl" to ByteArray(32) { 3 },
                "pic.bmp" to EpubFixtures.bmp2x1(),
            ),
        )
        val canvas = RecordingCanvas().also { doc.page(KiteLocation(0, 0)).renderTo(it) }
        assertEquals(1, canvas.calls.count { it is RecordingCanvas.Call.Image }, "the fallback image was not drawn")
    }

    @Test
    fun a_switch_paints_one_branch() {
        // A case for markup that no engine here renders is skipped; MathML renders since #32.
        val switch = """<epub:switch>
            <epub:case required-namespace="http://www.xml-cml.org/schema"><p>Case in CML.</p></epub:case>
            <epub:case required-namespace="http://www.w3.org/1998/Math/MathML"><p>Case in MathML.</p></epub:case>
            <epub:case required-namespace="http://www.w3.org/2000/svg"><p>Case in SVG.</p></epub:case>
            <epub:default><p>The default.</p></epub:default>
            </epub:switch>"""
        val chosen = book(
            manifest = """<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>""",
            spine = listOf("c1"), files = listOf("c1.xhtml" to xhtml(switch)),
        ).page(KiteLocation(0, 0)).textContent().plainText
        assertTrue("Case in MathML." in chosen, chosen)
        assertFalse("CML" in chosen || "SVG" in chosen || "default" in chosen, "more than one branch painted: $chosen")

        val onlyChemistry = """<epub:switch><epub:case required-namespace="http://www.xml-cml.org/schema"><p>Case in CML.</p></epub:case>
            <epub:default><p>The default.</p></epub:default></epub:switch>"""
        val fallback = book(
            manifest = """<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>""",
            spine = listOf("c1"), files = listOf("c1.xhtml" to xhtml(onlyChemistry)),
        ).page(KiteLocation(0, 0)).textContent().plainText
        assertEquals("The default.", fallback.trim())
    }
}
