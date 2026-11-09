package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Glyphs on the Skia canvas fill a coverage mask once for each outline, size and subpixel
 * position, and draw from it after that, as AWT does (#911): a page of 3,000 glyphs converted
 * and filled each outline on every draw.
 */
class SkiaGlyphCacheTest {

    private val outlines = List(12) { k ->
        KitePath.Builder().apply {
            moveTo(50.0, 0.0)
            for (i in 0 until 12) {
                val a = i * kotlin.math.PI / 6
                curveTo(
                    500 + 400 * kotlin.math.cos(a), 350 + 300 * kotlin.math.sin(a) + 10 * k,
                    500 + 420 * kotlin.math.cos(a + 0.2), 350 + 320 * kotlin.math.sin(a + 0.2),
                    500 + 400 * kotlin.math.cos(a + 0.5), 350 + 300 * kotlin.math.sin(a + 0.5),
                )
            }
            close()
        }.build()
    }

    /** Lines of glyphs at 15 pixels an em, at advances that land on every subpixel position. */
    private fun draw(canvas: SkiaCanvas, color: RgbColor = RgbColor(0.1, 0.2, 0.6), alpha: Double = 1.0) {
        for (line in 0 until 20) {
            val glyphs = List(30) { i -> TextGlyph(i, 1, i, "a", 590.0 + 7 * (i % 5), outlines[(line + i) % outlines.size], false) }
            canvas.drawGlyphs(
                glyphs, 10.0, 1000, true, FontSpec.SansSerif,
                KiteMatrix(1.5, 0.0, 0.0, -1.5, 3.3, 14.0 + line * 15.37), color, alpha, KiteBlendMode.Normal,
            )
        }
    }

    private fun pixels(surface: Surface): IntArray {
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(surface.width, surface.height)
        surface.readPixels(bitmap, 0, 0)
        return IntArray(surface.width * surface.height) { bitmap.getColor(it % surface.width, it / surface.width) }
    }

    @Test
    fun a_repeated_glyph_fills_its_mask_once() {
        val surface = Surface.makeRasterN32Premul(300, 320)
        val canvas = SkiaCanvas(surface.canvas)
        draw(canvas)
        val first = canvas.glyphMaskFills
        assertTrue(first in 1 until 600, "$first masks for 600 glyphs")
        assertEquals(outlines.size, canvas.glyphPathConversions, "each outline converts to a native path once")
        draw(canvas)
        assertEquals(first, canvas.glyphMaskFills, "the second draw filled masks again")
    }

    @Test
    fun masks_draw_what_the_path_fills_draw() {
        for ((color, alpha) in listOf(RgbColor(0.1, 0.2, 0.6) to 1.0, RgbColor(0.9, 0.3, 0.1) to 0.5)) {
            val cached = Surface.makeRasterN32Premul(300, 320).also { it.canvas.clear(Color.WHITE) }
            draw(SkiaCanvas(cached.canvas), color, alpha)
            val filled = Surface.makeRasterN32Premul(300, 320).also { it.canvas.clear(Color.WHITE) }
            draw(SkiaCanvas(filled.canvas).apply { glyphMasksEnabled = false }, color, alpha)
            val a = pixels(cached)
            val b = pixels(filled)
            var worst = 0
            for (i in a.indices) for (shift in intArrayOf(0, 8, 16)) {
                worst = maxOf(worst, kotlin.math.abs(((a[i] ushr shift) and 255) - ((b[i] ushr shift) and 255)))
            }
            // Skia blends a path's coverage straight into the pixel, and an alpha image through the
            // paint's premultiplied colour, which round apart by a few levels on edge pixels.
            assertTrue(worst <= 4, "a mask pixel is $worst levels from the path fill")
            assertTrue(a.count { it != -1 } > 10_000, "the glyphs drew")
        }
    }
}
