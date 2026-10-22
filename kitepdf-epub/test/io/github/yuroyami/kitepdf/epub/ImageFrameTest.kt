package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A block image paints its own background and border around the picture (CSS 2.1, 8.1 and 14.2, #101). */
class ImageFrameTest {

    private fun calls(body: String, writingMode: String? = null): List<RecordingCanvas.Call> {
        val book = EpubFixtures.epub(body, listOf("OEBPS/pic.bmp" to EpubFixtures.bmp2x1()), primaryWritingMode = writingMode)
        return EpubDocument.open(book).pages.flatMap { page -> RecordingCanvas().also { page.renderTo(it) }.calls }
    }

    /** [left, bottom, right, top] of [path] after [m]. */
    private fun box(path: KitePath, m: KiteMatrix): DoubleArray {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (seg in path.segments) when (seg) {
            is KitePath.Segment.MoveTo -> { xs += m.transformX(seg.x, seg.y); ys += m.transformY(seg.x, seg.y) }
            is KitePath.Segment.LineTo -> { xs += m.transformX(seg.x, seg.y); ys += m.transformY(seg.x, seg.y) }
            else -> {}
        }
        return doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /** The unit square an image draws into, after its matrix. */
    private fun box(m: KiteMatrix): DoubleArray = box(KitePath.Builder().apply { rectangle(0.0, 0.0, 1.0, 1.0) }.build(), m)

    private val framed = """<p>fig:</p><img src="pic.bmp" width="120" height="80"
        style="display:block;border:6px solid #0000ff;padding:4px;background-color:#00ff00"/>"""

    @Test
    fun a_framed_block_image_paints_its_background_and_border() {
        val calls = calls(framed)
        val fills = calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        assertEquals(1, fills.count { it.color == RgbColor(0.0, 1.0, 0.0) }, "the background paints once")
        assertEquals(4, fills.count { it.color == RgbColor(0.0, 0.0, 1.0) }, "one fill per border side")
    }

    @Test
    fun the_picture_sits_inside_the_border_and_padding() {
        val calls = calls(framed)
        val background = calls.filterIsInstance<RecordingCanvas.Call.Fill>().single { it.color == RgbColor(0.0, 1.0, 0.0) }
        val image = calls.filterIsInstance<RecordingCanvas.Call.Image>().single()
        val outer = box(background.path, background.ctm)
        val inner = box(image.ctm)
        // 120 by 80 CSS pixels is 90 by 60 points. Border 6 px plus padding 4 px is 7.5 points a side.
        assertEquals(90.0, inner[2] - inner[0], 1e-6)
        assertEquals(60.0, inner[3] - inner[1], 1e-6)
        for (side in 0..1) assertEquals(7.5, inner[side] - outer[side], 1e-6, "inset on side $side")
        for (side in 2..3) assertEquals(7.5, outer[side] - inner[side], 1e-6, "inset on side $side")
    }

    @Test
    fun an_unframed_image_keeps_its_place() {
        val plain = calls("""<p>fig:</p><img src="pic.bmp" width="120" height="80" style="display:block"/>""")
        assertTrue(plain.none { it is RecordingCanvas.Call.Fill }, "nothing to paint around the picture")
        assertEquals(90.0, box(plain.filterIsInstance<RecordingCanvas.Call.Image>().single().ctm).let { it[2] - it[0] }, 1e-6)
    }
}
