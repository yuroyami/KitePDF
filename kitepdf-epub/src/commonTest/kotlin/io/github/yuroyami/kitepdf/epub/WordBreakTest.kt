package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteTextLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `word-break` and `-epub-word-break`: `break-all` and `keep-all` (CSS Text 3, 5.2, #508). */
class WordBreakTest {

    private val settings = EpubSettings(pageWidth = 400.0, pageHeight = 2000.0)

    /** The text of the W3C test book's `break-all` sample, which is meant to fill each line to the edge. */
    private val austen = "In the society of his nephew and niece, and their children, the old Gentleman’s days were " +
        "comfortably spent. His attachment to them all increased. The constant attention of Mr. and Mrs. Henry " +
        "Dashwood to his wishes, which proceeded not merely from interest, but from goodness of heart, gave him " +
        "every degree of solid comfort which his age could receive; and the cheerfulness of the children added " +
        "a relish to his existence."

    private val korean = "한국어 문장은 띄어쓰기로 낱말을 나누므로 줄은 낱말 사이에서 바뀌어야 읽기 편합니다 ".repeat(6).trim()

    private val japanese = "日本語の文章です。".repeat(12)

    private fun text(body: String) = EpubDocument.open(EpubFixtures.epub(body), settings).pages[0].textContent()

    private fun lines(body: String): List<KiteTextLine> = text(body).blocks.single().lines

    @Test
    fun break_all_fills_each_line_and_breaks_inside_words() {
        for (property in listOf("word-break", "-epub-word-break")) {
            val lines = lines("""<p style="$property:break-all">$austen</p>""")
            assertTrue(lines.size > 3, property)
            for (line in lines.dropLast(1)) {
                assertTrue(line.bounds.right <= 353.0, "$property: '${line.text}' ends at ${line.bounds.right}")
                assertTrue(line.bounds.right > 352.0 - 20.0, "$property: '${line.text}' fills its line, ends at ${line.bounds.right}")
            }
            val words = austen.split(' ').toSet()
            assertTrue(
                lines.any { l -> l.text.trim().split(' ').last() !in words },
                "$property: some line ends inside a word (${lines.map { it.text }})",
            )
        }
    }

    @Test
    fun break_all_never_starts_a_line_with_a_closing_mark_and_copies_whole() {
        // Many widths, so that some line would end just before a comma, a stop or an apostrophe.
        for (width in 200..420 step 3) {
            val text = EpubDocument.open(
                EpubFixtures.epub("""<p style="word-break:break-all">$austen</p>"""),
                EpubSettings(pageWidth = width.toDouble(), pageHeight = 4000.0, margin = 10.0),
            ).pages[0].textContent()
            for (line in text.blocks[0].lines) {
                val first = line.text.trimStart().first()
                assertTrue(first !in ".,;’", "width $width: '${line.text}' starts with a mark that closes the line before")
            }
            assertEquals(austen, text.copyText(0, text.charCount - 1), "width $width")
        }
    }

    @Test
    fun break_all_breaks_a_long_word_without_overflow_wrap() {
        val url = "https://example.com/a/very/long/path/that/never/ends/and/keeps/going/on/and/on/and/on/index.html"
        val lines = lines("""<p style="overflow-wrap:normal;word-break:break-all">$url</p>""")
        assertTrue(lines.size > 1, "${lines.map { it.text }}")
        for (line in lines) assertTrue(line.bounds.right <= 353.0, "'${line.text}' ends at ${line.bounds.right}")
    }

    @Test
    fun break_all_does_not_hyphenate() {
        val lines = lines("""<p lang="en" style="hyphens:auto;word-break:break-all">$austen</p>""")
        assertTrue(lines.none { it.text.trimEnd().endsWith("-") }, "${lines.map { it.text }}")
    }

    @Test
    fun keep_all_breaks_korean_only_between_words() {
        val words = korean.split(' ').toSet()
        val normal = lines("<p>$korean</p>")
        assertTrue(
            normal.any { l -> l.text.trim().split(' ').any { it !in words } },
            "without keep-all a Korean line breaks inside a word (${normal.map { it.text }})",
        )
        for (property in listOf("word-break", "-epub-word-break")) {
            val lines = lines("""<p style="$property:keep-all">$korean</p>""")
            assertTrue(lines.size > 2, property)
            for (line in lines) {
                assertTrue(line.text.trim().split(' ').all { it in words }, "$property: '${line.text}' holds a broken word")
                assertTrue(line.bounds.right <= 353.0, "$property: '${line.text}' ends at ${line.bounds.right}")
            }
        }
    }

    @Test
    fun keep_all_breaks_cjk_text_after_its_closing_marks() {
        val lines = lines("""<div style="word-break:keep-all"><p>$japanese</p></div>""")
        assertTrue(lines.size > 2, "${lines.map { it.text }}")
        for (line in lines) assertTrue(line.text.trimEnd().endsWith("。"), "'${line.text}' ends after a full stop")
    }

    @Test
    fun keep_all_breaks_before_an_opening_mark() {
        val sentence = "前の文です「引用の文です」"
        val lines = lines("""<p style="word-break:keep-all">${sentence.repeat(10)}</p>""")
        assertTrue(lines.size > 2)
        for (line in lines) {
            val t = line.text.trim()
            assertTrue(t.startsWith("前") || t.startsWith("「"), "'$t' starts at a word: a sentence or an opening bracket")
        }
    }

    @Test
    fun normal_sets_it_back() {
        val lines = lines("""<div style="word-break:break-all"><p style="word-break:normal">$austen</p></div>""")
        val words = austen.split(' ').toSet()
        assertTrue(lines.all { l -> l.text.trim().split(' ').all { it in words } }, "${lines.map { it.text }}")
    }
}
