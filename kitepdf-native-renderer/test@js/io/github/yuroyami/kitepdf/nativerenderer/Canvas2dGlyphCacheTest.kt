package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlinx.browser.document
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Glyphs on Canvas2D fill a Path2D made once for each outline, under the glyph's matrix, where the
 * canvas emitted every outline's commands again on every draw (#911). The pixels stay those of a
 * path fill.
 */
class Canvas2dGlyphCacheTest {

    private val outline = KitePath.Builder().apply {
        moveTo(100.0, 0.0); lineTo(900.0, 0.0); curveTo(900.0, 500.0, 600.0, 700.0, 500.0, 700.0); lineTo(100.0, 700.0); close()
    }.build()

    private fun context(): CanvasRenderingContext2D {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = 120
        canvas.height = 60
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.fillStyle = "white"
        ctx.fillRect(0.0, 0.0, 120.0, 60.0)
        return ctx
    }

    private val textToDevice = KiteMatrix(1.0, 0.0, 0.0, -1.0, 7.3, 40.0)
    private val glyphs = List(4) { TextGlyph(it, 1, 1, "a", 900.0, outline, false) }

    @Test
    fun a_repeated_glyph_makes_one_path() {
        val canvas = Canvas2dCanvas(context())
        canvas.drawGlyphs(glyphs, 25.0, 1000, true, FontSpec.SansSerif, textToDevice, RgbColor(0.0, 0.0, 1.0), 1.0, KiteBlendMode.Normal)
        canvas.drawGlyphs(glyphs, 25.0, 1000, true, FontSpec.SansSerif, textToDevice, RgbColor(0.0, 0.0, 1.0), 1.0, KiteBlendMode.Normal)
        assertEquals(1, canvas.glyphPathConversions)
    }

    @Test
    fun a_cached_glyph_draws_as_its_path_fill() {
        val glyphCtx = context()
        Canvas2dCanvas(glyphCtx).drawGlyphs(glyphs, 25.0, 1000, true, FontSpec.SansSerif, textToDevice, RgbColor(0.0, 0.0, 1.0), 1.0, KiteBlendMode.Normal)
        val fillCtx = context()
        val fill = Canvas2dCanvas(fillCtx)
        for (i in 0 until 4) {
            val m = textToDevice.concat(KiteMatrix.translation(i * 22.5, 0.0)).concat(KiteMatrix(0.025, 0.0, 0.0, 0.025, 0.0, 0.0))
            fill.fillPath(outline, m, RgbColor(0.0, 0.0, 1.0), evenOdd = false)
        }
        val a = glyphCtx.getImageData(0.0, 0.0, 120.0, 60.0).data
        val b = fillCtx.getImageData(0.0, 0.0, 120.0, 60.0).data
        var worst = 0
        var ink = 0
        for (i in 0 until a.length) {
            val x = a.asDynamic()[i] as Int
            val y = b.asDynamic()[i] as Int
            worst = maxOf(worst, kotlin.math.abs(x - y))
            if (i % 4 == 0 && x < 128) ink++
        }
        assertTrue(ink > 500, "the glyphs drew $ink pixels")
        assertTrue(worst <= 2, "a cached glyph pixel is $worst levels from the path fill")
    }
}
