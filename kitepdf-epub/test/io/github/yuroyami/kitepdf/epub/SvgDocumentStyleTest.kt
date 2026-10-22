package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An SVG embedded by inclusion is part of its XHTML document, so the document's style sheets
 * style its elements, as in a browser. One embedded by reference, through `img`, stays apart
 * (EPUB 3.3, SVG embedded in XHTML) (#509).
 */
class SvgDocumentStyleTest {

    private val green = RgbColor(0.0, 1.0, 0.0)

    private fun fills(body: String, extra: List<Pair<String, ByteArray>> = emptyList()): List<RgbColor> {
        val pages = EpubDocument.open(EpubFixtures.epub(body, extra), EpubSettings(pageWidth = 400.0, pageHeight = 640.0)).pages
        return pages.flatMap { page -> RecordingCanvas().also { page.renderTo(it) }.calls }
            .filterIsInstance<RecordingCanvas.Call.Fill>().map { it.color }
            .filter { it != RgbColor(1.0, 1.0, 1.0) }
    }

    private fun svg(inner: String, attrs: String = "") =
        """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" $attrs>$inner</svg>"""

    @Test
    fun a_class_rule_of_the_document_fills_an_inline_svg_path() {
        val body = """<style>.fil0 { fill: #00ff00 }</style><p>${svg("<path class='fil0' d='M0 0H20V20H0Z'/>")}</p>"""
        assertEquals(listOf(green), fills(body))
    }

    @Test
    fun the_document_selectors_match_across_the_svg_and_its_host() {
        val css = "<style>figure.cover svg .leaf { fill: #00ff00 } #art rect { fill: #00ff00 }</style>"
        val body = """$css<figure class="cover">${svg("<path class='leaf' d='M0 0H20V20H0Z'/>")}</figure>""" +
            """<p>${svg("<rect width='20' height='20'/>", "id='art'")}</p>"""
        assertEquals(listOf(green, green), fills(body))
    }

    @Test
    fun a_document_rule_outranks_a_presentation_attribute() {
        val body = """<style>.a { fill: #00ff00 }</style><p>${svg("<rect class='a' width='20' height='20' fill='#ff0000'/>")}</p>"""
        assertEquals(listOf(green), fills(body))
    }

    @Test
    fun the_svg_own_style_and_inline_style_still_win() {
        val own = """<style>.a { fill: #ff0000 }</style><p>${svg("<style>.a { fill: #00ff00 }</style><rect class='a' width='20' height='20'/>")}</p>"""
        assertEquals(listOf(green), fills(own), "the svg's own rule comes later in the document")
        val inline = """<style>.a { fill: #ff0000 }</style><p>${svg("<rect class='a' style='fill: #00ff00' width='20' height='20'/>")}</p>"""
        assertEquals(listOf(green), fills(inline), "a style attribute beats any rule")
        val important = """<style>.a { fill: #00ff00 !important }</style><p>${svg("<style>.a { fill: #ff0000 }</style><rect class='a' width='20' height='20'/>")}</p>"""
        assertEquals(listOf(green), fills(important), "an important rule of the document beats a normal one of the svg")
    }

    @Test
    fun an_svg_embedded_by_reference_keeps_its_own_style() {
        val file = svg("<rect class='a' width='20' height='20' fill='#00ff00'/>").encodeToByteArray()
        val body = """<style>.a { fill: #ff0000 }</style><p><img src="pic.svg" alt=""/></p>"""
        assertEquals(listOf(green), fills(body, listOf("OEBPS/pic.svg" to file)))
    }
}
