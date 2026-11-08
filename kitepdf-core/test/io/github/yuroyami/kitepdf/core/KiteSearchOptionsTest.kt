package io.github.yuroyami.kitepdf.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Search by whole words and with diacritics ignored (#943). */
class KiteSearchOptionsTest {

    private fun page(text: String) = KiteStructuredText(
        listOf(KiteTextBlock(listOf(KiteTextLine(text, KiteRectangle(0.0, 0.0, 10.0 * text.length, 10.0), DoubleArray(text.length + 1) { it * 10.0 })))),
    )

    @Test
    fun aWholeWordNeedsNoLetterOrDigitBesideIt() {
        val text = page("start art, art and art2")
        val words = KiteSearchOptions(wholeWord = true)
        assertEquals(listOf("art", "art"), text.search("art", words).map { it.text }, "art and art, but not start or art2")
        assertEquals(4, text.search("art").size, "the default finds every art")
        // A script written without spaces has no boundary to find.
        assertEquals(1, page("日本語です").search("日本", words).size)
    }

    @Test
    fun diacriticsCanBeIgnored() {
        val precomposed = page("résumé sent")
        val decomposed = page("résumé sent")
        val folded = KiteSearchOptions(ignoreDiacritics = true)
        for ((name, text) in listOf("precomposed" to precomposed, "decomposed" to decomposed)) {
            val hits = text.search("resume", folded)
            assertEquals(1, hits.size, name)
            // The hit covers the whole word, its last mark included.
            assertEquals(0.0, hits[0].quads.single().left, 1e-9, name)
            val word = if (text === decomposed) 8 else 6
            assertEquals(word * 10.0, hits[0].quads.single().right, 1e-9, name)
            assertEquals(0, text.search("resume").size, "$name: the default counts diacritics")
        }
        // The needle folds too, and Malmö matches Malmo.
        assertEquals(1, page("Malmo").search("Malmö", folded).size)
        assertEquals(1, page("Malmö").search("malmo", folded).size)
        // Both options together.
        assertEquals(0, page("résumés").search("resume", KiteSearchOptions(wholeWord = true, ignoreDiacritics = true)).size)
    }
}
