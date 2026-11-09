package io.github.yuroyami.kitepdf.nativerenderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.RgbColor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The raster step on the Android canvas: a box of device pixels that a step reads, paints into
 * and draws back (#209, #308), as `SkiaRasterStepTest` and `Canvas2dRasterStepTest` check it (#893).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29, 35])
class AndroidRasterStepTest {

    private val red = RgbColor(1.0, 0.0, 0.0)
    private val yellow = RgbColor(1.0, 1.0, 0.0)
    private val green = RgbColor(0.0, 1.0, 0.0)

    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
        KitePath.Builder().apply { rectangle(x0, y0, x1 - x0, y1 - y0) }.build()

    /** A white bitmap of 20 by 20 pixels that [paint] draws on, with device space as user space. */
    private fun page(paint: (AndroidNativeCanvas) -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val host = Canvas(bitmap)
        host.drawColor(Color.WHITE)
        val canvas = AndroidNativeCanvas(host).apply { target = bitmap }
        canvas.beginPage(20.0, 20.0, KiteMatrix.IDENTITY)
        paint(canvas)
        canvas.endPage()
        return bitmap
    }

    private fun fill(canvas: AndroidNativeCanvas, path: KitePath, color: RgbColor, alpha: Double = 1.0, mode: KiteBlendMode = KiteBlendMode.Normal) =
        canvas.fillPath(path, KiteMatrix.IDENTITY, color, evenOdd = false, alpha = alpha, blendMode = mode)

    /** Asserts the device pixel ([x], [y]), with y down, is [rgb] within [tolerance] levels. */
    private fun assertRgb(bitmap: Bitmap, x: Int, y: Int, rgb: Int, tolerance: Int = 3) {
        val actual = bitmap.getPixel(x, y)
        val close = intArrayOf(16, 8, 0).all { abs(((actual ushr it) and 0xFF) - ((rgb ushr it) and 0xFF)) <= tolerance }
        assertTrue(close, "($x, $y): expected ${rgb.toString(16)}, got ${(actual and 0xFFFFFF).toString(16)}")
    }

    @Test
    fun a_step_reads_the_page_paints_onto_it_and_draws_back_in_its_blend_mode() {
        val image = page { c ->
            fill(c, rect(0.0, 0.0, 10.0, 20.0), green)
            c.pushClip(rect(0.0, 0.0, 20.0, 15.0), KiteMatrix.IDENTITY, evenOdd = false)
            val ran = c.rasterStep(KiteRectangle(5.0, 5.0, 15.0, 20.0), KiteMatrix.IDENTITY) { scope ->
                assertEquals(10, scope.width)
                assertEquals(10, scope.height, "the box is cut to the clip")
                assertEquals(0.0, scope.toPixels.transformX(5.0, 5.0), 1e-9)
                val backdrop = assertNotNull(scope.backdrop())
                assertEquals(0xFF00FF00.toInt(), backdrop[0, 0])
                assertEquals(-1, backdrop[9, 9])
                // Yellow multiplied onto a copy of the page.
                val over = scope.render(backdrop) { fill(c, rect(0.0, 0.0, 20.0, 20.0), yellow, mode = KiteBlendMode.Multiply) }
                assertEquals(0xFF00FF00.toInt(), over[0, 0])
                assertEquals(0xFFFFFF00.toInt(), over[9, 9])
                // A render gives straight colour: half red is full red at half alpha.
                val half = scope.render { fill(c, rect(0.0, 0.0, 20.0, 20.0), red, alpha = 0.5) }
                val r = (half[3, 3] ushr 16) and 0xFF
                assertTrue(r >= 250 && half[3, 3] and 0xFFFF == 0, "half red reads as red, got ${(half[3, 3] and 0xFFFFFF).toString(16)}")
                assertTrue(abs((half[3, 3] ushr 24) - 0x80) <= 1, "alpha ${half[3, 3] ushr 24}")
                val flat = KiteRaster(10, 10).apply { pixels.fill(0xFFFF0000.toInt()) }
                scope.draw(flat, alpha = 0.5, blendMode = KiteBlendMode.Screen)
                true
            }
            assertTrue(ran)
            c.popClip()
        }
        // Red screened onto green at half alpha, and onto white; nothing below the clip or outside the box.
        assertRgb(image, 7, 7, 0x80FF00)
        assertRgb(image, 12, 7, 0xFFFFFF)
        assertRgb(image, 12, 17, 0xFFFFFF)
        assertRgb(image, 2, 7, 0x00FF00)
    }

    @Test
    fun inside_a_layer_the_step_has_no_backdrop() {
        var backdrop: KiteRaster? = KiteRaster(0, 0)
        page { c ->
            c.beginTransparencyGroup(KiteRectangle(0.0, 0.0, 20.0, 20.0), KiteMatrix.IDENTITY, isolated = true, alpha = 0.5)
            c.rasterStep(KiteRectangle(0.0, 0.0, 10.0, 10.0), KiteMatrix.IDENTITY) { scope ->
                backdrop = scope.backdrop()
                false
            }
            c.endTransparencyGroup()
        }
        assertNull(backdrop)
    }

    @Test
    fun a_render_keeps_the_clips_and_groups_of_its_content_to_itself() {
        val image = page { c ->
            c.rasterStep(KiteRectangle(0.0, 0.0, 20.0, 20.0), KiteMatrix.IDENTITY) { scope ->
                val raster = scope.render {
                    c.pushClip(rect(0.0, 0.0, 10.0, 20.0), KiteMatrix.IDENTITY, evenOdd = false)
                    c.beginTransparencyGroup(KiteRectangle(0.0, 0.0, 20.0, 20.0), KiteMatrix.IDENTITY, isolated = true, alpha = 0.5)
                    fill(c, rect(0.0, 0.0, 20.0, 20.0), red)
                    // The group and the clip are left open: they end with the render.
                }
                assertTrue(abs((raster[5, 5] ushr 24) - 0x80) <= 2, "half alpha inside the clip")
                assertEquals(0, raster[15, 5] ushr 24)
                scope.draw(raster)
                true
            }
            // Back on the page, a paint is neither clipped nor faded.
            fill(c, rect(0.0, 18.0, 20.0, 20.0), green)
        }
        assertRgb(image, 5, 5, 0xFF8080)
        assertRgb(image, 15, 19, 0x00FF00)
    }
}
