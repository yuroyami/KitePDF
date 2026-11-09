package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** A PDF page gives its words, each with bounds that a point on it finds again (#639). */
class TextWordsTest {

    @Test
    fun a_pdf_page_gives_its_words() {
        val doc = PdfDocument.open(
            PdfBuilder().page(width = 300.0, height = 200.0) {
                text(StandardFont.Helvetica, 12.0, 20.0, 150.0, "Hello, world.")
            }.build(),
        )
        val text = assertNotNull(doc.pages[0].textContent())
        val words = text.words()
        assertEquals(listOf("Hello", "world"), words.map { it.text })
        val world = words[1]
        val centre = text.wordAt((world.bounds.left + world.bounds.right) / 2, (world.bounds.bottom + world.bounds.top) / 2)
        assertEquals("world", centre?.text)
    }
}
