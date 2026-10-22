package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Issue #6: no breakable line may end past the right content edge. The wrap
 * budget used to ignore text-indent, so a packed first line stuck out by up
 * to the indent width; past a thin margin that crossed the page bitmap edge
 * and the final glyphs were clipped.
 */
class LineOverflowTest {

    private val paras = listOf(
        "Он крепко держал " +
            "руль и смотрел на " +
            "дорогу впереди где " +
            "туман медленно " +
            "поднимался над рекой.",
        "The quick brown fox jumps over the lazy dog while the miller watches from the old stone bridge near the water.",
        "Alle Menschen sind frei und gleich an Wuerde und Rechten geboren und sollen einander im Geiste der Bruederlichkeit begegnen stets.",
        "Il faisait beau ce matin quand nous sommes partis vers la montagne avec nos sacs et nos chansons anciennes.",
    )

    private fun openDoc(css: String, width: Double, fontSize: Double, margin: Double, body: String): EpubDocument =
        EpubDocument.open(
            EpubFixtures.epub("<style>$css</style>$body"),
            EpubSettings(pageWidth = width, pageHeight = 640.0, fontSize = fontSize, margin = margin),
        )

    /** Worst right-edge overshoot past the content edge, with the guilty line. */
    private fun worstOverflow(css: String, width: Double, fontSize: Double, margin: Double = 24.0): Pair<Double, String> {
        val doc = openDoc(css, width, fontSize, margin, paras.joinToString("") { "<p>$it</p>" })
        val contentRight = width - margin
        var worst = 0.0
        var line = ""
        for (page in doc.pages) {
            for (block in page.textContent().blocks) {
                for (l in block.lines) {
                    val over = l.charEdges.last() - contentRight
                    if (over > worst) { worst = over; line = l.text }
                }
            }
        }
        return worst to line
    }

    @Test
    fun the_audits_worst_case_stays_inside_the_content_box() {
        // The exact worst case the audit sweep found: 19.74pt overflow before the fix.
        val (over, line) = worstOverflow(
            css = "p { text-indent: 2em; text-align: justify; }",
            width = 316.0, fontSize = 20.0,
        )
        assertTrue(over <= 0.01, "line ends ${over}pt past the content edge: '$line'")
    }

    @Test
    fun no_line_overflows_across_widths_indents_and_sizes() {
        var worst = 0.0
        var detail = ""
        for (width in 300..440 step 8) {
            for (indentEm in listOf(0.0, 1.0, 1.5, 2.0)) {
                for (fontSize in listOf(14.0, 17.0, 20.0)) {
                    val (over, line) = worstOverflow(
                        css = "p { text-indent: ${indentEm}em; text-align: justify; }",
                        width = width.toDouble(), fontSize = fontSize,
                    )
                    if (over > worst) { worst = over; detail = "w=$width indent=$indentEm fs=$fontSize '$line'" }
                }
            }
        }
        assertTrue(worst <= 0.01, "worst overflow ${worst}pt at $detail")
    }

    @Test
    fun no_glyph_crosses_the_page_bitmap_edge_even_at_thin_margins() {
        // The reporter's clipping: the page canvas is exactly pageWidth wide,
        // so an edge past pageWidth IS clipped ink. Before the fix, width 332
        // at 2em pushes an edge 14.74pt past the content edge, which is 2.74pt
        // past the bitmap edge itself (the red run must show that message);
        // after the fix everything sits inside the content box.
        val failures = StringBuilder()
        for (width in listOf(300.0, 316.0, 332.0)) {
            for (indentEm in listOf(1.5, 2.0)) {
                val (over, line) = worstOverflow(
                    css = "p { text-indent: ${indentEm}em; text-align: justify; }",
                    width = width, fontSize = 20.0, margin = 12.0,
                )
                if (over > 0.01) {
                    failures.append("w=$width indent=$indentEm: ${over}pt past the content edge")
                        .append(if (over > 12.0) " (past the BITMAP edge by ${over - 12.0}pt)" else "")
                        .append(": '$line'\n")
                }
            }
        }
        assertTrue(failures.isEmpty(), "\n$failures")
    }

    @Test
    fun a_line_after_an_explicit_break_is_not_a_first_line() {
        val css = "p { text-indent: 2em; }"
        val doc = openDoc(css, 316.0, 20.0, 24.0, "<p>Первая строка<br>${paras[0]}</p>")
        val indent = 2 * 20.0
        val lines = doc.pages.flatMap { it.textContent().blocks }.flatMap { it.lines }
        assertTrue(lines.size >= 3, "fixture must wrap past the break")
        val first = lines.first()
        val afterBreak = lines[1]
        // Placement: only the true first line is shifted by the indent. The
        // block itself may sit at any x, so compare the two lines to each
        // other rather than to an absolute edge.
        val shift = first.charEdges.first() - afterBreak.charEdges.first()
        assertTrue(
            shift in indent - 0.01..indent + 0.01,
            "only line 0 carries the indent: expected a ${indent}pt shift, got $shift",
        )
        // Budget: nothing overflows either way.
        val contentRight = 316.0 - 24.0
        for (l in lines) assertTrue(l.charEdges.last() <= contentRight + 0.01, "'${l.text}' overflows")
    }

    @Test
    fun a_hanging_indent_keeps_its_budget_and_never_overflows_right() {
        val (over, _) = worstOverflow(
            css = "p { padding-left: 2em; text-indent: -1.5em; text-align: justify; }",
            width = 316.0, fontSize = 20.0,
        )
        assertTrue(over <= 0.01, "hanging indent overflowed right by ${over}pt")
    }

    @Test
    fun an_oversized_indent_degenerates_without_breaking_later_lines() {
        // Indent wider than the line box: the first line's budget floors at
        // 1pt and its single token may overflow (the documented unbreakable
        // exception). Every LATER line must still behave.
        val doc = openDoc(
            "p { text-indent: 40em; }", 316.0, 20.0, 24.0,
            "<p>${paras[1]}</p>",
        )
        val contentRight = 316.0 - 24.0
        val lines = doc.pages.flatMap { it.textContent().blocks }.flatMap { it.lines }
        assertTrue(lines.size >= 2, "fixture must produce later lines")
        for (l in lines.drop(1)) {
            assertTrue(l.charEdges.last() <= contentRight + 0.01, "later line '${l.text}' overflows")
        }
    }

    @Test
    fun floats_and_indent_compose_on_the_first_line() {
        val doc = openDoc(
            "div.f { float: left; width: 60pt; height: 40pt; } p { text-indent: 2em; text-align: justify; }",
            316.0, 20.0, 24.0,
            "<div class=\"f\"></div>" + paras.joinToString("") { "<p>$it</p>" },
        )
        val contentRight = 316.0 - 24.0
        for (page in doc.pages) for (b in page.textContent().blocks) for (l in b.lines) {
            assertTrue(l.charEdges.last() <= contentRight + 0.01, "float+indent overflow on '${l.text}'")
        }
    }

    @Test
    fun rtl_first_lines_respect_the_content_box_too() {
        val doc = openDoc(
            "p { direction: rtl; text-indent: 2em; text-align: justify; }",
            316.0, 20.0, 24.0,
            "<p>שלום עולם זהו משפט ארוך למדי שנועד לבדוק גלישת שורות בכיוון ימין לשמאל עם הזחה בשורה הראשונה של הפסקה</p>",
        )
        val contentRight = 316.0 - 24.0
        for (page in doc.pages) for (b in page.textContent().blocks) for (l in b.lines) {
            assertTrue(l.charEdges.last() <= contentRight + 0.01, "RTL overflow on '${l.text}'")
        }
    }
}
