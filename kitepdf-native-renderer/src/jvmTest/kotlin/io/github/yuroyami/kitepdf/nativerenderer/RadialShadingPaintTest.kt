package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.render.GradientStops
import io.github.yuroyami.kitepdf.core.render.RgbColor
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [RadialShadingPaint] colours each pixel from the circle through it (ISO 32000-1, 8.7.4.5.4). */
class RadialShadingPaintTest {

    private val redToBlue = GradientStops(doubleArrayOf(0.0, 1.0), arrayOf(RgbColor(1.0, 0.0, 0.0), RgbColor(0.0, 0.0, 1.0)))

    /** A 200 by 200 image filled with the radial shading between the circles in [coords]. */
    private fun fill(coords: DoubleArray, extendStart: Boolean = true, extendEnd: Boolean = true): BufferedImage {
        val image = BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.paint = RadialShadingPaint(coords, extendStart, extendEnd, redToBlue, AffineTransform())
        g.fillRect(0, 0, 200, 200)
        g.dispose()
        return image
    }

    private fun alpha(argb: Int) = argb ushr 24
    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF

    private val concentric = doubleArrayOf(100.0, 100.0, 10.0, 100.0, 100.0, 90.0)

    @Test
    fun a_pixel_takes_the_colour_of_the_circle_through_it() {
        // The centre of pixel (150, 100) is 50.5 from the centre, so s = (50.5 - 10) / 80, about 0.506.
        val c = fill(concentric).getRGB(150, 100)
        assertEquals(126.0, red(c).toDouble(), 1.0)
        assertEquals(129.0, blue(c).toDouble(), 1.0)
    }

    @Test
    fun an_extended_end_keeps_its_colour_past_the_circle() {
        val image = fill(concentric)
        assertEquals(0xFFFF0000.toInt(), image.getRGB(100, 100), "inside the start circle")
        assertEquals(0xFF0000FF.toInt(), image.getRGB(196, 100), "outside the end circle")
    }

    @Test
    fun an_end_that_is_not_extended_stays_unpainted() {
        val image = fill(concentric, extendStart = false, extendEnd = false)
        assertEquals(0, alpha(image.getRGB(100, 100)), "inside the start circle")
        assertEquals(0, alpha(image.getRGB(196, 100)), "outside the end circle")
        assertEquals(255, alpha(image.getRGB(150, 100)), "between the circles")
    }

    @Test
    fun an_offset_start_circle_keeps_its_highlight() {
        // The start circle sits at (70, 130) inside the end circle, so its colour stays there.
        val image = fill(doubleArrayOf(70.0, 130.0, 5.0, 100.0, 100.0, 85.0))
        assertEquals(0xFFFF0000.toInt(), image.getRGB(70, 130))
        // The same distance from the end centre on the far side is much further along.
        assertTrue(blue(image.getRGB(130, 70)) > 150, "far side is mostly blue")
    }

    @Test
    fun a_shrinking_shading_runs_inwards() {
        val image = fill(doubleArrayOf(100.0, 100.0, 90.0, 100.0, 100.0, 10.0))
        assertEquals(0xFF0000FF.toInt(), image.getRGB(100, 100), "inside the end circle")
        assertEquals(0xFFFF0000.toInt(), image.getRGB(196, 100), "outside the start circle")
    }
}
