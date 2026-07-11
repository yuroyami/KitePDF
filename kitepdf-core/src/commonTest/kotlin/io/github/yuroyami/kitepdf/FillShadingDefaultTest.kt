package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.font.FontSpec
import io.github.yuroyami.kitepdf.font.TextGlyph
import io.github.yuroyami.kitepdf.render.BlendMode
import io.github.yuroyami.kitepdf.render.ColorSpace
import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.KiteCanvas
import io.github.yuroyami.kitepdf.render.KiteFunction
import io.github.yuroyami.kitepdf.render.KitePath
import io.github.yuroyami.kitepdf.render.KiteShading
import io.github.yuroyami.kitepdf.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The interface-default [KiteCanvas.fillShading] must paint SOMETHING for the
 * whole-clip case (`sh` passes `clipPath = null`, meaning "cover the current
 * clip / page"), not silently return (T-03). All shipped backends override
 * the default, so this exercises a minimal fake canvas that does not.
 */
class FillShadingDefaultTest {

    /** Implements only the required members; fillShading stays the default. */
    private class MinimalCanvas : KiteCanvas {
        val fills = mutableListOf<Triple<KitePath, Matrix, RgbColor>>()
        override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: Matrix) {}
        override fun endPage() {}
        override fun fillPath(path: KitePath, ctm: Matrix, color: RgbColor, evenOdd: Boolean, alpha: Double, blendMode: BlendMode) {
            fills.add(Triple(path, ctm, color))
        }
        override fun strokePath(path: KitePath, ctm: Matrix, color: RgbColor, lineWidth: Double, alpha: Double, blendMode: BlendMode, dashArray: List<Double>?, dashPhase: Double, lineCap: Int, lineJoin: Int, miterLimit: Double) {}
        override fun drawGlyphs(glyphs: List<TextGlyph>, fontSize: Double, unitsPerEm: Int, hasOutlines: Boolean, fontSpec: FontSpec, textToDevice: Matrix, color: RgbColor, alpha: Double, blendMode: BlendMode) {}
        override fun pushClip(path: KitePath, ctm: Matrix, evenOdd: Boolean) {}
        override fun popClip() {}
    }

    private fun redToBlueAxial() = KiteShading.Axial(
        colorSpace = ColorSpace.DeviceRGB,
        background = null,
        bbox = null,
        coords = doubleArrayOf(0.0, 0.0, 100.0, 0.0),
        domain = doubleArrayOf(0.0, 1.0),
        function = KiteFunction.Type2(
            domain = doubleArrayOf(0.0, 1.0),
            range = null,
            c0 = doubleArrayOf(1.0, 0.0, 0.0),
            c1 = doubleArrayOf(0.0, 0.0, 1.0),
            n = 1.0,
        ),
        extendStart = false,
        extendEnd = false,
    )

    @Test
    fun whole_clip_sh_paints_a_huge_rect_under_identity() {
        val canvas = MinimalCanvas()
        canvas.fillShading(redToBlueAxial(), Matrix(2.0, 0.0, 0.0, 2.0, 5.0, 5.0), clipPath = null)

        assertEquals(1, canvas.fills.size, "default fillShading must fill for the whole-clip case")
        val (path, ctm, _) = canvas.fills[0]
        assertEquals(Matrix.IDENTITY, ctm, "huge rect must be device-space (identity), not the shading ctm")
        // 5 segments: M, L, L, L, Close — spanning far beyond any page.
        assertEquals(5, path.segments.size)
    }

    @Test
    fun clipped_case_still_fills_the_clip_path() {
        val canvas = MinimalCanvas()
        val clip = KitePath.Builder().apply {
            moveTo(0.0, 0.0); lineTo(50.0, 0.0); lineTo(50.0, 50.0); close()
        }.build()
        val ctm = Matrix(1.0, 0.0, 0.0, 1.0, 10.0, 10.0)
        canvas.fillShading(redToBlueAxial(), ctm, clip)

        assertEquals(1, canvas.fills.size)
        assertEquals(clip, canvas.fills[0].first)
        assertEquals(ctm, canvas.fills[0].second)
    }

    @Test
    fun midpoint_color_is_used_for_the_flat_approximation() {
        val canvas = MinimalCanvas()
        canvas.fillShading(redToBlueAxial(), Matrix.IDENTITY, clipPath = null)
        val c = canvas.fills.single().third
        // Midpoint of red -> blue: red and blue meet halfway; exact stop choice
        // may bias to one side, but it must not be either pure endpoint.
        assertTrue(c.r < 1.0 && c.b > 0.0, "expected a mixed midpoint colour, got $c")
    }
}
