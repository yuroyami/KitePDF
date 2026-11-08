package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.KiteRectangle
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A clip, a group and a raster step on [KiteRasterCanvas] cost the area they cover, not the
 * canvas: each allocated arrays of the whole canvas, 16 MB for a 16 x 16 group on 2,048 x 2,048
 * pixels (#842).
 */
class RasterCanvasAllocationTest {

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private val square = KitePath.Builder().apply { rectangle(10.0, 10.0, 16.0, 16.0) }.build()
    private val red = RgbColor(1.0, 0.0, 0.0)

    private fun allocated(block: () -> Unit): Long {
        repeat(3) { block() }
        val id = Thread.currentThread().id
        var least = Long.MAX_VALUE
        repeat(5) {
            val before = threads.getThreadAllocatedBytes(id)
            block()
            least = minOf(least, threads.getThreadAllocatedBytes(id) - before)
        }
        return least
    }

    @Test
    fun a_small_clip_group_and_raster_step_cost_their_own_area() {
        val canvas = KiteRasterCanvas(2048, 2048)
        val clip = allocated {
            canvas.pushClip(square, KiteMatrix.IDENTITY, false)
            canvas.fillPath(square, KiteMatrix.IDENTITY, red, false)
            canvas.popClip()
        }
        val group = allocated {
            canvas.beginTransparencyGroup(KiteRectangle(10.0, 10.0, 26.0, 26.0), KiteMatrix.IDENTITY, isolated = true, knockout = false, alpha = 0.5, blendMode = KiteBlendMode.Normal)
            canvas.fillPath(square, KiteMatrix.IDENTITY, red, false)
            canvas.endTransparencyGroup()
        }
        val step = allocated {
            canvas.rasterStep(KiteRectangle(10.0, 10.0, 26.0, 26.0), KiteMatrix.IDENTITY) { scope ->
                val raster = scope.render { canvas.fillPath(square, KiteMatrix.IDENTITY, red, false) }
                scope.draw(raster)
                true
            }
        }
        // A canvas-sized array is 16 MB here; the boxes are a few hundred pixels.
        assertTrue(clip < 1_000_000, "a clip allocated $clip bytes")
        assertTrue(group < 1_000_000, "a group allocated $group bytes")
        assertTrue(step < 1_000_000, "a raster step allocated $step bytes")
        // And the square still draws, in the red the step and the group left.
        val raster = canvas.toRaster()
        assertEquals(255, raster[18, 18] ushr 24)
        assertEquals(255, raster[18, 18] ushr 16 and 0xFF)
        assertEquals(0, raster[40, 40] ushr 24)
    }
}
