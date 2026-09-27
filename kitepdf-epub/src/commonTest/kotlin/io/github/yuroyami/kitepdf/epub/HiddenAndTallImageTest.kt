package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An image with `display: none` generates no box: it is not drawn and takes no room (#424).
 */
class HiddenAndTallImageTest {

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

    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20"><rect width="20" height="20" fill="red"/></svg>"""

    private val images = listOf("OEBPS/pic.png" to tinyPng(), "OEBPS/pic.svg" to svg.encodeToByteArray())

    /** A book whose chapter has [head] in its head and [body] in its body. */
    private fun open(body: String, head: String = "", settings: EpubSettings = EpubSettings()): EpubDocument {
        val chapter = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head>$head</head><body>$body</body></html>"""
        return EpubDocument.open(EpubFixtures.epub(body, extraEntries = images, chapterBytes = chapter.encodeToByteArray()), settings)
    }

    /** Every draw of every page, in order. */
    private fun draw(doc: EpubDocument): RecordingCanvas {
        val canvas = RecordingCanvas()
        for (page in doc.pages) page.renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas
    }

    private fun imageDraws(canvas: RecordingCanvas): Int =
        canvas.calls.count { it is RecordingCanvas.Call.Image } +
            canvas.calls.count { it is RecordingCanvas.Call.Fill && it.color.r > 0.9 && it.color.g < 0.1 }

    /** Where the text "Beta" starts: its run's device y. */
    private fun betaY(canvas: RecordingCanvas): Double =
        canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().first { "Beta" in it.text }.textToDevice.f

    @Test
    fun a_hidden_image_is_neither_drawn_nor_given_room() {
        val plain = betaY(draw(open("<p>Alpha</p><p>Beta</p>")))
        val cases = listOf(
            "block PNG, inline style" to open("""<p>Alpha</p><img style="display:none" src="pic.png"/><p>Beta</p>"""),
            "block SVG, inline style" to open("""<p>Alpha</p><img style="display:none" src="pic.svg"/><p>Beta</p>"""),
            "block PNG, stylesheet" to open("""<p>Alpha</p><img class="hide" src="pic.png"/><p>Beta</p>""", head = "<style>img.hide { display: none }</style>"),
            "inline PNG, inline style" to open("""<p>Alpha <img style="display:none" src="pic.png"/></p><p>Beta</p>"""),
            "inline SVG, stylesheet" to open("""<p>Alpha <img class="hide" src="pic.svg"/></p><p>Beta</p>""", head = "<style>img.hide { display: none }</style>"),
            "inside a span, inline style" to open("""<p>Alpha <span><img style="display:none" src="pic.png"/></span></p><p>Beta</p>"""),
            "inside a link, stylesheet" to open("""<p>Alpha <a href="#x"><img class="hide" src="pic.svg"/></a></p><p>Beta</p>""", head = "<style>img.hide { display: none }</style>"),
        )
        for ((name, doc) in cases) {
            val canvas = draw(doc)
            assertEquals(0, imageDraws(canvas), "$name: the hidden image was drawn")
            assertEquals(plain, betaY(canvas), 0.01, "$name: the hidden image took room")
        }
        // The same image shown draws once, so the fixture can draw it.
        assertEquals(1, imageDraws(draw(open("""<p>Alpha <img src="pic.png" style="width:20pt;height:20pt"/></p><p>Beta</p>"""))))
    }
}
