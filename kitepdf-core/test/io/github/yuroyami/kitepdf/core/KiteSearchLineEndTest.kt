package io.github.yuroyami.kitepdf.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Search joins the lines of a block as copyText does, by how each line ended (#919). */
class KiteSearchLineEndTest {

    private fun line(text: String, y: Double, end: KiteLineEnd) =
        KiteTextLine(text, KiteRectangle(0.0, y, 10.0 * text.length, y + 10.0), DoubleArray(text.length + 1) { it * 10.0 }, end = end)

    private fun hits(a: String, end: KiteLineEnd, b: String, needle: String): Int {
        val text = KiteStructuredText(listOf(KiteTextBlock(listOf(line(a, 20.0, end), line(b, 0.0, KiteLineEnd.HARD)))))
        val all = text.copyText(0, text.charCount - 1)
        val found = text.search(needle).size
        if (found > 0 && end != KiteLineEnd.HARD) assertEquals(true, needle in all, "copy holds what search found: $all")
        return found
    }

    @Test
    fun eachLineEndJoinsAsCopyDoes() {
        assertEquals(1, hits("日本", KiteLineEnd.NONE, "語です", "日本語"))
        assertEquals(1, hits("well-", KiteLineEnd.NONE, "known", "well-known"))
        assertEquals(1, hits("compres-", KiteLineEnd.HYPHEN, "sion", "compression"))
        assertEquals(1, hits("hello", KiteLineEnd.SPACE, "world", "hello world"))
        assertEquals(0, hits("hello", KiteLineEnd.SPACE, "world", "helloworld"))
    }

    @Test
    fun aFixedPageKeepsItsJoining() {
        assertEquals(1, hits("compres-", KiteLineEnd.HARD, "sion", "compression"))
        assertEquals(1, hits("hello", KiteLineEnd.HARD, "world", "hello world"))
    }

    @Test
    fun aLigatureMatchesItsLetters() {
        // #807: "find" on a page that draws U+FB01, and its hit covers the ligature.
        val text = KiteStructuredText(listOf(KiteTextBlock(listOf(line("\uFB01nd the \uFB02ow", 0.0, KiteLineEnd.HARD)))))
        val hits = text.search("find")
        assertEquals(1, hits.size)
        assertEquals(1, text.search("flow").size)
        assertEquals(1, text.search("\uFB01nd").size, "a needle with the ligature still matches")
        assertEquals(0.0, hits[0].quads.single().left, 1e-9)
        assertEquals(30.0, hits[0].quads.single().right, 1e-9)
    }
}
