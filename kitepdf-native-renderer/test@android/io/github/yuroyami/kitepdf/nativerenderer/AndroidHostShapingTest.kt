package io.github.yuroyami.kitepdf.nativerenderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Host-font text in a script whose letters change shape with their neighbours draws as Android's
 * text stack shapes it, so an Arabic word joins, where each letter used to draw alone in its
 * isolated form. Latin text keeps each glyph at its own pen (#588). Robolectric's native graphics
 * draw it with Android's own Skia and fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29, 35])
class AndroidHostShapingTest {

    private val spec = FontSpec(KiteFontFamily.Serif, bold = false, italic = false)

    /** The red channel of each of [runs] drawn as host text at 60 px, a run at its own x. */
    private fun draw(vararg runs: Pair<List<TextGlyph>, Double>): IntArray {
        val bitmap = Bitmap.createBitmap(300, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val kite = AndroidNativeCanvas(canvas)
        for ((glyphs, x) in runs) {
            kite.drawGlyphs(glyphs, 60.0, 1000, false, spec, KiteMatrix(1.0, 0.0, 0.0, -1.0, x, 80.0), RgbColor.BLACK)
        }
        val pixels = IntArray(300 * 120)
        bitmap.getPixels(pixels, 0, 300, 0, 0, 300, 120)
        return IntArray(pixels.size) { (pixels[it] shr 16) and 0xFF }
    }

    private fun glyphs(vararg letters: String) = letters.map { TextGlyph(0, 1, -1, it, 1000.0, null, false) }

    @Test
    fun an_arabic_word_joins() {
        // The word بيت given in visual order, left to right: teh, yeh, beh, an em apart.
        val red = draw(glyphs("ت", "ي", "ب") to 20.0)
        assertTrue(red.any { it < 128 }, "the Arabic letters drew nothing")
        assertEquals(1, inkStrokes(red, 300, 120), "the letters drew apart")
    }

    @Test
    fun latin_letters_keep_their_own_pens() {
        val run = draw(glyphs("a", "b", "c") to 20.0)
        val alone = draw(glyphs("a") to 20.0, glyphs("b") to 80.0, glyphs("c") to 140.0)
        assertContentEquals(alone, run)
    }
}
