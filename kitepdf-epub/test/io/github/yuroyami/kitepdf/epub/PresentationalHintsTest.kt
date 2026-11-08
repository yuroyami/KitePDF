package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The presentational attributes of older books (HTML, 15 Rendering; #843). */
class PresentationalHintsTest {

    private fun doc(body: String) = EpubDocument.open(EpubFixtures.epub(body), EpubSettings(pageWidth = 300.0, pageHeight = 400.0))

    private fun starts(body: String): List<Double> =
        doc(body).pages[0].textContent().blocks.flatMap { it.lines }.map { it.bounds.left }

    @Test
    fun align_and_center_centre_a_paragraph_as_text_align_does() {
        val css = starts("<p style=\"text-align:center\">Title</p>").single()
        assertTrue(css > 50.0, "the fixture centres: $css")
        assertEquals(css, starts("<p align=\"center\">Title</p>").single(), 1e-6)
        assertEquals(css, starts("<center><p>Title</p></center>").single(), 1e-6)
        assertTrue(starts("<p align=\"right\">Title</p>").single() > css, "right-aligned")
        // An author rule still wins over the hint.
        assertEquals(starts("<p>Title</p>").single(), starts("<p align=\"center\" style=\"text-align:left\">Title</p>").single(), 1e-6)
    }

    @Test
    fun font_color_and_bgcolor_paint() {
        val page = doc("<p><font color=\"#ff0000\" size=\"7\">Big</font> small</p><table bgcolor=\"#00ff00\"><tr><td>cell</td></tr></table>").pages[0]
        val canvas = RecordingCanvas().also { page.renderTo(it) }
        val big = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().first { "Big" in it.text }
        val small = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().first { "small" in it.text }
        assertEquals(io.github.yuroyami.kitepdf.core.render.RgbColor(1.0, 0.0, 0.0), big.color)
        assertTrue(big.fontSize > small.fontSize * 2.5, "size 7 is three times medium: ${big.fontSize} vs ${small.fontSize}")
        assertTrue(canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().any { it.color == io.github.yuroyami.kitepdf.core.render.RgbColor(0.0, 1.0, 0.0) }, "the table's bgcolor")
    }
}
