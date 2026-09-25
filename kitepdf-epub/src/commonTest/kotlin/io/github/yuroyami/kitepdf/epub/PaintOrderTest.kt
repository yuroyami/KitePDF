package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/** The paint order of a page, after CSS 2.1, Appendix E (#172). */
class PaintOrderTest {

    /** The text of each run, `image` for each picture and `fill` for each background, in paint order. */
    private fun order(body: String): List<String> {
        val doc = EpubDocument.open(
            EpubFixtures.epub(body, extraEntries = listOf("OEBPS/pic.bmp" to EpubFixtures.bmp2x1())),
            EpubSettings(pageWidth = 400.0, pageHeight = 640.0),
        )
        return RecordingCanvas().also { doc.pages[0].renderTo(it, KiteMatrix.IDENTITY) }.calls.mapNotNull {
            when (it) {
                is RecordingCanvas.Call.Glyphs -> it.text.trim().ifEmpty { null }
                is RecordingCanvas.Call.Image -> "image"
                is RecordingCanvas.Call.Fill -> "fill"
                else -> null
            }
        }
    }

    @Test
    fun a_block_image_and_the_lines_paint_in_document_order() {
        // The text comes after the image, so it paints over the image where the two overlap.
        val img = """<img src="pic.bmp" style="display:block" width="40" height="40"/>"""
        assertEquals(listOf("image", "after"), order("""$img<p style="margin-top:-30px">after</p>"""))
        assertEquals(listOf("before", "image"), order("""<p>before</p>$img"""))
    }

    @Test
    fun positioned_boxes_paint_after_the_flow_in_the_order_of_their_z_index() {
        assertEquals(listOf("B", "A"), order("""<p style="position:relative">A</p><p>B</p>"""))
        assertEquals(listOf("B", "A"), order("""<p>A</p><p style="position:relative; z-index:-1">B</p>"""))
        assertEquals(
            listOf("C", "B", "A"),
            order("""<p style="position:relative; z-index:3">A</p><p style="position:relative; z-index:2">B</p><p>C</p>"""),
        )
    }

    @Test
    fun the_background_of_a_positioned_box_paints_over_the_flow_before_it() {
        assertEquals(listOf("A", "fill", "B"), order("""<p>A</p><div style="position:relative; background-color:red">B</div>"""))
        // In the flow, every background paints before every line.
        assertEquals(listOf("fill", "A", "B"), order("""<p>A</p><div style="background-color:red">B</div>"""))
    }
}
