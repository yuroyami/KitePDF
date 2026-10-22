package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The horizontal rule, and alignment, indent and list markers in right-to-left text. */
class RuleAndDirectionTest {

    private fun open(body: String) =
        EpubDocument.open(EpubFixtures.epub(body), EpubSettings(pageWidth = 400.0, pageHeight = 640.0))

    private fun calls(body: String): List<RecordingCanvas.Call> =
        RecordingCanvas().also { open(body).pages[0].renderTo(it, KiteMatrix.IDENTITY) }.calls

    /** [minX, minY, maxX, maxY] of a fill after its matrix. */
    private fun deviceBounds(f: RecordingCanvas.Call.Fill): DoubleArray {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        fun add(x: Double, y: Double) {
            xs += f.ctm.a * x + f.ctm.c * y + f.ctm.e
            ys += f.ctm.b * x + f.ctm.d * y + f.ctm.f
        }
        for (seg in f.path.segments) when (seg) {
            is KitePath.Segment.MoveTo -> add(seg.x, seg.y)
            is KitePath.Segment.LineTo -> add(seg.x, seg.y)
            is KitePath.Segment.CurveTo -> add(seg.x3, seg.y3)
            is KitePath.Segment.QuadTo -> add(seg.x2, seg.y2)
            KitePath.Segment.Close -> {}
        }
        return doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    @Test
    fun a_horizontal_rule_paints_a_line_across_the_column() {
        val fills = calls("<p>above the rule</p><hr/><p>below the rule</p>").filterIsInstance<RecordingCanvas.Call.Fill>()
        val line = assertNotNull(
            fills.map { deviceBounds(it) }.firstOrNull { it[2] - it[0] > 250.0 },
            "a rule spans the 304pt column (fills: ${fills.size})",
        )
        assertTrue(line[3] - line[1] <= 2.0, "and it is a thin line (height ${line[3] - line[1]})")
    }

    @Test
    fun a_right_to_left_list_marker_sits_beside_its_item() {
        val runs = calls("""<ul dir="rtl"><li>item text</li></ul>""").filterIsInstance<RecordingCanvas.Call.Glyphs>()
        val marker = runs.single { it.text.trim() == "\u2022" }
        val words = runs.filter { it !== marker }
        assertTrue(words.isNotEmpty())
        assertTrue(
            words.all { marker.textToDevice.e > it.textToDevice.e },
            "the bullet sits right of the item text (marker ${marker.textToDevice.e}, words ${words.map { it.textToDevice.e }})",
        )
    }

    @Test
    fun an_explicit_left_survives_right_to_left_text() {
        val blocks = open("""<div dir="rtl"><p style="text-align:left">left words</p><p>start words</p></div>""")
            .pages[0].textContent().blocks
        assertEquals(48.0, blocks[0].lines[0].bounds.left, 1.0, "text-align:left stays at the left edge")
        assertEquals(352.0, blocks[1].lines[0].bounds.right, 1.0, "no alignment set: right-to-left text starts at the right")
    }

    @Test
    fun a_right_to_left_paragraph_indents_and_ends_its_justified_text_at_the_right() {
        // The content box runs from 48 to 352. A line starts at the right edge in right-to-left
        // text, so the indent sits there, and so does a justified paragraph's last line (#572).
        val words = "word ".repeat(40)
        val blocks = open(
            """<div dir="rtl"><p style="text-align:justify">${words}end</p><p style="text-indent:30pt">${words}end</p>""" +
                """<p style="text-align:justify;text-indent:30pt">${words}end</p><p style="text-indent:-20pt;padding-right:20pt">${words}end</p></div>""" +
                """<p style="text-align:justify;text-indent:30pt">${words}end</p>""",
        ).pages[0].textContent().blocks
        val justified = blocks[0].lines
        assertTrue(justified.size > 2)
        for (line in justified) assertEquals(352.0, line.bounds.right, 1.0, "every justified line ends at the right edge")
        assertTrue(justified.last().bounds.left > 100.0, "the last line is short and stays at the right (${justified.last().bounds})")

        val indented = blocks[1].lines
        assertEquals(322.0, indented[0].bounds.right, 1.0, "the first line starts 30 points in from the right")
        assertEquals(352.0, indented[1].bounds.right, 1.0, "the next line starts at the right edge")

        val both = blocks[2].lines
        assertEquals(48.0, both[0].bounds.left, 1.0, "a justified first line still reaches the left edge")
        assertEquals(322.0, both[0].bounds.right, 1.0, "and leaves its indent at the right")
        assertEquals(352.0, both.last().bounds.right, 1.0)

        val hanging = blocks[3].lines
        assertEquals(352.0, hanging[0].bounds.right, 1.0, "a negative indent hangs out past the right padding")
        assertEquals(332.0, hanging[1].bounds.right, 1.0)

        val leftToRight = blocks[4].lines
        assertEquals(78.0, leftToRight[0].bounds.left, 1.0, "left-to-right text keeps its indent at the left")
        assertEquals(352.0, leftToRight[0].bounds.right, 1.0)
        assertEquals(48.0, leftToRight.last().bounds.left, 1.0, "and its last line at the left")
    }
}
