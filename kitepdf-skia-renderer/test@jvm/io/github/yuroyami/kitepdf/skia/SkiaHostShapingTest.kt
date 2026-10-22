package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import org.jetbrains.skia.Surface
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Host-font text in a script whose letters change shape with their neighbours draws as Skia's
 * shaper shapes it, so an Arabic word joins, where each letter used to draw alone in its
 * isolated form. Latin text keeps each glyph at its own pen, as MuPDF places a base-14 font (#588).
 */
class SkiaHostShapingTest {

    private val spec = FontSpec(KiteFontFamily.Serif, bold = false, italic = false)

    /** The red channel of each of [runs] drawn as host text at 60 px, a run at its own x. */
    private fun draw(vararg runs: Pair<List<TextGlyph>, Double>): IntArray {
        val surface = Surface.makeRasterN32Premul(300, 120)
        surface.canvas.clear(-1)
        val canvas = SkiaCanvas(surface.canvas)
        for ((glyphs, x) in runs) {
            canvas.drawGlyphs(glyphs, 60.0, 1000, false, spec, KiteMatrix(1.0, 0.0, 0.0, -1.0, x, 80.0), RgbColor.BLACK)
        }
        val pixels = surface.makeImageSnapshot().peekPixels() ?: error("no pixels")
        return IntArray(300 * 120) { (pixels.getColor(it % 300, it / 300) shr 16) and 0xFF }
    }

    private fun glyphs(vararg letters: String) = letters.map { TextGlyph(0, 1, -1, it, 1000.0, null, false) }

    /** The 8-connected strokes of the dark pixels of [red] that hold a tenth of its ink or more. */
    private fun inkStrokes(red: IntArray, w: Int, h: Int): Int {
        val seen = BooleanArray(red.size)
        val sizes = ArrayList<Int>()
        val stack = ArrayDeque<Int>()
        for (start in red.indices) {
            if (seen[start] || red[start] >= 128) continue
            seen[start] = true
            stack.addLast(start)
            var size = 0
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                size++
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = p % w + dx
                    val ny = p / w + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val q = ny * w + nx
                    if (!seen[q] && red[q] < 128) {
                        seen[q] = true
                        stack.addLast(q)
                    }
                }
            }
            sizes += size
        }
        val total = sizes.sum()
        return sizes.count { it * 10 >= total }
    }

    @Test
    fun an_arabic_word_joins() {
        // The word بيت given in visual order, left to right: teh, yeh, beh, an em apart. Joined,
        // the three letters are one stroke with dots apart from it; drawn alone, they are three.
        val red = draw(glyphs("ت", "ي", "ب") to 20.0)
        assumeTrue("no host face draws Arabic, skipping.", red.any { it < 128 })
        assertEquals(1, inkStrokes(red, 300, 120), "the letters drew apart")
    }

    @Test
    fun latin_letters_keep_their_own_pens() {
        val run = draw(glyphs("a", "b", "c") to 20.0)
        val alone = draw(glyphs("a") to 20.0, glyphs("b") to 80.0, glyphs("c") to 140.0)
        assertContentEquals(alone, run)
    }
}
