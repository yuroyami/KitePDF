package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plain text, HTML and FictionBook 2 open as books (#946, #947). */
class ConvertedBooksTest {

    private fun png(): ByteArray {
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 20) for (x in 0 until 20) image.setRGB(x, y, 0xFF0000)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun drawsImage(book: EpubDocument): Boolean = (0 until book.pageCount).any { i ->
        RecordingCanvas().also { book.pages[i].renderTo(it, KiteMatrix.IDENTITY) }.calls.any { it is RecordingCanvas.Call.Image }
    }

    private fun allText(book: EpubDocument): String = (0 until book.pageCount).joinToString("\n") { book.pages[it].textContent().plainText }

    private val fb2 = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description><title-info><author><first-name>Lev</first-name><last-name>Tolstoy</last-name></author>
<book-title>Short Stories</book-title><lang>ru</lang><annotation><p>Two stories.</p></annotation></title-info></description>
<body>
<section id="s1"><title><p>The First</p></title><p>It was a cold morning.<a l:href="#n1" type="note">[1]</a></p>
<image l:href="#pic"/>
<poem><stanza><v>A line of verse</v><v>Another line</v></stanza></poem></section>
<section id="s2"><title><p>The Second</p></title><p>The <emphasis>end</emphasis> came.</p></section>
</body>
<body name="notes"><section id="n1"><title><p>1</p></title><p>A footnote.</p></section></body>
<binary id="pic" content-type="image/png">${Base64.getEncoder().encodeToString(png())}</binary>
</FictionBook>"""

    @Test
    fun an_fb2_book_opens_with_its_chapters_image_poem_and_note() {
        val book = EpubDocument.openFb2(fb2.encodeToByteArray())
        assertEquals("Short Stories", book.metadata.title)
        assertEquals(listOf("Lev Tolstoy"), book.metadata.authors)
        assertEquals("ru", book.metadata.language)
        assertEquals(listOf("The First", "The Second", "Notes"), book.outline.map { it.title })
        val text = allText(book)
        assertContains(text, "It was a cold morning.")
        assertContains(text, "A line of verse")
        assertContains(text, "A footnote.")
        assertTrue(drawsImage(book), "the binary image draws")
        val note = (0 until book.pageCount).flatMap { book.pages[it].links }.single { it.href.contains("n1") }
        assertTrue(note.href.startsWith("notes.xhtml#n1") || note.href.endsWith("notes.xhtml#n1"), note.href)
    }

    @Test
    fun a_zipped_fb2_opens_the_same() {
        val zipped = EpubFixtures.storedZip(listOf("book.fb2" to fb2.encodeToByteArray()))
        assertTrue(EpubDocument.isFb2(zipped))
        assertEquals("Short Stories", EpubDocument.openFb2(zipped).metadata.title)
    }

    @Test
    fun plain_text_opens_with_its_paragraphs() {
        val book = EpubDocument.openText("First paragraph.\n\nSecond one\nwith a break.\n\n\nThird.".encodeToByteArray())
        val blocks = book.pages[0].textContent().blocks
        assertEquals(3, blocks.size, blocks.joinToString { b -> b.lines.joinToString(" ") { it.text } })
        // windows-1252 without a BOM: é is 0xE9.
        val accented = EpubDocument.openText(byteArrayOf('C'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte()))
        assertEquals("Café", accented.pages[0].textContent().plainText.trim())
    }

    @Test
    fun a_large_text_file_shows_its_first_page_without_laying_out_all_of_it() {
        val para = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(20)
        val bytes = (1..5000).joinToString("\n\n") { "$it $para" }.encodeToByteArray()
        assertTrue(bytes.size > 5_000_000)
        val book = EpubDocument.openText(bytes)
        assertTrue(book.chapterCount > 20, "split into chapters: ${book.chapterCount}")
        assertContains(book.pages[0].textContent().plainText, "1 Lorem")
    }

    @Test
    fun an_html_page_opens_with_its_styles_and_images() {
        val html = """<!DOCTYPE html><html><head><title>Page</title><style>p.big { font-size: 30px }</style></head>""" +
            """<body><p class="big">Hello web</p><img src="pics/a.png"/></body></html>"""
        val book = EpubDocument.openHtml(html.encodeToByteArray()) { url -> if (url == "pics/a.png") png() else null }
        assertEquals("Page", book.metadata.title)
        assertContains(allText(book), "Hello web")
        assertTrue(drawsImage(book), "the image came through the resolver")
        val glyphs = RecordingCanvas().also { book.pages[0].renderTo(it, KiteMatrix.IDENTITY) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        assertTrue(glyphs.any { it.fontSize > 20.0 }, "the inline style applies")
        assertTrue(!drawsImage(EpubDocument.openHtml(html.encodeToByteArray())), "without a resolver the image is left out")
    }
}
