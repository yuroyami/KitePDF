package io.github.yuroyami.kitepdf.document

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.cbz.CbzDocument
import io.github.yuroyami.kitepdf.core.KiteByteSource
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.svg.SvgDocument
import io.github.yuroyami.kitepdf.xps.XpsDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [KiteDoc.open] on a [KiteByteSource] knows each format as the array opener does, and a
 * document whose format reads from a source fetches only what it uses (#906).
 */
class KiteDocSourceTest {

    private class CountingSource(private val bytes: ByteArray) : KiteByteSource {
        var fetched = 0L
        override val size: Long get() = bytes.size.toLong()

        override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(length.toLong(), bytes.size - position).toInt()
            bytes.copyInto(into, offset, position.toInt(), position.toInt() + n)
            fetched += n
            return n
        }
    }

    private fun bmp(): ByteArray {
        val header = ByteArray(54)
        header[0] = 'B'.code.toByte(); header[1] = 'M'.code.toByte()
        fun le32(o: Int, v: Int) { var s = 0; var i = o; while (s < 32) { header[i++] = ((v ushr s) and 0xFF).toByte(); s += 8 } }
        fun le16(o: Int, v: Int) { header[o] = (v and 0xFF).toByte(); header[o + 1] = ((v ushr 8) and 0xFF).toByte() }
        le32(2, 62); le32(10, 54); le32(14, 40); le32(18, 2); le32(22, 1)
        le16(26, 1); le16(28, 24); le32(34, 8)
        return header + byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
    }

    private fun xps(): ByteArray {
        val ns = "http://schemas.microsoft.com/xps/2005/06"
        return storedZip(
            listOf(
                "_rels/.rels" to """<Relationships><Relationship Type="$ns/fixedrepresentation" Target="seq.bin"/></Relationships>""",
                "seq.bin" to """<FixedDocumentSequence xmlns="$ns"><DocumentReference Source="doc.bin"/></FixedDocumentSequence>""",
                "doc.bin" to """<FixedDocument><PageContent Source="page.bin"/></FixedDocument>""",
                "page.bin" to """<FixedPage Width="96" Height="192" xmlns="$ns"/>""",
            ).map { it.first to it.second.encodeToByteArray() },
        )
    }

    private val fb2 = """<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">""" +
        """<description><title-info><book-title>Tale</book-title><lang>en</lang></title-info></description>""" +
        """<body><section><p>Once upon a time.</p></section></body></FictionBook>"""

    @Test
    fun every_format_opens_from_a_source_as_from_its_bytes() {
        assertIs<PdfDocument>(KiteDoc.open(CountingSource(samplePdf())))
        assertIs<EpubDocument>(KiteDoc.open(CountingSource(sampleEpub())))
        assertIs<EpubDocument>(KiteDoc.open(CountingSource(sampleEpub(mimetypeFirst = false))))
        assertIs<CbzDocument>(KiteDoc.open(CountingSource(storedZip(listOf("p1.bmp" to bmp(), "p2.bmp" to bmp())))))
        assertEquals(72.0, assertIs<XpsDocument>(KiteDoc.open(CountingSource(xps()))).pages.single().displayWidth)
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="32"><rect width="8" height="8"/></svg>"""
        assertIs<SvgDocument>(KiteDoc.open(CountingSource(svg.encodeToByteArray())))
        assertEquals("Tale", KiteDoc.open(CountingSource(fb2.encodeToByteArray())).metadata.title)
        assertEquals("Tale", KiteDoc.open(CountingSource(storedZip(listOf("tale.fb2" to fb2.encodeToByteArray())))).metadata.title)
        val page = KiteDoc.open(CountingSource("<!DOCTYPE html><html><body><p>Hello page</p></body></html>".encodeToByteArray()))
        assertTrue("Hello page" in page.page(KiteLocation(0, 0)).textContent()!!.plainText)
    }

    @OptIn(io.github.yuroyami.kitepdf.core.KiteRawApi::class)
    @Test
    fun a_pdf_from_a_source_reads_page_one_without_its_unused_stream() {
        val editor = PdfDocument.open(samplePdf()).edit()
        editor.addObject(PdfStream(PdfDictionary(emptyMap()), ByteArray(8 * 1024 * 1024) { (it * 13).toByte() }))
        val file = editor.saveIncremental()
        val source = CountingSource(file)

        val doc = assertIs<PdfDocument>(KiteDoc.open(source))

        assertTrue("page one" in doc.pages[0].extractText())
        assertTrue(source.fetched < 1024 * 1024, "fetched ${source.fetched} bytes of ${file.size}")
    }

    @Test
    fun a_large_source_of_no_known_format_is_refused_without_reading_it_whole() {
        val junk = CountingSource(ByteArray(4 * 1024 * 1024) { 'x'.code.toByte() })
        assertFailsWith<KiteFormatException> { KiteDoc.open(junk, maxWholeBytes = 1024 * 1024) }
        assertNull(KiteDoc.openOrNull(CountingSource(ByteArray(16))))
    }
}
