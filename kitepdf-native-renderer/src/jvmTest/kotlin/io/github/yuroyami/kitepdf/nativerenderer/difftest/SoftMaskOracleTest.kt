package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-43 acceptance: the luminosity soft-mask fixture (white box on the
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
            "centre is opaque red through the mask (${'$'}{Integer.toHexString(centre)})",
        )
        // Outside the box the mask's black backdrop gives luminance 0: the
        // content is fully masked OUT, i.e. transparent (the page compositor
        // supplies the paper; ImageDiff flattens the same way mutool's PNG is).
        val edge = img.getRGB(180, 180)
        assertTrue((edge ushr 24) and 0xFF < 30, "edges are masked to transparency (${'$'}{Integer.toHexString(edge)})")
    }

    @Test
    fun luminosity_mask_matches_mutool() {
        assumeTrue("mutool not found — skipping.", MuPdfOracle.binary != null)
        val bytes = fixture().bytes
        val kite = AwtPdfRasterizer.renderToImage(KitePDF.open(bytes).pages[0])
        val pdf = File.createTempFile("kite-smask", ".pdf").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        val reference = MuPdfOracle.render(pdf, page = 1, dpi = 72)
        assertNotNull(reference)
        val mae = ImageDiff.compare(kite, reference).score
        println("[T-43] luminosity smask vs mutool: MAE=${(mae * 10000).toInt() / 10000.0}")
        assertTrue(mae <= 0.03, "luminosity mask MAE $mae must be <= 0.03")
    }
}
