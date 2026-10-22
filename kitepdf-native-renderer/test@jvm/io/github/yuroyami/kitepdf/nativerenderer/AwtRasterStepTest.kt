package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.KiteRasterScope
import io.github.yuroyami.kitepdf.core.render.RgbColor
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The raster step on AWT: a box of device pixels that a step reads, paints into and draws back (#209, #308). */
class AwtRasterStepTest {

    private val red = RgbColor(1.0, 0.0, 0.0)
    private val yellow = RgbColor(1.0, 1.0, 0.0)
    private val green = RgbColor(0.0, 1.0, 0.0)

    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
        KitePath.Builder().apply { rectangle(x0, y0, x1 - x0, y1 - y0) }.build()

    /** A page of 20 by 20 pixels filled with [paper], with device space as user space, that [paint] draws on. */
    private fun page(paper: Color = Color.WHITE, paint: (AwtCanvas) -> Unit): BufferedImage {
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = paper
        g.fillRect(0, 0, 20, 20)
        g.clip = java.awt.Rectangle(0, 0, 20, 20)
        val canvas = AwtCanvas(g)
        canvas.beginPage(20.0, 20.0, KiteMatrix.IDENTITY)
        paint(canvas)
        canvas.endPage()
        g.dispose()
        return image
    }

    private fun fill(canvas: AwtCanvas, path: KitePath, color: RgbColor, alpha: Double = 1.0, mode: KiteBlendMode = KiteBlendMode.Normal) =
        canvas.fillPath(path, KiteMatrix.IDENTITY, color, evenOdd = false, alpha = alpha, blendMode = mode)

    private fun assertRgb(image: BufferedImage, x: Int, y: Int, rgb: Int, tolerance: Int = 2) {
        val actual = image.getRGB(x, y)
        val close = intArrayOf(16, 8, 0).all { abs(((actual ushr it) and 0xFF) - ((rgb ushr it) and 0xFF)) <= tolerance }
        assertTrue(close, "($x, $y): expected ${Integer.toHexString(rgb)}, got ${Integer.toHexString(actual and 0xFFFFFF)}")
    }

    @Test
    fun a_step_paints_into_a_raster_of_its_box_and_draws_it_back() {
        var size = 0 to 0
        val image = page { c ->
            val ran = c.rasterStep(KiteRectangle(4.0, 4.0, 12.0, 10.0), KiteMatrix.IDENTITY) { scope ->
                size = scope.width to scope.height
                val raster = scope.render { fill(c, rect(0.0, 0.0, 20.0, 20.0), red) }
                // The content covers the whole box, and the step turns it green before it lands.
                for (i in raster.pixels.indices) raster.pixels[i] = 0xFF00FF00.toInt()
                scope.draw(raster)
                true
            }
            assertTrue(ran)
        }
        assertEquals(8 to 6, size)
        assertRgb(image, 5, 5, 0x00FF00)
        assertRgb(image, 11, 9, 0x00FF00)
        // Outside the box, neither the content nor the result shows.
        assertRgb(image, 3, 5, 0xFFFFFF)
        assertRgb(image, 12, 5, 0xFFFFFF)
        assertRgb(image, 5, 10, 0xFFFFFF)
    }

    @Test
    fun the_map_to_pixels_puts_the_region_at_the_top_left_of_the_raster() {
        var toPixels: KiteMatrix? = null
        page { c ->
            // A y-up region scaled by two: user (3, 1) lands on device (6, 18), the box's top left.
            val ctm = KiteMatrix(2.0, 0.0, 0.0, -2.0, 0.0, 20.0)
            c.rasterStep(KiteRectangle(3.0, 1.0, 7.0, 5.0), ctm) { scope ->
                toPixels = scope.toPixels
                true
            }
        }
        val m = assertNotNull(toPixels)
        assertEquals(0.0, m.transformX(3.0, 5.0), 1e-9)
        assertEquals(0.0, m.transformY(3.0, 5.0), 1e-9)
        assertEquals(8.0, m.transformX(7.0, 1.0), 1e-9)
        assertEquals(8.0, m.transformY(7.0, 1.0), 1e-9)
    }

    @Test
    fun the_backdrop_is_the_page_under_the_box_and_a_render_can_start_from_it() {
        val image = page { c ->
            fill(c, rect(0.0, 0.0, 10.0, 20.0), green)
            c.rasterStep(KiteRectangle(5.0, 5.0, 15.0, 15.0), KiteMatrix.IDENTITY) { scope ->
                val backdrop = assertNotNull(scope.backdrop())
                assertEquals(0xFF00FF00.toInt(), backdrop[0, 0], "green at the left of the box")
                assertEquals(0xFFFFFFFF.toInt(), backdrop[9, 9], "the white page at its right")
                // Yellow multiplied onto green is green, and onto white is yellow.
                val over = scope.render(backdrop) { fill(c, rect(0.0, 0.0, 20.0, 20.0), yellow, mode = KiteBlendMode.Multiply) }
                assertEquals(0xFF00FF00.toInt(), over[0, 0])
                assertEquals(0xFFFFFF00.toInt(), over[9, 9])
                // The render left the backdrop as it was.
                assertEquals(0xFFFFFFFF.toInt(), backdrop[9, 9])
                true
            }
        }
        // The step drew nothing, so the page is as it was.
        assertRgb(image, 12, 12, 0xFFFFFF)
    }

    @Test
    fun the_result_lands_in_its_blend_mode_at_its_alpha_inside_the_clip() {
        val image = page { c ->
            fill(c, rect(0.0, 0.0, 20.0, 20.0), green)
            c.pushClip(rect(0.0, 0.0, 10.0, 20.0), KiteMatrix.IDENTITY, evenOdd = false)
            c.rasterStep(KiteRectangle(0.0, 0.0, 20.0, 20.0), KiteMatrix.IDENTITY) { scope ->
                assertEquals(10, scope.width, "the box is cut to the clip")
                val raster = KiteRaster(scope.width, scope.height)
                raster.pixels.fill(0xFFFFFF00.toInt())
                scope.draw(raster, alpha = 0.5, blendMode = KiteBlendMode.Screen)
                true
            }
            c.popClip()
        }
        // Yellow screened onto green is yellow; half of it over green is (128, 255, 0).
        assertRgb(image, 5, 5, 0x80FF00)
        assertRgb(image, 15, 5, 0x00FF00)
    }

    @Test
    fun the_backdrop_under_a_curved_clip_is_the_page() {
        val circle = KitePath.Builder().apply {
            moveTo(18.0, 10.0)
            curveTo(18.0, 14.4, 14.4, 18.0, 10.0, 18.0)
            curveTo(5.6, 18.0, 2.0, 14.4, 2.0, 10.0)
            curveTo(2.0, 5.6, 5.6, 2.0, 10.0, 2.0)
            curveTo(14.4, 2.0, 18.0, 5.6, 18.0, 10.0)
            close()
        }.build()
        var middle = 0
        page { c ->
            fill(c, rect(0.0, 0.0, 20.0, 20.0), green)
            c.pushClip(circle, KiteMatrix.IDENTITY, evenOdd = false)
            // A paint inside the clip waits in the clip's layer until the step reads the backdrop.
            fill(c, rect(8.0, 8.0, 12.0, 12.0), red)
            c.rasterStep(KiteRectangle(0.0, 0.0, 20.0, 20.0), KiteMatrix.IDENTITY) { scope ->
                val backdrop = assertNotNull(scope.backdrop())
                middle = backdrop[10 - 2, 10 - 2]
                true
            }
            c.popClip()
        }
        assertEquals(0xFFFF0000.toInt(), middle, "the red square under the clip, not the clip's empty layer")
    }

    @Test
    fun a_box_outside_the_clip_runs_no_step() {
        var ran = false
        page { c ->
            c.pushClip(rect(0.0, 0.0, 5.0, 5.0), KiteMatrix.IDENTITY, evenOdd = false)
            assertTrue(c.rasterStep(KiteRectangle(10.0, 10.0, 15.0, 15.0), KiteMatrix.IDENTITY) { ran = true; true })
            c.popClip()
        }
        assertFalse(ran)
    }

    @Test
    fun a_step_that_declines_is_reported_to_the_caller() {
        page { c ->
            assertFalse(c.rasterStep(KiteRectangle(0.0, 0.0, 5.0, 5.0), KiteMatrix.IDENTITY) { false })
        }
    }

    @Test
    fun a_box_past_the_budget_works_at_a_lower_resolution_without_a_backdrop() {
        var scope: KiteRasterScope? = null
        var backdrop: KiteRaster? = KiteRaster(0, 0)
        val image = page { c ->
            c.maskPixelBudget = 100
            c.rasterStep(KiteRectangle(0.0, 0.0, 20.0, 20.0), KiteMatrix.IDENTITY) { s ->
                scope = s
                backdrop = s.backdrop()
                s.draw(s.render { fill(c, rect(0.0, 0.0, 10.0, 20.0), red) })
                true
            }
        }
        assertEquals(10, scope?.width)
        assertEquals(0.5, scope?.toPixels?.a)
        assertNull(backdrop)
        assertRgb(image, 3, 10, 0xFF0000)
        assertRgb(image, 16, 10, 0xFFFFFF)
    }

    @Test
    fun the_backdrop_of_a_step_inside_a_group_is_the_group() {
        var corner = 0
        page { c ->
            fill(c, rect(0.0, 0.0, 20.0, 20.0), green)
            c.beginTransparencyGroup(KiteRectangle(0.0, 0.0, 20.0, 20.0), KiteMatrix.IDENTITY, isolated = true, alpha = 0.5)
            fill(c, rect(0.0, 0.0, 5.0, 5.0), red)
            c.rasterStep(KiteRectangle(0.0, 0.0, 10.0, 10.0), KiteMatrix.IDENTITY) { scope ->
                val backdrop = assertNotNull(scope.backdrop())
                corner = backdrop[0, 0]
                assertEquals(0, backdrop[8, 8] ushr 24, "the isolated group is transparent where it has not painted")
                true
            }
            c.endTransparencyGroup()
        }
        assertEquals(0xFFFF0000.toInt(), corner)
    }
}
