package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlinx.browser.document
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Host-font text in a script whose letters change shape with their neighbours draws as the
 * browser shapes it, so an Arabic word joins, where each letter used to draw alone in its
 * isolated form. Latin text keeps each glyph at its own pen, as MuPDF places a base-14 font (#588).
 */
class Canvas2dHostShapingTest {

    private val spec = FontSpec(KiteFontFamily.Serif, bold = false, italic = false)

    /** The red channel of each of [runs] drawn as host text at 60 px, a run at its own x. */
    private fun draw(vararg runs: Pair<List<TextGlyph>, Double>): IntArray {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = 300
        canvas.height = 120
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.fillStyle = "white"
        ctx.fillRect(0.0, 0.0, 300.0, 120.0)
        val kite = Canvas2dCanvas(ctx)
        for ((glyphs, x) in runs) {
            kite.drawGlyphs(glyphs, 60.0, 1000, false, spec, KiteMatrix(1.0, 0.0, 0.0, -1.0, x, 80.0), RgbColor.BLACK)
        }
        val data = ctx.getImageData(0.0, 0.0, 300.0, 120.0).data.asDynamic()
        return IntArray(300 * 120) { data[it * 4] as Int }
    }

    private fun glyphs(vararg letters: String) = letters.map { TextGlyph(0, 1, -1, it, 1000.0, null, false) }

    @Test
    fun an_arabic_word_joins() {
        // The word بيت given in visual order, left to right: teh, yeh, beh, an em apart. Joined,
        // the three letters are one stroke with dots apart from it; drawn alone, they are three.
        // The test browser has a face for Arabic, so no ink at all is a failure too.
        val red = draw(glyphs("ت", "ي", "ب") to 20.0)
        assertTrue(red.any { it < 128 }, "no ink: the browser drew no Arabic")
        assertEquals(1, inkStrokes(red, 300, 120), "the letters drew apart")
    }

    @Test
    fun latin_letters_keep_their_own_pens() {
        val run = draw(glyphs("a", "b", "c") to 20.0)
        val alone = draw(glyphs("a") to 20.0, glyphs("b") to 80.0, glyphs("c") to 140.0)
        assertContentEquals(alone, run)
    }
}
