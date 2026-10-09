package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.drawOrderParts
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A run reaches the canvas in the order it draws, left to right, as the EPUB layout's bidi
 * reordering and a PDF's content stream give it. A host-font piece is drawn by a text engine
 * that runs the bidi algorithm again, so right-to-left letters must come out in the order they
 * came in, joined as the logical word joins (#486).
 */
class RtlHostTextTest {
    private val density = Density(1f)
    private val spec = FontSpec(KiteFontFamily.SansSerif, false, false)
    private val fontSize = 40.0
    private val x = 20.0
    private val baseline = 70.0
    private val measurer: TextMeasurer by lazy {
        onTestUiThread { TextMeasurer(testFontFamilyResolver(), density, LayoutDirection.Ltr) }
    }

    /** [logical] as the engine draws it on its own, in a paragraph of its own direction. */
    private fun reference(logical: String): IntArray = ink {
        val line = hostTextLine(logical, spec, fontSize.toFloat(), Color.Black, BlendMode.SrcOver)!!
        translate(x.toFloat(), baseline.toFloat()) { line.draw(this) }
    }

    /**
     * The glyphs of [visual], one a code point, through the canvas. The first glyph carries the
     * whole advance, which is the width the engine measures [logical] at, so the piece is not
     * stretched and a correct draw is the reference to the pixel.
     */
    private fun throughCanvas(visual: String, logical: String, hostLines: Boolean): IntArray {
        val width = hostTextLine(logical, spec, fontSize.toFloat(), Color.Black, BlendMode.SrcOver)!!.width
        val glyphs = codePoints(visual).mapIndexed { i, cp ->
            TextGlyph(0, 1, -1, cp, if (i == 0) width * 1000.0 / fontSize else 0.0, null, false)
        }
        return ink {
            ComposeCanvas(this, measurer, 1f, false, magnification = 1f, hostLines = hostLines)
                .drawGlyphs(glyphs, fontSize, 1000, false, spec, KiteMatrix(1.0, 0.0, 0.0, -1.0, x, baseline), RgbColor.BLACK)
        }
    }

    private fun codePoints(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val n = if (s[i].isHighSurrogate() && i + 1 < s.length) 2 else 1
            out += s.substring(i, i + n)
            i += n
        }
        return out
    }

    private fun ink(block: DrawScope.() -> Unit): IntArray {
        val bitmap = ImageBitmap(300, 100)
        onTestUiThread {
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(300f, 100f)) {
                drawRect(Color.White)
                block()
            }
        }
        return IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
    }

    private fun check(logical: String, visual: String) {
        val expected = reference(logical)
        assertTrue(expected.any { it != -1 }, "the host has no face for $logical")
        assertContentEquals(expected, throughCanvas(visual, logical, hostLines = true), "Skia's paragraph drew $logical out of order")
        assertContentEquals(expected, throughCanvas(visual, logical, hostLines = false), "Compose's text drew $logical out of order")
    }

    @Test
    fun a_hebrew_word_draws_in_the_order_the_layout_gave() {
        // shalom, logical shin lamed vav final-mem, comes in visual order: final-mem first.
        check(logical = "שלום", visual = "םולש")
    }

    @Test
    fun an_arabic_word_joins_as_its_logical_order_joins() {
        // salaam, logical seen lam alef meem: lam and alef join, and seen joins lam.
        check(logical = "سلام", visual = "مالس")
    }

    @Test
    fun digits_inside_arabic_keep_their_order() {
        // "aam 2024" in a right-to-left line: the layout puts the number left of the word, digits
        // left to right, and the word's letters right to left.
        check(logical = "عام 2024", visual = "2024 ماع")
    }

    @Test
    fun a_piece_of_both_directions_draws_each_part_at_its_own_pen() {
        // "ab shalom" in one style: the Latin part with the space after it, then the Hebrew word
        // in visual order. The engine draws each part as it would on its own, side by side.
        val latin = "ab "
        val hebrew = "\u05e9\u05dc\u05d5\u05dd"
        val latinWidth = hostTextLine(latin, spec, fontSize.toFloat(), Color.Black, BlendMode.SrcOver)!!.width
        val hebrewWidth = hostTextLine(hebrew, spec, fontSize.toFloat(), Color.Black, BlendMode.SrcOver)!!.width
        val expected = ink {
            translate(x.toFloat(), baseline.toFloat()) {
                hostTextLine(latin, spec, fontSize.toFloat(), Color.Black, BlendMode.SrcOver)!!.draw(this)
                translate(latinWidth, 0f) {
                    hostTextLine(hebrew, spec, fontSize.toFloat(), Color.Black, BlendMode.SrcOver)!!.draw(this)
                }
            }
        }
        val visual = codePoints(latin) + codePoints(hebrew).reversed()
        val glyphs = visual.mapIndexed { i, cp ->
            val advance = when (i) {
                0 -> latinWidth * 1000.0 / fontSize
                latin.length -> hebrewWidth * 1000.0 / fontSize
                else -> 0.0
            }
            TextGlyph(0, 1, -1, cp, advance, null, false)
        }
        assertEquals(listOf(latin.length, 4), drawOrderParts(glyphs).map { it.glyphs.size })
        for (hostLines in listOf(true, false)) {
            val actual = ink {
                ComposeCanvas(this, measurer, 1f, false, magnification = 1f, hostLines = hostLines)
                    .drawGlyphs(glyphs, fontSize, 1000, false, spec, KiteMatrix(1.0, 0.0, 0.0, -1.0, x, baseline), RgbColor.BLACK)
            }
            assertContentEquals(expected, actual, "hostLines = $hostLines")
        }
    }

    @Test
    fun latin_text_is_untouched() {
        check(logical = "office fly", visual = "office fly")
    }
}
