package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlinx.browser.document
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The raster step on Canvas2D: a box of device pixels that a step reads, paints into and draws back (#209, #308). */
class Canvas2dRasterStepTest {

    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
        KitePath.Builder().apply { rectangle(x0, y0, x1 - x0, y1 - y0) }.build()

    /** A white canvas of 20 by 20 pixels that [paint] draws on, with device space as user space. */
    private fun page(paint: (Canvas2dCanvas) -> Unit): CanvasRenderingContext2D {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = 20
        canvas.height = 20
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.fillStyle = "white"
        ctx.fillRect(0.0, 0.0, 20.0, 20.0)
        val kite = Canvas2dCanvas(ctx)
        kite.beginPage(20.0, 20.0, KiteMatrix.IDENTITY)
        paint(kite)
        kite.endPage()
        return ctx
    }

    private fun fill(canvas: Canvas2dCanvas, path: KitePath, color: RgbColor, alpha: Double = 1.0, mode: KiteBlendMode = KiteBlendMode.Normal) =
        canvas.fillPath(path, KiteMatrix.IDENTITY, color, evenOdd = false, alpha = alpha, blendMode = mode)

    private fun CanvasRenderingContext2D.assertRgb(x: Int, y: Int, expected: List<Int>, tolerance: Int = 3) {
        val data = getImageData(x.toDouble(), y.toDouble(), 1.0, 1.0).data.asDynamic()
        val actual = listOf(data[0] as Int, data[1] as Int, data[2] as Int)
        assertTrue(actual.zip(expected).all { (a, e) -> abs(a - e) <= tolerance }, "pixel ($x, $y): expected $expected, got $actual")
    }

    @Test
    fun a_step_reads_the_page_paints_onto_it_and_draws_back_in_its_blend_mode() {
        val ctx = page { c ->
            fill(c, rect(0.0, 0.0, 10.0, 20.0), RgbColor(0.0, 1.0, 0.0))
            val ran = c.rasterStep(KiteRectangle(5.0, 5.0, 15.0, 15.0), KiteMatrix.IDENTITY) { scope ->
                assertEquals(10, scope.width)
                val backdrop = assertNotNull(scope.backdrop())
                assertEquals(0xFF00FF00.toInt(), backdrop[0, 0])
                assertEquals(-1, backdrop[9, 9])
                val over = scope.render(backdrop) { fill(c, rect(0.0, 0.0, 20.0, 20.0), RgbColor(1.0, 1.0, 0.0), mode = KiteBlendMode.Multiply) }
                assertEquals(0xFF00FF00.toInt(), over[0, 0])
                assertEquals(0xFFFFFF00.toInt(), over[9, 9])
                val half = scope.render { fill(c, rect(0.0, 0.0, 20.0, 20.0), RgbColor(1.0, 0.0, 0.0), alpha = 0.5) }
                // A render gives straight colour, which the canvas keeps premultiplied, so red can lose a level or two.
                val red = (half[3, 3] ushr 16) and 0xFF
                assertTrue(red >= 250 && half[3, 3] and 0xFFFF == 0, "half red reads as red, got ${(half[3, 3] and 0xFFFFFF).toString(16)}")
                val flat = KiteRaster(10, 10).apply { pixels.fill(0xFFFF0000.toInt()) }
                scope.draw(flat, alpha = 0.5, blendMode = KiteBlendMode.Screen)
                true
            }
            assertTrue(ran)
        }
        // Red screened onto green at half alpha, and onto white; nothing outside the box.
        ctx.assertRgb(7, 7, listOf(128, 255, 0))
        ctx.assertRgb(12, 7, listOf(255, 255, 255))
        ctx.assertRgb(2, 7, listOf(0, 255, 0))
        ctx.assertRgb(7, 17, listOf(0, 255, 0))
    }
}
