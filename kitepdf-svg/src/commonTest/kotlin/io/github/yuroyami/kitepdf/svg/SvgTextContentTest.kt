package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The text an SVG draws, for search, selection and the fragments an overlay names (#523). */
class SvgTextContentTest {

    private fun image(body: String) =
        checkNotNull(SvgImage.parse("""<svg xmlns="http://www.w3.org/2000/svg" width="400" height="300">$body</svg>""".encodeToByteArray()))

    @Test
    fun runs_on_one_baseline_make_a_line_and_lines_a_line_apart_make_a_block() {
        val text = image(
            """<g id="para"><text x="10" y="20" font-size="10">Call me <tspan font-weight="bold">Ishmael</tspan>.</text>
              <text x="10" y="32" font-size="10">Some years ago</text></g>
              <text x="10" y="200" font-size="10">  The end  </text>""",
        ).textContent(KiteMatrix.IDENTITY).text
        assertEquals(listOf(listOf("Call me Ishmael.", "Some years ago"), listOf("The end")), text.blocks.map { b -> b.lines.map { it.text } })
        // Search runs across the line break of a block, and not across two blocks.
        assertEquals(1, text.search("Ishmael. Some").size)
        assertEquals(0, text.search("ago The").size)
    }

    @Test
    fun a_line_sits_where_its_glyphs_are_drawn() {
        val svg = image("""<text x="40" y="100" font-size="20">Hello</text>""")
        val ctm = KiteMatrix(2.0, 0.0, 0.0, 2.0, 5.0, 7.0)
        val line = svg.textContent(ctm).text.blocks.single().lines.single()
        val run = RecordingCanvas().also { svg.render(it, ctm) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().single()
        val m = run.textToDevice
        val width = run.glyphs.sumOf { it.advanceWidth } * run.fontSize / 1000.0 * m.a
        assertEquals(m.e, line.charEdges.first(), 1e-9)
        assertEquals(m.e + width, line.charEdges.last(), 1e-9)
        assertTrue(line.charEdges.toList().zipWithNext().all { (a, b) -> b > a }, "edges run left to right: ${line.charEdges.toList()}")
        // The smaller y is kept in bottom; the line crosses its baseline at y = 7 + 2 * 100.
        assertTrue(line.bounds.bottom < 207.0 && line.bounds.top > 207.0, "${line.bounds}")
        assertTrue(line.bounds.top - line.bounds.bottom > 30.0, "the box is about an em of 40 high: ${line.bounds}")
    }

    @Test
    fun text_that_is_paint_or_hidden_is_not_the_image_text() {
        val text = image(
            """<defs>
                 <pattern id="p" width="20" height="20" patternUnits="userSpaceOnUse"><text x="0" y="10">tile</text></pattern>
                 <clipPath id="none"><rect x="0" y="0" width="1" height="1"/></clipPath>
                 <text id="unused" x="0" y="10">defined</text>
               </defs>
               <rect x="0" y="0" width="100" height="100" fill="url(#p)"/>
               <text x="10" y="150" clip-path="url(#none)">clipped</text>
               <text x="10" y="170" display="none">gone</text>
               <text x="10" y="190" visibility="hidden">unseen</text>
               <text x="10" y="250">shown</text>""",
        ).textContent(KiteMatrix.IDENTITY).text
        assertEquals("shown", text.plainText)
    }

    @Test
    fun an_element_is_found_by_its_lines_or_by_what_it_draws() {
        val content = image(
            """<g id="para"><text x="10" y="20" font-size="10">first line</text><text x="10" y="32" font-size="10">second</text></g>
               <text x="10" y="60" font-size="10">elsewhere <tspan id="word">here</tspan></text>
               <rect id="box" x="100" y="150" width="50" height="20"/>""",
        ).textContent(KiteMatrix.IDENTITY)
        val para = content.rectsOf("para")
        assertEquals(2, para.size)
        assertTrue(para[0].bottom < 20.0 && para[0].top > 20.0 && para[1].bottom < 32.0 && para[1].top > 32.0, "$para")
        val word = content.rectsOf("word").single()
        val line = content.text.blocks.last().lines.single()
        assertEquals(line.charEdges["elsewhere ".length], word.left, 1e-9)
        assertEquals(line.charEdges.last(), word.right, 1e-9)
        val box = content.rectsOf("box").single()
        assertEquals(listOf(100.0, 150.0, 150.0, 170.0), listOf(box.left, box.bottom, box.right, box.top))
        assertEquals(emptyList(), content.rectsOf("missing"))
    }

    @Test
    fun a_standalone_svg_page_gives_its_text() {
        val page = SvgDocument.open(
            """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100"><text x="10" y="50">Label</text></svg>""".encodeToByteArray(),
        ).pages.single()
        val text = checkNotNull(page.textContent())
        assertEquals("Label", text.plainText)
        assertEquals(1, text.search("abe").size)
    }
}
