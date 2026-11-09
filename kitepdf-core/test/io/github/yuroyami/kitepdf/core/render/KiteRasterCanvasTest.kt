package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pixels that the canvas in plain Kotlin paints. */
class KiteRasterCanvasTest {

    private fun rect(x: Double, y: Double, w: Double, h: Double) = KitePath.Builder().apply { rectangle(x, y, w, h) }.build()

    private fun circle(cx: Double, cy: Double, r: Double): KitePath {
        val k = 0.5522847498 * r
        return KitePath.Builder().apply {
            moveTo(cx + r, cy)
            curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
            curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
            curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
            curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
            close()
        }.build()
    }

    private val red = RgbColor(1.0, 0.0, 0.0)
    private val blue = RgbColor(0.0, 0.0, 1.0)

    private fun KiteRaster.alpha(x: Int, y: Int) = this[x, y] ushr 24
    private fun KiteRaster.rgb(x: Int, y: Int) = this[x, y] and 0xFFFFFF

    @Test
    fun a_rectangle_on_whole_pixels_fills_them_and_nothing_else() {
        val c = KiteRasterCanvas(20, 10)
        c.fillPath(rect(2.0, 3.0, 5.0, 4.0), KiteMatrix.IDENTITY, red, evenOdd = false)
        val r = c.toRaster()
        for (y in 0 until 10) for (x in 0 until 20) {
            val inside = x in 2..6 && y in 3..6
            assertEquals(if (inside) 0xFFFF0000.toInt() else 0, r[x, y], "pixel ($x, $y)")
        }
    }

    @Test
    fun an_edge_through_the_middle_of_a_pixel_covers_half_of_it() {
        val c = KiteRasterCanvas(10, 10)
        c.fillPath(rect(2.5, 2.0, 5.0, 5.0), KiteMatrix.IDENTITY, red, evenOdd = false)
        val r = c.toRaster()
        assertEquals(128, r.alpha(2, 4), 1)
        assertEquals(255, r.alpha(3, 4))
        assertEquals(128, r.alpha(7, 4), 1)
        assertEquals(0xFF0000, r.rgb(2, 4), "straight colour stays red at the edge")
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected within $tolerance, got $actual")

    @Test
    fun a_circle_covers_its_area() {
        val c = KiteRasterCanvas(64, 64)
        c.fillPath(circle(32.0, 32.0, 20.0), KiteMatrix.IDENTITY, red, evenOdd = false)
        val r = c.toRaster()
        var sum = 0.0
        for (y in 0 until 64) for (x in 0 until 64) sum += r.alpha(x, y) / 255.0
        assertTrue(abs(sum - PI * 400) < PI * 400 * 0.003, "covered $sum, the circle has ${PI * 400}")
    }

    @Test
    fun the_even_odd_rule_leaves_a_hole_where_the_nonzero_rule_does_not() {
        val both = KitePath.Builder().apply { rectangle(0.0, 0.0, 10.0, 10.0); rectangle(3.0, 3.0, 4.0, 4.0) }.build()
        val nonzero = KiteRasterCanvas(10, 10).also { it.fillPath(both, KiteMatrix.IDENTITY, red, evenOdd = false) }.toRaster()
        val evenOdd = KiteRasterCanvas(10, 10).also { it.fillPath(both, KiteMatrix.IDENTITY, red, evenOdd = true) }.toRaster()
        assertEquals(255, nonzero.alpha(5, 5))
        assertEquals(0, evenOdd.alpha(5, 5))
        assertEquals(255, evenOdd.alpha(1, 1))
    }

    @Test
    fun a_clip_cuts_what_is_drawn_and_its_pop_restores_the_whole_canvas() {
        val c = KiteRasterCanvas(10, 10)
        c.pushClip(rect(0.0, 0.0, 5.0, 10.0), KiteMatrix.IDENTITY, evenOdd = false)
        c.pushClip(rect(0.0, 0.0, 10.0, 5.0), KiteMatrix.IDENTITY, evenOdd = false)
        c.fillPath(rect(0.0, 0.0, 10.0, 10.0), KiteMatrix.IDENTITY, red, evenOdd = false)
        c.popClip()
        c.popClip()
        c.fillPath(rect(9.0, 9.0, 1.0, 1.0), KiteMatrix.IDENTITY, blue, evenOdd = false)
        val r = c.toRaster()
        assertEquals(0xFFFF0000.toInt(), r[2, 2])
        assertEquals(0, r[7, 2], "outside the first clip")
        assertEquals(0, r[2, 7], "outside the second clip")
        assertEquals(0xFF0000FF.toInt(), r[9, 9])
    }

    @Test
    fun a_translucent_group_composites_its_overlap_once() {
        val c = KiteRasterCanvas(10, 10)
        c.beginTransparencyGroup(KiteRectangle(0.0, 0.0, 10.0, 10.0), KiteMatrix.IDENTITY, isolated = true, alpha = 0.5)
        c.fillPath(rect(0.0, 0.0, 6.0, 10.0), KiteMatrix.IDENTITY, red, evenOdd = false)
        c.fillPath(rect(4.0, 0.0, 6.0, 10.0), KiteMatrix.IDENTITY, red, evenOdd = false)
        c.endTransparencyGroup()
        val r = c.toRaster()
        assertEquals(r.alpha(1, 5), r.alpha(5, 5), "no darker seam")
        assertEquals(128, r.alpha(5, 5), 1)
    }

    @Test
    fun multiply_and_screen_follow_their_formulas() {
        fun over(mode: KiteBlendMode): Int {
            val c = KiteRasterCanvas(1, 1)
            c.fillPath(rect(0.0, 0.0, 1.0, 1.0), KiteMatrix.IDENTITY, RgbColor(1.0, 0.5, 0.0), evenOdd = false)
            c.fillPath(rect(0.0, 0.0, 1.0, 1.0), KiteMatrix.IDENTITY, RgbColor(0.5, 0.5, 1.0), evenOdd = false, blendMode = mode)
            return c.toRaster()[0, 0] and 0xFFFFFF
        }
        // The backdrop is already bytes, so 0.5 reads as 128 / 255 and a channel may be one level off.
        fun near(expected: Int, actual: Int) {
            for (shift in intArrayOf(16, 8, 0)) assertEquals(expected shr shift and 255, actual shr shift and 255, 1)
        }
        near(0x804000, over(KiteBlendMode.Multiply))
        near(0xFFC0FF, over(KiteBlendMode.Screen))
        near(0x8000FF, over(KiteBlendMode.Difference))
    }

    @Test
    fun an_axial_gradient_runs_between_its_points_and_extends_only_where_asked() {
        val shading = KiteShading.Axial(
            KiteColorSpace.DeviceRGB, null, null, doubleArrayOf(10.0, 0.0, 90.0, 0.0), doubleArrayOf(0.0, 1.0),
            KiteFunction.Type2(doubleArrayOf(0.0, 1.0), null, doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 1.0, 1.0), 1.0),
            extendStart = false, extendEnd = true,
        )
        val c = KiteRasterCanvas(100, 1)
        c.fillShading(shading, KiteMatrix.IDENTITY, null)
        val r = c.toRaster()
        assertEquals(0, r.alpha(5, 0), "before the start, which does not extend")
        assertEquals(127, r[50, 0] shr 16 and 255, 2)
        assertEquals(0xFFFFFF, r.rgb(95, 0), "past the end, which extends")
    }

    @Test
    fun an_image_lands_on_its_unit_square_with_row_zero_at_the_top() {
        val image = KiteImageData(
            width = 2, height = 2, bitsPerComponent = 8, colorSpace = "DeviceRGB", kind = KiteImageData.Kind.RAW,
            encodedBytes = ByteArray(0),
            pixelBytes = byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1),
            resolvedColorSpace = KiteColorSpace.DeviceRGB, interpolate = false,
        )
        val c = KiteRasterCanvas(4, 4)
        // The unit square onto the 4 by 4 pixels, its top at y = 0 as the y-down device has it.
        c.drawImage(image, KiteMatrix(4.0, 0.0, 0.0, -4.0, 0.0, 4.0))
        val r = c.toRaster()
        assertEquals(0xFF0000, r.rgb(0, 0))
        assertEquals(0x00FF00, r.rgb(3, 0))
        assertEquals(0x0000FF, r.rgb(0, 3))
        assertEquals(0xFFFFFF, r.rgb(3, 3))
    }

    @Test
    fun a_luminosity_mask_lets_through_where_it_is_white() {
        val c = KiteRasterCanvas(10, 10)
        c.applySoftMask(
            SoftMask.Kind.Luminosity, KiteRectangle(0.0, 0.0, 10.0, 10.0), KiteMatrix.IDENTITY,
            render = { c.fillPath(rect(0.0, 0.0, 10.0, 10.0), KiteMatrix.IDENTITY, red, evenOdd = false) },
            renderMask = { m -> m.fillPath(rect(0.0, 0.0, 5.0, 10.0), KiteMatrix.IDENTITY, RgbColor.WHITE, evenOdd = false) },
        )
        val r = c.toRaster()
        assertEquals(255, r.alpha(2, 5))
        assertEquals(0, r.alpha(7, 5))
    }

    @Test
    fun a_stroke_is_as_wide_as_its_line() {
        val c = KiteRasterCanvas(20, 20)
        val line = KitePath.Builder().apply { moveTo(2.0, 10.0); lineTo(18.0, 10.0) }.build()
        c.strokePath(line, KiteMatrix.IDENTITY, red, lineWidth = 4.0)
        val r = c.toRaster()
        assertEquals(0, r.alpha(10, 7))
        assertEquals(255, r.alpha(10, 8))
        assertEquals(255, r.alpha(10, 11))
        assertEquals(0, r.alpha(10, 12))
    }

    @Test
    fun text_without_outlines_paints_what_the_host_gives() {
        val square = rect(0.0, 0.0, 500.0, 500.0)
        val c = KiteRasterCanvas(40, 20, hostOutlines = { _, _ -> square })
        val glyphs = listOf(TextGlyph(0, 1, -1, "a", 600.0, null, false), TextGlyph(1, 1, -1, "b", 600.0, null, false))
        // 20 units a em, the baseline at y = 15, text space y-up onto the y-down pixels.
        c.drawGlyphs(glyphs, 20.0, 1000, hasOutlines = false, FontSpec.SansSerif, KiteMatrix(1.0, 0.0, 0.0, -1.0, 2.0, 15.0), blue)
        val r = c.toRaster()
        assertEquals(255, r.alpha(5, 10), "the first glyph, 10 pixels square from x = 2")
        assertEquals(0, r.alpha(13, 10), "the gap before the second, which starts at 2 + 12")
        assertEquals(255, r.alpha(16, 10))
        val none = KiteRasterCanvas(40, 20)
        none.drawGlyphs(glyphs, 20.0, 1000, hasOutlines = false, FontSpec.SansSerif, KiteMatrix(1.0, 0.0, 0.0, -1.0, 2.0, 15.0), blue)
        assertEquals(0, none.toRaster().alpha(5, 10), "no host outlines, no text")
    }

    @Test
    fun a_host_font_glyph_draws_at_the_pen_whatever_its_offsets() {
        // Every canvas draws a glyph of a host-font run at the pen; the offsets move outlines only (#904).
        val square = rect(0.0, 0.0, 500.0, 500.0)
        val c = KiteRasterCanvas(40, 20, hostOutlines = { _, _ -> square })
        val moved = TextGlyph(0, 1, -1, "a", 600.0, null, false, xOffset = 400.0, yOffset = 300.0)
        c.drawGlyphs(listOf(moved), 20.0, 1000, hasOutlines = false, FontSpec.SansSerif, KiteMatrix(1.0, 0.0, 0.0, -1.0, 2.0, 15.0), blue)
        val r = c.toRaster()
        assertEquals(255, r.alpha(3, 14), "the glyph's corner at the pen")
        assertEquals(0, r.alpha(13, 10), "nothing 8 pixels right, where the offset would put it")
    }

    @Test
    fun a_raster_step_reads_the_backdrop_and_draws_back() {
        val c = KiteRasterCanvas(10, 10)
        c.fillPath(rect(0.0, 0.0, 10.0, 10.0), KiteMatrix.IDENTITY, red, evenOdd = false)
        val ran = c.rasterStep(KiteRectangle(2.0, 2.0, 6.0, 6.0), KiteMatrix.IDENTITY) { scope ->
            val back = scope.backdrop()!!
            assertEquals(4, back.width)
            assertEquals(0xFFFF0000.toInt(), back[0, 0])
            val inner = scope.render { c.fillPath(rect(0.0, 0.0, 10.0, 10.0), KiteMatrix.IDENTITY, blue, evenOdd = false) }
            assertEquals(0xFF0000FF.toInt(), inner[3, 3])
            scope.draw(inner)
            true
        }
        assertTrue(ran)
        val r = c.toRaster()
        assertEquals(0xFF0000FF.toInt(), r[3, 3])
        assertEquals(0xFFFF0000.toInt(), r[7, 7])
    }

    @Test
    fun a_soft_mask_callback_that_throws_leaves_the_canvas_usable() {
        // Its layer stayed open, and every later paint went into it unseen (#765).
        val c = KiteRasterCanvas(10, 10)
        val box = io.github.yuroyami.kitepdf.core.KiteRectangle(0.0, 0.0, 10.0, 10.0)
        for (fails in listOf("content", "mask")) {
            kotlin.test.assertFailsWith<IllegalStateException> {
                c.applySoftMask(SoftMask.Kind.Alpha, box, KiteMatrix.IDENTITY,
                    { if (fails == "content") error("content") },
                    { if (fails == "mask") error("mask") })
            }
        }
        c.fillPath(rect(0.0, 0.0, 10.0, 10.0), KiteMatrix.IDENTITY, red, evenOdd = false)
        assertEquals(0xFFFF0000.toInt(), c.toRaster().pixels[55])
    }

    @Test
    fun a_non_isolated_group_blends_with_the_page_and_a_knockout_group_replaces_its_earlier_paints() {
        val all = rect(0.0, 0.0, 10.0, 10.0)
        val box = KiteRectangle(0.0, 0.0, 10.0, 10.0)
        // ISO 32000-1, 11.4.5: blue multiplied onto red inside a non-isolated group is black (#759).
        val nonIsolated = KiteRasterCanvas(10, 10)
        nonIsolated.fillPath(all, KiteMatrix.IDENTITY, red, evenOdd = false)
        nonIsolated.beginTransparencyGroup(box, KiteMatrix.IDENTITY, isolated = false, knockout = false, alpha = 1.0, blendMode = KiteBlendMode.Normal)
        nonIsolated.fillPath(all, KiteMatrix.IDENTITY, blue, evenOdd = false, alpha = 1.0, blendMode = KiteBlendMode.Multiply)
        nonIsolated.endTransparencyGroup()
        assertEquals(0xFF000000.toInt(), nonIsolated.toRaster()[5, 5])
        // 11.4.6: in a knockout group a half blue over red shows half blue over the white page, not over the red.
        val knockout = KiteRasterCanvas(10, 10)
        knockout.clear(RgbColor(1.0, 1.0, 1.0))
        knockout.beginTransparencyGroup(box, KiteMatrix.IDENTITY, isolated = true, knockout = true, alpha = 1.0, blendMode = KiteBlendMode.Normal)
        knockout.fillPath(all, KiteMatrix.IDENTITY, red, evenOdd = false)
        knockout.fillPath(all, KiteMatrix.IDENTITY, blue, evenOdd = false, alpha = 0.5)
        knockout.endTransparencyGroup()
        val p = knockout.toRaster()[5, 5]
        assertTrue((p ushr 16 and 0xFF) in 125..130 && (p ushr 8 and 0xFF) in 125..130 && (p and 0xFF) == 255, "half blue over white: ${p.toString(16)}")
    }

    @Test
    fun a_soft_masked_paint_keeps_its_blend_mode() {
        // ISO 32000-1, 11.6.4.3: an opaque mask leaves a Multiply paint as it is: red onto blue is black (#764).
        val all = rect(0.0, 0.0, 10.0, 10.0)
        val c = KiteRasterCanvas(10, 10)
        c.fillPath(all, KiteMatrix.IDENTITY, blue, evenOdd = false)
        c.applySoftMask(SoftMask.Kind.Alpha, KiteRectangle(0.0, 0.0, 10.0, 10.0), KiteMatrix.IDENTITY,
            render = { c.fillPath(all, KiteMatrix.IDENTITY, red, evenOdd = false, alpha = 1.0, blendMode = KiteBlendMode.Multiply) },
            renderMask = { mask -> mask.fillPath(all, KiteMatrix.IDENTITY, RgbColor(1.0, 1.0, 1.0), evenOdd = false) },
        )
        assertEquals(0xFF000000.toInt(), c.toRaster()[5, 5])
        // A half mask leaves half of the page and half of the black.
        val half = KiteRasterCanvas(10, 10)
        half.fillPath(all, KiteMatrix.IDENTITY, blue, evenOdd = false)
        half.applySoftMask(SoftMask.Kind.Alpha, KiteRectangle(0.0, 0.0, 10.0, 10.0), KiteMatrix.IDENTITY,
            render = { half.fillPath(all, KiteMatrix.IDENTITY, red, evenOdd = false, alpha = 1.0, blendMode = KiteBlendMode.Multiply) },
            renderMask = { mask -> mask.fillPath(all, KiteMatrix.IDENTITY, RgbColor(1.0, 1.0, 1.0), evenOdd = false, alpha = 0.5) },
        )
        val p = half.toRaster()[5, 5]
        assertTrue((p and 0xFF) in 125..130 && (p ushr 16 and 0xFF) == 0, "half blue: ${p.toString(16)}")
    }
}
