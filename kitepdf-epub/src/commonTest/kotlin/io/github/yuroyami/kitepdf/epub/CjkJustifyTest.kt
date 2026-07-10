package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.CssParser
import io.github.yuroyami.kitepdf.epub.css.Origin
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-71: spaceless CJK lines justify by inter-character expansion, and the
 * kinsoku opener rule keeps 「『（ etc. off line ends (they bind forward).
 * The closer set additionally covers small kana and the prolonged sound mark
 * (no line may start with them).
 */
class CjkJustifyTest {

    private fun layout(html: String, css: String = "", width: Double = 300.0): BlockBox {
        val tree = HtmlParser.parse(html)
        val rules = CssParser.parse(css, Origin.AUTHOR)
        val root = BoxBuilder(StyleResolver(rules, 12.0, width)) { it }.build(tree)
        BoxLayout(maxImageHeight = 500.0).layout(root, width)
        return root
    }

    private fun BlockBox.firstText(): TextBlockBox {
        var b: LayoutBox = this
        while (b is BlockBox) b = b.children.first()
        return b as TextBlockBox
    }

    private fun lineText(line: PositionedLine) =
        line.runs.filter { !it.isAnnotation }.flatMap { it.glyphs }.joinToString("") { it.text }

    /** Right edge of the line's painted content (pen position after the last glyph). */
    private fun lineEnd(line: PositionedLine): Double =
        line.runs.filter { !it.isAnnotation }.maxOf { r ->
            r.x + r.glyphs.sumOf { it.advanceWidth } * r.fontSize / 1000.0
        }

    @Test
    fun spaceless_cjk_lines_justify_to_content_width() {
        // 30 ideographs at 12pt (1em wide each = 12pt) on a 100pt-wide block:
        // 8 per line, several lines, no spaces anywhere.
        val text = "春夏秋冬山川草木花鳥風月雨雪雲霧星空海波光影".repeat(2)
        val p = layout("""<p style="text-align:justify; margin:0">$text</p>""", width = 100.0)
            .firstText()
        assertTrue(p.lines.size > 2, "fixture must wrap into several lines (got ${p.lines.size})")
        for ((i, line) in p.lines.withIndex()) {
            if (i == p.lines.lastIndex) continue
            val end = lineEnd(line)
            assertTrue(
                abs(end - 100.0) <= 0.5,
                "justified CJK line $i must fill the content width: end=$end",
            )
        }
    }

    @Test
    fun last_line_stays_ragged() {
        val text = "春夏秋冬山川草木花鳥風月雨雪".repeat(2)
        val p = layout("""<p style="text-align:justify; margin:0">$text</p>""", width = 100.0)
            .firstText()
        val lastEnd = lineEnd(p.lines.last())
        assertTrue(lastEnd < 99.0, "the paragraph's last line is not justified: end=$lastEnd")
    }

    @Test
    fun latin_spaceless_lines_are_not_stretched() {
        // A lone long Latin word on a justified line has no interior spaces
        // and no CJK cells: inter-character expansion must not touch it. Its
        // glyph advances at a narrow width must equal the unconstrained ones.
        val html = """<p style="text-align:justify; margin:0">supercalifragilistic anotherline follows here</p>"""
        val narrow = layout(html, width = 90.0).firstText()
        val wide = layout(html, width = 600.0).firstText()
        val word = "supercalifragilistic"
        val narrowLine = narrow.lines.first { lineText(it).trim() == word }
        val naturalAdvance = wide.lines.first().runs.first { !it.isAnnotation }
            .glyphs.filter { !it.isWordSpace }.take(word.length).sumOf { it.advanceWidth }
        val lineAdvance = narrowLine.runs.filter { !it.isAnnotation }
            .flatMap { it.glyphs }.filter { !it.isWordSpace }.sumOf { it.advanceWidth }
        assertTrue(
            abs(lineAdvance - naturalAdvance) < 1e-6,
            "Latin word must keep natural advances: $lineAdvance vs $naturalAdvance",
        )
    }

    @Test
    fun openers_never_end_a_line() {
        // Interleave openers so naive per-char breaking would strand some at
        // line ends across a range of widths.
        val text = "あい「うえお」かき『くけこ』さし（すせそ）たち【つてと】なに〈ぬねの〉はひ《ふへほ》まみ"
        for (width in listOf(60.0, 72.0, 84.0, 96.0, 108.0, 120.0)) {
            val p = layout("""<p style="margin:0">$text</p>""", width = width).firstText()
            for ((i, line) in p.lines.withIndex()) {
                val t = lineText(line)
                if (t.isEmpty()) continue
                val lastCp = t.last().code
                assertTrue(
                    lastCp !in setOf(0x300C, 0x300E, 0x3010, 0x3014, 0x3008, 0x300A, 0x3016, 0xFF08, 0xFF3B, 0xFF5B),
                    "width=$width line=$i ends with an opener: \"$t\"",
                )
            }
        }
    }

    @Test
    fun small_kana_and_prolonged_sound_mark_never_start_a_line() {
        val text = "コーヒーとチョコレートをゆっくり味わったあとでラーメンとギョーザっぽいものを食べたくなった"
        for (width in listOf(60.0, 72.0, 84.0, 96.0, 108.0)) {
            val p = layout("""<p style="margin:0">$text</p>""", width = width).firstText()
            for ((i, line) in p.lines.withIndex()) {
                val t = lineText(line)
                if (t.isEmpty()) continue
                val first = t.first()
                assertTrue(
                    first.code !in setOf(0x30FC, 0x3063, 0x3083, 0x3085, 0x3087, 0x30C3, 0x30E3, 0x30E5, 0x30E7),
                    "width=$width line=$i starts with no-break-before char: \"$t\"",
                )
            }
        }
    }

    @Test
    fun opener_binds_to_a_following_latin_word() {
        // （word must never split between （ and w.
        val text = "ああああああ（word）ああああああ"
        for (width in listOf(48.0, 60.0, 72.0, 84.0)) {
            val p = layout("""<p style="margin:0">$text</p>""", width = width).firstText()
            for (line in p.lines) {
                val t = lineText(line)
                assertTrue(!t.endsWith("（"), "width=$width: opener stranded at line end: \"$t\"")
            }
        }
    }
}
