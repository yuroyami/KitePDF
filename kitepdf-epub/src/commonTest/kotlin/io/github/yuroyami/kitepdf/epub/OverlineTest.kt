package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `text-decoration: overline` (CSS Text Decoration 3, 2.1 to 2.5, CSS 2.1, 16.3.1, #578). */
class OverlineTest {

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

    /** The lines drawn under, through and over [word], each as its range up from the baseline in text space. */
    private fun lines(calls: List<RecordingCanvas.Call>, word: String): List<ClosedFloatingPointRange<Double>> {
        val run = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().single { it.text == word }
        return calls.filterIsInstance<RecordingCanvas.Call.Fill>().filter { it.ctm == run.textToDevice }
            .map { f -> points(f).map { it.second }.let { it.min()..it.max() } }.sortedBy { it.start }
    }

    @Test
    fun an_overline_runs_over_the_text() {
        for (declaration in listOf("text-decoration:overline", "text-decoration-line:overline", "text-decoration:overline red")) {
            val calls = render("<p><span style='$declaration'>Over</span></p>")
            val over = lines(calls, "Over").single()
            assertTrue(over.start >= 7.5, "$declaration: over the capitals and ascenders: $over")
            val run = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().single()
            val fill = calls.filterIsInstance<RecordingCanvas.Call.Fill>().single()
            val xs = points(fill).map { it.first }
            assertEquals(run.glyphs.sumOf { it.advanceWidth } * run.fontSize / 1000.0, xs.max() - xs.min(), 1e-9, declaration)
        }
    }

    @Test
    fun under_through_and_over_draw_three_lines_in_that_order_up_the_text() {
        val (under, through, over) = lines(render("<p><span style='text-decoration:underline line-through overline'>all</span></p>"), "all")
        assertTrue(under.endInclusive <= 0.0 && through.start > 0.0 && through.endInclusive < over.start, "$under $through $over")
    }

    @Test
    fun descendants_take_the_line_and_its_colour_and_none_cannot_cancel_it() {
        val calls = render("<p style='text-decoration:overline;color:#ff0000'>one <span style='text-decoration:none;color:#0000ff'>two</span></p>")
        val fills = calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        assertEquals(3, fills.size, "two words and the space between them")
        assertTrue(fills.all { it.color == RgbColor(1.0, 0.0, 0.0) })
        assertEquals(lines(calls, "one"), lines(calls, "two"))
    }

    /** The column the glyphs of a vertical line take, and each of its lines, as physical x ranges. */
    private fun vertical(decoration: String, html: String): Pair<ClosedFloatingPointRange<Double>, List<ClosedFloatingPointRange<Double>>> {
        val calls = render("<p><span style='text-decoration:$decoration'>日本語</span></p>", "writing-mode:vertical-rl;$html")
        val xs = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().filter { it.text.isNotBlank() }.flatMap { g ->
            val adv = g.glyphs.sumOf { it.advanceWidth } * g.fontSize / 1000.0
            listOf(g.textToDevice.transformX(0.0, 0.0), g.textToDevice.transformX(adv, 0.0))
        }
        val fills = calls.filterIsInstance<RecordingCanvas.Call.Fill>()
            .map { f -> points(f).map { (x, y) -> f.ctm.transformX(x, y) }.let { it.min()..it.max() } }
        return xs.min()..xs.max() to fills
    }

    @Test
    fun a_vertical_overline_runs_right_of_the_column_and_moves_left_when_the_underline_takes_the_right() {
        val (column, over) = vertical("overline", "")
        assertTrue(over.single().start >= column.endInclusive, "over $over right of $column")
        val (column2, both) = vertical("underline overline", "text-underline-position:right")
        assertEquals(2, both.size)
        assertTrue(both.count { it.start >= column2.endInclusive } == 1 && both.count { it.endInclusive <= column2.start } == 1, "$both beside $column2")
    }
}
