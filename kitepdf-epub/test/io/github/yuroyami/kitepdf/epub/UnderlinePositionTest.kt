package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `text-underline-position` and `-epub-text-underline-position` (CSS Text Decoration 3, 3.4, #508). */
class UnderlinePositionTest {

    private val settings = EpubSettings(pageWidth = 300.0, pageHeight = 400.0, fontSize = 10.0, margin = 20.0)

    private fun render(body: String, html: String = ""): List<RecordingCanvas.Call> =
        RecordingCanvas().also {
            EpubDocument.open(EpubFixtures.epub("<style>html{$html}</style>$body"), settings).pages[0].renderTo(it)
        }.calls

    private fun points(fill: RecordingCanvas.Call.Fill) = fill.path.segments.mapNotNull {
        when (it) {
            is KitePath.Segment.MoveTo -> it.x to it.y
            is KitePath.Segment.LineTo -> it.x to it.y
            else -> null
        }
    }

    /** The underline of [word], from its bottom to its top in the run's own text space (y up from the baseline). */
    private fun underline(calls: List<RecordingCanvas.Call>, word: String): ClosedFloatingPointRange<Double> {
        val run = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().single { it.text == word }
        val ys = points(calls.filterIsInstance<RecordingCanvas.Call.Fill>().single { it.ctm == run.textToDevice }).map { it.second }
        return ys.min()..ys.max()
    }

    private fun horizontal(style: String, html: String = ""): ClosedFloatingPointRange<Double> =
        underline(render("<p><span style='text-decoration:underline;$style'>gap</span></p>", html), "gap")

    @Test
    fun under_drops_the_underline_below_the_descenders() {
        // Layout's nominal em box reaches 0.2 em below the baseline.
        val auto = horizontal("")
        assertTrue(auto.endInclusive <= 0.0 && auto.endInclusive > -2.0, "auto sits at the baseline, descenders cross it: $auto")
        for (name in listOf("text-underline-position", "-epub-text-underline-position", "-webkit-text-underline-position")) {
            val under = horizontal("$name:under")
            assertTrue(under.endInclusive <= -2.0, "$name: under clears the descenders: $under")
            assertEquals(auto.endInclusive - auto.start, under.endInclusive - under.start, 1e-9, "$name: one thickness")
        }
    }

    @Test
    fun in_horizontal_text_left_and_right_alone_keep_the_auto_place() {
        val auto = horizontal("")
        for (value in listOf("auto", "left", "right", "from-font")) assertEquals(auto, horizontal("text-underline-position:$value"), value)
        for (value in listOf("under left", "right under")) assertTrue(horizontal("text-underline-position:$value").endInclusive <= -2.0, value)
    }

    @Test
    fun the_value_inherits_from_the_root_as_the_test_book_sets_it() {
        val under = horizontal("text-underline-position:under")
        assertEquals(under, horizontal("", html = "text-underline-position:under"))
        assertEquals(under, horizontal("", html = "-epub-text-underline-position:under"))
    }

    @Test
    fun the_decorating_element_keeps_its_own_position() {
        // "It does not affect underlines specified by ancestor elements."
        val outer = render("<p style='text-decoration:underline;text-underline-position:under'>one <span style='text-underline-position:auto'>two</span></p>")
        assertEquals(underline(outer, "one"), underline(outer, "two"))
        assertTrue(underline(outer, "two").endInclusive <= -2.0)
        val inner = render("<p style='text-decoration:underline'>one <span style='text-underline-position:under'>two</span></p>")
        assertEquals(underline(inner, "one"), underline(inner, "two"))
        assertTrue(underline(inner, "two").endInclusive > -2.0)
    }

    @Test
    fun an_unreadable_value_leaves_the_inherited_one() {
        for (value in listOf("left right", "under under", "sideways", "over")) {
            assertEquals(horizontal("", html = "text-underline-position:under"), horizontal("text-underline-position:$value", html = "text-underline-position:under"), value)
        }
    }

    @Test
    fun inherit_and_unset_take_the_parents_value_over_an_earlier_one() {
        for (k in listOf("inherit", "unset")) {
            assertEquals(horizontal(""), horizontal("text-underline-position:under;text-underline-position:$k"), k)
            assertEquals(
                horizontal("", html = "text-underline-position:under"),
                horizontal("text-underline-position:auto;text-underline-position:$k", html = "text-underline-position:under"), k,
            )
        }
    }

    /** The column the glyphs of a vertical line take, and its underline, both as physical x ranges. */
    private fun vertical(mode: String, value: String): Pair<ClosedFloatingPointRange<Double>, ClosedFloatingPointRange<Double>> {
        val calls = render("<p><span style='text-decoration:underline'>日本語</span></p>", "writing-mode:$mode;text-underline-position:$value")
        val glyphs = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().filter { it.text.isNotBlank() }
        assertTrue(glyphs.isNotEmpty())
        val glyphXs = glyphs.flatMap { g ->
            val adv = g.glyphs.sumOf { it.advanceWidth } * g.fontSize / 1000.0
            listOf(g.textToDevice.transformX(0.0, 0.0), g.textToDevice.transformX(adv, 0.0))
        }
        val lineXs = calls.filterIsInstance<RecordingCanvas.Call.Fill>().flatMap { f -> points(f).map { (x, y) -> f.ctm.transformX(x, y) } }
        assertTrue(lineXs.isNotEmpty(), "$mode $value: an underline")
        return glyphXs.min()..glyphXs.max() to lineXs.min()..lineXs.max()
    }

    @Test
    fun right_puts_a_vertical_underline_right_of_the_column() {
        for (mode in listOf("vertical-rl", "vertical-lr")) for (value in listOf("under right", "right", "right under")) {
            val (column, line) = vertical(mode, value)
            assertTrue(line.start >= column.endInclusive, "$mode $value: line $line right of column $column")
        }
    }

    @Test
    fun left_and_under_put_a_vertical_underline_left_of_the_column() {
        for (mode in listOf("vertical-rl", "vertical-lr")) for (value in listOf("under left", "left", "under")) {
            val (column, line) = vertical(mode, value)
            assertTrue(line.endInclusive <= column.start, "$mode $value: line $line left of column $column")
        }
    }
}
