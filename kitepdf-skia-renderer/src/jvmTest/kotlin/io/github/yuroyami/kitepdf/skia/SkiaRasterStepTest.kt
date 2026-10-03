package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.svg.SvgImage
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The raster step on Skia: a box of device pixels that a step reads, paints into and draws back (#209, #308). */
class SkiaRasterStepTest {

    private val red = RgbColor(1.0, 0.0, 0.0)
    private val yellow = RgbColor(1.0, 1.0, 0.0)
    private val green = RgbColor(0.0, 1.0, 0.0)

    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
        KitePath.Builder().apply { rectangle(x0, y0, x1 - x0, y1 - y0) }.build()

    /** A white page of [size] pixels that [paint] draws on, with device space as user space, as straight ARGB. */
    private fun page(size: Int = 20, paint: (SkiaCanvas) -> Unit): KiteRaster {
        val surface = Surface.makeRasterN32Premul(size, size)
        surface.canvas.clear(-1)
        val canvas = SkiaCanvas(surface.canvas)
        canvas.beginPage(size.toDouble(), size.toDouble(), KiteMatrix.IDENTITY)
        paint(canvas)
        canvas.endPage()
        val info = ImageInfo(size, size, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
        val bitmap = Bitmap().apply { allocPixels(info) }
        surface.readPixels(bitmap, 0, 0)
        val bytes = assertNotNull(bitmap.readPixels(info, size * 4, 0, 0))
        return KiteRaster(size, size, IntArray(size * size) { i ->
            (bytes[4 * i].toInt() and 0xFF) or ((bytes[4 * i + 1].toInt() and 0xFF) shl 8) or
                ((bytes[4 * i + 2].toInt() and 0xFF) shl 16) or ((bytes[4 * i + 3].toInt() and 0xFF) shl 24)
        })
    }

    private fun fill(canvas: SkiaCanvas, path: KitePath, color: RgbColor, mode: KiteBlendMode = KiteBlendMode.Normal) =
        canvas.fillPath(path, KiteMatrix.IDENTITY, color, evenOdd = false, blendMode = mode)

    private fun assertRgb(raster: KiteRaster, x: Int, y: Int, rgb: Int, tolerance: Int = 3) {
        val actual = raster[x, y]
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
                val over = scope.render(backdrop) { fill(c, rect(0.0, 0.0, 20.0, 20.0), yellow, KiteBlendMode.Multiply) }
                assertEquals(0xFF00FF00.toInt(), over[0, 0])
                assertEquals(0xFFFFFF00.toInt(), over[9, 9])
                // A render gives straight colour: half red is full red at half alpha.
                val half = scope.render { c.fillPath(rect(0.0, 0.0, 20.0, 20.0), KiteMatrix.IDENTITY, RgbColor(1.0, 0.0, 0.0), evenOdd = false, alpha = 0.5) }
                assertEquals(0xFF0000, half[3, 3] and 0xFFFFFF)
                assertTrue(abs((half[3, 3] ushr 24) - 0x80) <= 1, "alpha ${half[3, 3] ushr 24}")
                val flat = KiteRaster(10, 10).apply { pixels.fill(0xFFFF0000.toInt()) }
                scope.draw(flat, alpha = 0.5)
                true
            }
            assertTrue(ran)
            c.popClip()
        }
        // Half red over green, and over white; nothing below the clip or outside the box.
        assertRgb(image, 7, 7, 0x808000)
        assertRgb(image, 12, 7, 0xFF8080)
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

    @Test
    fun an_svg_filter_draws_on_skia() {
        val svg = assertNotNull(
            SvgImage.parse(
                ("""<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">""" +
                    """<filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="100" height="100">""" +
                    """<feDropShadow dx="10" dy="10" stdDeviation="0" flood-color="#00f"/><feColorMatrix type="saturate" values="0"/></filter>""" +
                    """<rect x="20" y="20" width="20" height="20" fill="#f00" filter="url(#f)"/></svg>""").encodeToByteArray(),
            ),
        )
        val image = page(100) { c -> svg.render(c, KiteMatrix.IDENTITY) }
        // Red at saturation 0 in linear light, 0.213, is 128 in sRGB; the blue shadow, 0.072, is 75.
        assertRgb(image, 30, 30, 0x808080)
        assertRgb(image, 45, 45, 0x4B4B4B)
        assertRgb(image, 10, 10, 0xFFFFFF)
    }
}
