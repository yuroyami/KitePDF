package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import java.awt.Color
import java.awt.image.BufferedImage
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Host-font text in a script whose letters change shape with their neighbours draws as the
 * text engine shapes it, so an Arabic word joins, where each letter used to draw alone in its
 * isolated form. Latin text keeps each glyph at its own pen, as MuPDF places a base-14 font (#588).
 */
class AwtHostShapingTest {

    private val spec = FontSpec(KiteFontFamily.Serif, bold = false, italic = false)

    /** The red channel of [glyphs] drawn as host text at 60 px, each [glyphs] list one call. */
    private fun draw(vararg runs: Pair<List<TextGlyph>, Double>): IntArray {
        val img = BufferedImage(300, 120, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 300, 120)
        val canvas = AwtCanvas(g)
        for ((glyphs, x) in runs) {
            canvas.drawGlyphs(glyphs, 60.0, 1000, false, spec, KiteMatrix(1.0, 0.0, 0.0, -1.0, x, 80.0), RgbColor.BLACK)
        }
        g.dispose()
        return IntArray(300 * 120) { (img.getRGB(it % 300, it / 300) shr 16) and 0xFF }
    }

    private fun glyphs(vararg letters: String) = letters.map { TextGlyph(0, 1, -1, it, 1000.0, null, false) }

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
