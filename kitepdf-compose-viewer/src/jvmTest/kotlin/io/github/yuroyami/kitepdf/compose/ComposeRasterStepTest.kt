package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.svg.SvgImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The raster step on Compose: a box of the draw scope that a step reads, paints into and draws back (#209, #308). */
class ComposeRasterStepTest {

    private val yellow = RgbColor(1.0, 1.0, 0.0)
    private val green = RgbColor(0.0, 1.0, 0.0)

    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
        KitePath.Builder().apply { rectangle(x0, y0, x1 - x0, y1 - y0) }.build()

    /**
     * A white bitmap of [size] pixels that [body] draws on, with the scope's units as user space.
     * With [knowsTarget] the canvas knows the bitmap, as a rasterizer's does.
     */
    private fun paint(size: Int = 20, knowsTarget: Boolean = true, body: (ComposeCanvas) -> Unit): ImageBitmap {
        val bitmap = ImageBitmap(size, size)
        val density = Density(1f)
        val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
        CanvasDrawScope().drawOnTestUiThread(density, LayoutDirection.Ltr, Canvas(bitmap), Size(size.toFloat(), size.toFloat())) {
            drawRect(Color.White, size = this.size)
            val canvas = ComposeCanvas(this, measurer, 1f, false, magnification = 1f, target = if (knowsTarget) bitmap else null)
            canvas.beginPage(size.toDouble(), size.toDouble(), KiteMatrix.IDENTITY)
            body(canvas)
            canvas.endPage()
        }
        return bitmap
    }

    private fun fill(canvas: ComposeCanvas, path: KitePath, color: RgbColor, mode: KiteBlendMode = KiteBlendMode.Normal) =
        canvas.fillPath(path, KiteMatrix.IDENTITY, color, evenOdd = false, alpha = 1.0, blendMode = mode)

    private fun assertRgb(bitmap: ImageBitmap, x: Int, y: Int, rgb: Int, tolerance: Int = 3) {
        val actual = bitmap.toPixelMap()[x, y].toArgb()
        val close = intArrayOf(16, 8, 0).all { abs(((actual ushr it) and 0xFF) - ((rgb ushr it) and 0xFF)) <= tolerance }
        assertTrue(close, "($x, $y): expected ${rgb.toString(16)}, got ${(actual and 0xFFFFFF).toString(16)}")
    }

    @Test
    fun a_step_reads_the_bitmap_paints_onto_it_and_draws_back_in_its_blend_mode() {
        val image = paint { c ->
            fill(c, rect(0.0, 0.0, 10.0, 20.0), green)
            c.pushClip(rect(0.0, 0.0, 20.0, 15.0), KiteMatrix.IDENTITY, evenOdd = false)
            val ran = c.rasterStep(KiteRectangle(5.0, 5.0, 15.0, 20.0), KiteMatrix.IDENTITY) { scope ->
                assertEquals(10, scope.width)
                assertEquals(10, scope.height, "the box is cut to the clip")
                val backdrop = assertNotNull(scope.backdrop())
                assertEquals(0xFF00FF00.toInt(), backdrop[0, 0])
                assertEquals(-1, backdrop[9, 9])
                val over = scope.render(backdrop) { fill(c, rect(0.0, 0.0, 20.0, 20.0), yellow, KiteBlendMode.Multiply) }
                assertEquals(0xFF00FF00.toInt(), over[0, 0])
                assertEquals(0xFFFFFF00.toInt(), over[9, 9])
                // A render gives straight colour: half red is full red at half alpha.
                val half = scope.render { c.fillPath(rect(0.0, 0.0, 20.0, 20.0), KiteMatrix.IDENTITY, RgbColor(1.0, 0.0, 0.0), evenOdd = false, alpha = 0.5) }
                assertEquals(0xFF0000, half[3, 3] and 0xFFFFFF)
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
    fun a_canvas_on_screen_or_inside_a_layer_has_no_backdrop() {
        var onScreen: KiteRaster? = KiteRaster(0, 0)
        var inLayer: KiteRaster? = KiteRaster(0, 0)
        paint(knowsTarget = false) { c ->
            c.rasterStep(KiteRectangle(0.0, 0.0, 10.0, 10.0), KiteMatrix.IDENTITY) { onScreen = it.backdrop(); false }
        }
        paint { c ->
            c.beginTransparencyGroup(KiteRectangle(0.0, 0.0, 20.0, 20.0), KiteMatrix.IDENTITY, isolated = true, alpha = 0.5)
            c.rasterStep(KiteRectangle(0.0, 0.0, 10.0, 10.0), KiteMatrix.IDENTITY) { inLayer = it.backdrop(); false }
            c.endTransparencyGroup()
        }
        assertNull(onScreen)
        assertNull(inLayer)
    }

    @Test
    fun an_svg_filter_draws_on_compose() {
        val svg = assertNotNull(
            SvgImage.parse(
                ("""<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">""" +
                    """<filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="100" height="100">""" +
                    """<feDropShadow dx="10" dy="10" stdDeviation="0" flood-color="#00f"/><feColorMatrix type="saturate" values="0"/></filter>""" +
                    """<rect x="20" y="20" width="20" height="20" fill="#f00" filter="url(#f)"/></svg>""").encodeToByteArray(),
            ),
        )
        val image = paint(100, knowsTarget = false) { c -> svg.render(c, KiteMatrix.IDENTITY) }
        // Red at saturation 0 in linear light, 0.213, is 128 in sRGB; the blue shadow, 0.072, is 75.
        assertRgb(image, 30, 30, 0x808080)
        assertRgb(image, 45, 45, 0x4B4B4B)
        assertRgb(image, 10, 10, 0xFFFFFF)
    }
}
