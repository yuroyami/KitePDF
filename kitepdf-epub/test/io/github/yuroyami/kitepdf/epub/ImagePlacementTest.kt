package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where images land: glued to a word, sized by HTML attributes, placed by their margins. */
class ImagePlacementTest {

    // Page margin 36 plus the UA body margin of 1em (12pt): the content starts at x 48 and is 304 wide.
    private val contentLeft = 48.0
    private val contentWidth = 304.0

    private fun images(body: String): List<RecordingCanvas.Call.Image> {
        val doc = EpubDocument.open(
            EpubFixtures.epub(body, extraEntries = listOf("OEBPS/pic.bmp" to EpubFixtures.bmp2x1())),
            EpubSettings(pageWidth = 400.0, pageHeight = 640.0),
        )
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>()
    }

    @Test
    fun an_image_glued_to_a_word_still_draws() {
        val drawn = images("""<p>value<img src="pic.bmp" width="60" height="60"/>end</p>""")
        assertEquals(1, drawn.size, "no space before the image, and it still draws")
        assertEquals(45.0, drawn[0].ctm.a, 1e-9, "60 CSS pixels are 45pt")
    }

    @Test
    fun html_size_attributes_are_css_pixels_in_block_mode_too() {
        val inline = images("""<p><img src="pic.bmp" width="120" height="80"/></p>""").single()
        val block = images("""<img src="pic.bmp" width="120" height="80" style="display:block"/>""").single()
        assertEquals(90.0, inline.ctm.a, 1e-9)
        assertEquals(90.0, block.ctm.a, 1e-9, "width=120 is 90pt whatever the display mode")
        assertEquals(60.0, abs(block.ctm.d), 1e-9)
    }

    @Test
    fun a_block_image_sits_at_the_start_edge_unless_its_margins_are_auto() {
        val style = "display:block;width:60pt;height:30pt"
        val plain = images("""<img src="pic.bmp" style="$style"/>""").single()
        assertEquals(contentLeft, plain.ctm.e, 1e-9, "no auto margins: the left content edge")
        val centred = images("""<img src="pic.bmp" style="$style;margin:0 auto"/>""").single()
        assertEquals(contentLeft + (contentWidth - 60.0) / 2, centred.ctm.e, 1e-9, "both margins auto: centred")
        val indented = images("""<img src="pic.bmp" style="$style;margin-left:20pt"/>""").single()
        assertEquals(contentLeft + 20.0, indented.ctm.e, 1e-9)
        val rtl = images("""<div dir="rtl"><img src="pic.bmp" style="$style"/></div>""").single()
        assertEquals(contentLeft + contentWidth - 60.0, rtl.ctm.e, 1e-9, "right-to-left text starts at the right edge")
    }
}
