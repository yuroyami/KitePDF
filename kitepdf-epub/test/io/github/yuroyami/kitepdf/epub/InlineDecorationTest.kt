package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Draw-stream regressions for inline backgrounds and text decoration (#103, #168, #253, #259, #265, #271). */
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

    private fun fillHeight(fill: RecordingCanvas.Call.Fill) =
        (fill.path.segments[2] as KitePath.Segment.LineTo).y - (fill.path.segments[1] as KitePath.Segment.LineTo).y

    private fun open(body: String, settings: EpubSettings = EpubSettings(pageWidth = 400.0, pageHeight = 640.0)) =
        EpubDocument.open(EpubFixtures.epub("<body>$body</body>"), settings)

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

    @Test
    fun one_decorating_element_draws_one_colour_thickness_and_position() {
        // CSS Text Decoration 3, 2.3 and 2.5 (#271).
        val calls = render("<p><a href='next.xhtml'>see <span style='color:#cc0000;font-size:24pt'>this</span></a></p>")
        val fills = calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        val link = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().single { it.text == "see" }
        assertEquals(3, fills.size, "two words and the space between them")
        assertTrue(fills.all { it.color == link.color }, "the red child keeps the link's line colour")
        assertEquals(1, fills.map { fillHeight(it) }.distinct().size, "one thickness")
        assertEquals(1, fills.map { it.ctm.f + fillBottom(it) }.distinct().size, "one position")
    }

    @Test
    fun a_link_around_a_superscript_keeps_its_underline_on_the_line_baseline() {
        val calls = render("<p>note<a href='#fn1'><sup>1</sup></a></p>")
        val glyphs = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        val note = glyphs.single { it.text == "note" }
        val sup = glyphs.single { it.text == "1" }
        val line = calls.filterIsInstance<RecordingCanvas.Call.Fill>().single()
        assertTrue(sup.textToDevice.f > note.textToDevice.f, "the superscript is raised")
        assertEquals(note.textToDevice.f, line.ctm.f, 1e-9)
        assertEquals(sup.textToDevice.e, line.ctm.e, 1e-9)
    }

    @Test
    fun inline_blocks_and_floats_do_not_take_their_parents_lines() {
        // CSS Text Decoration 3, 2.1 (#265).
        for (child in listOf("display:inline-block", "float:right", "position:absolute")) {
            val calls = render("<p><a href='next.xhtml'>Next <span style='$child'>12</span></a></p>")
            val glyphs = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
            val fills = calls.filterIsInstance<RecordingCanvas.Call.Fill>()
            val next = glyphs.single { it.text == "Next" }
            val twelve = glyphs.single { it.text == "12" }
            assertTrue(fills.any { it.ctm == next.textToDevice }, "$child: the link text keeps its underline")
            assertTrue(fills.none { it.ctm == twelve.textToDevice }, "$child: the child text takes no underline")
        }
    }

    @Test
    fun background_alpha_paints_as_alpha_and_a_transparent_colour_paints_nothing() {
        // CSS Color 4, 4.2, 5.1, 5.2 and 6.3 (#253).
        for (clear in listOf("rgba(0,0,0,0)", "#00000000", "#0000", "transparent", "rgb(0 0 0 / 0%)")) {
            assertTrue(render("<p><span style='background-color:$clear'>x</span></p>").none { it is RecordingCanvas.Call.Fill }, clear)
        }
        val tint = render("<p><span style='background:rgba(27, 31, 35, .05)'>x</span></p>")
            .filterIsInstance<RecordingCanvas.Call.Fill>().single()
        assertEquals(RgbColor(27 / 255.0, 31 / 255.0, 35 / 255.0), tint.color)
        assertEquals(0.05, tint.alpha, 1e-9)
        val block = render("<div style='background-color:#ff000080'><p>x</p></div>")
            .filterIsInstance<RecordingCanvas.Call.Fill>().single()
        assertEquals(128 / 255.0, block.alpha, 1e-9)
    }

    @Test
    fun a_forced_text_colour_drops_author_backgrounds() {
        val doc = open(
            "<p><span style='background-color:#eeeeee'>x</span></p><div style='background:#eeeeee'><p>y</p></div>",
            EpubSettings(pageWidth = 400.0, pageHeight = 640.0, textColor = RgbColor(1.0, 1.0, 1.0)),
        )
        val calls = RecordingCanvas().also { doc.pages[0].renderTo(it) }.calls
        assertTrue(calls.none { it is RecordingCanvas.Call.Fill })
    }

    @Test
    fun a_space_between_elements_keeps_the_line_height_and_the_copied_space() {
        // #259: the space joins the text after it unless its lines or background differ.
        fun lineHeight(body: String) = open(body).pages[0].textContent().blocks.first().lines.first().bounds.height
        val split = lineHeight("<p style='font-size:12pt'><span style='font-size:8pt'>aaa</span> <span style='font-size:8pt'>bbb</span></p>")
        val joined = lineHeight("<p style='font-size:12pt'><span style='font-size:8pt'>aaa bbb</span></p>")
        assertEquals(joined, split, 1e-9)
        val text = open("<p>a <span style='font-size:24pt'>B</span> <u>c</u> <u style='font-size:24pt'>D</u></p>")
            .pages[0].textContent().plainText
        assertContains(text, "a B c D")
    }
}
