package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.Standard14Widths
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.font.standardFaceGlyphs
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A family the host has no face of draws from the bundled standard faces that the layout
 * measured it with. In a browser, Skia knows Roboto only, so each Roboto letter of a serif book
 * sat at its Times advance, with uneven gaps and no bold or italic (#593).
 */
class SkiaBundledFaceTextTest {

    /** [text] as a run of host-font text, one glyph a character, at the advances of [face]. */
    private fun run(text: String, face: String): List<TextGlyph> = text.map { c ->
        val name = when (c) {
            ' ' -> "space"; ':' -> "colon"; else -> c.toString()
        }
        TextGlyph(0, 1, -1, c.toString(), assertNotNull(Standard14Widths.widthOf(face, name)).toDouble(), null, c == ' ')
    }

    /** [glyphs] drawn at 60 px to the em on a white 320 x 120 canvas that has no host faces, as ARGB pixels. */
    private fun draw(glyphs: List<TextGlyph>, spec: FontSpec, hasOutlines: Boolean): IntArray {
        val surface = Surface.makeRasterN32Premul(320, 120)
        surface.canvas.clear(-1)
        val canvas = SkiaCanvas(surface.canvas).apply { hostFace = { _, _ -> false } }
        canvas.drawGlyphs(glyphs, 60.0, 1000, hasOutlines, spec, KiteMatrix(1.0, 0.0, 0.0, -1.0, 20.0, 80.0), RgbColor.BLACK)
        val pixels = surface.makeImageSnapshot().peekPixels() ?: error("no pixels")
        return IntArray(320 * 120) { pixels.getColor(it % 320, it / 320) }
    }

    private fun assertDrawsTheBundledFace(text: String, spec: FontSpec, face: String) {
        val glyphs = run(text, face)
        val asHostText = draw(glyphs, spec, hasOutlines = false)
        val asOutlines = draw(assertNotNull(standardFaceGlyphs(glyphs, spec)), spec, hasOutlines = true)
        val dark = asHostText.count { (it and 0xFF) < 128 }
        val differ = asHostText.indices.count { asHostText[it] != asOutlines[it] }
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
