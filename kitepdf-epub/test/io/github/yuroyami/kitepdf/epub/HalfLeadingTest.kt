package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/** The leading of a line splits in two around its text (CSS 2.1, 10.8.1; #852). */
class HalfLeadingTest {

    /** How far below the page top the baseline of `Hxg` sits, in a paragraph of [lineHeight]. */
    private fun baseline(lineHeight: String): Double {
        val doc = EpubDocument.open(
            EpubFixtures.epub("<p style=\"margin:0;line-height:$lineHeight\">Hxg</p>"),
            EpubSettings(pageWidth = 300.0, pageHeight = 400.0, fontSize = 12.0),
        )
        val canvas = RecordingCanvas().also { doc.pages[0].renderTo(it, KiteMatrix.IDENTITY) }
        return 400.0 - canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().first { "H" in it.text }.textToDevice.f
    }

    @Test
    fun extra_line_height_goes_half_above_the_text_and_half_below() {
        val one = baseline("1")
        // A line three times the font size has 24 points of leading, 12 of them above the text.
        assertEquals(12.0, baseline("3") - one, 1e-6)
        assertEquals(6.0, baseline("2") - one, 1e-6)
    }
}
