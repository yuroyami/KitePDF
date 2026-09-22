package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.difftest.ImageDiff
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.PdfRenderOracle

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.io.File
import java.awt.Color
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Acceptance: the luminosity soft-mask fixture (white box on the
 * spec's black backdrop gating a red fill) against mutool, plus direct
 * pixel checks that the mask actually gates.
 */
class SoftMaskOracleTest {

    private fun fixture() = SyntheticPdfs.all().first { it.name == "syn-smask-luminosity" }

    @Test
    fun luminosity_mask_gates_by_luminance() {
        val doc = KitePDF.open(fixture().bytes)
        val img = AwtPdfRasterizer.renderToImage(doc.pages[0])
        val centre = img.getRGB(80, 80) // inside the mask box: user (80,120) -> device (80,80)
        assertTrue(
            (centre ushr 24) and 0xFF > 200 && (centre ushr 16) and 0xFF > 200 && (centre ushr 8) and 0xFF < 60,
            "centre is opaque red through the mask (${Integer.toHexString(centre)})",
        )
        // Zero mask coverage removes only the new paint. The rasterizer's
        // prepainted white paper must survive (ISO 32000-1, 11.6.5.1, #78).
        assertEquals(Color.WHITE.rgb, img.getRGB(180, 180), "mask must preserve the white backdrop")
        val transparent = AwtPdfRasterizer.renderToImage(doc.pages[0], background = Color(0, 0, 0, 0))
        assertEquals(0, transparent.getRGB(180, 180) ushr 24, "masked content stays transparent without paper")
        assertEquals(Color.RED.rgb, transparent.getRGB(80, 80), "content inside the mask stays opaque red")
    }

    @Test
    fun luminosity_mask_matches_mutool() {
        assumeTrue("mutool not found, skipping.", MuPdfOracle.binary != null)
        val bytes = fixture().bytes
        val kite = AwtPdfRasterizer.renderToImage(KitePDF.open(bytes).pages[0])
        val pdf = File.createTempFile("kite-smask", ".pdf").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        val reference = MuPdfOracle.render(pdf, page = 1, dpi = 72)
        assertNotNull(reference)
        val mae = ImageDiff.compare(kite, reference).score
        println("luminosity smask vs mutool: MAE=${(mae * 10000).toInt() / 10000.0}")
        assertTrue(mae <= 0.03, "luminosity mask MAE $mae must be <= 0.03")
    }
}
