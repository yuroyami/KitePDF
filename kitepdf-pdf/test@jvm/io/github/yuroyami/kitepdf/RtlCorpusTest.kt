package io.github.yuroyami.kitepdf

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The Arabic Wikipedia booklet reads in logical order, as Poppler's pdftotext gives it (#913). */
class RtlCorpusTest {

    @Test
    fun theBookletTitleReadsInLogicalOrder() {
        val file = listOf("corpus/pdf/public-wikimedia-editing-arabic-wikipedia.pdf", "../corpus/pdf/public-wikimedia-editing-arabic-wikipedia.pdf").map(::File).firstOrNull { it.exists() }
        assumeTrue("the Arabic Wikipedia corpus PDF is not here; run ./kotlin check corpus", file != null)
        val page = PdfDocument.open(file!!.readBytes()).pages[1]
        val lines = page.structuredText.blocks.flatMap { it.lines }.map { it.text }
        // pdftotext -f 2 -l 2: "اسم الكتيب" ("booklet title") and "التحرير يف ويكيبيديا العربية".
        assertTrue(lines.any { "اسم الكتيب" in it }, lines.joinToString("\n"))
        val text = page.extractText()
        assertTrue("ويكيبيديا العربية" in text, text)
        assertTrue("اسم الكت" in text, text)
    }
}
