package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.text.search
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Text that a page draws twice in one place reads once (#642, #643). Most labels here carry
 * their size in the text matrix with `Tf` at 1, as the aeronautical charts of the reports do (#23).
 */
class RedrawnTextTest {

    /** A page of [content] with Helvetica as /F1 and Helvetica-Bold as /F2. */
    private fun page(content: String): PdfPage = PdfDocument.open(
        RawPdf.page(
            content.encodeToByteArray(),
            resources = "<< /Font << /F1 4 0 R /F2 6 0 R >> >>",
            extra = listOf(RawPdf.obj(6, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>")),
        ),
    ).pages[0]

    private fun lines(content: String): List<String> =
        page(content).structuredText.blocks.flatMap { block -> block.lines.map { it.text } }

    /** One text object that shows [text] from [x], [y] in render mode [mode]. */
    private fun label(text: String, x: Double, y: Double, mode: Int = 0, font: String = "F2", size: Double = 8.0) =
        "BT $mode Tr /$font 1 Tf $size 0 0 $size $x $y Tm ($text) Tj ET\n"

    @Test
    fun a_label_filled_and_then_stroked_reads_once() {
        // #642: a fake bold strokes each label over its fill. Since #274 the stroke reaches the
        // text layer too, and the line read "LFLX/CHR CHATEAUROUX, FRANCECHATEAUROUX, FRANCE".
        val content = label("LFLX/CHR", 72.0, 760.0, font = "F1") + label("CHATEAUROUX, FRANCE", 200.0, 760.0) +
            "0.2 w\n" + label("CHATEAUROUX, FRANCE", 200.0, 760.0, mode = 1)
        assertEquals(listOf("LFLX/CHR CHATEAUROUX, FRANCE"), lines(content))
    }

    @Test
    fun a_pass_in_any_render_mode_under_a_fill_reads_once() {
        // A halo strokes the label wide in white under its fill, and an OCR layer lays it there
        // invisible (ISO 32000-1, 9.3.6). Each mode reaches the text layer, and each folds into one.
        for (mode in 1..7) {
            val content = "1 1 1 RG 2 w\n" + label("01-45", 200.0, 700.0, mode = mode) + "0 0 0 rg\n" + label("01-45", 200.0, 700.0)
            assertEquals(listOf("01-45"), lines(content), "render mode $mode")
        }
    }

    @Test
    fun a_label_filled_twice_reads_once() {
        // #643: the second fill lies on the first, or a fraction of a point aside for a fake bold.
        for ((dx, dy) in listOf(0.0 to 0.0, 0.3 to 0.0, 0.3 to 0.3, -0.5 to -0.2)) {
            val content = label("TARBES, FRANCE", 200.0, 760.0) + label("TARBES, FRANCE", 200.0 + dx, 760.0 + dy)
            assertEquals(listOf("TARBES, FRANCE"), lines(content), "second pass at $dx, $dy")
        }
    }

    @Test
    fun search_finds_a_redrawn_label_once() {
        val page = page(label("27 OCT 23", 200.0, 760.0) + "0.2 w\n" + label("27 OCT 23", 200.0, 760.0, mode = 1))
        assertEquals(1, page.search("27 OCT 23").size)
    }

    @Test
    fun a_pass_that_splits_the_label_into_other_runs_reads_once() {
        // The first pass places each letter on its own, the second shows the word, and a third
        // shows it through TJ in two strings: the glyphs match one by one, whatever the runs.
        val widths = mapOf('D' to 722, 'E' to 667, 'O' to 778, 'L' to 611, 'S' to 667) // Helvetica-Bold
        var x = 200.0
        val letters = StringBuilder()
        for (c in "DEOLS") {
            letters.append(label("$c", x, 760.0))
            x += widths.getValue(c) * 8.0 / 1000.0
        }
        val content = letters.toString() + "0.2 w\n" + label("DEOLS", 200.0, 760.0, mode = 1) +
            "BT 1 Tr /F2 1 Tf 8 0 0 8 200 760 Tm [(DE) (OLS)] TJ ET\n"
        assertEquals(listOf("DEOLS"), lines(content))
    }

    @Test
    fun a_longer_pass_keeps_only_the_text_the_first_did_not_draw() {
        val text = page(label("TARBES", 200.0, 760.0) + "0.2 w\n" + label("TARBES, FRANCE", 200.0, 760.0, mode = 1)).structuredText
        assertEquals("TARBES, FRANCE", text.plainText)
        // The piece that stays starts at its comma, after "TARBES" in Helvetica-Bold.
        val piece = text.spans.single { it.text.startsWith(",") }
        assertEquals(", FRANCE", piece.text)
        assertEquals(200.0 + (611 + 722 + 722 + 722 + 667 + 667) * 8.0 / 1000.0, piece.origin.first, 1e-9)
        assertEquals(piece.origin.first, piece.charEdgePoints!!.first().first, 1e-9)
    }

    @Test
    fun the_same_label_elsewhere_reads_twice() {
        // No second pass: one label in two places, and one label right after another on a line.
        assertEquals(listOf("46-53", "46-53"), lines(label("46-53", 200.0, 700.0) + label("46-53", 300.0, 600.0)))
        assertEquals(listOf("46-5346-53"), lines(label("46-53", 200.0, 700.0) + label("46-53", 220.5, 700.0)))
    }

    @Test
    fun other_text_another_size_or_another_direction_at_one_origin_stays() {
        assertEquals(listOf("ABCXYZ"), lines(label("ABC", 200.0, 760.0) + label("XYZ", 200.0, 760.0)))
        // A label half as large again starts where the small one does, but none of its letters is
        // a second pass of the small one, so its D stays too.
        val sizes = page(label("DEOLS", 200.0, 760.0) + label("DEOLS", 200.0, 760.0, size = 12.0)).structuredText
        assertEquals(listOf("DEOLS", "DEOLS"), sizes.spans.map { it.text })
        // The same label turned a quarter turn about the same origin.
        val turned = page(label("DEOLS", 200.0, 500.0) + "BT /F2 1 Tf 0 8 -8 0 200 500 Tm (DEOLS) Tj ET\n").structuredText
        assertEquals(listOf("DEOLS", "DEOLS"), turned.spans.map { it.text })
    }

    @Test
    fun letters_of_one_run_never_match_each_other() {
        // A tracking of -1.5 sets the two l's of a run 0.28 points apart at 8 points, closer than a
        // tenth of an em, yet each run shows each of its letters once. A page of one run is not
        // compared at all, so this one has two.
        val content = "BT /F1 8 Tf -1.5 Tc 1 0 0 1 72 600 Tm (all) Tj ET\nBT /F1 8 Tf -1.5 Tc 1 0 0 1 72 580 Tm (ill) Tj ET\n"
        assertEquals(listOf("all", "ill"), lines(content))
    }

    @Test
    fun a_space_never_matches_one_beside_it() {
        // As on the right-to-left lines of public-wikimedia-editing-arabic-wikipedia.pdf: the space
        // before a word is drawn first, and the space after a dot leader 1.2 points from it later.
        // Neither is a second pass, and the word must not join the leader.
        val content = label(" ", 101.2, 500.0, font = "F1", size = 14.0) + label("word", 101.1, 500.0, font = "F1", size = 14.0) +
            label(" ", 100.0, 500.0, font = "F1", size = 14.0) + label(".....", 100.0 - 5 * 278 * 14.0 / 1000.0, 500.0, font = "F1", size = 14.0)
        assertEquals(listOf("..... word"), lines(content))
    }

    @Test
    fun a_pass_in_another_font_drops_out_whole_or_not_at_all() {
        // Helvetica sets "abcd" narrower than Helvetica-Bold: its a, b and c lie on the bold ones,
        // but its d starts 0.89 points from the bold d. Half of the run would read as noise.
        val drift = page(label("abcd", 200.0, 760.0) + label("abcd", 200.0, 760.0, font = "F1")).structuredText
        assertEquals(listOf("abcd", "abcd"), drift.spans.map { it.text })
        // In capitals the two fonts differ only at L, so each glyph of the second pass lies on one
        // of the first, and the pass drops out whole.
        assertEquals(listOf("DEOLS"), lines(label("DEOLS", 200.0, 760.0) + label("DEOLS", 200.0, 760.0, font = "F1")))
    }

    @Test
    fun two_passes_with_their_own_actual_text_read_once() {
        // Each pass carries the same /ActualText (ISO 32000-1, 14.9.4), which replaces its text.
        val content = "/Span << /ActualText (Tarbes) >> BDC\n" + label("TARBES", 200.0, 760.0) + "EMC\n" +
            "/Span << /ActualText (Tarbes) >> BDC\n0.2 w\n" + label("TARBES", 200.0, 760.0, mode = 1) + "EMC\n"
        assertEquals(listOf("Tarbes"), lines(content))
    }

    @Test
    fun a_tagged_page_reads_a_redrawn_label_once_in_its_sequence() {
        // The reading order of a tagged page reads the runs of each marked-content sequence (#208).
        fun readingOrder(content: String) = PdfDocument.open(
            RawPdf.page(
                content.encodeToByteArray(),
                catalogExtra = "/StructTreeRoot 6 0 R /MarkInfo << /Marked true >>",
                extra = listOf(
                    RawPdf.obj(6, "<< /Type /StructTreeRoot /K [7 0 R 8 0 R] >>"),
                    RawPdf.obj(7, "<< /Type /StructElem /S /P /Pg 3 0 R /K 0 >>"),
                    RawPdf.obj(8, "<< /Type /StructElem /S /P /Pg 3 0 R /K 1 >>"),
                ),
            ),
        ).pages[0].readingOrder().map { it.text }
        val twice = "/P << /MCID 0 >> BDC\n" + label("TARBES", 200.0, 700.0, font = "F1") + "0.2 w\n" +
            label("TARBES", 200.0, 700.0, mode = 1, font = "F1") + "EMC\n"
        assertEquals(listOf("TARBES", "FRANCE"), readingOrder(twice + "/P << /MCID 1 >> BDC\n" + label("FRANCE", 200.0, 680.0, font = "F1") + "EMC\n"))
        // A sequence of its own keeps its text, though another drew the same label in its place.
        assertEquals(listOf("TARBES", "TARBES"), readingOrder(twice + "/P << /MCID 1 >> BDC\n" + label("TARBES", 200.0, 700.0, font = "F1") + "EMC\n"))
    }
}
