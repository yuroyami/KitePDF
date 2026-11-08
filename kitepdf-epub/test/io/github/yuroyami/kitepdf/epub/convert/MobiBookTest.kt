package io.github.yuroyami.kitepdf.epub.convert

import io.github.yuroyami.kitepdf.core.zip.ZipReader
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubFormatException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The MOBI reader on books written here (#948): the record layout, PalmDOC and HUFF/CDIC text,
 * trailing entries, a MOBI 6 book's page breaks, `filepos` links and `recindex` images, and
 * DRM. The KF8 half is checked on real books in `MobiCorpusTest`.
 */
class MobiBookTest {

    @Test
    fun palmdoc_lz77_reads_literals_runs_spaces_and_copies() {
        // "abc", a copy of 6 bytes from 3 back, a space and 'A', then a run of two raw bytes.
        val packed = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0x80.toByte(), 0x1B, 0xC1.toByte(), 0x02, 0x01, 0x02)
        val out = MobiBook.Bytes()
        MobiBook.lz77(packed, packed.size, out)
        assertEquals("abcabcabc A\u0001\u0002", out.toArray().decodeToString())
    }

    @Test
    fun trailing_entries_end_with_their_size_and_the_multibyte_overlap_comes_first() {
        // The text, the overlap (one byte and its count), then an entry of three bytes that ends with its size.
        val rec = "hello".encodeToByteArray() + byteArrayOf(0xAA.toByte(), 0x01) + byteArrayOf('X'.code.toByte(), 'Y'.code.toByte(), 0x83.toByte())
        assertEquals(5, MobiBook.trailing(rec, 0b11))
        assertEquals(3, MobiBook.trailing(rec, 0b10))
        assertEquals(0, MobiBook.trailing(rec, 0))
    }

    @Test
    fun a_mobi6_book_is_cut_at_its_page_breaks_with_its_links_and_images() {
        val (bytes, target) = mobi6()
        val epub = MobiBook.convert(bytes)
        val zip = ZipReader(epub)
        val one = zip.readText("OEBPS/chapter0000.html")!!
        val two = zip.readText("OEBPS/chapter0001.html")!!
        assertContains(one, "href=\"chapter0001.html#filepos$target\"")
        assertContains(one, "src=\"res00001.png\"")
        assertContains(one, "text-indent:-2em;margin-left:2em;", message = "a negative width is a hanging indent")
        assertFalse(one.contains("mbp:"), "no Mobipocket tag is left")
        assertContains(two, "<a id=\"filepos$target\"></a><h2>Two</h2>")
        assertContains(zip.readText("OEBPS/content.opf")!!, "properties=\"cover-image\"")
        assertTrue(zip.read("OEBPS/res00001.png")!!.contentEquals(PNG))

        val doc = EpubDocument.open(epub)
        assertEquals(2, doc.chapterCount)
        assertEquals("A Fixture Book", doc.metadata.title)
        assertEquals(listOf("Ann Author"), doc.metadata.authors)
        assertEquals("fr", doc.metadata.language)
        val text = (0 until doc.pageCount).joinToString(" ") { doc.pages[it].textContent().plainText }
        assertContains(text, "Chapter one")
        assertContains(text, "Second chapter text")
    }

    @Test
    fun huff_cdic_text_unpacks_its_phrases_and_a_phrase_made_of_phrases() {
        // Every code is eight bits, and its top byte is 255 minus the phrase number.
        fun code(vararg phrase: Int) = ByteArray(phrase.size) { (255 - phrase[it]).toByte() }
        val phrases = (0 until 256).map { byteArrayOf(it.toByte()) to true }.toMutableList()
        phrases[1] = code('H'.code, 'i'.code) to false
        val text = code('x'.code, 1, '!'.code)
        val r0 = record0(compression = 17480, textLength = 4, textRecords = 1, firstImage = -1, huffRecord = 2, huffCount = 2)
        val bytes = pdb(listOf(r0, text, huffRecord(), cdic(phrases)))
        val doc = EpubDocument.open(MobiBook.convert(bytes))
        assertContains(doc.pages[0].textContent().plainText, "xHi!")
    }

    @Test
    fun a_book_with_drm_says_so() {
        val r0 = record0(compression = 2, textLength = 4, textRecords = 1, firstImage = -1, encryption = 2)
        val e = assertFailsWith<EpubFormatException> { MobiBook.convert(pdb(listOf(r0, "text".encodeToByteArray()))) }
        assertContains(e.message!!, "DRM")
    }

    @Test
    fun only_a_bookmobi_database_is_a_mobi() {
        assertTrue(MobiBook.isMobi(mobi6().first))
        assertFalse(MobiBook.isMobi(ByteArray(100)))
        assertFalse(MobiBook.isMobi("%PDF-1.7".encodeToByteArray()))
    }

    // ---- The fixture writer ----

    /** A MOBI 6 book of two chapters in two uncompressed text records with a trailing entry each, an image and a cover. */
    private fun mobi6(): Pair<ByteArray, Int> {
        var html = "<html><head><guide><reference type=\"toc\" title=\"Contents\" filepos=0000000000 /></guide></head><body>" +
            "<p width=\"-2em\" height=\"1em\">Chapter one <a filepos=0000000000>to two</a></p><img recindex=\"00001\"/>" +
            "<mbp:pagebreak/><h2>Two</h2><p>Second chapter text</p></body></html>"
        val target = html.indexOf("<h2>")
        html = html.replace("filepos=0000000000", "filepos=" + target.toString().padStart(10, '0'))
        val text = html.encodeToByteArray()
        val half = text.size / 2
        // Each record ends with one trailing entry of one byte, its own size.
        val records = listOf(text.copyOfRange(0, half) + byteArrayOf(0x81.toByte()), text.copyOfRange(half, text.size) + byteArrayOf(0x81.toByte()))
        val r0 = record0(
            compression = 1, textLength = text.size, textRecords = 2, firstImage = 3, extraFlags = 0b10,
            exth = mapOf(503 to listOf("A Fixture Book".encodeToByteArray()), 100 to listOf("Ann Author".encodeToByteArray()),
                524 to listOf("fr".encodeToByteArray()), 201 to listOf(u32(0))),
        )
        return pdb(listOf(r0) + records + listOf(PNG)) to target
    }

    private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)

    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun pdb(records: List<ByteArray>): ByteArray {
        val head = ByteArray(78)
        "fixture".encodeToByteArray().copyInto(head)
        "BOOKMOBI".encodeToByteArray().copyInto(head, 60)
        u16(records.size).copyInto(head, 76)
        var offset = 78 + records.size * 8 + 2
        val list = records.flatMapIndexed { i, r -> (u32(offset) + u32(i * 2).also { offset += r.size }).toList() }.toByteArray()
        return records.fold(head + list + byteArrayOf(0, 0)) { acc, r -> acc + r }
    }

    private fun record0(
        compression: Int, textLength: Int, textRecords: Int, firstImage: Int, encryption: Int = 0, extraFlags: Int = 0,
        huffRecord: Int = 0, huffCount: Int = 0, exth: Map<Int, List<ByteArray>> = emptyMap(),
    ): ByteArray {
        val headerLength = 0xE8
        val r = ByteArray(16 + headerLength)
        u16(compression).copyInto(r, 0); u32(textLength).copyInto(r, 4); u16(textRecords).copyInto(r, 8)
        u16(4096).copyInto(r, 10); u16(encryption).copyInto(r, 12)
        "MOBI".encodeToByteArray().copyInto(r, 16); u32(headerLength).copyInto(r, 20); u32(2).copyInto(r, 24)
        u32(65001).copyInto(r, 0x1C); u32(6).copyInto(r, 0x24)
        u32(firstImage).copyInto(r, 0x6C); u32(huffRecord).copyInto(r, 0x70); u32(huffCount).copyInto(r, 0x74)
        u32(if (exth.isEmpty()) 0 else 0x40).copyInto(r, 0x80)
        u32(-1).copyInto(r, 0xC0); u32(extraFlags).copyInto(r, 0xF0); u32(-1).copyInto(r, 0xF4)
        val items = exth.flatMap { (type, values) -> values.map { u32(type) + u32(it.size + 8) + it } }
        val exthBlock = if (items.isEmpty()) ByteArray(0) else
            "EXTH".encodeToByteArray() + u32(12 + items.sumOf { it.size }) + u32(items.size) + items.fold(ByteArray(0)) { a, b -> a + b }
        val name = "fixture".encodeToByteArray()
        val out = r + exthBlock
        u32(out.size).copyInto(out, 0x54); u32(name.size).copyInto(out, 0x58)
        return out + name + ByteArray(4)
    }

    /** A HUFF record whose 256 codes are all eight bits long and terminal. */
    private fun huffRecord(): ByteArray {
        val table1 = (0 until 256).map { u32((255 shl 8) or 0x80 or 8) }.fold(ByteArray(0)) { a, b -> a + b }
        return "HUFF".encodeToByteArray() + u32(24) + u32(24) + u32(24 + 1024) + ByteArray(8) + table1 + ByteArray(256)
    }

    private fun cdic(phrases: List<Pair<ByteArray, Boolean>>): ByteArray {
        val offsets = ArrayList<Int>()
        var body = ByteArray(0)
        for ((bytes, plain) in phrases) {
            offsets += phrases.size * 2 + body.size
            body += u16(bytes.size or (if (plain) 0x8000 else 0)) + bytes
        }
        return "CDIC".encodeToByteArray() + u32(16) + u32(phrases.size) + u32(8) +
            offsets.map(::u16).fold(ByteArray(0)) { a, b -> a + b } + body
    }
}
