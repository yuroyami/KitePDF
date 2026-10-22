package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertTrue

/** `line-break` and `-epub-line-break`: which characters may start a line (CSS Text 3, 5.3, #508). */
class LineBreakTest {

    private val kana = "\u7F8E\u3057\u3044\u306A\u3041\u3068\u601D\u308F\u305A\u3064\u3076\u3084\u3044\u305F\u3002\u30B3\u30FC\u30D2\u30FC\u3092\u98F2\u307F\u307E\u3057\u305F\u3002\u6728\u3005\u304C\u8F1D\u3044\u3066\u3044\u305F\u3001".repeat(3)

    private val dots = "\u6771\u4EAC\u30FB\u5927\u962A\u30FB\u540D\u53E4\u5C4B\u30FB\u798F\u5CA1\u30FB\u672D\u5E4C\u30FB\u4ED9\u53F0\uFF01".repeat(4)

    /** The first character of every line after the first, over many widths of the page. */
    private fun starts(body: String): Set<Char> {
        val out = HashSet<Char>()
        for (width in 90..400 step 3) {
            val text = EpubDocument.open(EpubFixtures.epub(body), EpubSettings(pageWidth = width.toDouble(), pageHeight = 4000.0, margin = 10.0))
                .pages[0].textContent()
            for (block in text.blocks) for (line in block.lines.drop(1)) line.text.trimStart().firstOrNull()?.let(out::add)
        }
        return out
    }

    private fun starts(rule: String, text: String, property: String = "line-break") =
        starts("""<p lang="ja" style="$property:$rule">$text</p>""")

    @Test
    fun strict_and_auto_keep_small_kana_iteration_marks_and_middle_dots_off_the_start() {
        for (rule in listOf("strict", "auto")) {
            val s = starts(rule, kana) + starts(rule, dots)
            for (c in "\u3041\u30FC\u3005\u30FB\uFF01\u3002\u3001") assertTrue(c !in s, "$rule: a line starts with $c ($s)")
        }
    }

    @Test
    fun normal_lets_small_kana_start_a_line_but_not_iteration_marks_or_middle_dots() {
        val s = starts("normal", kana) + starts("normal", dots)
        assertTrue('\u3041' in s || '\u30FC' in s, "normal: no line starts with a small kana or a long vowel mark ($s)")
        for (c in "\u3005\u30FB\uFF01\u3002\u3001") assertTrue(c !in s, "normal: a line starts with $c ($s)")
    }

    @Test
    fun loose_lets_iteration_marks_and_middle_dots_start_a_line_but_not_a_stop() {
        for (property in listOf("line-break", "-epub-line-break")) {
            val s = starts("loose", kana, property) + starts("loose", dots, property)
            assertTrue('\u3005' in s, "$property loose: no line starts with \u3005 ($s)")
            assertTrue('\u30FB' in s || '\uFF01' in s, "$property loose: no line starts with a middle dot ($s)")
            for (c in "\u3002\u3001") assertTrue(c !in s, "$property loose: a line starts with $c ($s)")
        }
    }

    @Test
    fun anywhere_breaks_between_any_two_characters() {
        val s = starts("anywhere", kana)
        assertTrue('\u3002' in s || '\u3001' in s, "anywhere: no line starts with a stop ($s)")
        val word = "internationalization".repeat(3)
        val lines = EpubDocument.open(
            EpubFixtures.epub("""<p style="overflow-wrap:normal;line-break:anywhere">Some $word text</p>"""),
            EpubSettings(pageWidth = 200.0, pageHeight = 2000.0, margin = 10.0),
        ).pages[0].textContent().blocks.single().lines
        assertTrue(lines.size > 2, "${lines.map { it.text }}")
        assertTrue(lines.first().text.startsWith("Some i"), "the line before the word fills with it: ${lines.map { it.text }}")
        assertTrue(lines.none { it.text.trimEnd().endsWith("-") }, "${lines.map { it.text }}")
    }

    @Test
    fun it_inherits_and_an_unknown_value_leaves_it() {
        val s = starts("""<div style="line-break:loose"><p lang="ja" style="line-break:bogus">$kana</p></div>""")
        assertTrue('\u3005' in s, "the paragraph keeps the loose rule of its parent ($s)")
    }
}
