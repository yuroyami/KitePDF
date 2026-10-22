package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `text-emphasis` and its longhands, under their `-epub-` names too (CSS Text Decoration 3, 3, #508). */
class TextEmphasisTest {

    private val settings = EpubSettings(pageWidth = 300.0, pageHeight = 400.0, fontSize = 10.0, margin = 20.0)

    private fun open(body: String, html: String = "") =
        EpubDocument.open(EpubFixtures.epub("<style>html{$html}</style>$body"), settings)

    private fun runs(body: String, html: String = ""): List<RecordingCanvas.Call.Glyphs> =
        RecordingCanvas().also { open(body, html).pages[0].renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()

    private val marks = "\u2022\u25E6\u25CF\u25CB\u25C9\u25CE\u25B2\u25B3\uFE45\uFE46"

    private fun List<RecordingCanvas.Call.Glyphs>.marks(mark: String? = null) =
        filter { r -> r.text.isNotEmpty() && r.text.all { mark?.contains(it) ?: (it in marks) } }

    private fun List<RecordingCanvas.Call.Glyphs>.text() = filter { r -> r.text.isNotBlank() && r.text.none { it in marks } }

    private fun RecordingCanvas.Call.Glyphs.advance() = glyphs.sumOf { it.advanceWidth } * fontSize / 1000.0

    @Test
    fun a_filled_dot_goes_over_each_letter_at_half_its_size() {
        for (name in listOf("text-emphasis-style", "-epub-text-emphasis-style", "-webkit-text-emphasis-style")) {
            val all = runs("<p><span style='$name:filled dot'>ab c</span></p>")
            val dots = all.marks("\u2022")
            assertEquals(3, dots.sumOf { it.glyphs.size }, "$name: a mark for a, b and c, none for the space: ${all.map { it.text }}")
            val text = all.text()
            assertTrue(dots.all { it.fontSize == 5.0 }, "$name: half of 10pt")
            assertTrue(dots.all { d -> d.textToDevice.f > text.first().textToDevice.f + 5.0 }, "$name: over the letters")
            // Each dot sits over the middle of its letter.
            val centres = text.flatMap { r ->
                var x = r.textToDevice.e
                r.glyphs.map { g -> val w = g.advanceWidth * r.fontSize / 1000.0; (x + w / 2).also { x += w } }
            }
            for (d in dots) assertTrue(centres.any { abs(it - (d.textToDevice.e + d.advance() / 2)) < 0.01 }, "$name: $centres")
        }
    }

    @Test
    fun each_shape_draws_its_own_character_filled_or_open() {
        val shapes = listOf(
            "dot" to "\u2022\u25E6", "circle" to "\u25CF\u25CB", "double-circle" to "\u25C9\u25CE",
            "triangle" to "\u25B2\u25B3", "sesame" to "\uFE45\uFE46",
        )
        for ((shape, chars) in shapes) {
            assertEquals(1, runs("<p style='text-emphasis-style:$shape'>a</p>").marks(chars[0].toString()).size, "$shape alone is filled")
            assertEquals(1, runs("<p style='text-emphasis-style:filled $shape'>a</p>").marks(chars[0].toString()).size, "filled $shape")
            assertEquals(1, runs("<p style='text-emphasis-style:$shape open'>a</p>").marks(chars[1].toString()).size, "$shape open")
        }
        assertEquals(1, runs("<p style='text-emphasis-style:filled'>a</p>").marks("\u25CF").size, "filled alone is a circle across the line")
        assertEquals(1, runs("<p style='text-emphasis-style:open'>a</p>").marks("\u25CB").size)
        assertEquals(1, runs("<p style='text-emphasis-style:filled'>\u65E5</p>", "writing-mode:vertical-rl").marks("\uFE45").size, "and a sesame down a column")
        val custom = runs("<p style=\"text-emphasis-style:'x'\">ab</p>")
        assertEquals(2, custom.filter { r -> r.text.isNotEmpty() && r.text.all { it == 'x' } }.sumOf { it.glyphs.size }, "one x over each letter")
    }

    @Test
    fun spaces_punctuation_and_controls_take_no_mark_but_the_listed_symbols_do() {
        assertEquals(2, runs("<p style='text-emphasis-style:dot'>a, b!</p>").marks().sumOf { it.glyphs.size })
        assertEquals(3, runs("<p style='text-emphasis-style:dot'>#1%</p>").marks().sumOf { it.glyphs.size })
        assertEquals(2, runs("<p style='text-emphasis-style:dot'>\u65E5\u3001\u672C\u3002</p>").marks().sumOf { it.glyphs.size })
    }

    @Test
    fun the_marks_take_their_colour_or_each_letter_its_own() {
        val blue = runs("<p style='color:#ff0000'><span style='text-emphasis-style:dot;text-emphasis-color:#0000ff'>a</span></p>").marks()
        assertEquals(listOf(RgbColor(0.0, 0.0, 1.0)), blue.map { it.color })
        val own = runs("<p style='color:#ff0000;text-emphasis-style:dot'>a<span style='color:#00ff00'>b</span></p>").marks()
        assertEquals(setOf(RgbColor(1.0, 0.0, 0.0), RgbColor(0.0, 1.0, 0.0)), own.map { it.color }.toSet())
    }

    @Test
    fun the_shorthand_sets_style_and_colour_and_leaves_the_position() {
        for (name in listOf("text-emphasis", "-epub-text-emphasis")) {
            val all = runs("<p style='text-emphasis-position:under;$name:open circle #0000ff'>a</p>")
            val mark = all.marks("\u25CB").single()
            assertEquals(RgbColor(0.0, 0.0, 1.0), mark.color, name)
            assertTrue(mark.textToDevice.f < all.text().single().textToDevice.f, "$name: still under")
        }
        assertTrue(runs("<p style='text-emphasis-style:dot;text-emphasis:none'>a</p>").marks().isEmpty())
    }

    @Test
    fun under_puts_the_marks_below_the_text() {
        for (name in listOf("text-emphasis-position", "-epub-text-emphasis-position")) for (value in listOf("under", "under right", "left under")) {
            val all = runs("<p style='text-emphasis-style:dot;$name:$value'>a</p>")
            assertTrue(all.marks().single().textToDevice.f < all.text().single().textToDevice.f - 2.0, "$name:$value")
        }
    }

    /** The first column's glyphs and its marks, as physical x ranges. */
    private fun column(html: String): Pair<ClosedFloatingPointRange<Double>, ClosedFloatingPointRange<Double>> {
        val all = runs("<p style='text-emphasis-style:dot'>\u65E5\u672C\u8A9E</p>", "writing-mode:vertical-rl;$html")
        fun xs(rs: List<RecordingCanvas.Call.Glyphs>) = rs.flatMap { r -> listOf(r.textToDevice.transformX(0.0, 0.0), r.textToDevice.transformX(r.advance(), 0.0)) }
        assertEquals(3, all.marks().sumOf { it.glyphs.size }, "$html: ${all.map { it.text }}")
        return xs(all.text()).let { it.min()..it.max() } to xs(all.marks()).let { it.min()..it.max() }
    }

    @Test
    fun a_column_takes_its_marks_on_the_right_or_the_left() {
        for (value in listOf("", "text-emphasis-position:over right", "text-emphasis-position:under")) {
            val (text, marks) = column(value)
            assertTrue(marks.start >= text.endInclusive, "$value: $marks right of $text")
        }
        for (value in listOf("text-emphasis-position:over left", "-epub-text-emphasis-position:under left")) {
            val (text, marks) = column(value)
            assertTrue(marks.endInclusive <= text.start, "$value: $marks left of $text")
        }
    }

    @Test
    fun the_marks_stay_out_of_the_page_text_and_make_room_as_ruby_does() {
        val page = open("<p style='text-emphasis-style:dot'>emphasis</p>").pages[0]
        assertFalse(page.textContent().plainText.any { it in marks })
        fun pitch(style: String): Double {
            val lines = open("<p style='$style'>one two three four five six seven eight nine ten eleven twelve thirteen fourteen</p>")
                .pages[0].textContent().blocks.flatMap { it.lines }
            return lines[1].bounds.top - lines[0].bounds.top
        }
        assertTrue(abs(pitch("text-emphasis-style:dot")) > abs(pitch("")) + 1.0, "${pitch("text-emphasis-style:dot")} vs ${pitch("")}")
    }

    @Test
    fun the_style_inherits_and_none_takes_it_away() {
        assertEquals(2, runs("<p style='text-emphasis-style:dot'>a<span>b</span></p>").marks().sumOf { it.glyphs.size })
        assertEquals(1, runs("<p style='text-emphasis-style:dot'>a<span style='text-emphasis-style:none'>b</span></p>").marks().sumOf { it.glyphs.size })
    }
}
