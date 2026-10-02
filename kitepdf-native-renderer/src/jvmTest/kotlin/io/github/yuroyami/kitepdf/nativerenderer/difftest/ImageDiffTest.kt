package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.difftest.ImageDiff
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.PdfRenderOracle

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImageDiffTest {

    @Test
    fun accepts_a_single_rounding_pixel_and_compares_the_pixels_both_have() {
        val kite = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val reference = BufferedImage(101, 99, BufferedImage.TYPE_INT_RGB)

        val result = ImageDiff.compare(kite, reference)

        assertEquals(100, result.width)
        assertEquals(99, result.height)
    }

    @Test
    fun a_rounding_pixel_does_not_resample_the_page() {
        // A sharp edge that both renders share. Scaling the wider reference to the KitePDF width
        // would blur it and score a difference that no engine drew (#461).
        fun page(width: Int) = BufferedImage(width, 100, BufferedImage.TYPE_INT_RGB).also { image ->
            val g = image.createGraphics()
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, width, 100)
            g.color = java.awt.Color.BLACK
            for (x in 10 until 90 step 4) g.fillRect(x, 10, 2, 80)
            g.dispose()
        }
        assertEquals(0.0, ImageDiff.compare(page(100), page(101)).meanAbsError)
    }

    @Test
    fun rejects_geometry_mismatches_instead_of_rescaling_them_away() {
        val kite = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val reference = BufferedImage(50, 100, BufferedImage.TYPE_INT_RGB)

        assertFailsWith<IllegalArgumentException> {
            ImageDiff.compare(kite, reference)
        }
    }

    @Test
    fun allows_resizing_for_reflowable_content() {
        val kite = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val reference = BufferedImage(50, 120, BufferedImage.TYPE_INT_RGB)

        val result = ImageDiff.compare(kite, reference, maxDimensionDelta = null)

        assertEquals(100, result.width)
        assertEquals(100, result.height)
    }
}
