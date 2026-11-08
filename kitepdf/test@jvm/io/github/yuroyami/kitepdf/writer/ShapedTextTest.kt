package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.PdfDocument
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shaped text and paragraphs on a [PdfBuilder] page (#945), read back by KitePDF and by Poppler.
 * Skips without the Noto fonts of MuPDF's resources, and each Poppler check without its tool.
 */
class ShapedTextTest {

    private fun font(name: String): ByteArray {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "mupdf-master/resources/fonts/noto/$name").exists()) d = d.parentFile
        assumeTrue("the Noto font $name of mupdf-master/resources", d != null)
        return File(d, "mupdf-master/resources/fonts/noto/$name").readBytes()
    }

    private fun tool(name: String): String? = listOf("/opt/homebrew/bin/$name", "/usr/local/bin/$name", "/usr/bin/$name").firstOrNull { File(it).canExecute() }

    private fun pdftotext(pdf: ByteArray): String? {
        val exe = tool("pdftotext") ?: return null
        val f = File.createTempFile("shaped", ".pdf").apply { deleteOnExit(); writeBytes(pdf) }
        val p = ProcessBuilder(exe, "-enc", "UTF-8", f.path, "-").redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        p.waitFor(60, TimeUnit.SECONDS)
        return out
    }

    /** Every stream of [pdf], inflated where it inflates, as one string: the page content among them. */
    private fun contents(pdf: ByteArray): String {
        val text = String(pdf, Charsets.ISO_8859_1)
        return Regex("stream\r?\n").findAll(text).filter { !text.startsWith("end", it.range.first - 3) }.joinToString("\n") { m ->
            val end = text.indexOf("endstream", m.range.last)
            val raw = pdf.copyOfRange(m.range.last + 1, end)
            val inflated = runCatching { java.util.zip.InflaterInputStream(raw.inputStream()).readBytes() }.getOrDefault(raw)
            String(inflated, Charsets.ISO_8859_1)
        }
    }

    /** What pdftotext prints, without the embedding marks it wraps a right-to-left line in. */
    private fun clean(s: String) = s.replace(Regex("[\u202A-\u202E\u200E\u200F\u000C]"), "").trim()

    @Test
    fun arabic_is_written_joined_right_to_left_and_reads_back_in_logical_order() {
        val font = ShapedFont.load(font("NotoNaskhArabic-Regular.otf"))
        val pdf = PdfBuilder().page(width = 300.0, height = 200.0) { shapedText(font, 40.0, 50.0, 100.0, "سلام") }.build()
        val doc = PdfDocument.open(pdf)
        assertEquals("سلام", doc.pages[0].extractText().trim())
        // The glyphs drawn are the joined forms, from the left: the final meem first, the initial seen last.
        val line = font.shaper.shape("سلام")
        val isolated = "سلام".map { font.shaper.glyphId(it.code) }.reversed()
        assertTrue(line.glyphs.map { it.gid }.zip(isolated).count { (a, b) -> a != b } >= 3, "joined forms: ${line.glyphs.map { it.gid }} against $isolated")
        val content = contents(pdf)
        for (g in line.glyphs) assertContains(content, g.gid.toString(16).uppercase().padStart(4, '0'))
        assertTrue("/ActualText" !in content, "a line that readers reorder themselves needs no ActualText")
        pdftotext(pdf)?.let { assertEquals("سلام", clean(it), "pdftotext") }
    }

    @Test
    fun a_latin_line_maps_each_glyph_and_needs_no_actual_text() {
        val font = ShapedFont.load(font("NotoSans-Regular.otf"))
        val pdf = PdfBuilder().page(width = 300.0, height = 200.0) { shapedText(font, 20.0, 20.0, 100.0, "office AVATAR") }.build()
        val doc = PdfDocument.open(pdf)
        assertEquals("office AVATAR", doc.pages[0].extractText().trim())
        assertTrue("/ActualText" !in contents(pdf), "a line in reading order carries no ActualText")
        pdftotext(pdf)?.let { assertEquals("office AVATAR", clean(it), "pdftotext") }
    }

    private val words = ("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna " +
        "aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. ").repeat(6)
        .split(' ').filter { it.isNotEmpty() }.take(200)

    @Test
    fun a_paragraph_of_200_words_breaks_into_lines_that_fit_its_box() {
        val font = ShapedFont.load(font("NotoSans-Regular.otf"))
        val text = words.joinToString(" ")
        var result: PdfParagraph? = null
        val pdf = PdfBuilder().page(width = 612.0, height = 792.0) {
            result = paragraph(font, 11.0, x = 72.0, y = 72.0, width = 300.0, height = 648.0, text = text, align = TextAlign.Justify)
        }.build()
        val r = result!!
        assertEquals("", r.overflow, "the box holds the paragraph")
        assertTrue(r.lines > 20, "${r.lines} lines")
        val page = PdfDocument.open(pdf).pages[0]
        val lines = page.textContent().blocks.flatMap { it.lines }
        assertEquals(r.lines, lines.size, "one text line for each line drawn")
        for (l in lines) {
            assertTrue(l.bounds.left >= 72.0 - 0.5 && l.bounds.right <= 372.0 + 0.5, "the line '${l.text}' spans ${l.bounds.left}..${l.bounds.right}")
        }
        // Justified: every line but the last ends at the right edge of the box.
        assertTrue(lines.dropLast(1).all { it.bounds.right > 372.0 - 2.0 }, "justified lines reach the right edge")
        assertEquals(words, page.extractText().split(Regex("\\s+")).filter { it.isNotEmpty() })
    }

    @Test
    fun what_does_not_fit_comes_back_and_continues_where_the_box_ended() {
        val font = ShapedFont.load(font("NotoSans-Regular.otf"))
        val text = words.joinToString(" ")
        var first: PdfParagraph? = null
        var second: PdfParagraph? = null
        val pdf = PdfBuilder()
            .page(width = 612.0, height = 792.0) { first = paragraph(font, 11.0, 72.0, 600.0, 300.0, 120.0, text) }
            .page(width = 612.0, height = 792.0) { second = paragraph(font, 11.0, 72.0, 72.0, 300.0, 648.0, first!!.overflow) }
            .build()
        val one = first!!
        assertTrue(one.overflow.isNotEmpty() && one.lines in 5..10, "${one.lines} lines, overflow ${one.overflow.length} chars")
        assertTrue(one.height <= 120.0 + 0.01, "the lines drawn stay in the box: ${one.height}")
        assertEquals("", second!!.overflow)
        val doc = PdfDocument.open(pdf)
        val read = (0 until 2).flatMap { doc.pages[it].extractText().split(Regex("\\s+")) }.filter { it.isNotEmpty() }
        assertEquals(words, read, "the second page goes on with the word after the last one of the first")
    }

    @Test
    fun a_word_wider_than_the_box_breaks_at_a_hyphenation_point() {
        val font = ShapedFont.load(font("NotoSans-Regular.otf"))
        var r: PdfParagraph? = null
        val pdf = PdfBuilder().page(width = 300.0, height = 300.0) {
            r = paragraph(font, 12.0, 20.0, 20.0, 80.0, 260.0, "incomprehensibilities", language = "en-US")
        }.build()
        assertTrue(r!!.lines >= 2, "${r!!.lines} lines")
        val text = PdfDocument.open(pdf).pages[0].extractText()
        assertTrue(text.lines().first().trim().endsWith("-"), "the first line ends with a hyphen: $text")
        assertEquals("incomprehensibilities", text.replace("-\n", "").replace(Regex("\\s+"), ""))
    }

    @Test
    fun a_right_to_left_paragraph_starts_at_the_right() {
        val font = ShapedFont.load(font("NotoNaskhArabic-Regular.otf"))
        val pdf = PdfBuilder().page(width = 400.0, height = 300.0) {
            paragraph(font, 14.0, 50.0, 50.0, 300.0, 200.0, "مرحبا بالعالم")
        }.build()
        val page = PdfDocument.open(pdf).pages[0]
        val line = page.textContent().blocks.flatMap { it.lines }.single()
        assertEquals(350.0, line.bounds.right, 1.0, "start alignment of a right-to-left paragraph is the right edge")
        assertEquals("مرحبا بالعالم", page.extractText().trim())
        pdftotext(pdf)?.let { assertEquals("مرحبا بالعالم", clean(it), "pdftotext") }
    }

    /**
     * A vowel sign drawn before its consonant belongs to the consonant's cluster, so the first glyph
     * drawn maps to the whole cluster and the line reads back in logical order.
     */
    @Test
    fun a_reordered_devanagari_line_reads_back_in_logical_order() {
        val font = ShapedFont.load(font("NotoSerifDevanagari-Regular.otf"))
        val line = font.shaper.shape("कि")
        assertEquals(listOf("कि", ""), line.glyphs.map { it.text }, "the vowel sign, drawn first, carries the cluster")
        val pdf = PdfBuilder().page(width = 300.0, height = 200.0) { shapedText(font, 30.0, 20.0, 100.0, "हिन्दी किताब") }.build()
        assertEquals("हिन्दी किताब", PdfDocument.open(pdf).pages[0].extractText().trim())
        pdftotext(pdf)?.let { assertEquals("हिन्दी किताब", clean(it), "pdftotext") }
    }

    /** A ligature is one glyph for several letters, and maps to all of them. */
    @Test
    fun a_ligature_maps_to_every_letter_it_stands_for() {
        val font = ShapedFont.load(font("NotoSans-Regular.otf"))
        val line = font.shaper.shape("office")
        assertTrue(line.glyphs.size < 6 && line.glyphs.any { it.text.length > 1 }, "the font joins the f's and the i: ${line.glyphs.map { it.text }}")
        val pdf = PdfBuilder().page(width = 300.0, height = 200.0) { shapedText(font, 30.0, 20.0, 100.0, "office") }.build()
        assertEquals("office", PdfDocument.open(pdf).pages[0].extractText().trim())
        pdftotext(pdf)?.let { assertEquals("office", clean(it), "pdftotext") }
    }
}
