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
    fun accepts_a_single_rounding_pixel() {
        val kite = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val reference = BufferedImage(101, 99, BufferedImage.TYPE_INT_RGB)

        val result = ImageDiff.compare(kite, reference)

        assertEquals(100, result.width)
        assertEquals(100, result.height)
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
