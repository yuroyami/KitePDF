package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.content.ContentStreamParser
import io.github.yuroyami.kitepdf.text.TextExtractor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [PdfPage.extractText] reads a label that the page draws twice in one place once (#644), as
 * [RedrawnTextTest] checks for the structured text (#642, #643). Most labels here carry their
 * size in the text matrix with `Tf` at 1, as the aeronautical charts of the reports do (#23).
 */
class TextExtractorRedrawnTest {

    /**
     * The plain text of a page of [content], with Helvetica as /F1, Helvetica-Bold as /F2 and
     * the entries of [fonts] and [xobjects], whose objects are in [extra].
     */
    private fun text(
        content: String,
        fonts: String = "",
        xobjects: String = "",
        extra: List<Pair<Int, ByteArray>> = emptyList(),
    ): String = PdfDocument.open(
        RawPdf.page(
            content.encodeToByteArray(),
            resources = "<< /Font << /F1 4 0 R /F2 6 0 R $fonts >> $xobjects >>",
            extra = listOf(RawPdf.obj(6, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>")) + extra,
        ),
    ).pages[0].extractText()

    /** One text object that shows [text] from [x], [y] in render mode [mode]. */
    private fun label(text: String, x: Double, y: Double, mode: Int = 0, font: String = "F2", size: Double = 8.0) =
        "BT $mode Tr /$font 1 Tf $size 0 0 $size $x $y Tm ($text) Tj ET\n"

    /** A form XObject numbered [number] that draws [content] with /F2, placed by [matrix]. */
    private fun form(number: Int, content: String, matrix: String = "1 0 0 1 0 0") = RawPdf.obj(
        number,
        "<< /Type /XObject /Subtype /Form /BBox [0 0 200 20] /Matrix [$matrix] /Resources << /Font << /F2 6 0 R >> >> >>",
        content.encodeToByteArray(),
    )

    @Test
    fun a_label_filled_and_then_stroked_reads_once() {
        // #642: a fake bold strokes each label over its fill, in a text object of its own.
        val content = label("LFLX/CHR", 72.0, 760.0, font = "F1") + label("CHATEAUROUX, FRANCE", 200.0, 760.0) +
            "0.2 w\n" + label("CHATEAUROUX, FRANCE", 200.0, 760.0, mode = 1)
        assertEquals("LFLX/CHR\nCHATEAUROUX, FRANCE", text(content))
    }

    @Test
    fun a_label_filled_twice_reads_once_and_leaves_no_blank_line() {
        // #643: the second fill lies on the first, or a fraction of a point aside. The line break
        // before the skipped pass and the one after it make one break.
        for ((dx, dy) in listOf(0.0 to 0.0, 0.3 to 0.0, 0.3 to 0.3, -0.5 to -0.2)) {
            val content = label("TARBES, FRANCE", 200.0, 760.0) + label("TARBES, FRANCE", 200.0 + dx, 760.0 + dy) +
                label("27 OCT 23", 200.0, 740.0)
            assertEquals("TARBES, FRANCE\n27 OCT 23", text(content), "second pass at $dx, $dy")
        }
    }

    @Test
    fun a_halo_in_any_render_mode_under_the_fill_reads_once() {
        // A halo strokes the label wide in white under its fill, and every render mode is text
        // all the same (ISO 32000-1, 9.3.6).
        for (mode in 1..7) {
            val content = "1 1 1 RG 2 w\n" + label("01-45", 200.0, 700.0, mode = mode) + "0 0 0 rg\n" + label("01-45", 200.0, 700.0)
            assertEquals("01-45", text(content), "render mode $mode")
        }
    }

    @Test
    fun halos_drawn_before_every_label_read_once() {
        // A chart can draw all its halos first and all its labels after them.
        val labels = listOf("01-45" to 700.0, "01-46" to 680.0, "46-53" to 660.0)
        val content = "1 1 1 RG 2 w\n" + labels.joinToString("") { (t, y) -> label(t, 200.0, y, mode = 1) } +
            "0 0 0 rg\n" + labels.joinToString("") { (t, y) -> label(t, 200.0, y) }
        assertEquals("01-45\n01-46\n46-53", text(content))
    }

    @Test
    fun a_pass_placed_by_the_ctm_or_by_a_form_reads_once() {
        // Both passes start at 0 0 in text space, and the CTM places them 0.3 points apart.
        val moved = "q 1 0 0 1 200 760 cm\n" + label("TARBES", 0.0, 0.0) + "Q q 1 0 0 1 200.3 760 cm\n" + label("TARBES", 0.0, 0.0) + "Q\n"
        assertEquals("TARBES", text(moved))
        // A Q puts the CTM back, so a label after it compares where it lies.
        assertEquals("TARBES", text(label("TARBES", 200.0, 760.0) + "q 1 0 0 1 50 0 cm Q\n" + label("TARBES", 200.0, 760.0)))
        // One form drawn twice in one place, and another that its /Matrix puts there (8.10.1).
        val forms = listOf(form(7, label("TARBES", 0.0, 0.0)), form(8, label("TARBES", 0.0, 0.0), matrix = "1 0 0 1 200 760"))
        val resources = "/XObject << /Fm0 7 0 R /Fm1 8 0 R >>"
        assertEquals("TARBES", text("q 1 0 0 1 200 760 cm /Fm0 Do Q q 1 0 0 1 200 760 cm /Fm0 Do Q\n", xobjects = resources, extra = forms))
        assertEquals("TARBES", text("q 1 0 0 1 200 760 cm /Fm0 Do Q /Fm1 Do\n", xobjects = resources, extra = forms))
    }

    @Test
    fun a_q_inside_a_text_object_restores_the_pen_as_the_renderer_does() {
        // ISO 32000-1, 8.2 has no q or Q inside a text object, but files have them. The renderer
        // keeps the text matrix in its graphics state, so this Q puts the pen back to where the
        // first TARBES starts, and the second one draws on top of it in both readings.
        val content = "BT /F2 1 Tf 8 0 0 8 200 760 Tm q (TARBES) Tj Q (TARBES) Tj ET\n"
        val page = PdfDocument.open(
            RawPdf.page(
                content.encodeToByteArray(),
                resources = "<< /Font << /F1 4 0 R /F2 6 0 R >> >>",
                extra = listOf(RawPdf.obj(6, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>")),
            ),
        ).pages[0]
        assertEquals("TARBES", page.structuredText.plainText)
        assertEquals("TARBES", page.extractText())
    }

    @Test
    fun a_label_shown_in_pieces_reads_once() {
        // The pen moves over each piece as the renderer moves it (ISO 32000-1, 9.4.4), so the
        // second pass of a piece starts where the first pass of that piece did.
        fun twice(body: String) = "BT 0 Tr /F2 1 Tf 8 0 0 8 200 760 Tm $body ET\n0.2 w\n" +
            "BT 1 Tr /F2 1 Tf 8 0 0 8 200 760 Tm $body ET\n" + label("NEXT", 200.0, 700.0)
        assertEquals("TARBES, FRANCE\nNEXT", text(twice("(TARBES, ) Tj (FRANCE) Tj")))
        assertEquals("TARBES\nNEXT", text(twice("[(TAR) -20 (BES)] TJ")))
        // A space between two pieces goes with the skipped piece before it.
        assertEquals("TARBES, FRANCE\nNEXT", text(twice("(TARBES,) Tj ( ) Tj (FRANCE) Tj")))
        // Two lines, by Td and by ', read as two lines, with no blank line after them.
        assertEquals("TARBES\nFRANCE\nNEXT", text(twice("(TARBES) Tj 0 -1.2 Td (FRANCE) Tj")))
        assertEquals("TARBES\nFRANCE\nNEXT", text(twice("1.2 TL (TARBES) Tj (FRANCE) '")))
    }

    @Test
    fun two_passes_with_their_own_actual_text_read_once() {
        // Each pass carries the same /ActualText (ISO 32000-1, 14.9.4), which replaces its text.
        val content = "/Span << /ActualText (Tarbes) >> BDC\n" + label("TARBES", 200.0, 760.0) + "EMC\n" +
            "/Span << /ActualText (Tarbes) >> BDC\n0.2 w\n" + label("TARBES", 200.0, 760.0, mode = 1) + "EMC\n" +
            label("NEXT", 200.0, 700.0)
        assertEquals("Tarbes\nNEXT", text(content))
        // In another place, the second sequence keeps its text.
        val elsewhere = "/Span << /ActualText (Tarbes) >> BDC\n" + label("TARBES", 200.0, 760.0) + "EMC\n" +
            "/Span << /ActualText (Tarbes) >> BDC\n" + label("TARBES", 200.0, 700.0) + "EMC\n"
        assertEquals("Tarbes\nTarbes", text(elsewhere))
    }

    @Test
    fun the_same_label_elsewhere_reads_twice() {
        // No second pass: one label in two places, and one label right after itself on a line.
        assertEquals("46-53\n46-53", text(label("46-53", 200.0, 700.0) + label("46-53", 300.0, 600.0)))
        assertEquals("46-53\n46-53", text(label("46-53", 200.0, 700.0) + label("46-53", 220.5, 700.0)))
        assertEquals("46-5346-53", text("BT /F2 1 Tf 8 0 0 8 200 700 Tm (46-53) Tj (46-53) Tj ET\n"))
        // Both labels start at 0 0 in text space, and the CTM puts them in two places.
        val placed = "q 1 0 0 1 200 700 cm\n" + label("46-53", 0.0, 0.0) + "Q q 1 0 0 1 300 600 cm\n" + label("46-53", 0.0, 0.0) + "Q\n"
        assertEquals("46-53\n46-53", text(placed))
        // One form in two places.
        val form = listOf(form(7, label("46-53", 0.0, 0.0)))
        assertEquals(
            "46-53\n46-53",
            text("q 1 0 0 1 200 700 cm /Fm0 Do Q q 1 0 0 1 300 600 cm /Fm0 Do Q\n", xobjects = "/XObject << /Fm0 7 0 R >>", extra = form),
        )
    }

    @Test
    fun double_letters_in_tight_type_stay() {
        // A tracking of -1.5 sets the two l's 0.28 points apart at 8 points, closer than a tenth of
        // an em. The letters of one string never compare, and neither do strings that continue
        // one another along a line.
        val strings = "BT /F1 8 Tf -1.5 Tc 1 0 0 1 72 600 Tm (all) Tj ET\nBT /F1 8 Tf -1.5 Tc 1 0 0 1 72 580 Tm (ill) Tj ET\n"
        assertEquals("all\nill", text(strings))
        assertEquals("all", text("BT /F1 8 Tf -1.5 Tc 1 0 0 1 72 600 Tm (a) Tj (l) Tj (l) Tj ET\n"))
    }

    @Test
    fun other_text_another_size_or_another_direction_at_one_origin_stays() {
        assertEquals("ABC\nXYZ", text(label("ABC", 200.0, 760.0) + label("XYZ", 200.0, 760.0)))
        assertEquals("DEOLS\nDEOLS", text(label("DEOLS", 200.0, 760.0) + label("DEOLS", 200.0, 760.0, size = 12.0)))
        // An I at 8 and at 10 points: both ends lie within a tenth of an em, and only the sizes differ.
        assertEquals("I\nI", text(label("I", 200.0, 760.0) + label("I", 200.0, 760.0, size = 10.0)))
        // The same I turned 10 degrees about its origin: both ends lie within a tenth of an em,
        // and only the direction differs.
        assertEquals("I\nI", text(label("I", 200.0, 500.0) + "BT /F2 1 Tf 7.878 1.389 -1.389 7.878 200 500 Tm (I) Tj ET\n"))
    }

    @Test
    fun a_pass_in_a_font_of_other_widths_stays_unless_it_ends_where_the_first_does() {
        // Helvetica sets "abcd" 1.33 points narrower than Helvetica-Bold at 8 points, so the two
        // passes end farther apart than a tenth of an em, and a run in another font that drifts
        // stays in the structured text too. In capitals the two fonts differ only at L, the
        // passes end 0.44 points apart, and the second one drops out.
        assertEquals("abcd\nabcd", text(label("abcd", 200.0, 760.0) + label("abcd", 200.0, 760.0, font = "F1")))
        assertEquals("DEOLS", text(label("DEOLS", 200.0, 760.0) + label("DEOLS", 200.0, 760.0, font = "F1")))
    }

    @Test
    fun a_show_in_a_type3_font_never_matches() {
        // A Type 3 width is in glyph space, and /FontMatrix maps it (ISO 32000-1, 9.6.5). Here a
        // glyph advances half an em, so "aa" moves the pen 12 points and the first b starts at 112.
        // Read as thousandths of an em, the widths would start that b 0.7 points from the second
        // b, which would drop out.
        val type3 = RawPdf.obj(
            7,
            "<< /Type /Font /Subtype /Type3 /FontMatrix [0.01 0 0 0.01 0 0] /FontBBox [0 0 50 50] " +
                "/Encoding << /Differences [97 /a /b] >> /FirstChar 97 /LastChar 98 /Widths [50 50] " +
                "/CharProcs << /a 8 0 R /b 8 0 R >> >>",
        )
        val proc = RawPdf.obj(8, "<< >>", "50 0 0 0 50 50 d1 0 0 50 50 re f".encodeToByteArray())
        val content = "BT /T3 12 Tf 100 700 Td (aa) Tj (b) Tj ET\nBT /T3 12 Tf 100.5 700 Td (b) Tj ET\n"
        assertEquals("aab\nb", text(content, fonts = "/T3 7 0 R", extra = listOf(type3, proc)))
    }

    @Test
    fun the_font_blind_overload_cannot_place_a_show_and_skips_none() {
        val ops = ContentStreamParser.parse((label("TARBES", 200.0, 760.0) + label("TARBES", 200.0, 760.0)).encodeToByteArray())
        assertEquals("TARBES\nTARBES", TextExtractor.extract(ops))
    }
}
