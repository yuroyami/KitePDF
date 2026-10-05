package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.Standard14Widths
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.font.standardFaceGlyphs
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A family the host has no face of draws from the bundled standard faces that the layout
 * measured it with. In a browser, Skia knows Roboto only, and each word of a serif book drew
 * in Roboto stretched to its Times width (#593).
 */
class BundledFaceTextTest {

    /** [text] as a run of host-font text, one glyph a character, at the advances of [face]. */
    private fun run(text: String, face: String): List<TextGlyph> = text.map { c ->
        val name = when (c) {
            ' ' -> "space"; ':' -> "colon"; else -> c.toString()
        }
        TextGlyph(0, 1, -1, c.toString(), assertNotNull(Standard14Widths.widthOf(face, name)).toDouble(), null, c == ' ')
    }

    /** [glyphs] drawn at 60 px to the em on a white 320 x 120 canvas that has no host faces. */
    private fun draw(glyphs: List<TextGlyph>, spec: FontSpec, hasOutlines: Boolean): PixelMap {
        val bitmap = ImageBitmap(320, 120)
        val density = Density(1f)
        val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
        CanvasDrawScope().drawOnTestUiThread(density, LayoutDirection.Ltr, Canvas(bitmap), Size(320f, 120f)) {
            drawRect(Color.White)
            val canvas = ComposeCanvas(this, measurer, 1f, false, 1f, hostFace = { false })
            canvas.drawGlyphs(glyphs, 60.0, 1000, hasOutlines, spec, KiteMatrix(1.0, 0.0, 0.0, -1.0, 20.0, 80.0), RgbColor.BLACK)
        }
        return bitmap.toPixelMap()
    }

    /** How many pixels of [a] and [b] differ, and how many of [a] are dark. */
    private fun differ(a: PixelMap, b: PixelMap): Pair<Int, Int> {
        var differ = 0
        var dark = 0
        for (y in 0 until 120) for (x in 0 until 320) {
            if (a[x, y] != b[x, y]) differ++
            if (a[x, y].red < 0.5f) dark++
        }
        return differ to dark
    }

    private fun assertDrawsTheBundledFace(text: String, spec: FontSpec, face: String) {
        val glyphs = run(text, face)
        val asHostText = draw(glyphs, spec, hasOutlines = false)
        val asOutlines = draw(assertNotNull(standardFaceGlyphs(glyphs, spec)), spec, hasOutlines = true)
        val (differ, dark) = differ(asHostText, asOutlines)
        assertTrue(dark > 300, "$text drew $dark dark pixels")
        assertEquals(0, differ, "$text in $face drew in another face: $differ pixels differ")
    }

    @Test
    fun serif_text_without_a_host_face_draws_in_the_bundled_serif_face() {
        assertDrawsTheBundledFace("life: In", FontSpec(KiteFontFamily.Serif, bold = false, italic = false), "Times-Roman")
    }

    @Test
    fun the_bundled_face_keeps_the_weight_and_the_slant() {
        assertDrawsTheBundledFace("Voyage", FontSpec(KiteFontFamily.Serif, bold = true, italic = true), "Times-BoldItalic")
        assertDrawsTheBundledFace("Mono", FontSpec(KiteFontFamily.Monospace, bold = false, italic = false), "Courier")
    }
}
