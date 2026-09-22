package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An `<svg>` in inline content flows on the line like an `<img>`, and a hidden one takes no space (#275). */
class InlineSvgTest {

    private fun page(body: String) = EpubDocument.open(
        EpubFixtures.epub(body, listOf("OEBPS/pic.bmp" to EpubFixtures.bmp2x1())),
    ).pages[0]

    private fun calls(body: String) = RecordingCanvas().also { page(body).renderTo(it) }.calls

    private val icon = """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20"><title>icon</title>""" +
        """<rect width="20" height="20" fill="#00ff00"/></svg>"""

    @Test
    fun an_svg_inside_a_span_draws_on_the_line() {
        val body = "<p>Before <span>$icon</span> after</p>"
        val green = calls(body).filterIsInstance<RecordingCanvas.Call.Fill>().filter { it.color.g > 0.9 && it.color.r < 0.1 }
        assertEquals(1, green.size, "the icon paints once")
        val p = page(body)
        assertEquals("Before after", p.textContent().plainText.trim(), "the picture adds no text")
        assertTrue(p.readingOrder().any { it.role == EpubRole.IMAGE && it.text == "icon" }, "${p.readingOrder()}")
    }

    @Test
    fun em_and_ex_sizes_follow_the_font_of_the_svg() {
        // 20 CSS pixels is 15 points, so 2em by 2ex is 30 by 15 points.
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="2em" height="2ex" """ +
            """viewBox="0 0 200 100"><image width="200" height="100" xlink:href="pic.bmp"/></svg>"""
        val image = calls("""<p>x <span style="font-size:20px">$svg</span> y</p>""").filterIsInstance<RecordingCanvas.Call.Image>().single()
        assertEquals(30.0, image.ctm.a, 1e-9)
        assertEquals(15.0, image.ctm.d, 1e-9)
    }

    @Test
    fun a_hidden_svg_takes_no_space() {
        fun firstLineTop(body: String) = page(body).textContent().blocks.first().lines.first().bounds.top
        val sprites = """<div><svg xmlns="http://www.w3.org/2000/svg" style="display:none"><defs><path id="g" d="M0 0L1 1"/></defs></svg></div>"""
        assertEquals(firstLineTop("<p>Text</p>"), firstLineTop("$sprites<p>Text</p>"), 1e-9)
        assertEquals(firstLineTop("<p>Text</p>"), firstLineTop("<p><span>${sprites.removePrefix("<div>").removeSuffix("</div>")}</span>Text</p>"), 1e-9)
    }

    @Test
    fun an_svg_hidden_from_assistive_technology_stays_out_of_the_reading_order() {
        val hidden = icon.replace("<svg ", """<svg aria-hidden="true" """)
        val items = page("<p>Before <span>$hidden</span> after</p>").readingOrder()
        assertTrue(items.none { it.role == EpubRole.IMAGE }, "$items")
    }
}
