package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-66: an inline `<img>` flows on the line (bottom on the baseline, line
 * grows to fit) instead of being dropped; `float:left/right` registers an
 * exclusion band so text lines beside the float are narrower and lines below
 * are full width again; `clear` drops content below floats.
 */
class InlineImageFloatTest {

    /** A real 2x1 grayscale PNG (stored-deflate IDAT); decodes via PngDecoder. */
    private fun tinyPng(): ByteArray {
        fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        fun chunk(type: String, data: ByteArray) =
            be32(data.size) + type.encodeToByteArray() + data + byteArrayOf(0, 0, 0, 0)
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

    private fun open(body: String, pageWidth: Double = 400.0): EpubDocument =
        EpubDocument.open(
            EpubFixtures.epub(body, extraEntries = listOf("OEBPS/pic.png" to tinyPng())),
            EpubSettings(pageWidth = pageWidth, pageHeight = 640.0),
        ) ?: error("fixture failed to open")

    /* ─── Fixture A: inline image ────────────────────────────────────────── */

    @Test
    fun inline_image_is_not_dropped_and_draws_on_the_line() {
        val doc = open(
            """<body><p>before <img src="pic.png" style="width:30pt;height:30pt"/> after</p></body>""",
        )
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)
        val images = canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>()
        assertEquals(1, images.size, "the inline image must draw")
        val glyphs = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        val text = glyphs.joinToString("") { it.text }
        assertTrue("before" in text && "after" in text, "surrounding text intact: $text")

        // Same line: image x sits between 'before' and 'after' pen positions.
        val beforeRun = glyphs.first { "before" in it.text }
        val imgX = images.single().ctm.e
        assertTrue(imgX > beforeRun.textToDevice.e, "image drawn after the text before it")
    }

    @Test
    fun inline_image_sits_on_the_baseline_and_grows_the_line() {
        val body = """<body><p>x <img src="pic.png" style="width:40pt;height:40pt"/> y</p><p>next</p></body>"""
        val doc = open(body)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)
        val img = canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>().single()
        val run = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().first { "x" in it.text }

        // Baseline: the image bottom equals the text baseline. Its draw ctm
        // f-translation (device y-up bottom edge) must equal the run's.
        assertEquals(run.textToDevice.f, img.ctm.f, 1e-6, "image bottom on the text baseline")

        // Line growth: the first baseline sits at least the image height below
        // the block's top (ascent >= 40pt), instead of the text's own 9.6pt.
        // Doc-space baseline y = pageHeight - device f; block top = page margin
        // (36) + UA body margin (12).
        val baselineDocY = 640.0 - run.textToDevice.f
        assertTrue(
            baselineDocY >= 36.0 + 12.0 + 40.0 - 1e-6,
            "line ascent must cover the 40pt image (baseline at $baselineDocY)",
        )
    }

    /* ─── Fixture B: floats ──────────────────────────────────────────────── */

    @Test
    fun float_left_narrows_beside_lines_and_releases_below() {
        val words = "alpha beta gamma delta epsilon zeta ".repeat(20)
        val body =
            """<body><img src="pic.png" style="float:left;width:80pt;height:60pt"/><p style="margin:0">$words</p></body>"""
        val doc = open(body)
        val text = doc.pages[0].textContent()
        val lines = text.blocks.flatMap { it.lines }
        assertTrue(lines.size > 5, "long paragraph wraps (got ${lines.size})")

        val beside = lines.filter { it.bounds.top < 36.0 + 60.0 - 5.0 } // overlapping the float's 60pt band
        val below = lines.filter { it.bounds.top > 36.0 + 60.0 + 20.0 }
        assertTrue(beside.isNotEmpty() && below.isNotEmpty(), "need lines both beside and below the float")
        for (line in beside) {
            assertTrue(
                line.bounds.left >= 36.0 + 80.0 - 1.0,
                "a line beside a float:left starts right of it: left=${line.bounds.left}, top=${line.bounds.top}",
            )
        }
        for (line in below) {
            assertTrue(
                line.bounds.left < 36.0 + 80.0 - 10.0,
                "a line below the float returns to full width: left=${line.bounds.left}, top=${line.bounds.top}",
            )
        }
    }

    @Test
    fun float_right_narrows_the_right_side() {
        val words = "alpha beta gamma delta epsilon zeta ".repeat(20)
        val body =
            """<body><img src="pic.png" style="float:right;width:80pt;height:60pt"/><p style="margin:0">$words</p></body>"""
        val doc = open(body, pageWidth = 400.0)
        val contentRight = 400.0 - 36.0 - 12.0 // page margin + UA body margin
        val lines = doc.pages[0].textContent().blocks.flatMap { it.lines }
        val beside = lines.filter { it.bounds.top < 36.0 + 60.0 - 5.0 }
        assertTrue(beside.isNotEmpty())
        for (line in beside) {
            assertTrue(
                line.bounds.right <= contentRight - 80.0 + 1.0,
                "a line beside a float:right ends left of it: right=${line.bounds.right}",
            )
        }
    }

    @Test
    fun clear_drops_below_the_float() {
        val body =
            """<body><img src="pic.png" style="float:left;width:50pt;height:100pt"/>""" +
                """<p style="margin:0">short</p><p style="clear:left;margin:0">cleared</p></body>"""
        val doc = open(body)
        val text = doc.pages[0].textContent()
        val cleared = text.blocks.first { b -> b.lines.any { "cleared" in it.text } }.lines.first()
        assertTrue(
            cleared.bounds.top >= 36.0 + 100.0 - 1.0,
            "clear:left content starts below the 100pt float (top=${cleared.bounds.top})",
        )
        assertTrue(cleared.bounds.left < 36.0 + 50.0, "cleared line is full width again")
    }
}
