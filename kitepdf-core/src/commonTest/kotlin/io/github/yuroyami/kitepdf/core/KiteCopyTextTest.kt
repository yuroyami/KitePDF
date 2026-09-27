package io.github.yuroyami.kitepdf.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Copied text joins the lines of a block as the layout broke them: a wrapped paragraph copies as
 * one line, a hyphenated word copies whole, and a break the text holds stays a break (#438).
 */
class KiteCopyTextTest {

    private fun line(text: String, end: KiteLineEnd = KiteLineEnd.HARD) = KiteTextLine(
        text, KiteRectangle(0.0, 0.0, text.length.toDouble(), 1.0), DoubleArray(text.length + 1) { it.toDouble() }, end = end,
    )

    private val book = KiteStructuredText(
        listOf(
            KiteTextBlock(
                listOf(
                    line("The quick", KiteLineEnd.SPACE),
                    line("brown hy-", KiteLineEnd.HYPHEN),
                    line("phenated fox", KiteLineEnd.HARD),
                    line("日本", KiteLineEnd.NONE),
                    line("語 end"),
                ),
            ),
            KiteTextBlock(listOf(line("Next block"))),
        ),
    )

    @Test
    fun a_wrapped_block_copies_as_the_text_holds_it() {
        assertEquals("The quick brown hyphenated fox\n日本語 end\n\nNext block", book.copyText(0, book.charCount - 1))
        assertEquals(
            "The quick\nbrown hy-\nphenated fox\n日本\n語 end\n\nNext block",
            book.textRange(0, book.charCount - 1),
            "textRange keeps the lines as the layout drew them",
        )
    }

    @Test
    fun an_added_hyphen_is_never_copied() {
        // "brown hy-" is chars 9 to 17; a range that ends on the hyphen leaves it out too.
        assertEquals("brown hy", book.copyText(9, 17))
        assertEquals("brown hyphenated", book.copyText(9, 25))
    }

    @Test
    fun lines_that_end_hard_keep_their_breaks() {
        val page = KiteStructuredText(listOf(KiteTextBlock(listOf(line("first line"), line("second-"), line("line")))))
        assertEquals(page.textRange(0, page.charCount - 1), page.copyText(0, page.charCount - 1))
    }
}
