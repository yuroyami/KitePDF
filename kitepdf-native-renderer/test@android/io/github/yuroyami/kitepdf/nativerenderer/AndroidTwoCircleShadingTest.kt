package io.github.yuroyami.kitepdf.nativerenderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteColorSpace
import io.github.yuroyami.kitepdf.core.render.KiteFunction
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteShading
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * A radial shading between two circles whose centres differ keeps its highlight where the start
 * circle is. Android draws such a gradient from API 31 on, and below it the canvas drew one
 * around the end circle only, so the highlight moved to the end circle's centre (#591).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29, 35])
class AndroidTwoCircleShadingTest {

    /** A small white circle at (70, 130) inside a navy one of radius 85 at (100, 100): a spotlight. */
    private val spotlight = KiteShading.Radial(
        KiteColorSpace.DeviceRGB, null, null,
        doubleArrayOf(70.0, 130.0, 5.0, 100.0, 100.0, 85.0), doubleArrayOf(0.0, 1.0),
        KiteFunction.Type2(doubleArrayOf(0.0, 1.0), null, doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(0.0, 0.0, 0.5), 1.0),
        extendStart = true, extendEnd = true,
    )

    /** The spotlight over a white 200 by 200 bitmap, filled into [region] or the whole bitmap. */
    private fun draw(region: KitePath?): Bitmap {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        AndroidNativeCanvas(canvas).fillShading(spotlight, KiteMatrix.IDENTITY, region, 1.0, KiteBlendMode.Normal)
        return bitmap
    }

    private fun red(bitmap: Bitmap, x: Int, y: Int) = (bitmap.getPixel(x, y) shr 16) and 0xFF

    private fun assertRed(expected: Int, bitmap: Bitmap, x: Int, y: Int) {
        val actual = red(bitmap, x, y)
        assertTrue(abs(actual - expected) <= 6, "red at ($x, $y) is $actual, not $expected")
    }

    @Test
    fun the_highlight_stays_on_the_start_circle() {
        val bitmap = draw(region = null)
        // Inside the start circle s is below 0, so the start colour. At the end circle's centre
        // the circle of s = 0.306 passes, so 1 - 0.306 of white.
        assertRed(255, bitmap, 70, 130)
        assertRed(177, bitmap, 100, 100)
        // Outside the end circle the end colour holds.
        assertRed(0, bitmap, 195, 5)
    }

    @Test
    fun a_region_keeps_the_page_outside_it() {
        val triangle = KitePath.Builder().apply {
            moveTo(40.0, 40.0); lineTo(160.0, 40.0); lineTo(100.0, 140.0); close()
        }.build()
        val bitmap = draw(triangle)
        assertRed(177, bitmap, 100, 100)
        assertRed(255, bitmap, 20, 180)
        assertRed(255, bitmap, 180, 180)
    }
}
