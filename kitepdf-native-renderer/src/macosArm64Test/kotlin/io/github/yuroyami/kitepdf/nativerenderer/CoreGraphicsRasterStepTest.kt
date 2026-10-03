package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The raster step on CoreGraphics (#209, #308). The context's user space is its device space,
 * with y up, and a raster's first row is the top of its box.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreGraphicsRasterStepTest {

    private val side = 20

    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
        KitePath.Builder().apply { rectangle(x0, y0, x1 - x0, y1 - y0) }.build()

    /** Draws through [block] onto a white bitmap context, and returns its pixels, first row on top. */
    private fun render(block: (CoreGraphicsCanvas) -> Unit): UByteArray {
        val pixels = UByteArray(side * side * 4)
        pixels.usePinned { pinned ->
            val ctx = CGBitmapContextCreate(
                pinned.addressOf(0), side.toULong(), side.toULong(),
                8u, (side * 4).toULong(), CGColorSpaceCreateDeviceRGB(),
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )!!
            CGContextSetRGBFillColor(ctx, 1.0, 1.0, 1.0, 1.0)
            CGContextFillRect(ctx, CGRectMake(0.0, 0.0, side.toDouble(), side.toDouble()))
            block(CoreGraphicsCanvas(ctx))
            CGContextRelease(ctx)
        }
        return pixels
    }

    /** The colour at device pixel ([x], [y]), with y up. */
    private fun UByteArray.at(x: Int, y: Int): List<Int> {
        val p = ((side - 1 - y) * side + x) * 4
        return listOf(this[p].toInt(), this[p + 1].toInt(), this[p + 2].toInt())
    }

    private fun assertRgb(pixels: UByteArray, x: Int, y: Int, expected: List<Int>, tolerance: Int = 3) {
        val actual = pixels.at(x, y)
        assertTrue(actual.zip(expected).all { (a, e) -> abs(a - e) <= tolerance }, "pixel ($x, $y): expected $expected, got $actual")
    }

    private fun fill(canvas: CoreGraphicsCanvas, x0: Double, y0: Double, x1: Double, y1: Double, color: RgbColor, alpha: Double = 1.0, mode: KiteBlendMode = KiteBlendMode.Normal) =
        canvas.fillPath(rect(x0, y0, x1, y1), KiteMatrix.IDENTITY, color, evenOdd = false, alpha = alpha, blendMode = mode)

    @Test
    fun a_step_reads_the_bitmap_paints_onto_it_and_draws_back_in_its_blend_mode() {
        val pixels = render { c ->
            fill(c, 0.0, 0.0, 10.0, 20.0, RgbColor(0.0, 1.0, 0.0))
            val ran = c.rasterStep(KiteRectangle(5.0, 5.0, 15.0, 15.0), KiteMatrix.IDENTITY) { scope ->
                assertEquals(10, scope.width)
                // The top left corner of the box, (5, 15) with y up, is the first pixel.
                assertEquals(0.0, scope.toPixels.transformX(5.0, 15.0), 1e-9)
                assertEquals(0.0, scope.toPixels.transformY(5.0, 15.0), 1e-9)
                val backdrop = assertNotNull(scope.backdrop())
                assertEquals(0xFF00FF00.toInt(), backdrop[0, 0])
                assertEquals(-1, backdrop[9, 9])
                val over = scope.render(backdrop) { fill(c, 0.0, 0.0, 20.0, 20.0, RgbColor(1.0, 1.0, 0.0), mode = KiteBlendMode.Multiply) }
                assertEquals(0xFF00FF00.toInt(), over[0, 0])
                assertEquals(0xFFFFFF00.toInt(), over[9, 9])
                // A render that paints only the bottom half of the box fills the last rows of the raster.
                val bottom = scope.render { fill(c, 0.0, 0.0, 20.0, 10.0, RgbColor(1.0, 0.0, 0.0)) }
                assertEquals(0, bottom[3, 2] ushr 24)
                assertEquals(0xFFFF0000.toInt(), bottom[3, 7])
                val flat = KiteRaster(10, 10).apply { pixels.fill(0xFFFF0000.toInt()) }
                scope.draw(flat, alpha = 0.5, blendMode = KiteBlendMode.Screen)
                true
            }
            assertTrue(ran)
        }
        // Red screened onto green at half alpha, and onto white; nothing outside the box.
        assertRgb(pixels, 7, 7, listOf(128, 255, 0))
        assertRgb(pixels, 12, 7, listOf(255, 255, 255))
        assertRgb(pixels, 2, 7, listOf(0, 255, 0))
        assertRgb(pixels, 7, 17, listOf(0, 255, 0))
    }
}
