package io.github.yuroyami.kitepdf.epub.convert

import io.github.yuroyami.kitepdf.core.zip.ZipReader
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.orSkip
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kindle books of the corpus against the EPUB they were made from (#948): Project
 * Gutenberg's Alice as KF8 (AZW3) and MOBI 6, both built by Ebookmaker with calibre. Each opens
 * with the EPUB's title, author, table of contents and text, and the KF8 book with its images
 * and every link resolved to an anchor that exists.
 */
class MobiCorpusTest {

    private fun repoFile(relative: String): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, relative).exists()) d = d.parentFile
        return d?.let { File(it, relative) }
    }

    private fun bytes(name: String) = repoFile("corpus/mobi/$name").orSkip("corpus/mobi/$name").readBytes()
    private fun words(doc: EpubDocument) = (0 until doc.pageCount).flatMap { doc.pages[it].textContent().plainText.split(Regex("\\s+")) }.filter { it.isNotEmpty() }

    /** The words of [reference] that [actual] lacks, counted with their multiplicity. */
    private fun missing(reference: List<String>, actual: List<String>): Int {
        val have = actual.groupingBy { it }.eachCount()
        return reference.groupingBy { it }.eachCount().entries.sumOf { (w, n) -> (n - (have[w] ?: 0)).coerceAtLeast(0) }
    }

    private fun checkAgainstEpub(name: String) {
        val epub = EpubDocument.open(bytes("gutenberg-11-alice.epub"))
        val doc = EpubDocument.openMobi(bytes(name))
        assertEquals("Alice's Adventures in Wonderland", doc.metadata.title)
        assertEquals(listOf("Lewis Carroll"), doc.metadata.authors)
        assertEquals("en", doc.metadata.language)
        assertEquals(epub.outline.map { it.title }, doc.outline.map { it.title }, "$name: the table of contents")

        // Reading every page lays the whole book out, so each contents entry then has its page.
        val reference = words(epub)
        val actual = words(doc)
        assertTrue(doc.outline.all { it.pageIndex != null }, "$name: every contents entry goes to a page")
        assertEquals(doc.outline.map { it.pageIndex }, doc.outline.map { it.pageIndex }.sortedBy { it }, "$name: the contents run in reading order")
        val lost = missing(reference, actual)
        // A word the two books break differently across a line, nothing more (the EPUB has 29,569).
        assertTrue(lost <= 30, "$name lost $lost of the EPUB's ${reference.size} words")
        assertTrue(actual.size < reference.size * 1.02, "$name has ${actual.size} words to the EPUB's ${reference.size}")
        val chapter12 = doc.outline.first { it.title.startsWith("CHAPTER XII.") }.pageIndex!!
        assertTrue(doc.pages[chapter12].textContent().plainText.contains("Alice’s Evidence"), "$name: chapter XII starts on its page")
    }

    @Test
    fun the_kf8_book_reads_as_its_epub() = checkAgainstEpub("gutenberg-11-alice.azw3")

    @Test
    fun the_mobi6_book_reads_as_its_epub() = checkAgainstEpub("gutenberg-11-alice.mobi")

    @Test
    fun the_kf8_package_has_its_images_styles_and_every_link_target() {
        val zip = ZipReader(MobiBook.convert(bytes("gutenberg-11-alice.azw3")))
        val parts = zip.names.filter { it.endsWith(".xhtml") && "part" in it }
        assertEquals(19, parts.size)
        assertTrue(zip.names.any { it.endsWith(".jpg") }, "the cover image: ${zip.names}")
        assertTrue(zip.names.any { it.endsWith(".css") }, "the style sheets: ${zip.names}")
        assertTrue(zip.readText("OEBPS/content.opf")!!.contains("properties=\"cover-image\""))
        val texts = parts.associate { it.removePrefix("OEBPS/") to zip.readText(it)!! }
        var links = 0
        for ((file, text) in texts) {
            assertTrue("kindle:" !in text, "$file keeps a kindle: reference")
            for (m in Regex("""href="(part\d+\.xhtml)#([^"]+)"""").findAll(text)) {
                links++
                val target = texts[m.groupValues[1]] ?: error("$file links to a missing part ${m.groupValues[1]}")
                assertTrue("id=\"${m.groupValues[2]}\"" in target, "$file links to #${m.groupValues[2]}, which ${m.groupValues[1]} lacks")
            }
            for (m in Regex("""src="([^"]+)"""").findAll(text)) assertTrue("OEBPS/${m.groupValues[1]}" in zip.names, "$file shows a missing ${m.groupValues[1]}")
        }
        assertTrue(links >= 12, "the contents page links to each chapter: $links links")
    }

    /**
     * A joint file: the MOBI 6 book, a BOUNDARY record and the KF8 book, which EXTH 121 names.
     * The reader takes the KF8 half. Made here by joining the two corpus books.
     */
    @Test
    fun a_joint_file_opens_its_kf8_half() {
        val mobi6 = records(bytes("gutenberg-11-alice.mobi"))
        val kf8 = records(bytes("gutenberg-11-alice.azw3"))
        val boundary = mobi6.size + 1
        val joint = listOf(withExth121(mobi6[0], boundary)) + mobi6.drop(1) + listOf("BOUNDARY".toByteArray()) + kf8
        val doc = EpubDocument.openMobi(pdb(joint))
        assertEquals(19, doc.chapterCount)
        // The KF8 half has the XHTML parts; the MOBI 6 half would have been cut into chapter files.
        val zip = ZipReader(MobiBook.convert(pdb(joint)))
        assertTrue(zip.names.any { it.endsWith("part0000.xhtml") }, "${zip.names}")
        assertTrue(zip.names.any { it.endsWith(".jpg") }, "the KF8 half finds its images: ${zip.names}")
    }

    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, p: Int) = (u16(b, p) shl 16) or u16(b, p + 2)
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun records(b: ByteArray): List<ByteArray> {
        val n = u16(b, 76)
        val starts = (0 until n).map { u32(b, 78 + it * 8) } + b.size
        return (0 until n).map { b.copyOfRange(starts[it], starts[it + 1]) }
    }

    private fun pdb(records: List<ByteArray>): ByteArray {
        val head = ByteArray(78)
        "joint".toByteArray().copyInto(head)
        "BOOKMOBI".toByteArray().copyInto(head, 60)
        head[76] = (records.size ushr 8).toByte(); head[77] = records.size.toByte()
        var offset = 78 + records.size * 8 + 2
        val out = java.io.ByteArrayOutputStream()
        out.write(head)
        for ((i, r) in records.withIndex()) { out.write(be32(offset)); out.write(be32(i * 2)); offset += r.size }
        out.write(ByteArray(2))
        for (r in records) out.write(r)
        return out.toByteArray()
    }

    /** Record 0 with an EXTH 121 record added, and the full name that follows the EXTH moved along. */
    private fun withExth121(r0: ByteArray, boundary: Int): ByteArray {
        val p = 16 + u32(r0, 20)
        val len = u32(r0, p + 4)
        val item = be32(121) + be32(12) + be32(boundary)
        val out = r0.copyOfRange(0, p + len) + item + r0.copyOfRange(p + len, r0.size)
        be32(len + 12).copyInto(out, p + 4)
        be32(u32(r0, p + 8) + 1).copyInto(out, p + 8)
        be32(u32(r0, 0x54) + 12).copyInto(out, 0x54)
        return out
    }
}
