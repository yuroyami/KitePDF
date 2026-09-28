package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.epub.EpubDocument
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The visual CSS of fixed-layout books, checked on the pixels an AWT raster gives (#28). The
 * content box of the first page starts at (48, 48): a 36 point margin and the body's 12 point
 * margin, one pixel a point.
 */
class EpubVisualCssTest {

    private fun raster(body: String, extra: List<Pair<String, ByteArray>> = emptyList()): BufferedImage =
        EpubCorpus.rasterize(EpubDocument.open(EpubCorpus.epub(body, extra)).pages.first())

    private fun rgb(img: BufferedImage, x: Int, y: Int): Triple<Int, Int, Int> {
        val p = img.getRGB(x, y)
        return Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
    }

    private fun assertColor(img: BufferedImage, x: Int, y: Int, r: Int, g: Int, b: Int, what: String, tol: Int = 12) {
        val (pr, pg, pb) = rgb(img, x, y)
        assertTrue(abs(pr - r) <= tol && abs(pg - g) <= tol && abs(pb - b) <= tol, "$what at ($x, $y): expected ($r, $g, $b), got ($pr, $pg, $pb)")
    }

    private fun darkIn(img: BufferedImage, top: Int, bottom: Int): Int {
        var n = 0
        for (y in top until bottom) for (x in 0 until img.width) {
            val (r, g, b) = rgb(img, x, y)
            if (r * 0.299 + g * 0.587 + b * 0.114 < 128) n++
        }
        return n
    }

    @Test
    fun opacity_blends_the_box_with_the_paper_under_it() {
        val img = raster("""<div style="background-color:#ff0000;opacity:0.5;height:50px"></div>""")
        assertColor(img, 100, 60, 255, 128, 128, "a half-transparent red box")
    }

    @Test
    fun opacity_applies_to_the_box_and_its_content_as_one_group() {
        // A blue box covers part of a red one inside a parent at half opacity. As one group, the
        // blue hides the red under it, and the group then blends with the paper.
        val img = raster(
            """<div style="opacity:0.5;position:relative;height:60px">""" +
                """<div style="background-color:#ff0000;height:60px"></div>""" +
                """<div style="position:absolute;left:0;top:0;width:40px;height:60px;background-color:#0000ff"></div></div>""",
        )
        assertColor(img, 58, 60, 128, 128, 255, "the blue box over the red one")
        assertColor(img, 200, 60, 255, 128, 128, "the red box alone")
    }

    /** The first row that holds a pixel near pure blue, or -1. */
    private fun firstBlueRow(img: BufferedImage): Int {
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val (r, g, b) = rgb(img, x, y)
            if (b > 150 && r < 100 && g < 100) return y
        }
        return -1
    }

    @Test
    fun a_hidden_box_keeps_its_room_and_a_visible_child_still_paints() {
        val after = """<p style="color:#0000ff">Shown words</p>"""
        val hidden = raster("""<p style="visibility:hidden;font-size:40px;color:#000000">HIDDEN</p>$after""")
        val shown = raster("""<p style="font-size:40px;color:#000000">HIDDEN</p>$after""")
        val blueTop = firstBlueRow(shown)
        assertTrue(blueTop > 60, "the fixture needs the blue paragraph below the big one: $blueTop")
        // Everything above the blue paragraph is the big one.
        assertEquals(0, darkIn(hidden, 0, blueTop - 1), "the hidden paragraph painted")
        assertTrue(darkIn(shown, 0, blueTop - 1) > 100)
        // The paragraph after it sits where it sits when the first one shows.
        assertEquals(blueTop, firstBlueRow(hidden), "the hidden paragraph lost its room")
        val child = raster("""<div style="visibility:hidden"><p style="visibility:visible">Back again</p></div>""")
        assertTrue(darkIn(child, 48, 100) > 20, "a visible child of a hidden box did not paint")
        // A child that says nothing inherits the hidden visibility.
        assertEquals(0, darkIn(raster("""<div style="visibility:hidden"><p>Inherited</p></div>"""), 0, 200))
    }

    @Test
    fun border_radius_rounds_the_background_the_border_an_image_and_a_clip() {
        // 100 pixels are 75 points, so a circle of radius 37.5 around (85.5, 85.5).
        val circle = raster("""<div style="width:100px;height:100px;background-color:#ff0000;border-radius:50%"></div>""")
        assertColor(circle, 85, 85, 255, 0, 0, "the middle of the circle")
        assertColor(circle, 86, 49, 255, 0, 0, "the top of the circle")
        assertColor(circle, 50, 50, 255, 255, 255, "the corner outside the circle")

        // A 6 pixel border is 4.5 points, and the corners round with a 15 point radius.
        val ring = raster("""<div style="width:100px;height:60px;border:6px solid #0000ff;border-radius:20px"></div>""")
        assertColor(ring, 90, 50, 0, 0, 255, "the top border")
        assertColor(ring, 49, 49, 255, 255, 255, "the rounded corner")
        assertColor(ring, 90, 70, 255, 255, 255, "the inside of the border")

        val picture = raster(
            """<img src="pic.png" style="display:block;width:100px;height:100px;border-radius:50%"/>""",
            listOf("OEBPS/pic.png" to EpubCorpus.redPng()),
        )
        assertColor(picture, 85, 85, 255, 0, 0, "the middle of the round picture")
        assertColor(picture, 50, 50, 255, 255, 255, "the corner of the round picture")

        val clip = raster(
            """<div style="width:100px;height:100px;overflow:hidden;border-radius:50%">""" +
                """<div style="width:100px;height:100px;background-color:#00ff00"></div></div>""",
        )
        assertColor(clip, 85, 85, 0, 255, 0, "the child in the round clip")
        assertColor(clip, 50, 50, 255, 255, 255, "the child outside the round clip")
    }

    @Test
    fun box_shadow_paints_an_offset_copy_outside_the_box_and_a_blur_fades() {
        // The box is 75 by 37.5 points at (48, 48), and the shadow 7.5 points right and down.
        val solid = raster("""<div style="width:100px;height:50px;background-color:#ffffff;box-shadow:10px 10px 0 #000000"></div>""")
        assertColor(solid, 127, 70, 0, 0, 0, "the shadow right of the box")
        assertColor(solid, 80, 70, 255, 255, 255, "under the box")
        assertColor(solid, 50, 89, 255, 255, 255, "left of the shadow below the box")
        assertColor(solid, 80, 89, 0, 0, 0, "the shadow below the box")

        // A 20 pixel blur is 15 points: it fades from 7.5 points outside the box's edge at 123.
        val blurred = raster("""<div style="width:100px;height:50px;box-shadow:0 0 20px #000000"></div>""")
        val near = rgb(blurred, 125, 66).first
        val far = rgb(blurred, 129, 66).first
        assertEquals(255, rgb(blurred, 132, 66).first, "the blur reaches too far")
        assertTrue(near < far && far < 255, "the blur does not fade out: $near near the box, $far farther away")
        assertColor(blurred, 80, 66, 255, 255, 255, "inside a box without a background", tol = 2)
    }

    @Test
    fun hidden_overflow_clips_the_content_but_not_the_border() {
        val child = """<div style="width:300px;height:40px;background-color:#00ff00"></div>"""
        val clipped = raster("""<div style="width:100px;height:40px;overflow:hidden">$child</div>""")
        // The parent is 75 points wide and the child 225 points.
        assertColor(clipped, 60, 60, 0, 255, 0, "the child inside its parent")
        assertColor(clipped, 200, 60, 255, 255, 255, "the child outside its parent")
        val open = raster("""<div style="width:100px;height:40px">$child</div>""")
        assertColor(open, 200, 60, 0, 255, 0, "the child of a parent that does not clip")

        // A 4 pixel border is 3 points, outside the padding box that clips.
        val bordered = raster("""<div style="width:100px;height:40px;overflow:hidden;border:4px solid #0000ff">$child</div>""")
        assertColor(bordered, 127, 60, 0, 0, 255, "the right border")
        assertColor(bordered, 140, 60, 255, 255, 255, "the child past the border")
    }
}
