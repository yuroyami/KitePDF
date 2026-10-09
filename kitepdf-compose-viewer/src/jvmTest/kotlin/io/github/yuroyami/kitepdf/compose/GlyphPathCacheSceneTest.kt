package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A glyph drawn many times converts its outline once per draw, and draws where its outline does.
 * Each glyph converted its outline to a new path, however often the page drew it (#382).
 */
class GlyphPathCacheSceneTest {

    private val measurer = TextMeasurer(testFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    /** A glyph that is a 600 by 700 box, 100 units from its origin, in a 1000-unit em. */
    private val box = KitePath.Builder().apply { rectangle(100.0, 0.0, 600.0, 700.0) }.build()

    private fun glyph(outline: KitePath) = TextGlyph(0, 1, 1, "A", 800.0, outline, isWordSpace = false)

    private fun draw(block: (ComposeCanvas) -> Unit): Pair<IntArray, ComposeCanvas> {
        val bitmap = ImageBitmap(200, 60)
        lateinit var canvas: ComposeCanvas
        CanvasDrawScope().drawOnTestUiThread(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(200f, 60f)) {
            drawRect(Color.White)
            canvas = ComposeCanvas(this, measurer)
            block(canvas)
        }
        return IntArray(200 * 60).also { bitmap.readPixels(it) } to canvas
    }

    /** Text space to the bitmap: y flips, and the baseline is 45 pixels down. */
    private val textToDevice = KiteMatrix(1.0, 0.0, 0.0, -1.0, 7.3, 45.0)

    @Test
    fun a_repeated_glyph_converts_once_and_draws_where_its_outline_does() {
        val (text, canvas) = draw {
            it.drawGlyphs(
                List(8) { glyph(box) }, 20.0, unitsPerEm = 1000, hasOutlines = true, fontSpec = FontSpec(KiteFontFamily.SansSerif, bold = false, italic = false),
                textToDevice = textToDevice, color = RgbColor(0.0, 0.0, 1.0), alpha = 1.0, blendMode = KiteBlendMode.Normal,
            )
        }
        assertEquals(1, canvas.convertedGlyphs, "the canvas converted the same outline more than once")
        // The same boxes as plain fills, each at its pen position: 800 units of advance at 20 pt.
        val (fills, _) = draw { c ->
            for (i in 0 until 8) {
                c.fillPath(box, textToDevice.concat(KiteMatrix(0.02, 0.0, 0.0, 0.02, i * 16.0, 0.0)), RgbColor(0.0, 0.0, 1.0), evenOdd = false)
            }
        }
        var worst = 0
        for (i in text.indices) {
            for (shift in listOf(0, 8, 16)) {
                worst = maxOf(worst, abs((text[i] ushr shift and 0xFF) - (fills[i] ushr shift and 0xFF)))
            }
        }
        assertTrue(worst <= 2, "the glyphs drew $worst levels away from their outlines")
        assertTrue(text.any { it != -1 }, "nothing drew")
    }
}
