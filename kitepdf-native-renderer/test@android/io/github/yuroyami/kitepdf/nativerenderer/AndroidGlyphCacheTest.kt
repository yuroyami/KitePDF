package io.github.yuroyami.kitepdf.nativerenderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Glyphs on the Android canvas fill a path made once for each outline and mapped by the glyph's
 * matrix, where the canvas built a new path for every glyph on every draw (#911), as
 * `Canvas2dGlyphCacheTest` checks on Canvas2D. The pixels stay those of a path fill.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29, 35])
class AndroidGlyphCacheTest {

    private val outline = KitePath.Builder().apply {
        moveTo(100.0, 0.0); lineTo(900.0, 0.0); curveTo(900.0, 500.0, 600.0, 700.0, 500.0, 700.0); lineTo(100.0, 700.0); close()
    }.build()

    private fun bitmap(width: Int = 120, height: Int = 60) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(Color.WHITE) }

    private val textToDevice = KiteMatrix(1.0, 0.0, 0.0, -1.0, 7.3, 40.0)
    private val glyphs = List(4) { TextGlyph(it, 1, 1, "a", 900.0, outline, false) }
    private val blue = RgbColor(0.0, 0.0, 1.0)

    @Test
    fun a_repeated_glyph_makes_one_path() {
        val canvas = AndroidNativeCanvas(Canvas(bitmap()))
        canvas.drawGlyphs(glyphs, 25.0, 1000, true, FontSpec.SansSerif, textToDevice, blue, 1.0, KiteBlendMode.Normal)
        canvas.drawGlyphs(glyphs, 25.0, 1000, true, FontSpec.SansSerif, textToDevice, blue, 1.0, KiteBlendMode.Normal)
        assertEquals(1, canvas.glyphPathConversions)
    }

    @Test
    fun a_cached_glyph_draws_as_its_path_fill() {
        val glyphBitmap = bitmap()
        AndroidNativeCanvas(Canvas(glyphBitmap)).drawGlyphs(glyphs, 25.0, 1000, true, FontSpec.SansSerif, textToDevice, blue, 1.0, KiteBlendMode.Normal)
        val fillBitmap = bitmap()
        val fill = AndroidNativeCanvas(Canvas(fillBitmap))
        for (i in 0 until 4) {
            val m = textToDevice.concat(KiteMatrix.translation(i * 22.5, 0.0)).concat(KiteMatrix(0.025, 0.0, 0.0, 0.025, 0.0, 0.0))
            fill.fillPath(outline, m, blue, evenOdd = false)
        }
        var worst = 0
        var ink = 0
        for (y in 0 until 60) for (x in 0 until 120) {
            val a = glyphBitmap.getPixel(x, y)
            val b = fillBitmap.getPixel(x, y)
            for (shift in intArrayOf(0, 8, 16)) worst = maxOf(worst, kotlin.math.abs((a ushr shift and 0xFF) - (b ushr shift and 0xFF)))
            if ((a ushr 16 and 0xFF) < 128) ink++
        }
        assertTrue(ink > 500, "the glyphs drew $ink pixels")
        assertTrue(worst <= 2, "a cached glyph pixel is $worst levels from the path fill")
    }

    @Test
    fun a_page_of_repeated_glyphs_draws_from_kept_paths() {
        // 3,000 glyphs from 60 outlines, in 50 lines of 60, as SkiaGlyphCacheTest measures.
        val outlines = List(60) { k ->
            KitePath.Builder().apply {
                moveTo(80.0, 0.0); lineTo(520.0, 0.0)
                curveTo(560.0 + k, 240.0, 520.0, 520.0 + 3 * k, 300.0, 700.0)
                curveTo(120.0, 520.0, 40.0 + k, 240.0, 80.0, 0.0); close()
                moveTo(220.0, 120.0); lineTo(380.0, 120.0); lineTo(300.0, 400.0 + k); close()
            }.build()
        }
        val line = List(60) { TextGlyph(it, 1, 1, "a", 600.0, outlines[it], false) }
        val target = bitmap(600, 800)
        val canvas = AndroidNativeCanvas(Canvas(target))
        fun drawPage(): Double {
            val start = System.nanoTime()
            for (row in 0 until 50) {
                canvas.drawGlyphs(line, 9.0, 1000, true, FontSpec.SansSerif, KiteMatrix(1.0, 0.0, 0.0, -1.0, 10.0, 20.0 + row * 15.5), blue, 1.0, KiteBlendMode.Normal)
            }
            return (System.nanoTime() - start) / 1e6
        }
        val first = drawPage()
        val later = (0 until 3).minOf { drawPage() }
        println("[android-glyph-cache] SDK ${android.os.Build.VERSION.SDK_INT}, 3,000 glyphs: first draw $first ms, later draws $later ms")
        assertEquals(60, canvas.glyphPathConversions, "each outline converts once over four draws")
    }
}
