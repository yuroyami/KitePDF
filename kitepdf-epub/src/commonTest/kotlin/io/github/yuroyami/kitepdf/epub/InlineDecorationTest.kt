package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Draw-stream regressions for inline backgrounds and text decoration (#103, #168). */
class InlineDecorationTest {
    private val yellow = RgbColor(1.0, 1.0, 0.0)
    private val green = RgbColor(0.0, 1.0, 0.0)

    private fun render(body: String, width: Double = 400.0, style: String = "", ctm: KiteMatrix = KiteMatrix.IDENTITY): List<RecordingCanvas.Call> {
        val doc = EpubDocument.open(
            EpubFixtures.epub("<body style='$style'>$body</body>"),
            EpubSettings(pageWidth = width, pageHeight = 640.0),
        )
        return doc.pages.flatMap { page -> RecordingCanvas().also { page.renderTo(it, ctm) }.calls }
    }

    private fun fillWidth(fill: RecordingCanvas.Call.Fill): Double {
        val start = fill.path.segments[0] as KitePath.Segment.MoveTo
        val end = fill.path.segments[1] as KitePath.Segment.LineTo
        return end.x - start.x
    }

    private fun fillBottom(fill: RecordingCanvas.Call.Fill) = (fill.path.segments[0] as KitePath.Segment.MoveTo).y

    @Test
    fun links_insertions_and_deletions_paint_lines_at_their_baselines() {
        val calls = render("<p><a href='next.xhtml'>link</a> <ins>added</ins> <del>removed</del> plain</p>")
        val glyphs = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        val fills = calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        assertEquals(3, fills.size)
        for (word in listOf("link", "added", "removed")) {
            val run = glyphs.single { it.text == word }
            val line = fills.single { it.ctm == run.textToDevice }
            assertEquals(run.glyphs.sumOf { it.advanceWidth } * run.fontSize / 1000.0, fillWidth(line), 1e-9)
            assertEquals(run.color, line.color)
            assertTrue(if (word == "removed") fillBottom(line) > 0 else fillBottom(line) < 0)
        }
    }

    @Test
    fun combined_lines_include_spaces_and_survive_nested_none() {
        val calls = render("<p><span style='text-decoration:underline line-through'>one <span style='text-decoration:none'>two</span></span></p>")
        val fills = calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        assertEquals(6, fills.size, "two words and their space each carry both lines")
        val starts = fills.groupBy { it.ctm.e }
        assertEquals(3, starts.size)
        assertTrue(starts.values.all { it.size == 2 })
    }

    @Test
    fun author_none_overrides_the_same_elements_ua_decoration() {
        for (declaration in listOf("text-decoration:none", "text-decoration-line:none")) {
            val calls = render("<p><a href='next.xhtml' style='$declaration'>link</a></p>")
            assertTrue(calls.none { it is RecordingCanvas.Call.Fill })
        }
    }

    @Test
    fun mark_background_covers_nested_text_and_interior_spaces_before_glyphs() {
        val calls = render("<p>plain <mark>one <em>two</em></mark> after <span style='background-color:#00ff00'>green</span></p>")
        val fills = calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        assertEquals(3, fills.count { it.color == yellow }, "mark covers two words and their interior space")
        assertEquals(1, fills.count { it.color == green })
        assertTrue(calls.indexOfLast { it is RecordingCanvas.Call.Fill } < calls.indexOfFirst { it is RecordingCanvas.Call.Glyphs })
        val marked = fills.filter { it.color == yellow }.sortedBy { it.ctm.e }
        for ((left, right) in marked.zipWithNext()) {
            assertEquals(left.ctm.e + fillWidth(left), right.ctm.e, 1e-9, "highlight has no gaps")
        }
    }

    @Test
    fun adjacent_unspaced_spans_keep_distinct_backgrounds() {
        val calls = render("<p><mark>one</mark><span style='background-color:#00ff00'>two</span></p>")
        val fills = calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        assertEquals(listOf(yellow, green), fills.map { it.color })
        assertEquals(fills[0].ctm.e + fillWidth(fills[0]), fills[1].ctm.e, 1e-9)
    }

    @Test
    fun wrapped_fragments_paint_on_each_line_without_trailing_space() {
        val calls = render("<p><mark>one two three four five six seven eight</mark></p>", width = 180.0)
        val fills = calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        val glyphs = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        assertTrue(fills.map { it.ctm.f }.distinct().size > 1)
        for ((baseline, fragments) in fills.groupBy { it.ctm.f }) {
            val words = glyphs.filter { it.textToDevice.f == baseline }
            val end = words.maxOf { it.textToDevice.e + it.glyphs.sumOf { g -> g.advanceWidth } * it.fontSize / 1000.0 }
            assertEquals(end, fragments.maxOf { it.ctm.e + fillWidth(it) }, 1e-9)
        }
    }

    @Test
    fun vertical_fragments_follow_the_inline_axis_and_device_transform() {
        for (mode in listOf("vertical-rl", "vertical-lr")) {
            val calls = render("<p><mark><u>日本語</u></mark></p>", style = "writing-mode:$mode", ctm = KiteMatrix.scaling(2.0, 2.0))
            val fills = calls.filterIsInstance<RecordingCanvas.Call.Fill>()
            assertTrue(fills.any { it.color == yellow })
            assertTrue(fills.any { it.color != yellow })
            assertTrue(fills.all { it.ctm.a == 0.0 && it.ctm.d == 0.0 && abs(it.ctm.b) == 2.0 && abs(it.ctm.c) == 2.0 })
        }
    }
}
