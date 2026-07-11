package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.RecordingCanvas
import io.github.yuroyami.kitepdf.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end proof that :kitepdf-epub is a real handler on :kitepdf-core: build
 * a minimal EPUB, open it, and render a page through the SAME [RecordingCanvas]
 * the PDF engine's render tests use. If text reaches drawGlyphs, the shared
 * substrate genuinely serves a second, non-PDF format.
 */
class EpubRenderTest {

    @Test
    fun opens_spine_and_renders_body_text_through_core_canvas() {
        val epub = buildMinimalEpub()

        val doc = EpubDocument.open(epub)
        assertNotNull(doc, "EPUB should open")
        assertTrue(doc.pageCount >= 1, "expected at least one reflowed page")

        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)

        val glyphRuns = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        assertTrue(glyphRuns.isNotEmpty(), "page should emit drawGlyphs runs")

        // Every EPUB run uses the substitute-serif path (no embedded outlines).
        assertTrue(glyphRuns.all { !it.hasOutlines }, "EPUB body text uses the fallback font path")

        val rendered = glyphRuns.joinToString(" ") { it.text }
        assertTrue(rendered.contains("Hello"), "rendered text should carry the <h1>: <<$rendered>>")
        assertTrue(rendered.contains("kitepdf"), "rendered text should carry the <p>: <<$rendered>>")
    }

    @Test
    fun emphasis_and_headings_reach_the_canvas_with_style() {
        val doc = EpubDocument.open(buildStyledEpub())
        assertNotNull(doc)
        val glyphRuns = doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls
                .filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }
        assertTrue(glyphRuns.isNotEmpty())

        val boldRun = glyphRuns.firstOrNull { it.fontSpec.bold && it.text.contains("strong") }
        assertNotNull(boldRun, "a <strong> run should carry fontSpec.bold")
        val italicRun = glyphRuns.firstOrNull { it.fontSpec.italic && it.text.contains("slanted") }
        assertNotNull(italicRun, "an <em> run should carry fontSpec.italic")

        // The <h1> must render larger than body text.
        val headingSize = glyphRuns.filter { it.text.contains("Big") }.maxOf { it.fontSize }
        val bodySize = glyphRuns.filter { it.text.contains("strong") }.minOf { it.fontSize }
        assertTrue(headingSize > bodySize, "h1 ($headingSize) should be larger than body ($bodySize)")
    }

    @Test
    fun img_element_emits_a_drawImage_call() {
        val doc = EpubDocument.open(buildImageEpub())
        assertNotNull(doc)
        val images = doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls
                .filterIsInstance<RecordingCanvas.Call.Image>()
        }
        assertTrue(images.size == 1, "the <img> should emit exactly one drawImage")
        assertEquals(48, images.single().image.width, "sniffed JPEG width")
        assertEquals(32, images.single().image.height, "sniffed JPEG height")
    }

    @Test
    fun long_content_paginates_without_losing_text() {
        val body = buildString {
            for (i in 0 until 120) append("<p>Paragraph number $i of the very long chapter that must overflow.</p>")
        }
        val doc = EpubDocument.open(buildEpubWithBody(body))
        assertNotNull(doc)
        assertTrue(doc.pageCount > 1, "120 paragraphs should span multiple pages (got ${doc.pageCount})")

        val allText = doc.pages.joinToString(" ") { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls
                .filterIsInstance<RecordingCanvas.Call.Glyphs>().joinToString(" ") { it.text }
        }
        assertTrue(allText.contains("number 0 "), "first paragraph rendered")
        assertTrue(allText.contains("number 119 "), "last paragraph rendered on a later page")
    }

    @Test
    fun png_image_decodes_end_to_end_and_draws() {
        // A real 2x1 grayscale PNG reaches the canvas as a decoded RAW image.
        val png = buildEpubWithImage("pic.png", minimalGrayPng())
        val doc = EpubDocument.open(png)
        assertNotNull(doc)
        val images = doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls
                .filterIsInstance<RecordingCanvas.Call.Image>()
        }
        assertEquals(1, images.size)
        val img = images.single().image
        assertEquals(2, img.width); assertEquals(1, img.height)
        assertEquals(io.github.yuroyami.kitepdf.render.ImageXObject.Kind.RAW, img.kind, "PNG decodes to RAW pixels in core")
    }

    // ---- Phase 2: CSS reaching the canvas -----------------------------------

    private fun renderRuns(body: String): List<RecordingCanvas.Call.Glyphs> {
        val doc = EpubDocument.open(buildEpubWithBody(body))
        assertNotNull(doc)
        return doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }
    }

    @Test
    fun author_css_color_reaches_canvas() {
        val runs = renderRuns("<style>p{color:#008000}</style><p>green words here</p>")
        assertTrue(runs.any { it.color == RgbColor(0.0, 128 / 255.0, 0.0) && "green" in it.text }, "author color applied")
    }

    @Test
    fun inline_font_size_scales_glyphs() {
        val runs = renderRuns("""<p style="font-size:24pt">big</p>""")
        assertEquals(24.0, runs.first { "big" in it.text }.fontSize, 1e-6)
    }

    @Test
    fun text_align_center_shifts_run_right() {
        val eCenter = renderRuns("""<p style="text-align:center">hi</p>""").first { "hi" in it.text }.textToDevice.e
        val eLeft = renderRuns("<p>hi</p>").first { "hi" in it.text }.textToDevice.e
        assertTrue(eCenter > eLeft + 20.0, "centered line starts further right ($eCenter vs $eLeft)")
    }

    @Test
    fun display_none_is_not_rendered() {
        val text = renderRuns("""<p style="display:none">HIDDEN</p><p>VISIBLE</p>""").joinToString(" ") { it.text }
        assertTrue("VISIBLE" in text && "HIDDEN" !in text, "display:none suppressed, sibling shown: <<$text>>")
    }

    @Test
    fun sans_serif_css_selects_sans_face() {
        val runs = renderRuns("""<p style="font-family:sans-serif">abc</p>""")
        assertTrue(runs.any { "abc" in it.text && it.fontSpec.family == io.github.yuroyami.kitepdf.font.FontFamily.SansSerif })
    }

    // ---- Phase 3: box model painting ----------------------------------------

    private fun renderCalls(body: String): List<RecordingCanvas.Call> {
        val doc = EpubDocument.open(buildEpubWithBody(body))
        assertNotNull(doc)
        return doc.pages.flatMap { page -> RecordingCanvas().also { page.renderTo(it) }.calls }
    }

    @Test
    fun background_color_paints_a_fill() {
        val fills = renderCalls("""<p style="background-color:#ffff00">hi</p>""").filterIsInstance<RecordingCanvas.Call.Fill>()
        assertTrue(fills.any { it.color == RgbColor(1.0, 1.0, 0.0) }, "yellow background fill emitted")
    }

    @Test
    fun border_paints_fills() {
        val fills = renderCalls("""<p style="border:2px solid #ff0000">hi</p>""").filterIsInstance<RecordingCanvas.Call.Fill>()
        assertTrue(fills.any { it.color == RgbColor(1.0, 0.0, 0.0) }, "red border fill emitted")
    }

    @Test
    fun background_is_painted_before_text() {
        val calls = renderCalls("""<p style="background-color:#00ff00">hi</p>""")
        val firstFill = calls.indexOfFirst { it is RecordingCanvas.Call.Fill }
        val firstGlyph = calls.indexOfFirst { it is RecordingCanvas.Call.Glyphs }
        assertTrue(firstFill in 0 until firstGlyph, "background ($firstFill) drawn before text ($firstGlyph)")
    }

    @Test
    fun padding_shifts_text_right() {
        val plain = renderCalls("<p>hi</p>").filterIsInstance<RecordingCanvas.Call.Glyphs>().first { "hi" in it.text }.textToDevice.e
        val padded = renderCalls("""<div style="padding-left:40px"><p>hi</p></div>""").filterIsInstance<RecordingCanvas.Call.Glyphs>().first { "hi" in it.text }.textToDevice.e
        assertTrue(padded > plain + 25.0, "padding-left pushes content right ($padded vs $plain)")
    }

    // ---- Phase 4: bidi ------------------------------------------------------

    @Test
    fun bidi_reverses_a_hebrew_run() {
        // Latin then Hebrew alef-bet-gimel; the Hebrew must draw in visual (reversed) order.
        val text = renderRuns("<p>abcאבג</p>").joinToString("") { it.text }
        assertTrue("גבא" in text, "Hebrew drawn reversed (visual order): <<$text>>")
        assertTrue("אבג" !in text, "not in logical order")
        assertTrue(text.startsWith("abc"), "Latin stays leftmost, in logical order")
    }

    @Test
    fun rtl_paragraph_places_first_word_rightmost() {
        // dir=rtl: the Hebrew word (first logical) sits to the right of the Latin word.
        val runs = renderRuns("<p dir=\"rtl\">שלום world</p>")
        val hebrew = runs.firstOrNull { r -> r.text.any { it.code in 0x0590..0x05FF } }
        val latin = runs.firstOrNull { it.text.contains("world") }
        assertNotNull(hebrew); assertNotNull(latin)
        assertTrue(hebrew.textToDevice.e > latin.textToDevice.e, "RTL base: Hebrew is right of Latin")
    }

    @Test
    fun missing_container_is_not_a_readable_epub() {
        // A zip with no META-INF/container.xml is not a readable EPUB (T-22:
        // open throws, openOrNull maps it to null).
        val notEpub = storedZip(listOf("random.txt" to "nothing here".encodeToByteArray()))
        assertTrue(EpubDocument.openOrNull(notEpub) == null)
    }

    // ---- fixtures -----------------------------------------------------------

    private fun buildMinimalEpub(): ByteArray {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <manifest>
                <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="c1"/>
              </spine>
            </package>
        """.trimIndent()

        val chapter = """
            <?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>Chapter</title></head>
              <body>
                <h1>Hello from the EPUB handler</h1>
                <p>This paragraph is laid out and painted through the shared kitepdf-core
                   Canvas, the same one the PDF engine uses. One engine, many formats.</p>
              </body>
            </html>
        """.trimIndent()

        return storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/chapter1.xhtml" to chapter.encodeToByteArray(),
            ),
        )
    }

    /** Wrap [bodyHtml] in a one-document EPUB (single spine item). */
    private fun buildEpubWithBody(bodyHtml: String): ByteArray {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <manifest><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="c1"/></spine>
            </package>
        """.trimIndent()
        val chapter =
            """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>$bodyHtml</body></html>"""
        return storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/chapter1.xhtml" to chapter.encodeToByteArray(),
            ),
        )
    }

    private fun buildStyledEpub(): ByteArray {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()

        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <manifest><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="c1"/></spine>
            </package>
        """.trimIndent()

        val chapter = """
            <?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>Styled</title></head>
              <body>
                <h1>Big Title Here</h1>
                <p>Body text with a <strong>strong</strong> word and a <em>slanted</em> word.</p>
              </body>
            </html>
        """.trimIndent()

        return storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/chapter1.xhtml" to chapter.encodeToByteArray(),
            ),
        )
    }

    private fun buildImageEpub(): ByteArray {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()

        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <manifest>
                <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                <item id="cover" href="cover.jpg" media-type="image/jpeg"/>
              </manifest>
              <spine><itemref idref="c1"/></spine>
            </package>
        """.trimIndent()

        val chapter = """
            <?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body><p>See the figure:</p><img src="cover.jpg" alt="cover"/></body>
            </html>
        """.trimIndent()

        // A minimal JPEG: SOI + SOF0 declaring a 48x32 frame + EOI. Enough for the
        // dimension sniffer; the RecordingCanvas records the image without decoding it.
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),                                     // SOI
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08,                   // SOF0, len=17, prec=8
            0x00, 0x20, 0x00, 0x30,                                           // height=32, width=48
            0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,      // 3 components
            0xFF.toByte(), 0xD9.toByte(),                                     // EOI
        )

        return storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/chapter1.xhtml" to chapter.encodeToByteArray(),
                "OEBPS/cover.jpg" to jpeg,
            ),
        )
    }

    private fun buildEpubWithImage(imageName: String, imageBytes: ByteArray): ByteArray {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <manifest>
                <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                <item id="im" href="$imageName" media-type="image/png"/>
              </manifest>
              <spine><itemref idref="c1"/></spine>
            </package>
        """.trimIndent()
        val chapter =
            """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><img src="$imageName"/></body></html>"""
        return storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/chapter1.xhtml" to chapter.encodeToByteArray(),
                "OEBPS/$imageName" to imageBytes,
            ),
        )
    }

    /** A 2x1 grayscale-8 PNG (pixels 64, 192) using a STORED deflate block. */
    private fun minimalGrayPng(): ByteArray {
        fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        fun chunk(type: String, data: ByteArray) =
            be32(data.size) + type.encodeToByteArray() + data + byteArrayOf(0, 0, 0, 0)
        val scan = byteArrayOf(0, 0x40, 0xC0.toByte()) // filter none + two grey samples
        val nlen = scan.size.inv() and 0xFFFF
        val zlib = byteArrayOf(0x78, 0x01, 0x01, (scan.size and 0xFF).toByte(), ((scan.size ushr 8) and 0xFF).toByte(), (nlen and 0xFF).toByte(), ((nlen ushr 8) and 0xFF).toByte()) +
            scan + byteArrayOf(0, 0, 0, 1)
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = be32(2) + be32(1) + byteArrayOf(8, 0, 0, 0, 0)
        return sig + chunk("IHDR", ihdr) + chunk("IDAT", zlib) + chunk("IEND", ByteArray(0))
    }

    /** Build a STORED (uncompressed) zip. CRCs are left zero; [ZipReader] does not verify them. */
    private fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out.add((v and 0xFF).toByte()); out.add(((v ushr 8) and 0xFF).toByte()) }
        fun u32(v: Long) { var s = 0; while (s < 32) { out.add(((v ushr s) and 0xFF).toByte()); s += 8 } }
        fun raw(b: ByteArray) { for (x in b) out.add(x) }

        data class Cd(val name: ByteArray, val offset: Int, val size: Int)
        val cds = ArrayList<Cd>()

        for ((name, data) in entries) {
            val nb = name.encodeToByteArray()
            val offset = out.size
            u32(0x04034b50L); u16(20); u16(0); u16(0); u16(0); u16(0)  // sig ver flags method time date
            u32(0L); u32(data.size.toLong()); u32(data.size.toLong())  // crc csize usize
            u16(nb.size); u16(0)                                       // nameLen extraLen
            raw(nb); raw(data)
            cds.add(Cd(nb, offset, data.size))
        }

        val cdStart = out.size
        for (cd in cds) {
            u32(0x02014b50L); u16(20); u16(20); u16(0); u16(0)         // sig verMade verNeed flags method
            u16(0); u16(0); u32(0L)                                    // time date crc
            u32(cd.size.toLong()); u32(cd.size.toLong())              // csize usize
            u16(cd.name.size); u16(0); u16(0)                          // nameLen extraLen commentLen
            u16(0); u16(0); u32(0L)                                    // diskStart intAttr extAttr
            u32(cd.offset.toLong())                                    // local header offset
            raw(cd.name)
        }
        val cdSize = out.size - cdStart

        u32(0x06054b50L); u16(0); u16(0)                              // sig disk cdDisk
        u16(cds.size); u16(cds.size)                                  // entriesThisDisk entriesTotal
        u32(cdSize.toLong()); u32(cdStart.toLong()); u16(0)          // cdSize cdOffset commentLen
        return out.toByteArray()
    }
}
