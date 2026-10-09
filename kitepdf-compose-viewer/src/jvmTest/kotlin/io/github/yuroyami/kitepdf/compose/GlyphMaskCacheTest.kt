package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A raster fills a glyph's outline once for each size and position class, and every later
 * draw of it, in the same raster or a later one that shares the cache, draws its coverage, with
 * the pixels of the path to within a level (#382). Each glyph filled its path on every raster.
 */
class GlyphMaskCacheTest {

    private val measurer = TextMeasurer(testFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    /** A 600 by 700 box, 100 units from its origin, in a 1000-unit em. */
    private val box = KitePath.Builder().apply { rectangle(100.0, 0.0, 600.0, 700.0) }.build()

    /** An O: a ring of two curved contours, the inner one turning the other way. */
    private val ring = KitePath.Builder().apply {
        fun loop(cx: Double, cy: Double, r: Double, turn: Double) {
            val k = 0.5523 * r
            moveTo(cx + r, cy)
            curveTo(cx + r, cy + turn * k, cx + k, cy + turn * r, cx, cy + turn * r)
            curveTo(cx - k, cy + turn * r, cx - r, cy + turn * k, cx - r, cy)
            curveTo(cx - r, cy - turn * k, cx - k, cy - turn * r, cx, cy - turn * r)
            curveTo(cx + k, cy - turn * r, cx + r, cy - turn * k, cx + r, cy)
            close()
        }
        loop(400.0, 350.0, 330.0, 1.0)
        loop(400.0, 350.0, 200.0, -1.0)
    }.build()

    private val spec = FontSpec(KiteFontFamily.SansSerif, bold = false, italic = false)

    private fun run(outline: KitePath, count: Int) = List(count) { TextGlyph(0, 1, 1, "A", 800.0, outline, isWordSpace = false) }

    /** [count] glyphs of [outline] at [size] points, drawn on a white bitmap of [w] by [h] pixels. */
    private fun draw(
        masks: GlyphMaskCache?, outline: KitePath, textToDevice: KiteMatrix,
        size: Double = 20.0, count: Int = 8, w: Int = 200, h: Int = 60,
    ): IntArray {
        val bitmap = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
            drawRect(Color.White)
            ComposeCanvas(this, measurer, hairlineWidthPx = 1f, skipSystemFontText = false, magnification = 1f, glyphMasks = masks)
                .drawGlyphs(run(outline, count), size, 1000, true, spec, textToDevice, RgbColor(0.1, 0.2, 0.9), 1.0, KiteBlendMode.Normal)
        }
        return IntArray(w * h).also { bitmap.readPixels(it) }
    }

    private fun worst(a: IntArray, b: IntArray): Int {
        var worst = 0
        for (i in a.indices) for (shift in intArrayOf(0, 8, 16, 24)) {
            worst = maxOf(worst, abs((a[i] ushr shift and 0xFF) - (b[i] ushr shift and 0xFF)))
        }
        return worst
    }

    @Test
    fun glyphs_at_whole_pixels_draw_the_pixels_of_their_paths() {
        // 20 points, an advance of 16 pixels: every origin is a whole pixel.
        val textToDevice = KiteMatrix(1.0, 0.0, 0.0, -1.0, 7.0, 45.0)
        for ((name, outline) in listOf("box" to box, "ring" to ring)) {
            val masks = GlyphMaskCache()
            val masked = draw(masks, outline, textToDevice)
            assertEquals(1, masks.built, "$name: the eight glyphs of one position built more than one mask")
            assertTrue(masked.any { it != -1 }, "$name: nothing drew")
            assertTrue(worst(draw(null, outline, textToDevice), masked) <= 2, "$name: the masks drew other pixels than the paths")
        }
    }

    @Test
    fun a_glyph_between_pixels_draws_at_the_nearest_quarter() {
        // 20 pixels to the em takes quarters: 7.3 rounds to 7.25 and 45.6 to 45.5.
        val masks = GlyphMaskCache()
        val masked = draw(masks, ring, KiteMatrix(1.0, 0.0, 0.0, -1.0, 7.3, 45.6))
        val atQuarter = draw(null, ring, KiteMatrix(1.0, 0.0, 0.0, -1.0, 7.25, 45.5))
        assertEquals(1, masks.built)
        assertTrue(worst(atQuarter, masked) <= 2, "the masks drew away from the path at the rounded origin")
    }

    @Test
    fun a_rotated_glyph_draws_the_pixels_of_its_path() {
        // A quarter turn: the run goes down the bitmap.
        val textToDevice = KiteMatrix(0.0, 1.0, 1.0, 0.0, 30.0, 10.0)
        val masks = GlyphMaskCache()
        val masked = draw(masks, ring, textToDevice, count = 4, w = 60, h = 100)
        assertEquals(1, masks.built)
        assertTrue(worst(draw(null, ring, textToDevice, count = 4, w = 60, h = 100), masked) <= 2)
    }

    @Test
    fun a_later_raster_that_shares_the_cache_builds_no_mask() {
        val masks = GlyphMaskCache()
        val textToDevice = KiteMatrix(1.0, 0.0, 0.0, -1.0, 7.3, 45.6)
        val first = draw(masks, ring, textToDevice)
        val built = masks.built
        val second = draw(masks, ring, textToDevice)
        assertEquals(built, masks.built, "the second raster built masks again")
        assertTrue(worst(first, second) == 0)
    }

    @Test
    fun a_glyph_larger_than_a_mask_draws_as_its_path() {
        // 400 points: the box is 280 pixels tall, past the 256 of a mask.
        val textToDevice = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, 300.0)
        val masks = GlyphMaskCache()
        val masked = draw(masks, box, textToDevice, size = 400.0, count = 1, w = 300, h = 300)
        assertEquals(0, masks.built)
        assertEquals(0, worst(draw(null, box, textToDevice, size = 400.0, count = 1, w = 300, h = 300), masked))
    }

    @Test
    fun a_glyph_in_a_knockout_group_leaves_the_backdrop_beside_it() {
        // A knockout group paints with Src, which would clear the empty pixels of a mask's box.
        val textToDevice = KiteMatrix(1.0, 0.0, 0.0, -1.0, 7.0, 45.0)
        fun inGroup(masks: GlyphMaskCache?): IntArray {
            val bitmap = ImageBitmap(200, 60)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(200f, 60f)) {
                drawRect(Color.White)
                val canvas = ComposeCanvas(this, measurer, hairlineWidthPx = 1f, skipSystemFontText = false, magnification = 1f, glyphMasks = masks)
                canvas.beginTransparencyGroup(KiteRectangle(0.0, 0.0, 200.0, 60.0), KiteMatrix.IDENTITY, isolated = true, knockout = true)
                canvas.fillPath(KitePath.Builder().apply { rectangle(0.0, 0.0, 200.0, 60.0) }.build(), KiteMatrix.IDENTITY, RgbColor(1.0, 0.0, 0.0), evenOdd = false)
                canvas.drawGlyphs(run(ring, 8), 20.0, 1000, true, spec, textToDevice, RgbColor(0.1, 0.2, 0.9), 1.0, KiteBlendMode.Normal)
                canvas.endTransparencyGroup()
            }
            return IntArray(200 * 60).also { bitmap.readPixels(it) }
        }
        assertEquals(0, worst(inGroup(null), inGroup(GlyphMaskCache())))
    }

    @Test
    fun a_page_rastered_again_fills_no_glyph() {
        val doc = PdfDocument.open(
            PdfBuilder().page(width = 300.0, height = 200.0) {
                for (line in 0 until 8) text(StandardFont.Helvetica, 11.0, 12.0, 180.0 - line * 20.0, "The quick brown fox jumps over the lazy dog $line")
            }.build(compress = false),
        )
        val rasterizer = KitePageRasterizer(Density(1f), LayoutDirection.Ltr, measurer)
        val first = onTestUiThread { rasterizer.rasterize(doc.pages[0], 600, 400) }
        val built = rasterizer.glyphMasks.built
        assertTrue(built in 1 until 8 * 44, "a page of 350 glyphs built $built masks")
        val second = onTestUiThread { rasterizer.rasterize(doc.pages[0], 600, 400) }
        assertEquals(built, rasterizer.glyphMasks.built, "the second raster filled glyph paths again")
        val a = IntArray(600 * 400).also { first.readPixels(it) }
        val b = IntArray(600 * 400).also { second.readPixels(it) }
        assertEquals(0, worst(a, b))
    }
}
