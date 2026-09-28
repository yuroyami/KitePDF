package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val switch = """<epub:switch>
            <epub:case required-namespace="http://www.w3.org/1998/Math/MathML"><p>Case in MathML.</p></epub:case>
            <epub:case required-namespace="http://www.w3.org/2000/svg"><p>Case in SVG.</p></epub:case>
            <epub:default><p>The default.</p></epub:default>
            </epub:switch>"""
        val chosen = book(
            manifest = """<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>""",
            spine = listOf("c1"), files = listOf("c1.xhtml" to xhtml(switch)),
        ).page(KiteLocation(0, 0)).textContent().plainText
        assertTrue("Case in SVG." in chosen, chosen)
        assertFalse("MathML" in chosen || "default" in chosen, "more than one branch painted: $chosen")

        val onlyMath = """<epub:switch><epub:case required-namespace="http://www.w3.org/1998/Math/MathML"><p>Case in MathML.</p></epub:case>
            <epub:default><p>The default.</p></epub:default></epub:switch>"""
        val fallback = book(
            manifest = """<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>""",
            spine = listOf("c1"), files = listOf("c1.xhtml" to xhtml(onlyMath)),
        ).page(KiteLocation(0, 0)).textContent().plainText
        assertEquals("The default.", fallback.trim())
    }
}
