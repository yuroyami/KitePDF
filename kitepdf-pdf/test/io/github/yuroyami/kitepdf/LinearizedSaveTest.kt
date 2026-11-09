package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.PdfOutlineItem
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A linearized save (ISO 32000-1, Annex F; #907) reads back as the document it was made from,
 * and its linearization dictionary describes the file: its length, its page count, its first
 * page and where that page's section ends. `LinearizedQpdfTest` has qpdf check the rest.
 */
@OptIn(KiteRawApi::class)
class LinearizedSaveTest {

    internal companion object {
        /** Five pages sharing a font, one with a page of its own font, an outline and a title. */
        fun document(): ByteArray = PdfBuilder()
            .apply {
                for (i in 1..5) page(300.0, 300.0) {
                    text(StandardFont.Helvetica, 12.0, 20.0, 250.0, "Page $i of five")
                    if (i == 3) text(StandardFont.Courier, 10.0, 20.0, 200.0, "Courier only here")
                }
            }
            .setInfo(title = "Linearized")
            .outline(listOf(PdfOutlineItem("First", pageIndex = 0), PdfOutlineItem("Third", pageIndex = 2, children = listOf(PdfOutlineItem("Fifth", pageIndex = 4)))))
            .build(compress = true)

        private val NUMBER = Regex("""/(L|O|E|N|T) (\d+)""")

        /** The values of the linearization dictionary, from the file's first kilobyte (F.2). */
        fun parameters(bytes: ByteArray): Map<String, Long> {
            val head = bytes.copyOf(minOf(1024, bytes.size)).decodeToString()
            val start = head.indexOf("/Linearized")
            check(start > 0) { "no linearization dictionary in the first kilobyte" }
            val dict = head.substring(start, head.indexOf(">>", start))
            return NUMBER.findAll(dict).associate { it.groupValues[1] to it.groupValues[2].toLong() }
        }
    }

    private fun assertReadsBack(bytes: ByteArray, password: String? = null) {
        val doc = if (password == null) PdfDocument.open(bytes) else PdfDocument.open(bytes, password = password.encodeToByteArray())
        assertEquals(5, doc.pages.size)
        for (i in 0 until 5) {
            val text = doc.pages[i].textContent().plainText
            assertTrue("Page ${i + 1} of five" in text, "page $i reads \"$text\"")
        }
        assertTrue("Courier only here" in doc.pages[2].textContent().plainText)
        assertEquals("Linearized", doc.metadata.title)
        assertEquals(listOf("First", "Third"), doc.outline.map { it.title })

        val p = parameters(bytes)
        assertEquals(bytes.size.toLong(), p["L"], "/L is the file length")
        assertEquals(5L, p["N"])
        assertEquals(doc.pages[0].reference?.objectNumber, p["O"], "/O is the first page object")
        val end = p.getValue("E").toInt()
        assertTrue(bytes.copyOfRange(end - 7, end).decodeToString() == "endobj\n", "/E ends an object")
        assertTrue(bytes.copyOfRange(p.getValue("T").toInt() - 4, p.getValue("T").toInt() + 1).decodeToString().startsWith("0 "), "/T is in the main cross-reference table's head")
    }

    @Test
    fun a_linearized_save_reads_back() {
        assertReadsBack(PdfDocument.open(document()).edit().saveLinearized())
    }

    @Test
    fun an_encrypted_document_stays_encrypted() {
        val locked = PdfDocument.open(document()).edit().apply { encrypt("pw", random = Random(4)) }.saveRewritten()
        val doc = PdfDocument.open(locked, password = "pw".encodeToByteArray())
        val bytes = doc.edit(Random(5)).saveLinearized()
        assertTrue(PdfDocument.open(bytes, password = "pw".encodeToByteArray()).isEncrypted)
        assertReadsBack(bytes, "pw")
    }

    @Test
    fun a_one_page_document() {
        val one = PdfBuilder().page(200.0, 200.0) { text(StandardFont.Helvetica, 12.0, 20.0, 100.0, "Alone") }.build()
        val bytes = PdfDocument.open(one).edit().saveLinearized()
        val doc = PdfDocument.open(bytes)
        assertEquals(1, doc.pages.size)
        assertTrue("Alone" in doc.pages[0].textContent().plainText)
        assertEquals(bytes.size.toLong(), parameters(bytes)["L"])
    }
}
