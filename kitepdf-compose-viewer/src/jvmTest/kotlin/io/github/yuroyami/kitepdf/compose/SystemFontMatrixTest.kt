package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * System-font text keeps the whole text matrix: shear, reflection, and a scale that is not the
 * same on both axes (ISO 32000-1, 9.4.4, #416). Text space is y up and the page y down, so a plain
 * matrix has d = -1.
 */
class SystemFontMatrixTest {

    private class Ink(val points: List<Pair<Int, Int>>) {
        val left = points.minOf { it.first }
        val right = points.maxOf { it.first }
        val top = points.minOf { it.second }
        val bottom = points.maxOf { it.second }
        val cx = points.map { it.first }.average()
        val cy = points.map { it.second }.average()
        val width get() = (right - left).toDouble()
        val height get() = (bottom - top).toDouble()

        /** How far the top third of the ink sits right of its bottom third. */
        val slant: Double
            get() {
                val third = (bottom - top) / 3
                val high = points.filter { it.second < top + third }.map { it.first }.average()
                val low = points.filter { it.second > bottom - third }.map { it.first }.average()
                return high - low
            }
    }

    /** The dark pixels of [text], drawn at 70 pt in a system font under [matrix] on a white page. */
    private fun ink(text: String, matrix: KiteMatrix, spacing: Double = 0.0): Ink {
        val bitmap = ImageBitmap(300, 300)
        val density = Density(1f)
        val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(300f, 300f)) {
            drawRect(Color.White, size = size)
            val canvas = ComposeCanvas(this, measurer)
            canvas.beginPage(300.0, 300.0, KiteMatrix.IDENTITY)
            val glyphs = text.mapIndexed { i, ch -> TextGlyph(i, 1, -1, ch.toString(), 600.0, null, false, advanceAdjust = spacing) }
            canvas.drawGlyphs(
                glyphs, 70.0, 1000, hasOutlines = false, fontSpec = FontSpec(KiteFontFamily.SansSerif, false, false),
                textToDevice = matrix, color = RgbColor(0.0, 0.0, 0.0), alpha = 1.0, blendMode = KiteBlendMode.Normal,
            )
            canvas.endPage()
        }
        val pixels = bitmap.toPixelMap()
        val points = buildList { for (x in 0 until 300) for (y in 0 until 300) if (pixels[x, y].red < 0.5f) add(x to y) }
        assertTrue(points.isNotEmpty(), "the text drew under $matrix")
        return Ink(points)
    }

    @Test
    fun a_sheared_run_slants_the_way_its_matrix_says() {
        val upright = ink("O", KiteMatrix(1.0, 0.0, 0.0, -1.0, 100.0, 170.0))
        val right = ink("O", KiteMatrix(1.0, 0.0, 0.8, -1.0, 100.0, 170.0))
        val left = ink("O", KiteMatrix(1.0, 0.0, -0.8, -1.0, 150.0, 170.0))
        assertTrue(right.slant > 10, "c = +0.8 leans right: ${right.slant}")
        assertTrue(left.slant < -10, "c = -0.8 leans left: ${left.slant}")
        // Shear moves the glyph sideways and leaves its height alone.
        assertEquals(upright.height, right.height, 2.0, "the sheared run keeps its height")
    }

    @Test
    fun a_reflected_run_is_drawn_mirrored() {
        // An L has its stem on the left and its foot at the bottom.
        fun stemSide(ink: Ink) = ink.cx - (ink.left + ink.right) / 2.0
        fun footSide(ink: Ink) = ink.cy - (ink.top + ink.bottom) / 2.0
        val plain = ink("L", KiteMatrix(1.0, 0.0, 0.0, -1.0, 100.0, 170.0))
        assertTrue(stemSide(plain) < 0 && footSide(plain) > 0, "an L as drawn: ${stemSide(plain)}, ${footSide(plain)}")
        val mirrored = ink("L", KiteMatrix(-1.0, 0.0, 0.0, -1.0, 200.0, 170.0))
        assertTrue(stemSide(mirrored) > 0, "mirrored left to right, the stem is on the right: ${stemSide(mirrored)}")
        assertTrue(footSide(mirrored) > 0, "mirrored left to right, the foot stays at the bottom: ${footSide(mirrored)}")
        val flipped = ink("L", KiteMatrix(1.0, 0.0, 0.0, 1.0, 100.0, 100.0))
        assertTrue(stemSide(flipped) < 0, "mirrored top to bottom, the stem stays on the left: ${stemSide(flipped)}")
        assertTrue(footSide(flipped) < 0, "mirrored top to bottom, the foot is at the top: ${footSide(flipped)}")
    }

    @Test
    fun spaced_pieces_move_with_the_matrix_scale() {
        // Character spacing cuts the run into one piece per glyph, each placed by the pen.
        fun stems(ink: Ink): List<Int> {
            val columns = ink.points.map { it.first }.toSortedSet().toList()
            return columns.filterIndexed { i, x -> i == 0 || x - columns[i - 1] > 5 }
        }
        val single = stems(ink("II", KiteMatrix(1.0, 0.0, 0.0, -1.0, 20.0, 170.0), spacing = 50.0))
        val double = stems(ink("II", KiteMatrix(2.0, 0.0, 0.0, -2.0, 20.0, 250.0), spacing = 50.0))
        assertEquals(2, single.size, "two stems at scale 1: $single")
        assertEquals(2, double.size, "two stems at scale 2: $double")
        assertEquals(2.0 * (single[1] - single[0]), (double[1] - double[0]).toDouble(), 3.0, "the pen doubles with the scale")
    }

    @Test
    fun a_turned_run_keeps_each_scale_on_its_own_axis() {
        // Text x runs up the page at twice the size, and text y runs to the left at the normal size.
        val plain = ink("O", KiteMatrix(1.0, 0.0, 0.0, -1.0, 100.0, 170.0))
        val turned = ink("O", KiteMatrix(0.0, -2.0, -1.0, 0.0, 200.0, 250.0))
        assertEquals(plain.width * 2, turned.height, plain.width * 0.2, "the doubled text x axis runs up the page")
        assertEquals(plain.height, turned.width, plain.height * 0.1, "the text y axis keeps its size across the page")
    }
}
