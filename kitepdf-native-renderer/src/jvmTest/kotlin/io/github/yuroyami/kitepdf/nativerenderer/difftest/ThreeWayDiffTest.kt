package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.difftest.ThreeWayDiff
import io.github.yuroyami.kitepdf.difftest.ThreeWayDiff.Engine
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The verdict logic of [ThreeWayDiff], on renders drawn by hand. */
class ThreeWayDiffTest {

    /** A white 120 by 120 page with a grey square, plus whatever [extra] draws. */
    private fun page(extra: (java.awt.Graphics2D) -> Unit = {}): BufferedImage =
        BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB).also { image ->
            val g = image.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, 120, 120)
            g.color = Color.GRAY
            g.fillRect(20, 20, 60, 60)
            extra(g)
            g.dispose()
        }

    private val mark: (java.awt.Graphics2D) -> Unit = { g ->
        g.color = Color.RED
        g.fillRect(90, 90, 20, 20)
    }

    @Test
    fun kitepdf_is_the_odd_one_out_when_it_alone_draws_a_mark() {
        val result = ThreeWayDiff.compare(page(mark), page(), page())
        assertTrue(result.kiteIsOutlier)
        assertTrue(result.outlierTiles.getValue(Engine.KITE) > 0)
        assertEquals(0, result.outlierTiles.getValue(Engine.PDFIUM))
        assertEquals(0.0, result.mupdfPdfium)
        val tile = result.worstKiteTile!!
        assertTrue(tile.x in 72..96 && tile.y in 72..96, "the worst tile holds the mark: $tile")
    }

    @Test
    fun a_mark_that_only_pdfium_draws_is_not_a_kitepdf_fault() {
        val result = ThreeWayDiff.compare(page(), page(), page(mark))
        assertFalse(result.kiteIsOutlier)
        assertTrue(result.outlierTiles.getValue(Engine.PDFIUM) > 0)
    }

    @Test
    fun a_mark_that_only_mupdf_draws_is_not_a_kitepdf_fault() {
        val result = ThreeWayDiff.compare(page(), page(mark), page())
        assertFalse(result.kiteIsOutlier)
        assertTrue(result.outlierTiles.getValue(Engine.MUPDF) > 0)
    }

    @Test
    fun a_small_shift_of_every_colour_makes_kitepdf_the_odd_one_out_of_the_page() {
        val shifted = page().also { image ->
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val p = image.getRGB(x, y) and 0xFFFFFF
                val r = ((p ushr 16) and 0xFF).coerceAtMost(252)
                image.setRGB(x, y, (r shl 16) or (p and 0xFFFF))
            }
        }
        val result = ThreeWayDiff.compare(shifted, page(), page())
        assertEquals(Engine.KITE, result.pageOutlier)
        assertTrue(result.kiteIsOutlier)
    }

    @Test
    fun three_equal_renders_have_no_odd_one_out() {
        val result = ThreeWayDiff.compare(page(), page(), page())
        assertNull(result.pageOutlier)
        assertFalse(result.kiteIsOutlier)
        assertEquals(0.0, result.kiteMupdf)
    }

    @Test
    fun three_different_renders_have_no_odd_one_out() {
        assertNull(ThreeWayDiff.outlier(kiteMupdf = 0.3, kitePdfium = 0.3, mupdfPdfium = 0.3, margin = 0.02))
        assertEquals(Engine.KITE, ThreeWayDiff.outlier(kiteMupdf = 0.3, kitePdfium = 0.3, mupdfPdfium = 0.01, margin = 0.02))
        assertEquals(Engine.PDFIUM, ThreeWayDiff.outlier(kiteMupdf = 0.01, kitePdfium = 0.3, mupdfPdfium = 0.3, margin = 0.02))
        assertEquals(Engine.MUPDF, ThreeWayDiff.outlier(kiteMupdf = 0.3, kitePdfium = 0.01, mupdfPdfium = 0.3, margin = 0.02))
    }

    @Test
    fun missing_text_counts_repeats_and_ignores_whitespace_and_ligature_forms() {
        assertEquals("", ParityHarness.missingCharacters("of\r\nfice", "oﬃce"))
        assertEquals("ll", ParityHarness.missingCharacters("hello", "heo"))
        assertEquals("", ParityHarness.missingCharacters("abc", "a b c d"))
    }
}
