package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `break-before` and `break-after` apply to every block-level box, and `break-inside: avoid` to
 * every block, not only to the text block that owns a line (CSS Fragmentation 3, 3.1 and 3.2):
 * a wrapping `div`, a `section`, a table and an image (#423).
 */
class BlockBreakTest {

    /** A real 2x1 grayscale PNG (stored-deflate IDAT); decodes via PngDecoder. */
    private fun tinyPng(): ByteArray {
        fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        fun chunk(type: String, data: ByteArray): ByteArray {
            val body = type.encodeToByteArray() + data
            var c = -1
            for (b in body) {
                c = c xor (b.toInt() and 0xFF)
                repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt()) xor (c ushr 1) else c ushr 1 }
            }
            return be32(data.size) + body + be32(c.inv())
        }
        val scan = byteArrayOf(0, 0x40, 0xC0.toByte())
        val nlen = scan.size.inv() and 0xFFFF
        val zlib = byteArrayOf(
            0x78, 0x01, 0x01,
            (scan.size and 0xFF).toByte(), ((scan.size ushr 8) and 0xFF).toByte(),
            (nlen and 0xFF).toByte(), ((nlen ushr 8) and 0xFF).toByte(),
        ) + scan + byteArrayOf(0, 0, 0, 1)
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = be32(2) + be32(1) + byteArrayOf(8, 0, 0, 0, 0)
        return sig + chunk("IHDR", ihdr) + chunk("IDAT", zlib) + chunk("IEND", ByteArray(0))
    }

    private fun open(body: String, settings: EpubSettings = EpubSettings(pageWidth = 400.0, pageHeight = 2000.0, margin = 36.0)) =
        EpubDocument.open(EpubFixtures.epub(body, extraEntries = listOf("OEBPS/pic.png" to tinyPng())), settings)

    /** The text each page draws, and whether it draws an image, page by page. */
    private fun pages(doc: EpubDocument): List<Pair<String, Boolean>> = doc.pages.map { page ->
        val calls = RecordingCanvas().also { page.renderTo(it, KiteMatrix.IDENTITY) }.calls
        calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().joinToString("") { it.text } to
            calls.any { it is RecordingCanvas.Call.Image }
    }

    private fun texts(doc: EpubDocument) = pages(doc).map { it.first }

    @Test
    fun a_forced_break_on_a_container_starts_a_new_page() {
        val cases = listOf(
            """<p>Alpha</p><p style="break-before:page">Beta</p>""",
            """<p>Alpha</p><div style="break-before:page"><p>Beta</p></div>""",
            """<div style="break-after:page"><p>Alpha</p></div><p>Beta</p>""",
            """<p>Alpha</p><section><div style="break-before:page"><div><p>Beta</p></div></div></section>""",
            """<section><div style="page-break-after:always"><div><p>Alpha</p></div></div></section><p>Beta</p>""",
            """<p>Alpha</p><table style="break-before:page"><tr><td>Beta</td></tr></table>""",
            """<table style="break-after:page"><tr><td>Alpha</td></tr></table><p>Beta</p>""",
        )
        for (body in cases) assertEquals(listOf("Alpha", "Beta"), texts(open(body)), body)
    }

    @Test
    fun a_forced_break_on_an_image_starts_a_new_page() {
        assertEquals(
            listOf("Alpha" to false, "Beta" to true),
            pages(open("""<p>Alpha</p><img style="display:block;break-before:page" src="pic.png"/><p>Beta</p>""")),
        )
        assertEquals(
            listOf("Alpha" to true, "Beta" to false),
            pages(open("""<p>Alpha</p><img style="display:block;break-after:page" src="pic.png"/><p>Beta</p>""")),
        )
    }

    @Test
    fun a_break_at_the_edges_of_the_book_adds_no_blank_page() {
        assertEquals(listOf("AlphaBeta"), texts(open("""<div style="break-before:page"><p>Alpha</p></div><p>Beta</p>""")))
        assertEquals(listOf("AlphaBeta"), texts(open("""<p>Alpha</p><div style="break-after:page"><p>Beta</p></div>""")))
    }

    @Test
    fun break_inside_avoid_keeps_a_group_together_when_it_fits_a_page() {
        val small = EpubSettings(pageWidth = 400.0, pageHeight = 200.0, margin = 10.0)
        val group = (1..3).joinToString("") { "<p>Gamma$it</p>" }
        fun filler(n: Int) = (1..n).joinToString("") { "<p>Filler$it</p>" }
        fun body(n: Int, wrap: String, style: String) = """${filler(n)}<$wrap style="$style">$group</$wrap><p>Delta</p>"""
        fun pageOf(pages: List<String>, word: String) = pages.indexOfFirst { word in it }
        // Enough filler that the group starts on the first page and ends on the second.
        val n = (1..20).first { n ->
            val pages = texts(open(body(n, "div", ""), small))
            pageOf(pages, "Gamma1") == 0 && pageOf(pages, "Gamma3") == 1
        }
        for (wrap in listOf("div", "section")) {
            val pages = texts(open(body(n, wrap, "break-inside:avoid"), small))
            assertEquals(1, pageOf(pages, "Gamma1"), "$wrap: the group moved to the next page whole: $pages")
            assertEquals(1, pageOf(pages, "Gamma3"), "$wrap: the group stays together: $pages")
        }
        // A group taller than a page cannot stay together, so it breaks where it is.
        val tall = (1..30).joinToString("") { "<p>Tall$it</p>" }
        val pages = texts(open("""<p>Before</p><div style="break-inside:avoid">$tall</div>""", small))
        assertEquals(0, pageOf(pages, "Tall1"), "a group taller than a page starts where it is: $pages")
    }
}
